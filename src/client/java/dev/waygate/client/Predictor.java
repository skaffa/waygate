package dev.waygate.client;

import dev.waygate.core.Axis;
import dev.waygate.core.Border;
import dev.waygate.core.Dim;
import dev.waygate.core.PortalMemory;
import dev.waygate.core.PortalRect;
import dev.waygate.core.Pos;
import dev.waygate.core.Prediction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Feeds {@link Prediction} with what the client knows: the portal the player stands in or looks at, the
 * scales and borders learned per dimension, and portals from the integrated server (exact) or from
 * memory. Recomputed a few times per second; the HUD and markers read the result.
 */
final class Predictor {
	/** The portal the HUD is about. {@code standing}: the player is inside it. */
	record Focus(PortalRect rect, boolean standing) {
	}

	/**
	 * One prediction with the facts the HUD needs to word it.
	 * @param exact all portals came from the integrated server
	 * @param pending some server answers have not arrived yet (memory used meanwhile)
	 * @param known remembered portals in the target dimension
	 * @param sim new-portal placement in singleplayer, or null
	 */
	record View(Focus focus, String from, String to, Prediction.Result result, boolean exact, boolean pending,
			int known, boolean borderKnown, ServerPeek.Sim sim) {
	}

	private static View current;
	/** Marker status per portal in the current dimension, refreshed once a second. */
	private static Map<PortalRect, Prediction.Result> markerResults = Map.of();
	private static int ticks;

	private Predictor() {
	}

	static View current() {
		return current;
	}

	static Map<PortalRect, Prediction.Result> markerResults() {
		return markerResults;
	}

	static void clear() {
		current = null;
		markerResults = Map.of();
	}

	static void tick(Minecraft mc) {
		ticks++;
		if (mc.level == null || mc.player == null) {
			clear();
			return;
		}
		if (ticks % 4 == 0) {
			current = compute(mc);
		}
		if (ticks % 20 == 0) {
			markerResults = Waygate.config.markers ? markers(mc) : Map.of();
		}
	}

	private static View compute(Minecraft mc) {
		var mode = Waygate.config.hudMode;
		if (mode == dev.waygate.core.WaygateConfig.HudMode.OFF) {
			return null;
		}
		Focus focus = focus(mc);
		if (focus == null && mode != dev.waygate.core.WaygateConfig.HudMode.ALWAYS) {
			return null;
		}
		double x, y, z;
		if (focus == null || focus.standing()) {
			x = mc.player.getX();
			y = mc.player.getY();
			z = mc.player.getZ();
		} else {
			x = focus.rect().centerX();
			y = focus.rect().min().y();
			z = focus.rect().centerZ();
		}
		return predict(mc.level, focus, x, y, z);
	}

	/** Prediction for a player at x, y, z, optionally inside {@code focus}. */
	static View predict(ClientLevel level, Focus focus, double x, double y, double z) {
		String from = Waygate.dimId(level);
		String to = Waygate.targetDimId(from);
		PortalMemory memory = Waygate.memory();
		PortalMemory.DimInfo fromD = memory.dim(from);
		PortalMemory.DimInfo toD = memory.dim(to);
		Dim fromDim = Waygate.dim(from);
		double fromScale = fromD.scale != null ? fromD.scale : fromDim.defaultScale();
		double toScale = toD.scale != null ? toD.scale : Waygate.dim(to).defaultScale();
		Border fromBorder = fromD.border().orElse(Border.VANILLA_DEFAULT);
		Border toBorder = toD.border().orElse(Border.VANILLA_DEFAULT);

		boolean exact = ServerPeek.active();
		boolean[] pending = {false};
		Prediction.Portals portals = (targetSide, center, radius) -> {
			String dim = targetSide ? to : from;
			if (exact) {
				ServerPeek.Portals p = ServerPeek.portals(dim, center, radius);
				if (p != null) {
					return p.rects();
				}
				pending[0] = true;
			}
			return memory.dim(dim).rects();
		};
		Prediction.Input in = new Prediction.Input(focus == null ? null : focus.rect(), x, y, z, fromDim, fromScale, toScale,
				fromBorder, toBorder);
		Prediction.Result r = Prediction.predict(in, portals);
		ServerPeek.Sim sim = null;
		if (r.linked().isEmpty() && exact) {
			Axis axis = focus == null ? Axis.X : focus.rect().axis();
			sim = ServerPeek.simulate(to, r.exit(), axis);
			if (sim == null) {
				pending[0] = true;
			}
		}
		return new View(focus, from, to, r, exact, pending[0], toD.portals.size(), toD.border().isPresent(), sim);
	}

	/** The portal the player stands in, else the one under the crosshair within the look distance. */
	static Focus focus(Minecraft mc) {
		ClientLevel level = mc.level;
		AABB box = mc.player.getBoundingBox();
		for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
				BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (PortalScanner.isPortal(level.getBlockState(p))) {
				return new Focus(rectAt(level, p.immutable()), true);
			}
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 end = eye.add(mc.player.getViewVector(1.0f).scale(Waygate.config.lookDistance));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		if (hit.getType() == HitResult.Type.BLOCK && PortalScanner.isPortal(level.getBlockState(hit.getBlockPos()))) {
			return new Focus(rectAt(level, hit.getBlockPos()), false);
		}
		return null;
	}

	/** The remembered portal containing {@code p}, or the rectangle grown from it now. */
	static PortalRect rectAt(ClientLevel level, BlockPos p) {
		String dim = Waygate.dimId(level);
		Optional<PortalMemory.Known> k = Waygate.memory().at(dim, new Pos(p.getX(), p.getY(), p.getZ()));
		if (k.isPresent()) {
			return k.get().rect();
		}
		BlockState s = level.getBlockState(p);
		List<PortalRect> rects = PortalScanner.group(PortalScanner.fill(level, p, PortalScanner.axis(s)), PortalScanner.axis(s));
		return rects.isEmpty() ? new PortalRect(new Pos(p.getX(), p.getY(), p.getZ()), PortalScanner.axis(s), 1, 1) : rects.getFirst();
	}

	/** Predictions for every remembered portal near the player, for the markers. */
	private static Map<PortalRect, Prediction.Result> markers(Minecraft mc) {
		String dim = Waygate.dimId(mc.level);
		PortalMemory.DimInfo d = Waygate.memory().dims.get(dim);
		if (d == null) {
			return Map.of();
		}
		double range = Waygate.config.markerRange;
		Map<PortalRect, Prediction.Result> out = new HashMap<>();
		for (PortalMemory.Known k : d.portals) {
			PortalRect r = k.rect();
			if (mc.player.distanceToSqr(r.centerX(), r.min().y() + r.height() / 2.0, r.centerZ()) > range * range) {
				continue;
			}
			View v = predict(mc.level, new Focus(r, false), r.centerX(), r.min().y(), r.centerZ());
			out.put(r, v.result());
		}
		return out;
	}

	/** Name or corner of a portal in {@code dim}. */
	static String label(String dim, PortalRect r) {
		return Waygate.memory().dims.containsKey(dim)
				? Waygate.memory().dim(dim).portals.stream().filter(k -> PortalMemory.overlaps(k.rect(), r)).findFirst()
						.map(PortalMemory.Known::label).orElse(r.min().toString())
				: r.min().toString();
	}
}

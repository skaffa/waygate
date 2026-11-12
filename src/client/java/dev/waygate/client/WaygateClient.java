package dev.waygate.client;

import dev.waygate.client.dev.DevScript;
import dev.waygate.core.Border;
import dev.waygate.core.PortalMath;
import dev.waygate.core.PortalMemory;
import dev.waygate.core.PortalRect;
import dev.waygate.core.PortalSearch;
import dev.waygate.core.Pos;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Client entrypoint: events, per-tick work and the {@code /waygate} command. */
public final class WaygateClient implements ClientModInitializer {
	private static int tickCount;
	private static KeyMapping hudKey;

	@Override
	public void onInitializeClient() {
		Waygate.loadConfig();
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Waygate.MOD_ID, "main"));
		hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.waygate.hud", InputConstants.KEY_U, category));
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(Waygate.MOD_ID, "panel"),
				WaygateHud::extract);
		ClientTickEvents.END_CLIENT_TICK.register(WaygateClient::tick);
		ClientChunkEvents.CHUNK_LOAD.register(PortalScanner::onChunkLoad);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			Waygate.close();
			PortalScanner.clear();
			ServerPeek.clear();
			Predictor.clear();
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(literal("waygate")
				.then(literal("debug").executes(WaygateClient::debug))
				.then(literal("forget").then(literal("all").executes(c -> {
					int n = Waygate.forgetAll();
					c.getSource().sendFeedback(Component.literal("Waygate: forgot " + n + " portals of this world"));
					return 1;
				})))));
		Waygate.LOG.info("Waygate loaded");
	}

	/** Called from the {@code ClientLevel} mixin for every applied block change. */
	public static void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
		if (oldState != newState) {
			PortalScanner.onBlockChanged(pos, oldState, newState);
		}
	}

	private static void tick(Minecraft mc) {
		DevScript.tick(mc);
		if (mc.level == null || mc.player == null) {
			return;
		}
		tickCount++;
		if (tickCount % 20 == 0) {
			learn(mc.level);
		}
		while (hudKey.consumeClick()) {
			Waygate.config.hudMode = Waygate.config.hudMode.next();
			Waygate.saveConfig();
			Waygate.say(switch (Waygate.config.hudMode) {
				case AUTO -> "panel when at a portal";
				case ALWAYS -> "panel always (also where your spot leads)";
				case OFF -> "panel off";
			});
		}
		PortalScanner.tick(mc);
		ServerPeek.tick(mc);
		Predictor.tick(mc);
		Markers.tick(mc);
		if (tickCount % 100 == 0) {
			Waygate.save();
		}
	}

	/** What the client knows for sure about the dimension it is in: scale, border, heights. */
	static void learn(ClientLevel level) {
		PortalMemory memory = Waygate.memory();
		PortalMemory.DimInfo d = memory.dim(Waygate.dimId(level));
		var b = level.getWorldBorder();
		boolean changed = d.learnScale(level.dimensionType().coordinateScale())
				| d.learnBorder(new Border(b.getMinX(), b.getMinZ(), b.getMaxX(), b.getMaxZ()))
				| d.learnHeights(level.getMinY(), level.getMaxY(), level.dimensionType().logicalHeight());
		if (changed) {
			memory.markDirty();
		}
	}

	private static int debug(CommandContext<FabricClientCommandSource> c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return 0;
		}
		learn(mc.level);
		PortalMemory memory = Waygate.memory();
		boolean exact = ServerPeek.active();
		say(c, "world " + Waygate.currentWorldKey() + (exact ? " (singleplayer: exact)" : " (multiplayer: remembered portals only)"));
		for (String id : List.of(Waygate.OVERWORLD, Waygate.NETHER)) {
			PortalMemory.DimInfo d = memory.dim(id);
			long seen = d.portals.stream().filter(k -> k.seen).count();
			say(c, String.format(Locale.ROOT, "%s: scale %s, border %s, y %s..%s logical %s, %d portals (%d seen)",
					id, d.scale == null ? "?" : d.scale, d.border().map(WaygateClient::border).orElse("?"),
					d.minY == null ? "?" : d.minY, d.maxY == null ? "?" : d.maxY, d.logicalHeight == null ? "?" : d.logicalHeight,
					d.portals.size(), seen));
		}

		String from = Waygate.dimId(mc.level);
		String to = Waygate.targetDimId(from);
		PortalMemory.DimInfo fromD = memory.dim(from);
		PortalMemory.DimInfo toD = memory.dim(to);
		double fromScale = fromD.scale != null ? fromD.scale : Waygate.dim(from).defaultScale();
		double toScale = toD.scale != null ? toD.scale : Waygate.dim(to).defaultScale();
		Border toBorder = toD.border().orElse(Border.VANILLA_DEFAULT);
		Pos exit = PortalMath.exit(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
				PortalMath.teleportScale(fromScale, toScale), toBorder);
		boolean toNether = Waygate.NETHER.equals(to);
		say(c, "from here: exit " + exit + " in " + to + (toD.border().isEmpty() ? " (border unknown)" : ""));
		List<PortalRect> known = toD.rects();
		describe(c, "remembered", PortalSearch.search(exit, toNether, toBorder, known), known);
		if (exact) {
			ServerPeek.Portals p = ServerPeek.portals(to, exit, PortalSearch.radius(toNether));
			if (p == null) {
				say(c, "server: asked, run again in a moment");
			} else {
				describe(c, "server", PortalSearch.search(exit, toNether, toBorder, p.rects()), p.rects());
			}
		}
		return 1;
	}

	private static void describe(CommandContext<FabricClientCommandSource> c, String source, PortalSearch.Result r, List<PortalRect> rects) {
		if (r.createsNew()) {
			say(c, source + ": no portal within " + r.radius() + " -> new portal near " + r.exit());
			return;
		}
		PortalSearch.Hit h = r.chosen().orElseThrow();
		say(c, String.format(Locale.ROOT, "%s: links to %s via block %s, %.1f blocks%s (%d candidates)", source,
				rects.get(h.portal()), h.block(), h.distance(), r.ambiguous() ? ", AMBIGUOUS tie" : "", r.ranked().size()));
	}

	private static String border(Border b) {
		return String.format(Locale.ROOT, "%.0f..%.0f x %.0f..%.0f", b.minX(), b.maxX(), b.minZ(), b.maxZ());
	}

	private static void say(CommandContext<FabricClientCommandSource> c, String msg) {
		c.getSource().sendFeedback(Component.literal(msg));
		Waygate.LOG.info("[waygate] {}", msg);
	}
}

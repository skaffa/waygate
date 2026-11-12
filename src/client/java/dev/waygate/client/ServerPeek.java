package dev.waygate.client;

import dev.waygate.core.Axis;
import dev.waygate.core.Border;
import dev.waygate.core.CreatePortalSim;
import dev.waygate.core.PortalGrouping;
import dev.waygate.core.PortalMemory;
import dev.waygate.core.PortalRect;
import dev.waygate.core.PortalSearch;
import dev.waygate.core.Pos;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleplayer and LAN host: the server runs in this process, so Waygate reads the real portal POIs
 * (including portals the player never saw), each dimension's scale, border and height, and can run the
 * new-portal placement on the server's loaded chunks.
 *
 * <p>Reads run on the server thread and never load or generate chunks: POI data comes from the POI
 * storage, block states are only read in chunks that are already loaded. Results come back a tick later.
 */
public final class ServerPeek {
	/** Portals found around {@code center} (vanilla search square of {@code radius}). */
	record Portals(String dim, Pos center, int radius, List<PortalRect> rects, long time) {
	}

	/** New-portal simulation result: {@code loaded} false when the area is not fully loaded on the server. */
	public record Sim(boolean loaded, Optional<CreatePortalSim.Placement> placement, long time) {
	}

	/** Facts of one dimension, read from the server. */
	record Facts(double scale, Border border, CreatePortalSim.Level level) {
	}

	private record PortalKey(String dim, Pos center, int radius) {
	}

	private record SimKey(String dim, Pos origin, Axis axis) {
	}

	/** Results older than this are asked for again. */
	private static final long FRESH_MS = 1000;
	private static final Map<PortalKey, Portals> PORTALS = new ConcurrentHashMap<>();
	private static final Map<SimKey, Sim> SIMS = new ConcurrentHashMap<>();
	private static final Map<PortalKey, Boolean> WANT_PORTALS = new HashMap<>();
	private static final Map<SimKey, Boolean> WANT_SIMS = new HashMap<>();
	private static final List<Portals> TO_MERGE = new ArrayList<>();
	private static volatile Map<String, Facts> facts = Map.of();
	private static volatile boolean pending;
	private static int factsTimer;

	private ServerPeek() {
	}

	public static boolean active() {
		return Minecraft.getInstance().hasSingleplayerServer();
	}

	/** Exact portals around {@code center}, or null until the first answer arrives. */
	static Portals portals(String dim, Pos center, int radius) {
		if (!active()) {
			return null;
		}
		PortalKey k = new PortalKey(dim, center, radius);
		Portals p = PORTALS.get(k);
		if (p == null || System.currentTimeMillis() - p.time() > FRESH_MS) {
			WANT_PORTALS.put(k, true);
		}
		return p;
	}

	/** Where vanilla would build a new portal, or null until the first answer arrives. */
	static Sim simulate(String dim, Pos origin, Axis axis) {
		if (!active()) {
			return null;
		}
		SimKey k = new SimKey(dim, origin, axis);
		Sim s = SIMS.get(k);
		if (s == null || System.currentTimeMillis() - s.time() > FRESH_MS) {
			WANT_SIMS.put(k, true);
		}
		return s;
	}

	static Facts facts(String dim) {
		return facts.get(dim);
	}

	static void clear() {
		PORTALS.clear();
		SIMS.clear();
		WANT_PORTALS.clear();
		WANT_SIMS.clear();
		synchronized (TO_MERGE) {
			TO_MERGE.clear();
		}
		facts = Map.of();
	}

	/** Once per client tick: applies answers to the memory and hands new questions to the server thread. */
	static void tick(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.level == null) {
			return;
		}
		applyFacts();
		mergePortals();
		long now = System.currentTimeMillis();
		PORTALS.values().removeIf(p -> now - p.time() > 10_000);
		SIMS.values().removeIf(s -> now - s.time() > 10_000);

		boolean wantFacts = factsTimer-- <= 0;
		if (pending || (!wantFacts && WANT_PORTALS.isEmpty() && WANT_SIMS.isEmpty())) {
			return;
		}
		if (wantFacts) {
			factsTimer = 40;
		}
		List<PortalKey> portalKeys = new ArrayList<>(WANT_PORTALS.keySet());
		List<SimKey> simKeys = new ArrayList<>(WANT_SIMS.keySet());
		WANT_PORTALS.clear();
		WANT_SIMS.clear();
		pending = true;
		server.execute(() -> {
			try {
				if (wantFacts) {
					readFacts(server);
				}
				for (PortalKey k : portalKeys) {
					ServerLevel level = server.getLevel(key(k.dim()));
					if (level != null) {
						Portals p = new Portals(k.dim(), k.center(), k.radius(), readPortals(level, k.center(), k.radius()),
								System.currentTimeMillis());
						PORTALS.put(k, p);
						synchronized (TO_MERGE) {
							TO_MERGE.add(p);
						}
					}
				}
				for (SimKey k : simKeys) {
					ServerLevel level = server.getLevel(key(k.dim()));
					if (level != null) {
						SIMS.put(k, simulate(level, k.origin(), k.axis()));
					}
				}
			} catch (RuntimeException e) {
				Waygate.LOG.warn("server peek failed", e);
			} finally {
				pending = false;
			}
		});
	}

	static ResourceKey<Level> key(String dim) {
		return ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim));
	}

	// ---- server thread ----

	private static void readFacts(IntegratedServer server) {
		Map<String, Facts> out = new HashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			WorldBorder b = level.getWorldBorder();
			out.put(Waygate.dimId(level), new Facts(level.dimensionType().coordinateScale(),
					new Border(b.getMinX(), b.getMinZ(), b.getMaxX(), b.getMaxZ()),
					new CreatePortalSim.Level(level.getMinY(), level.getMaxY(), level.dimensionType().logicalHeight())));
		}
		facts = Map.copyOf(out);
	}

	/**
	 * All portal POIs in the square, grouped into portals. Like vanilla's search but without
	 * {@code ensureLoadedAndValid}: in loaded chunks the block must still be a portal (vanilla's axis
	 * check); in unloaded chunks the POI is trusted.
	 */
	static List<PortalRect> readPortals(ServerLevel level, Pos center, int radius) {
		PoiManager poi = level.getPoiManager();
		Map<Pos, Axis> axes = new HashMap<>();
		List<Pos> blocks = new ArrayList<>();
		poi.getInSquare(t -> t.is(PoiTypes.NETHER_PORTAL), new BlockPos(center.x(), center.y(), center.z()), radius,
						PoiManager.Occupancy.ANY)
				.map(PoiRecord::getPos)
				.forEach(bp -> {
					LevelChunk chunk = level.getChunkSource().getChunkNow(bp.getX() >> 4, bp.getZ() >> 4);
					Pos p = new Pos(bp.getX(), bp.getY(), bp.getZ());
					if (chunk == null) {
						blocks.add(p);
						return;
					}
					BlockState s = chunk.getBlockState(bp);
					if (PortalScanner.isPortal(s)) {
						blocks.add(p);
						axes.put(p, PortalScanner.axis(s));
					}
				});
		return PortalGrouping.group(blocks, axes::get);
	}

	/** {@code PortalForcer.createPortal} on the server's blocks, only when the whole area is loaded. */
	static Sim simulate(ServerLevel level, Pos origin, Axis axis) {
		long now = System.currentTimeMillis();
		int margin = CreatePortalSim.SPIRAL_RADIUS + 4;
		for (int cx = (origin.x() - margin) >> 4; cx <= (origin.x() + margin) >> 4; cx++) {
			for (int cz = (origin.z() - margin) >> 4; cz <= (origin.z() + margin) >> 4; cz++) {
				if (level.getChunkSource().getChunkNow(cx, cz) == null) {
					return new Sim(false, Optional.empty(), now);
				}
			}
		}
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		CreatePortalSim.Blocks blocks = new CreatePortalSim.Blocks() {
			@Override
			public boolean replaceable(int x, int y, int z) {
				BlockState s = level.getBlockState(m.set(x, y, z));
				return s.canBeReplaced() && s.getFluidState().isEmpty();
			}

			@Override
			public boolean solid(int x, int y, int z) {
				return level.getBlockState(m.set(x, y, z)).isSolid();
			}

			@Override
			public int motionBlockingHeight(int x, int z) {
				return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			}
		};
		WorldBorder b = level.getWorldBorder();
		CreatePortalSim.Level lv = new CreatePortalSim.Level(level.getMinY(), level.getMaxY(), level.dimensionType().logicalHeight());
		return new Sim(true, CreatePortalSim.place(origin, axis,
				lv, new Border(b.getMinX(), b.getMinZ(), b.getMaxX(), b.getMaxZ()), blocks), now);
	}

	// ---- client thread ----

	private static void applyFacts() {
		Map<String, Facts> f = facts;
		if (f.isEmpty()) {
			return;
		}
		PortalMemory memory = Waygate.memory();
		for (Map.Entry<String, Facts> e : f.entrySet()) {
			PortalMemory.DimInfo d = memory.dim(e.getKey());
			Facts x = e.getValue();
			boolean changed = d.learnScale(x.scale()) | d.learnBorder(x.border())
					| d.learnHeights(x.level().minY(), x.level().maxY(), x.level().logicalHeight());
			if (changed) {
				memory.markDirty();
			}
		}
	}

	/**
	 * Server answers are the truth: portals there are remembered (as not seen by the player), and
	 * remembered portals entirely inside the searched square that the server does not have are dropped.
	 */
	private static void mergePortals() {
		List<Portals> batch;
		synchronized (TO_MERGE) {
			if (TO_MERGE.isEmpty()) {
				return;
			}
			batch = new ArrayList<>(TO_MERGE);
			TO_MERGE.clear();
		}
		PortalMemory memory = Waygate.memory();
		for (Portals p : batch) {
			PortalMemory.DimInfo d = memory.dim(p.dim());
			List<PortalMemory.Known> stale = new ArrayList<>();
			for (PortalMemory.Known k : d.portals) {
				PortalRect r = k.rect();
				boolean inside = r.blocks().stream().allMatch(b -> PortalSearch.inSquare(b, p.center(), p.radius()));
				if (inside && p.rects().stream().noneMatch(x -> overlaps(x, r))) {
					stale.add(k);
				}
			}
			for (PortalMemory.Known k : stale) {
				memory.remove(p.dim(), k);
			}
			for (PortalRect r : p.rects()) {
				memory.upsert(p.dim(), r, p.time(), false);
			}
		}
	}

	private static boolean overlaps(PortalRect a, PortalRect b) {
		for (Pos p : a.blocks()) {
			if (b.contains(p)) {
				return true;
			}
		}
		return false;
	}
}

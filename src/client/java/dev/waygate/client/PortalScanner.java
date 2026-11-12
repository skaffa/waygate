package dev.waygate.client;

import dev.waygate.core.Axis;
import dev.waygate.core.PortalGrouping;
import dev.waygate.core.PortalMemory;
import dev.waygate.core.PortalRect;
import dev.waygate.core.Pos;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the portals in the chunks the client has loaded and keeps the memory up to date: scans each chunk
 * when it arrives (only sections that may contain portal blocks), rescans around every portal block that
 * appears or disappears, and drops remembered portals whose blocks are visibly gone.
 */
final class PortalScanner {
	/** Portal blocks to grow into rectangles on the next tick. */
	private static final Set<BlockPos> SEEDS = new LinkedHashSet<>();
	/** Positions where a portal block disappeared: the remembered portal there is re-checked. */
	private static final Set<BlockPos> GONE = new LinkedHashSet<>();
	/** Guard against huge custom shapes; a vanilla portal has at most 21x21 = 441 blocks. */
	private static final int MAX_FILL = 2000;

	private PortalScanner() {
	}

	static boolean isPortal(BlockState s) {
		return s.is(Blocks.NETHER_PORTAL);
	}

	static Axis axis(BlockState s) {
		return s.getValue(NetherPortalBlock.AXIS) == Direction.Axis.Z ? Axis.Z : Axis.X;
	}

	/** Chunk data arrived: queue every portal block in it. */
	static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir() || !section.maybeHas(PortalScanner::isPortal)) {
				continue;
			}
			int baseY = level.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (isPortal(section.getBlockState(x, y, z))) {
							SEEDS.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
						}
					}
				}
			}
		}
		// a portal remembered in this chunk may be gone now
		String dim = Waygate.dimId(level);
		PortalMemory.DimInfo d = Waygate.memory().dims.get(dim);
		if (d != null) {
			for (PortalMemory.Known k : d.portals) {
				Pos m = k.rect().min();
				if (m.x() >> 4 == chunk.getPos().x() && m.z() >> 4 == chunk.getPos().z()) {
					GONE.add(new BlockPos(m.x(), m.y(), m.z()));
				}
			}
		}
	}

	/** From the {@code ClientLevel} mixin: every block change the client applies. */
	static void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
		if (isPortal(newState)) {
			SEEDS.add(pos.immutable());
		} else if (isPortal(oldState)) {
			GONE.add(pos.immutable());
		}
	}

	static void clear() {
		SEEDS.clear();
		GONE.clear();
	}

	static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || (SEEDS.isEmpty() && GONE.isEmpty())) {
			return;
		}
		String dim = Waygate.dimId(level);
		PortalMemory memory = Waygate.memory();
		long now = System.currentTimeMillis();

		for (BlockPos g : GONE) {
			memory.at(dim, new Pos(g.getX(), g.getY(), g.getZ())).ifPresent(k -> recheck(level, memory, dim, k, now));
		}
		GONE.clear();

		Set<BlockPos> done = new HashSet<>();
		List<BlockPos> seeds = new ArrayList<>(SEEDS);
		SEEDS.clear();
		for (BlockPos seed : seeds) {
			if (done.contains(seed) || !loaded(level, seed)) {
				continue;
			}
			BlockState s = level.getBlockState(seed);
			if (!isPortal(s)) {
				continue;
			}
			Set<BlockPos> blocks = fill(level, seed, axis(s));
			done.addAll(blocks);
			for (PortalRect r : group(blocks, axis(s))) {
				memory.upsert(dim, r, now, true);
			}
		}
	}

	/**
	 * A remembered portal lost a block or its chunk was reloaded: if its blocks are all loaded and none is
	 * a portal any more, forget it; otherwise rescan what is left.
	 */
	private static void recheck(ClientLevel level, PortalMemory memory, String dim, PortalMemory.Known k, long now) {
		List<BlockPos> remaining = new ArrayList<>();
		for (Pos p : k.rect().blocks()) {
			BlockPos bp = new BlockPos(p.x(), p.y(), p.z());
			if (!loaded(level, bp)) {
				return;
			}
			BlockState s = level.getBlockState(bp);
			if (isPortal(s) && axis(s) == k.axis) {
				remaining.add(bp);
			}
		}
		if (remaining.isEmpty()) {
			memory.remove(dim, k);
			return;
		}
		if (remaining.size() < k.width * k.height) {
			// smaller now: grow again from what is left; upsert replaces the overlapping old one and keeps its name
			Set<BlockPos> blocks = fill(level, remaining.getFirst(), k.axis);
			for (PortalRect r : group(blocks, k.axis)) {
				memory.upsert(dim, r, now, true);
			}
		}
	}

	private static List<PortalRect> group(Set<BlockPos> blocks, Axis axis) {
		List<Pos> ps = new ArrayList<>(blocks.size());
		for (BlockPos b : blocks) {
			ps.add(new Pos(b.getX(), b.getY(), b.getZ()));
		}
		return PortalGrouping.group(ps, p -> axis);
	}

	/** Connected portal blocks of one axis: up, down and along the axis. */
	private static Set<BlockPos> fill(ClientLevel level, BlockPos start, Axis axis) {
		Set<BlockPos> out = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		out.add(start);
		queue.add(start);
		Direction along = axis == Axis.X ? Direction.EAST : Direction.SOUTH;
		while (!queue.isEmpty() && out.size() < MAX_FILL) {
			BlockPos p = queue.poll();
			for (BlockPos n : new BlockPos[]{p.above(), p.below(), p.relative(along), p.relative(along.getOpposite())}) {
				if (out.contains(n) || !loaded(level, n)) {
					continue;
				}
				BlockState s = level.getBlockState(n);
				if (isPortal(s) && axis(s) == axis) {
					out.add(n);
					queue.add(n);
				}
			}
		}
		return out;
	}

	private static boolean loaded(ClientLevel level, BlockPos p) {
		return level.getChunkSource().getChunk(p.getX() >> 4, p.getZ() >> 4, ChunkStatus.FULL, false) != null;
	}
}

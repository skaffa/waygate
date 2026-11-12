package dev.waygate.core;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Where vanilla builds a new exit portal when the search finds none. A line-by-line port of 26.3
 * {@code PortalForcer.createPortal}, reading blocks through {@link Blocks} so it can run on the integrated
 * server's loaded chunks or on test data.
 */
public final class CreatePortalSim {
	public static final int SPIRAL_RADIUS = 16;

	private CreatePortalSim() {
	}

	/** Read-only view of the target level. */
	public interface Blocks {
		/** {@code state.canBeReplaced() && state.getFluidState().isEmpty()}. */
		boolean replaceable(int x, int y, int z);

		/** {@code state.isSolid()}. */
		boolean solid(int x, int y, int z);

		/** {@code level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)}: the Y above the top motion-blocking block. */
		int motionBlockingHeight(int x, int z);
	}

	/** Target level height facts. */
	public record Level(int minY, int maxY, int logicalHeight) {
		public static final Level OVERWORLD = new Level(-64, 319, 384);
		public static final Level NETHER = new Level(0, 255, 128);

		/** {@code min(level.getMaxY(), level.getMinY() + level.getLogicalHeight() - 1)}. */
		public int maxPlaceableY() {
			return Math.min(maxY, minY + logicalHeight - 1);
		}
	}

	public enum Kind {
		/** Spot with a full 3-deep platform. */
		FULL,
		/** Spot where only the frame line fits. */
		PARTIAL,
		/** No spot: vanilla builds a platform in the air at a clamped Y. */
		FORCED
	}

	/** The new portal's bottom corner (min corner of the 2x3 opening). */
	public record Placement(Pos min, Axis axis, Kind kind) {
		public PortalRect rect() {
			return new PortalRect(min, axis, 2, 3);
		}
	}

	/** Empty when vanilla would fail ("Unable to create a portal"). */
	public static Optional<Placement> place(Pos origin, Axis axis, Level level, Border border, Blocks blocks) {
		int sx = axis.stepX;
		int sz = axis.stepZ;
		long closestFull = -1;
		Pos closestFullPos = null;
		long closestPartial = -1;
		Pos closestPartialPos = null;
		int maxPlaceableY = level.maxPlaceableY();

		for (int[] col : spiral(origin.x(), origin.z(), SPIRAL_RADIUS)) {
			int cx = col[0];
			int cz = col[1];
			int height = Math.min(maxPlaceableY, blocks.motionBlockingHeight(cx, cz));
			if (!border.isWithin(cx, cz) || !border.isWithin(cx + sx, cz + sz)) {
				continue;
			}
			for (int y = height; y >= level.minY(); y--) {
				if (!blocks.replaceable(cx, y, cz)) {
					continue;
				}
				int firstEmptyY = y;
				while (y > level.minY() && blocks.replaceable(cx, y - 1, cz)) {
					y--;
				}
				if (y + 4 <= maxPlaceableY) {
					int deltaY = firstEmptyY - y;
					if (deltaY <= 0 || deltaY >= 3) {
						Pos p = new Pos(cx, y, cz);
						if (canHostFrame(p, axis, 0, blocks)) {
							long d = origin.distSqr(p);
							if (canHostFrame(p, axis, -1, blocks) && canHostFrame(p, axis, 1, blocks)
									&& (closestFull == -1 || closestFull > d)) {
								closestFull = d;
								closestFullPos = p;
							}
							if (closestFull == -1 && (closestPartial == -1 || closestPartial > d)) {
								closestPartial = d;
								closestPartialPos = p;
							}
						}
					}
				}
			}
		}

		if (closestFull != -1) {
			return Optional.of(new Placement(closestFullPos, axis, Kind.FULL));
		}
		if (closestPartial != -1) {
			return Optional.of(new Placement(closestPartialPos, axis, Kind.PARTIAL));
		}
		int minStartY = Math.max(level.minY() + 1, 70);
		int maxStartY = maxPlaceableY - 9;
		if (maxStartY < minStartY) {
			return Optional.empty();
		}
		int y = Math.min(Math.max(origin.y(), minStartY), maxStartY);
		Pos forced = border.clampToBounds(origin.x() - sx, y, origin.z() - sz);
		return Optional.of(new Placement(forced, axis, Kind.FORCED));
	}

	/** Vanilla {@code canHostFrame}: solid floor under the 4-wide frame line, replaceable 4x4 above it. */
	static boolean canHostFrame(Pos origin, Axis axis, int offset, Blocks blocks) {
		for (int width = -1; width < 3; width++) {
			for (int height = -1; height < 4; height++) {
				int x = origin.x() + axis.stepX * width + axis.clockStepX() * offset;
				int y = origin.y() + height;
				int z = origin.z() + axis.stepZ * width + axis.clockStepZ() * offset;
				if (height < 0 && !blocks.solid(x, y, z)) {
					return false;
				}
				if (height >= 0 && !blocks.replaceable(x, y, z)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Vanilla {@code BlockPos.spiralAround(center, radius, EAST, SOUTH)}: the centre, then legs EAST, SOUTH,
	 * WEST, NORTH of 1, 1, 2, 2, 3, 3, ... steps. Yields {x, z}.
	 */
	public static Iterable<int[]> spiral(int centerX, int centerZ, int radius) {
		int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}}; // EAST, SOUTH, WEST, NORTH
		return () -> new Iterator<>() {
			// cursor starts one step SOUTH of the centre; the first move (NORTH) lands on the centre
			private int lastX = centerX;
			private int lastZ = centerZ + 1;
			private final int legs = 4 * radius;
			private int leg = -1;
			private int legSize;
			private int legIndex;
			private int[] next = advance();

			private int[] advance() {
				int[] d = dirs[(leg + 4) % 4];
				lastX += d[0];
				lastZ += d[1];
				if (legIndex >= legSize) {
					if (leg >= legs) {
						return null;
					}
					leg++;
					legIndex = 0;
					legSize = leg / 2 + 1;
				}
				legIndex++;
				return new int[]{lastX, lastZ};
			}

			@Override
			public boolean hasNext() {
				return next != null;
			}

			@Override
			public int[] next() {
				if (next == null) {
					throw new NoSuchElementException();
				}
				int[] out = next;
				next = advance();
				return out;
			}
		};
	}
}

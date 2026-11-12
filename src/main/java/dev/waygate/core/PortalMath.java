package dev.waygate.core;

/**
 * Where a portal points in the other dimension. Mirrors vanilla 26.3
 * {@code NetherPortalBlock.getPortalDestination}:
 * <pre>
 * scale = from.coordinateScale / to.coordinateScale          (DimensionType.getTeleportationScale)
 * exit  = toBorder.clampToBounds(entity.x * scale, entity.y, entity.z * scale)
 * </pre>
 * The entity's exact position is used (not the portal block), x and z are clamped to the target
 * dimension's border, and Y is neither scaled nor clamped.
 */
public final class PortalMath {
	/** Vanilla player hitbox, used for the range of positions a player can be in while touching a portal. */
	public static final double PLAYER_WIDTH = 0.6;
	public static final double PLAYER_HEIGHT = 1.8;
	/** Half thickness of the portal block shape ({@code Block.column(4, 16, 0, 16)}: 4/16 thick). */
	private static final double HALF_THICKNESS = 2.0 / 16.0;

	private PortalMath() {
	}

	/** Vanilla {@code DimensionType.getTeleportationScale}. */
	public static double teleportScale(double fromScale, double toScale) {
		return fromScale / toScale;
	}

	/** The approximate exit position vanilla searches around. */
	public static Pos exit(double x, double y, double z, double scale, Border toBorder) {
		return toBorder.clampToBounds(x * scale, y, z * scale);
	}

	/** Lowest and highest corner of the exits a player can get from anywhere inside {@code rect}. */
	public record ExitRange(Pos low, Pos high) {
		public boolean isSingle() {
			return low.equals(high);
		}

		public boolean contains(Pos p) {
			return p.x() >= low.x() && p.x() <= high.x() && p.y() >= low.y() && p.y() <= high.y()
					&& p.z() >= low.z() && p.z() <= high.z();
		}
	}

	/**
	 * Range of exits for a player anywhere in the portal: along the axis the player's centre stays
	 * between the obsidian sides, across it the player touches the 4/16-thick portal shape, and the feet
	 * are between the bottom and {@code height - playerHeight}.
	 */
	public static ExitRange exitRange(PortalRect rect, double scale, Border toBorder) {
		double half = PLAYER_WIDTH / 2.0;
		double alongMin = (rect.axis() == Axis.X ? rect.min().x() : rect.min().z()) + half;
		double alongMax = (rect.axis() == Axis.X ? rect.min().x() : rect.min().z()) + rect.width() - half;
		if (alongMax < alongMin) {
			alongMin = alongMax = (alongMin + alongMax) / 2.0;
		}
		double acrossCenter = (rect.axis() == Axis.X ? rect.min().z() : rect.min().x()) + 0.5;
		// strictly inside the touch zone, so the far edge does not floor into the next block
		double acrossMin = acrossCenter - HALF_THICKNESS - half + 1e-6;
		double acrossMax = acrossCenter + HALF_THICKNESS + half - 1e-6;
		double yMin = rect.min().y();
		double yMax = Math.max(yMin, rect.min().y() + rect.height() - PLAYER_HEIGHT);
		double xMin = rect.axis() == Axis.X ? alongMin : acrossMin;
		double xMax = rect.axis() == Axis.X ? alongMax : acrossMax;
		double zMin = rect.axis() == Axis.Z ? alongMin : acrossMin;
		double zMax = rect.axis() == Axis.Z ? alongMax : acrossMax;
		// scale may be any positive value; floor is monotonic, so the corners map to the corners
		Pos a = exit(xMin, yMin, zMin, scale, toBorder);
		Pos b = exit(xMax, yMax, zMax, scale, toBorder);
		return new ExitRange(
				new Pos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
				new Pos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
	}

	/** Exit for a player standing in the bottom middle of the portal. */
	public static Pos centerExit(PortalRect rect, double scale, Border toBorder) {
		return exit(rect.centerX(), rect.min().y(), rect.centerZ(), scale, toBorder);
	}
}

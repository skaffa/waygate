package dev.waygate.core;

/**
 * A world border's bounds. Mirrors vanilla {@code WorldBorder.isWithinBounds} and {@code clampToBounds}.
 * Each dimension has its own border in 26.3 (per-level data storage).
 */
public record Border(double minX, double minZ, double maxX, double maxZ) {
	/** Vanilla {@code WorldBorder.absoluteMaxSize}. */
	public static final int ABSOLUTE_MAX = 29999984;
	/** Vanilla default border: size {@code 5.999997E7F} at 0, 0. */
	public static final Border VANILLA_DEFAULT = ofCenter(0, 0, 5.999997E7F);
	/** The {@code 1.0E-5F} that vanilla subtracts from the max edge when clamping (float, widened to double). */
	private static final double EDGE = 1.0E-5F;

	/** Like vanilla {@code StaticBorderExtent}: edges clamped to the absolute maximum. */
	public static Border ofCenter(double centerX, double centerZ, double size) {
		double h = size / 2.0;
		return new Border(
				clamp(centerX - h, -ABSOLUTE_MAX, ABSOLUTE_MAX),
				clamp(centerZ - h, -ABSOLUTE_MAX, ABSOLUTE_MAX),
				clamp(centerX + h, -ABSOLUTE_MAX, ABSOLUTE_MAX),
				clamp(centerZ + h, -ABSOLUTE_MAX, ABSOLUTE_MAX));
	}

	/** Vanilla {@code isWithinBounds(x, z)}: min inclusive, max exclusive. */
	public boolean isWithin(double x, double z) {
		return x >= minX && x < maxX && z >= minZ && z < maxZ;
	}

	public boolean isWithin(Pos p) {
		return isWithin(p.x(), p.z());
	}

	public double clampX(double x) {
		return clamp(x, minX, maxX - EDGE);
	}

	public double clampZ(double z) {
		return clamp(z, minZ, maxZ - EDGE);
	}

	/** Vanilla {@code clampToBounds(x, y, z)}: clamp x and z, then {@code BlockPos.containing} (floor). Y is untouched. */
	public Pos clampToBounds(double x, double y, double z) {
		return new Pos(floor(clampX(x)), floor(y), floor(clampZ(z)));
	}

	/** Vanilla {@code Mth.clamp(double, double, double)}. */
	static double clamp(double v, double min, double max) {
		return v < min ? min : Math.min(v, max);
	}

	/** Vanilla {@code Mth.floor}. */
	static int floor(double v) {
		return (int) Math.floor(v);
	}
}

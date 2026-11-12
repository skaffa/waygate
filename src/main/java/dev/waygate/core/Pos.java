package dev.waygate.core;

/** Block position. Mirrors vanilla {@code BlockPos} for the parts Waygate needs. */
public record Pos(int x, int y, int z) {
	/** Vanilla {@code Vec3i.distSqr}: squared distance between the two low corners. Exact as a long. */
	public long distSqr(Pos o) {
		long dx = (long) x - o.x;
		long dy = (long) y - o.y;
		long dz = (long) z - o.z;
		return dx * dx + dy * dy + dz * dz;
	}

	public Pos offset(int dx, int dy, int dz) {
		return new Pos(x + dx, y + dy, z + dz);
	}

	@Override
	public String toString() {
		return x + " " + y + " " + z;
	}
}

package dev.waygate.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The search for an existing exit portal. Mirrors vanilla 26.3 {@code PortalForcer.findClosestPortalPosition}:
 * every portal block within a horizontal square ({@code |dx| <= r && |dz| <= r}, any Y) of the exit, inside
 * the target border, closest by 3D squared distance, then lowest Y. r is 16 when going to the nether and
 * 128 otherwise.
 */
public final class PortalSearch {
	public static final int NETHER_RADIUS = 16;
	public static final int OVERWORLD_RADIUS = 128;

	/** Vanilla order: squared distance, then Y. */
	public static final Comparator<Hit> ORDER = Comparator.<Hit>comparingLong(Hit::distSqr).thenComparingInt(h -> h.block().y());

	private PortalSearch() {
	}

	public static int radius(boolean toNether) {
		return toNether ? NETHER_RADIUS : OVERWORLD_RADIUS;
	}

	/** Vanilla {@code PoiManager.getInSquare} filter. */
	public static boolean inSquare(Pos p, Pos center, int radius) {
		return Math.abs(p.x() - center.x()) <= radius && Math.abs(p.z() - center.z()) <= radius;
	}

	/** Exactly vanilla's choice over loose portal blocks (e.g. POIs from the integrated server). */
	public static Optional<Pos> closestBlock(Pos exit, boolean toNether, Border border, Iterable<Pos> blocks) {
		int r = radius(toNether);
		Pos best = null;
		long bestD = Long.MAX_VALUE;
		for (Pos p : blocks) {
			if (!inSquare(p, exit, r) || !border.isWithin(p)) {
				continue;
			}
			long d = p.distSqr(exit);
			if (best == null || d < bestD || (d == bestD && p.y() < best.y())) {
				best = p;
				bestD = d;
			}
		}
		return Optional.ofNullable(best);
	}

	/** The best block of one portal and its distance to the exit. */
	public record Hit(int portal, Pos block, long distSqr) {
		public double distance() {
			return Math.sqrt(distSqr);
		}
	}

	/**
	 * Result of a search over known portals.
	 * @param ranked every portal with at least one block in range, best first (vanilla links to the first)
	 * @param ambiguous the first two have the same distance and Y, so vanilla's pick depends on storage order
	 */
	public record Result(Pos exit, int radius, List<Hit> ranked, boolean ambiguous) {
		public Optional<Hit> chosen() {
			return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.get(0));
		}

		public boolean createsNew() {
			return ranked.isEmpty();
		}
	}

	public static Result search(Pos exit, boolean toNether, Border border, List<PortalRect> portals) {
		int r = radius(toNether);
		List<Hit> hits = new ArrayList<>();
		for (int i = 0; i < portals.size(); i++) {
			Hit best = null;
			for (Pos b : portals.get(i).blocks()) {
				if (!inSquare(b, exit, r) || !border.isWithin(b)) {
					continue;
				}
				Hit h = new Hit(i, b, b.distSqr(exit));
				if (best == null || ORDER.compare(h, best) < 0) {
					best = h;
				}
			}
			if (best != null) {
				hits.add(best);
			}
		}
		hits.sort(ORDER);
		boolean ambiguous = hits.size() > 1 && ORDER.compare(hits.get(0), hits.get(1)) == 0;
		return new Result(exit, r, List.copyOf(hits), ambiguous);
	}
}

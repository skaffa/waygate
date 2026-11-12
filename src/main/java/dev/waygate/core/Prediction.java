package dev.waygate.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everything the HUD says about one portal: where it points, which portal it links to, whether the trip
 * back returns here, and what is wrong. Minecraft-free; the client supplies the portals through
 * {@link Portals} (remembered ones, or exact ones from the integrated server).
 */
public final class Prediction {
	/** Range limit for checking every exit a big portal can reach (a 21-wide nether portal spans 168 blocks). */
	private static final int MAX_RANGE_POINTS = 20_000;

	/** Source of known portals on either side. */
	public interface Portals {
		/**
		 * Portals in the target dimension ({@code targetSide}) or the source dimension around {@code center},
		 * at least all within the square of {@code radius}.
		 */
		List<PortalRect> around(boolean targetSide, Pos center, int radius);
	}

	/**
	 * @param source the portal (null: predict for the player's spot without a portal)
	 * @param x player x (or the portal centre)
	 */
	public record Input(PortalRect source, double x, double y, double z, Dim from, double fromScale, double toScale,
			Border fromBorder, Border toBorder) {
		public Dim to() {
			return from.portalTarget();
		}
	}

	public enum WarningKind {
		/** A portal that leads back here exists, but another one wins. */
		MISLINK,
		/** The linked portal's own trip leads somewhere else (or to a new portal). */
		ONE_WAY,
		/** Spots inside this portal reach different exits. */
		SPLIT,
		/** Two portals at exactly the same distance and Y: vanilla's pick depends on storage order. */
		AMBIGUOUS,
		/** The target was moved inside the other dimension's world border. */
		CLAMPED
	}

	/**
	 * @param other the portal it is about (MISLINK: the one that would lead back; ONE_WAY: where the trip back
	 *              goes, null for a new portal)
	 */
	public record Warning(WarningKind kind, PortalRect other, double distance, double otherDistance, int count) {
	}

	/**
	 * @param back where the linked portal leads back to (empty for a new portal), only when {@code linked} is set
	 * @param returnsHere the trip back ends in the source portal
	 * @param intended portals on the target side that would lead back here
	 */
	public record Result(Pos exit, PortalMath.ExitRange range, PortalSearch.Result search, List<PortalRect> candidates,
			Optional<PortalRect> linked, Optional<PortalRect> back, boolean returnsHere, List<PortalRect> intended,
			List<Warning> warnings) {
		public Optional<PortalSearch.Hit> hit() {
			return search.chosen();
		}

		public boolean has(WarningKind k) {
			return warnings.stream().anyMatch(w -> w.kind() == k);
		}
	}

	public static Result predict(Input in, Portals portals) {
		double scale = PortalMath.teleportScale(in.fromScale(), in.toScale());
		Pos exit = PortalMath.exit(in.x(), in.y(), in.z(), scale, in.toBorder());
		boolean clamped = exit.x() != (int) Math.floor(in.x() * scale) || exit.z() != (int) Math.floor(in.z() * scale);
		PortalMath.ExitRange range = in.source() == null
				? new PortalMath.ExitRange(exit, exit)
				: PortalMath.exitRange(in.source(), scale, in.toBorder());
		// the range must contain the player's own exit (it can stand slightly outside the computed zone)
		range = new PortalMath.ExitRange(min(range.low(), exit), max(range.high(), exit));

		boolean toNether = in.to() == Dim.NETHER;
		int r = PortalSearch.radius(toNether);
		int span = Math.max(range.high().x() - range.low().x(), range.high().z() - range.low().z());
		List<PortalRect> cand = portals.around(true, exit, r + span + 1);
		PortalSearch.Result search = PortalSearch.search(exit, toNether, in.toBorder(), cand);

		List<Warning> warnings = new ArrayList<>();
		if (clamped) {
			warnings.add(new Warning(WarningKind.CLAMPED, null, 0, 0, 0));
		}
		if (search.ambiguous()) {
			warnings.add(new Warning(WarningKind.AMBIGUOUS, cand.get(search.ranked().get(1).portal()), 0, 0, 0));
		}

		Set<Integer> outcomes = outcomes(range, toNether, in.toBorder(), cand);
		if (outcomes.size() > 1) {
			warnings.add(new Warning(WarningKind.SPLIT, null, 0, 0, outcomes.size()));
		}

		Optional<PortalRect> linked = search.chosen().map(h -> cand.get(h.portal()));
		Optional<PortalRect> back = Optional.empty();
		boolean returnsHere = false;
		List<PortalRect> intended = new ArrayList<>();
		boolean backKnown = in.from() != Dim.OTHER;
		if (backKnown && linked.isPresent()) {
			back = backTarget(linked.get(), in, portals);
			returnsHere = in.source() != null && back.isPresent() && overlaps(back.get(), in.source());
		}
		if (backKnown && in.source() != null) {
			for (PortalRect t : cand) {
				if (linked.isPresent() && t.equals(linked.get())) {
					if (returnsHere) {
						intended.add(t);
					}
					continue;
				}
				Optional<PortalRect> b = backTarget(t, in, portals);
				if (b.isPresent() && overlaps(b.get(), in.source())) {
					intended.add(t);
				}
			}
		}
		if (linked.isPresent() && !intended.isEmpty() && !returnsHere) {
			PortalRect want = intended.getFirst();
			double wantD = search.ranked().stream().filter(h -> cand.get(h.portal()).equals(want)).findFirst()
					.map(PortalSearch.Hit::distance).orElse(Double.NaN);
			warnings.add(new Warning(WarningKind.MISLINK, want, search.chosen().get().distance(), wantD, intended.size()));
		}
		if (in.source() != null && linked.isPresent() && backKnown && !returnsHere) {
			warnings.add(new Warning(WarningKind.ONE_WAY, back.orElse(null), 0, 0, 0));
		}
		return new Result(exit, range, search, cand, linked, back, returnsHere, List.copyOf(intended), List.copyOf(warnings));
	}

	/** Where a player standing in the middle of {@code t} (target side) ends up back in the source dimension. */
	static Optional<PortalRect> backTarget(PortalRect t, Input in, Portals portals) {
		double scale = PortalMath.teleportScale(in.toScale(), in.fromScale());
		Pos backExit = PortalMath.centerExit(t, scale, in.fromBorder());
		boolean toNether = in.from() == Dim.NETHER;
		int r = PortalSearch.radius(toNether);
		List<PortalRect> fromCand = portals.around(false, backExit, r + 1);
		PortalSearch.Result res = PortalSearch.search(backExit, toNether, in.fromBorder(), fromCand);
		return res.chosen().map(h -> fromCand.get(h.portal()));
	}

	/** Distinct outcomes (portal index or -1) over every exit in the range; empty when the range is too big. */
	static Set<Integer> outcomes(PortalMath.ExitRange range, boolean toNether, Border border, List<PortalRect> cand) {
		Set<Integer> out = new LinkedHashSet<>();
		long points = (long) (range.high().x() - range.low().x() + 1) * (range.high().z() - range.low().z() + 1)
				* (range.high().y() - range.low().y() + 1);
		if (points > MAX_RANGE_POINTS) {
			return out;
		}
		for (int x = range.low().x(); x <= range.high().x(); x++) {
			for (int z = range.low().z(); z <= range.high().z(); z++) {
				for (int y = range.low().y(); y <= range.high().y(); y++) {
					out.add(PortalSearch.search(new Pos(x, y, z), toNether, border, cand).chosen()
							.map(PortalSearch.Hit::portal).orElse(-1));
				}
			}
		}
		return out;
	}

	static boolean overlaps(PortalRect a, PortalRect b) {
		return PortalMemory.overlaps(a, b);
	}

	private static Pos min(Pos a, Pos b) {
		return new Pos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
	}

	private static Pos max(Pos a, Pos b) {
		return new Pos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
	}
}

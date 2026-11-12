package dev.waygate.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Both-ways link check between the portals of two dimensions, and the partner planner for {@code /waygate plan}. */
public final class Pairing {
	private Pairing() {
	}

	/** One side of the portal network: a dimension's known portals, its scale and border. */
	public record Side(Dim dim, double scale, Border border, List<PortalRect> portals) {
	}

	public enum Status {
		/** A leads to B and B leads back to A. */
		PAIRED,
		/** A leads to B, but B leads to another portal (or to a new one). */
		ONE_WAY,
		/** A finds no portal: a new one will be created. */
		NO_EXIT
	}

	/**
	 * @param target index of the portal in the other side that A links to (from its centre), or -1
	 * @param back index in A's side that the target links back to, or -1 for a new portal
	 * @param split spots inside A reach more than one outcome (part of A leads elsewhere)
	 * @param ambiguous a distance-and-Y tie in either direction
	 */
	public record Link(int portal, Status status, int target, int back, boolean split, boolean ambiguous) {
	}

	/** Outcome index for an exit: the chosen portal or -1 for "new portal". */
	static int outcome(PortalSearch.Result r) {
		return r.chosen().map(PortalSearch.Hit::portal).orElse(-1);
	}

	/** Search from a player standing in the bottom middle of {@code from}. */
	public static PortalSearch.Result forward(PortalRect from, Side fromSide, Side toSide) {
		double scale = PortalMath.teleportScale(fromSide.scale(), toSide.scale());
		Pos exit = PortalMath.centerExit(from, scale, toSide.border());
		return PortalSearch.search(exit, toSide.dim() == Dim.NETHER, toSide.border(), toSide.portals());
	}

	/** Every outcome reachable from some spot inside {@code from}. */
	public static Set<Integer> outcomes(PortalRect from, Side fromSide, Side toSide) {
		double scale = PortalMath.teleportScale(fromSide.scale(), toSide.scale());
		PortalMath.ExitRange range = PortalMath.exitRange(from, scale, toSide.border());
		Set<Integer> out = new LinkedHashSet<>();
		boolean toNether = toSide.dim() == Dim.NETHER;
		for (int x = range.low().x(); x <= range.high().x(); x++) {
			for (int z = range.low().z(); z <= range.high().z(); z++) {
				for (int y = range.low().y(); y <= range.high().y(); y++) {
					out.add(outcome(PortalSearch.search(new Pos(x, y, z), toNether, toSide.border(), toSide.portals())));
				}
			}
		}
		return out;
	}

	/** Links of every portal in {@code a} to {@code b} and back. */
	public static List<Link> check(Side a, Side b) {
		List<Link> links = new ArrayList<>();
		for (int i = 0; i < a.portals().size(); i++) {
			PortalRect p = a.portals().get(i);
			PortalSearch.Result fwd = forward(p, a, b);
			int target = outcome(fwd);
			boolean split = outcomes(p, a, b).size() > 1;
			if (target < 0) {
				links.add(new Link(i, Status.NO_EXIT, -1, -1, split, fwd.ambiguous()));
				continue;
			}
			PortalSearch.Result back = forward(b.portals().get(target), b, a);
			int backIdx = outcome(back);
			Status s = backIdx == i ? Status.PAIRED : Status.ONE_WAY;
			links.add(new Link(i, s, target, backIdx, split, fwd.ambiguous() || back.ambiguous()));
		}
		return links;
	}

	/**
	 * Plan for the partner of portal {@code p} (in side {@code a}): build it with its bottom corner at
	 * {@code target} in side {@code b}.
	 * @param target P's centre exit, Y clamped to where a 2x3 portal fits in {@code b}
	 * @param competitors portals in {@code b} that would beat (or tie with) a partner at {@code target}
	 * @param backBlockers portals in {@code a} that the partner would link back to instead of {@code p}
	 * @param inRange portals in {@code b} inside the search square of P's exit; they win if the partner is
	 *                built somewhere else
	 */
	public record Plan(Pos target, List<Integer> competitors, List<Integer> backBlockers, List<Integer> inRange) {
		public boolean clean() {
			return competitors.isEmpty() && backBlockers.isEmpty();
		}
	}

	public static Plan plan(PortalRect p, Side a, Side b, CreatePortalSim.Level bLevel) {
		double scale = PortalMath.teleportScale(a.scale(), b.scale());
		Pos exit = PortalMath.centerExit(p, scale, b.border());
		// opening 3 high, frame top at +3 must stay at or below the buildable top
		int maxY = bLevel.maxPlaceableY() - 3;
		int y = Math.min(Math.max(exit.y(), bLevel.minY() + 1), maxY);
		Pos target = new Pos(exit.x(), y, exit.z());
		PortalRect partner = new PortalRect(target, p.axis(), 2, 3);

		List<PortalRect> bPortals = new ArrayList<>(b.portals());
		bPortals.add(partner);
		int partnerIdx = bPortals.size() - 1;
		Side b2 = new Side(b.dim(), b.scale(), b.border(), bPortals);

		PortalSearch.Result fwd = PortalSearch.search(exit, b.dim() == Dim.NETHER, b.border(), bPortals);
		List<Integer> competitors = beating(fwd, partnerIdx);
		List<Integer> inRange = new ArrayList<>();
		for (PortalSearch.Hit h : fwd.ranked()) {
			if (h.portal() != partnerIdx) {
				inRange.add(h.portal());
			}
		}

		int pIdx = a.portals().indexOf(p);
		List<PortalRect> aPortals = a.portals();
		if (pIdx < 0) {
			aPortals = new ArrayList<>(a.portals());
			aPortals.add(p);
			pIdx = aPortals.size() - 1;
		}
		Side a2 = new Side(a.dim(), a.scale(), a.border(), aPortals);
		List<Integer> backBlockers = beating(forward(partner, b2, a2), pIdx);
		return new Plan(target, competitors, backBlockers, inRange);
	}

	/** Portals in {@code r} that rank before or level with {@code own} (all of them if {@code own} is out of range). */
	static List<Integer> beating(PortalSearch.Result r, int own) {
		PortalSearch.Hit ownHit = null;
		for (PortalSearch.Hit h : r.ranked()) {
			if (h.portal() == own) {
				ownHit = h;
			}
		}
		List<Integer> out = new ArrayList<>();
		for (PortalSearch.Hit h : r.ranked()) {
			if (h.portal() != own && (ownHit == null || PortalSearch.ORDER.compare(h, ownHit) <= 0)) {
				out.add(h.portal());
			}
		}
		return out;
	}
}

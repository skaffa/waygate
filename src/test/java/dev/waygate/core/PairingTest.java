package dev.waygate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PairingTest {
	static final Border B = Border.VANILLA_DEFAULT;

	static PortalRect portal(int x, int y, int z) {
		return new PortalRect(new Pos(x, y, z), Axis.X, 2, 3);
	}

	static Pairing.Side ow(PortalRect... p) {
		return new Pairing.Side(Dim.OVERWORLD, 1, B, List.of(p));
	}

	static Pairing.Side nether(PortalRect... p) {
		return new Pairing.Side(Dim.NETHER, 8, B, List.of(p));
	}

	@Test
	void correctPairLinksBothWays() {
		var a = ow(portal(800, 64, 0));
		var b = nether(portal(100, 64, 0));
		var links = Pairing.check(a, b);
		assertEquals(Pairing.Status.PAIRED, links.get(0).status());
		assertEquals(0, links.get(0).target());
		assertEquals(Pairing.Status.PAIRED, Pairing.check(b, a).get(0).status());
	}

	@Test
	void noPortalInRangeMeansNewPortal() {
		// exit (200, 64, 0); the nether portal at 100 is 100 blocks away, outside 16
		var links = Pairing.check(ow(portal(1600, 64, 0)), nether(portal(100, 64, 0)));
		assertEquals(Pairing.Status.NO_EXIT, links.get(0).status());
		assertEquals(-1, links.get(0).target());
	}

	@Test
	void olderPortalCloserToTheBackExitBreaksTheLink() {
		// P2 -> N2 (210 is within 16 of 200), N2 back-exit (1688, 64, 4): P3 at 1700 is closer than P2 at 1600
		var a = ow(portal(1600, 64, 0), portal(1700, 64, 0));
		var b = nether(portal(210, 64, 0));
		var links = Pairing.check(a, b);
		assertEquals(Pairing.Status.ONE_WAY, links.get(0).status());
		assertEquals(0, links.get(0).target());
		assertEquals(1, links.get(0).back());
		// P3 -> exit (212, 64, 0) -> N2 -> back to P3: paired
		assertEquals(Pairing.Status.PAIRED, links.get(1).status());
	}

	@Test
	void wideRangeThatReachesTwoPortalsIsSplit() {
		// nether portal at x 300..301 -> overworld exits x 2402..2413
		var n = nether(portal(300, 64, 0));
		var o = ow(portal(2390, 64, 4), portal(2425, 64, 4));
		var link = Pairing.check(n, o).get(0);
		assertTrue(link.split());
		assertEquals(2, Pairing.outcomes(portal(300, 64, 0), n, o).size());
		// with only one overworld portal there is no split
		assertFalse(Pairing.check(n, ow(portal(2390, 64, 4))).get(0).split());
	}

	@Test
	void ambiguousTieIsReported() {
		// exit (100, 64, 0): two 1-wide portals 3 blocks north and south, both 9 away at the same Y
		var a = ow(portal(800, 64, 0));
		var b = nether(new PortalRect(new Pos(100, 64, 3), Axis.X, 1, 3), new PortalRect(new Pos(100, 64, -3), Axis.X, 1, 3));
		assertTrue(Pairing.check(a, b).get(0).ambiguous());
	}

	@Test
	void planForAPortalWithoutPartnerIsClean() {
		var a = ow(portal(1600, 64, 0));
		var b = nether(portal(100, 64, 0));
		var plan = Pairing.plan(a.portals().get(0), a, b, CreatePortalSim.Level.NETHER);
		assertEquals(new Pos(200, 64, 0), plan.target());
		assertTrue(plan.clean());
		assertTrue(plan.inRange().isEmpty());
	}

	@Test
	void planReportsCompetitorsAndBackBlockers() {
		// a nether portal already sits on the target: it ties with the planned partner
		var a = ow(portal(1600, 64, 0), portal(1608, 64, 4));
		var b = nether(portal(200, 64, 0));
		var plan = Pairing.plan(a.portals().get(0), a, b, CreatePortalSim.Level.NETHER);
		assertEquals(List.of(0), plan.competitors());
		assertEquals(List.of(0), plan.inRange());
		// partner back-exit (1608, 64, 4) is exactly where the second overworld portal is
		assertEquals(List.of(1), plan.backBlockers());
		assertFalse(plan.clean());
	}

	@Test
	void planClampsYIntoTheNether() {
		var a = ow(portal(800, 200, 0));
		var plan = Pairing.plan(a.portals().get(0), a, nether(), CreatePortalSim.Level.NETHER);
		// opening top at y+2, frame at y+3 <= 127
		assertEquals(124, plan.target().y());
		var low = ow(portal(800, -40, 0));
		assertEquals(1, Pairing.plan(low.portals().get(0), low, nether(), CreatePortalSim.Level.NETHER).target().y());
	}

	@Test
	void planForAPortalNotYetBuilt() {
		var a = ow(portal(800, 64, 0));
		var b = nether(portal(100, 64, 0));
		// planning a second overworld portal 32 blocks away: its partner at (104, 64, 0) is closer than N1
		var plan = Pairing.plan(portal(832, 64, 0), a, b, CreatePortalSim.Level.NETHER);
		assertEquals(new Pos(104, 64, 0), plan.target());
		assertTrue(plan.competitors().isEmpty());
		assertEquals(List.of(0), plan.inRange());
		assertTrue(plan.backBlockers().isEmpty());
	}

	@Test
	void customScales() {
		// a datapack nether with scale 4: overworld 800 -> 200
		var a = ow(portal(800, 64, 0));
		var b = new Pairing.Side(Dim.NETHER, 4, B, List.of(portal(200, 64, 0)));
		assertEquals(Pairing.Status.PAIRED, Pairing.check(a, b).get(0).status());
		assertEquals(Pairing.Status.NO_EXIT, Pairing.check(a, nether(portal(200, 64, 0))).get(0).status());
	}
}

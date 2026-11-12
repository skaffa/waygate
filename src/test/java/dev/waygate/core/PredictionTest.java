package dev.waygate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PredictionTest {
	static final Border B = Border.VANILLA_DEFAULT;

	static PortalRect portal(int x, int y, int z) {
		return new PortalRect(new Pos(x, y, z), Axis.X, 2, 3);
	}

	static Prediction.Portals world(List<PortalRect> overworld, List<PortalRect> nether, Dim from) {
		return (targetSide, center, radius) -> (targetSide == (from != Dim.NETHER)) ? nether : overworld;
	}

	static Prediction.Input fromOverworld(PortalRect p) {
		return new Prediction.Input(p, p.centerX(), p.min().y(), p.centerZ(), Dim.OVERWORLD, 1, 8, B, B);
	}

	static Prediction.Input fromNether(PortalRect p) {
		return new Prediction.Input(p, p.centerX(), p.min().y(), p.centerZ(), Dim.NETHER, 8, 1, B, B);
	}

	@Test
	void correctPairHasNoWarnings() {
		PortalRect p = portal(800, 64, 0);
		PortalRect n = portal(100, 64, 0);
		var r = Prediction.predict(fromOverworld(p), world(List.of(p), List.of(n), Dim.OVERWORLD));
		assertEquals(new Pos(100, 64, 0), r.exit());
		assertEquals(n, r.linked().orElseThrow());
		assertTrue(r.returnsHere());
		assertEquals(List.of(n), r.intended());
		assertTrue(r.warnings().isEmpty(), r.warnings().toString());
	}

	@Test
	void nothingInRangeCreatesNewWithoutWarnings() {
		PortalRect p = portal(1600, 64, 0);
		var r = Prediction.predict(fromOverworld(p), world(List.of(p), List.of(portal(100, 64, 0)), Dim.OVERWORLD));
		assertTrue(r.search().createsNew());
		assertTrue(r.linked().isEmpty());
		assertTrue(r.warnings().isEmpty());
	}

	@Test
	void closerPortalStealsTheLinkFromThePartner() {
		// P -> exit (200, 64, 0). B at 212 would lead back to P, but C at 190 is closer and leads to Q.
		PortalRect p = portal(1600, 64, 0);
		PortalRect q = portal(1520, 64, 4);
		PortalRect b = portal(212, 64, 0);
		PortalRect c = portal(190, 64, 0);
		var r = Prediction.predict(fromOverworld(p), world(List.of(p, q), List.of(b, c), Dim.OVERWORLD));
		assertEquals(c, r.linked().orElseThrow());
		assertEquals(q, r.back().orElseThrow());
		assertFalse(r.returnsHere());
		assertEquals(List.of(b), r.intended());
		assertTrue(r.has(Prediction.WarningKind.MISLINK));
		assertTrue(r.has(Prediction.WarningKind.ONE_WAY));
		Prediction.Warning m = r.warnings().stream().filter(w -> w.kind() == Prediction.WarningKind.MISLINK).findFirst().orElseThrow();
		assertEquals(b, m.other());
		// exit x 200: C's nearest block 191 is 9 away, B's nearest block 212 is 12 away
		assertEquals(9.0, m.distance());
		assertEquals(12.0, m.otherDistance());
	}

	@Test
	void oneWayWhenTheReturnTripGoesElsewhere() {
		// P2 -> N2; N2's trip back lands at P3, which is closer to N2's exit
		PortalRect p2 = portal(1600, 64, 0);
		PortalRect p3 = portal(1700, 64, 0);
		PortalRect n2 = portal(210, 64, 0);
		var r = Prediction.predict(fromOverworld(p2), world(List.of(p2, p3), List.of(n2), Dim.OVERWORLD));
		assertEquals(n2, r.linked().orElseThrow());
		assertEquals(p3, r.back().orElseThrow());
		assertTrue(r.has(Prediction.WarningKind.ONE_WAY));
		assertFalse(r.has(Prediction.WarningKind.MISLINK));
	}

	@Test
	void splitNetherPortal() {
		PortalRect n = portal(300, 64, 0);
		var r = Prediction.predict(fromNether(n), world(List.of(portal(2390, 64, 4), portal(2425, 64, 4)), List.of(n), Dim.NETHER));
		assertTrue(r.has(Prediction.WarningKind.SPLIT));
		assertEquals(2, r.warnings().stream().filter(w -> w.kind() == Prediction.WarningKind.SPLIT).findFirst().orElseThrow().count());
	}

	@Test
	void ambiguousIsReported() {
		PortalRect p = portal(800, 64, 0);
		var r = Prediction.predict(fromOverworld(p), world(List.of(p),
				List.of(new PortalRect(new Pos(100, 64, 3), Axis.X, 1, 3), new PortalRect(new Pos(100, 64, -3), Axis.X, 1, 3)),
				Dim.OVERWORLD));
		assertTrue(r.has(Prediction.WarningKind.AMBIGUOUS));
	}

	@Test
	void borderClampIsReported() {
		Border netherBorder = Border.ofCenter(0, 0, 100); // -50 .. 50
		PortalRect p = portal(800, 64, 0);
		var in = new Prediction.Input(p, 801, 64, 0.5, Dim.OVERWORLD, 1, 8, B, netherBorder);
		var r = Prediction.predict(in, world(List.of(p), List.of(), Dim.OVERWORLD));
		assertEquals(49, r.exit().x());
		assertTrue(r.has(Prediction.WarningKind.CLAMPED));
	}

	@Test
	void playerSpotWithoutPortal() {
		var in = new Prediction.Input(null, 801, 70, 0.5, Dim.OVERWORLD, 1, 8, B, B);
		var r = Prediction.predict(in, world(List.of(), List.of(portal(100, 64, 0)), Dim.OVERWORLD));
		assertEquals(portal(100, 64, 0), r.linked().orElseThrow());
		assertTrue(r.warnings().isEmpty());
		assertTrue(r.range().isSingle());
	}

	@Test
	void customDimensionOnlyLooksForward() {
		var in = new Prediction.Input(portal(0, 64, 0), 1, 64, 0.5, Dim.OTHER, 1, 8, B, B);
		var r = Prediction.predict(in, world(List.of(), List.of(portal(0, 64, 0)), Dim.OTHER));
		assertTrue(r.linked().isPresent());
		assertFalse(r.has(Prediction.WarningKind.ONE_WAY));
	}
}

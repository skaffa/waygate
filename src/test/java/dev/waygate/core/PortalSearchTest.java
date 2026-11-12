package dev.waygate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PortalSearchTest {
	static final Border B = Border.VANILLA_DEFAULT;

	static PortalRect portal(int x, int y, int z) {
		return new PortalRect(new Pos(x, y, z), Axis.X, 2, 3);
	}

	@Test
	void radiusIsSixteenToNetherAndOneTwentyEightElsewhere() {
		assertEquals(16, PortalSearch.radius(true));
		assertEquals(128, PortalSearch.radius(false));
	}

	@Test
	void netherSquareEdges() {
		Pos exit = new Pos(0, 64, 0);
		// x 16 is inside (|dx| <= 16), corner of the square too
		assertTrue(PortalSearch.closestBlock(exit, true, B, List.of(new Pos(16, 64, 16))).isPresent());
		assertTrue(PortalSearch.closestBlock(exit, true, B, List.of(new Pos(-16, 64, -16))).isPresent());
		assertFalse(PortalSearch.closestBlock(exit, true, B, List.of(new Pos(17, 64, 0))).isPresent());
		assertFalse(PortalSearch.closestBlock(exit, true, B, List.of(new Pos(0, 64, -17))).isPresent());
	}

	@Test
	void overworldSquareEdges() {
		Pos exit = new Pos(1000, 70, -1000);
		assertTrue(PortalSearch.closestBlock(exit, false, B, List.of(new Pos(1128, 70, -872))).isPresent());
		assertFalse(PortalSearch.closestBlock(exit, false, B, List.of(new Pos(1129, 70, -1000))).isPresent());
		assertFalse(PortalSearch.closestBlock(exit, false, B, List.of(new Pos(1000, 70, -1129))).isPresent());
	}

	@Test
	void anyYCountsButYAddsToDistance() {
		Pos exit = new Pos(0, 64, 0);
		// far above, still inside the square
		assertTrue(PortalSearch.closestBlock(exit, true, B, List.of(new Pos(3, 250, 3))).isPresent());
		// A: 5 away horizontally, same Y (25). B: 3 away horizontally, 10 up (109). A wins.
		Pos a = new Pos(5, 64, 0);
		Pos b = new Pos(3, 74, 0);
		assertEquals(Optional.of(a), PortalSearch.closestBlock(exit, true, B, List.of(b, a)));
	}

	@Test
	void sameDistanceLowerYWins() {
		Pos exit = new Pos(0, 64, 0);
		Pos up = new Pos(0, 67, 0);
		Pos down = new Pos(0, 61, 0);
		assertEquals(Optional.of(down), PortalSearch.closestBlock(exit, true, B, List.of(up, down)));
		PortalSearch.Result r = PortalSearch.search(exit, true, B, List.of(
				new PortalRect(new Pos(0, 67, 0), Axis.X, 1, 1), new PortalRect(new Pos(0, 61, 0), Axis.X, 1, 1)));
		assertEquals(1, r.chosen().orElseThrow().portal());
		assertFalse(r.ambiguous());
	}

	@Test
	void exactTieIsAmbiguous() {
		Pos exit = new Pos(0, 64, 0);
		PortalSearch.Result r = PortalSearch.search(exit, true, B, List.of(portal(5, 64, 3), portal(-6, 64, -3)));
		// nearest blocks (5,64,3) and (-5,64,-3): both 34
		assertEquals(34, r.ranked().get(0).distSqr());
		assertEquals(34, r.ranked().get(1).distSqr());
		assertTrue(r.ambiguous());
	}

	@Test
	void largePortalUsesItsClosestBlock() {
		// 10 wide, 8 high along Z at x 4, z -2..7, y 60..67
		PortalRect big = new PortalRect(new Pos(4, 60, -2), Axis.Z, 10, 8);
		PortalSearch.Result r = PortalSearch.search(new Pos(0, 64, 3), true, B, List.of(big));
		assertEquals(new Pos(4, 64, 3), r.chosen().orElseThrow().block());
		assertEquals(16, r.chosen().orElseThrow().distSqr());
		// exit below the portal: the bottom row is closest
		r = PortalSearch.search(new Pos(4, 20, 30), false, B, List.of(big));
		assertEquals(new Pos(4, 60, 7), r.chosen().orElseThrow().block());
	}

	@Test
	void partOfAPortalInsideTheSquareIsEnough() {
		// portal spans x 15..18; only blocks with x <= 16 are in the nether square
		PortalRect r = new PortalRect(new Pos(15, 64, 0), Axis.X, 4, 3);
		PortalSearch.Result res = PortalSearch.search(new Pos(0, 64, 0), true, B, List.of(r));
		assertEquals(new Pos(15, 64, 0), res.chosen().orElseThrow().block());
	}

	@Test
	void portalsOutsideTheBorderAreSkipped() {
		Border small = Border.ofCenter(0, 0, 20); // -10 .. 10, max exclusive
		Pos exit = new Pos(9, 64, 0);
		assertFalse(PortalSearch.closestBlock(exit, true, small, List.of(new Pos(10, 64, 0))).isPresent());
		assertEquals(Optional.of(new Pos(-5, 64, 0)),
				PortalSearch.closestBlock(exit, true, small, List.of(new Pos(10, 64, 0), new Pos(-5, 64, 0))));
	}

	@Test
	void nothingFoundCreatesNew() {
		PortalSearch.Result r = PortalSearch.search(new Pos(0, 64, 0), true, B, List.of(portal(40, 64, 0)));
		assertTrue(r.createsNew());
		assertTrue(r.chosen().isEmpty());
	}

	@Test
	void rankingIsBestFirst() {
		PortalSearch.Result r = PortalSearch.search(new Pos(0, 64, 0), false, B,
				List.of(portal(100, 64, 0), portal(10, 64, 0), portal(-50, 64, 0)));
		assertEquals(List.of(1, 2, 0), r.ranked().stream().map(PortalSearch.Hit::portal).toList());
	}

	@Test
	void searchAgreesWithVanillaBlockChoiceOnRandomSets() {
		Random rnd = new Random(1234);
		for (int round = 0; round < 500; round++) {
			boolean toNether = rnd.nextBoolean();
			int spread = toNether ? 30 : 200;
			List<PortalRect> portals = new ArrayList<>();
			List<Pos> blocks = new ArrayList<>();
			int n = rnd.nextInt(6);
			for (int i = 0; i < n; i++) {
				PortalRect p = new PortalRect(new Pos(rnd.nextInt(2 * spread) - spread, rnd.nextInt(100), rnd.nextInt(2 * spread) - spread),
						rnd.nextBoolean() ? Axis.X : Axis.Z, 1 + rnd.nextInt(4), 1 + rnd.nextInt(4));
				portals.add(p);
				blocks.addAll(p.blocks());
			}
			Pos exit = new Pos(rnd.nextInt(20) - 10, rnd.nextInt(100), rnd.nextInt(20) - 10);
			Optional<Pos> vanilla = PortalSearch.closestBlock(exit, toNether, B, blocks);
			PortalSearch.Result r = PortalSearch.search(exit, toNether, B, portals);
			assertEquals(vanilla.isPresent(), r.chosen().isPresent());
			if (vanilla.isPresent() && !r.ambiguous()) {
				assertEquals(vanilla.get().distSqr(exit), r.chosen().get().distSqr());
				assertEquals(vanilla.get().y(), r.chosen().get().block().y());
				assertTrue(portals.get(r.chosen().get().portal()).contains(vanilla.get()));
			}
		}
	}
}

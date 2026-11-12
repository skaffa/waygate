package dev.waygate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

class CreatePortalSimTest {
	static final Border B = Border.VANILLA_DEFAULT;
	static final CreatePortalSim.Level OW = CreatePortalSim.Level.OVERWORLD;

	/** Columns where {@code ground} is true have solid blocks up to {@code top}; everything else is air. */
	static CreatePortalSim.Blocks world(int top, int minY, BiPredicate<Integer, Integer> ground) {
		return new CreatePortalSim.Blocks() {
			@Override
			public boolean replaceable(int x, int y, int z) {
				return !solid(x, y, z);
			}

			@Override
			public boolean solid(int x, int y, int z) {
				return ground.test(x, z) && y <= top && y >= minY;
			}

			@Override
			public int motionBlockingHeight(int x, int z) {
				return ground.test(x, z) ? top + 1 : minY;
			}
		};
	}

	@Test
	void spiralStartsAtCentreAndTurnsEastSouthWestNorth() {
		List<int[]> first = new ArrayList<>();
		for (int[] p : CreatePortalSim.spiral(0, 0, 16)) {
			first.add(p);
			if (first.size() == 10) {
				break;
			}
		}
		int[][] expected = {{0, 0}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {2, -1}};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i][0], first.get(i)[0], "x at " + i);
			assertEquals(expected[i][1], first.get(i)[1], "z at " + i);
		}
	}

	@Test
	void spiralCoversTheWholeSquare() {
		Set<Long> seen = new HashSet<>();
		for (int[] p : CreatePortalSim.spiral(100, -50, 16)) {
			seen.add(((long) p[0] << 32) ^ (p[1] & 0xffffffffL));
		}
		for (int x = 84; x <= 116; x++) {
			for (int z = -66; z <= -34; z++) {
				assertTrue(seen.contains(((long) x << 32) ^ (z & 0xffffffffL)), x + " " + z);
			}
		}
	}

	@Test
	void flatGroundPicksTheSpotUnderTheOrigin() {
		var blocks = world(63, OW.minY(), (x, z) -> true);
		var p = CreatePortalSim.place(new Pos(0, 70, 0), Axis.X, OW, B, blocks).orElseThrow();
		assertEquals(new Pos(0, 64, 0), p.min());
		assertEquals(CreatePortalSim.Kind.FULL, p.kind());
		assertEquals(new PortalRect(new Pos(0, 64, 0), Axis.X, 2, 3), p.rect());
	}

	@Test
	void narrowRidgeGivesAPartialSpot() {
		// solid only along z == 0: the frame line fits, the side rows do not
		var blocks = world(63, OW.minY(), (x, z) -> z == 0);
		var p = CreatePortalSim.place(new Pos(0, 70, 0), Axis.X, OW, B, blocks).orElseThrow();
		assertEquals(CreatePortalSim.Kind.PARTIAL, p.kind());
		assertEquals(new Pos(0, 64, 0), p.min());
	}

	@Test
	void ridgeAcrossTheAxisCannotHostTheFrame() {
		// solid only along x == 0, portal along X needs 4 blocks of floor in x: nothing fits
		var blocks = world(63, OW.minY(), (x, z) -> x == 0);
		var p = CreatePortalSim.place(new Pos(0, 70, 0), Axis.X, OW, B, blocks).orElseThrow();
		assertEquals(CreatePortalSim.Kind.FORCED, p.kind());
		// the same ridge fits a portal along Z
		p = CreatePortalSim.place(new Pos(0, 70, 0), Axis.Z, OW, B, blocks).orElseThrow();
		assertEquals(CreatePortalSim.Kind.PARTIAL, p.kind());
	}

	@Test
	void voidForcesAPlatformWithClampedY() {
		var air = world(0, 0, (x, z) -> false);
		var p = CreatePortalSim.place(new Pos(10, 64, 20), Axis.X, OW, B, air).orElseThrow();
		assertEquals(CreatePortalSim.Kind.FORCED, p.kind());
		// x - stepX, y clamped up to 70
		assertEquals(new Pos(9, 70, 20), p.min());
		p = CreatePortalSim.place(new Pos(10, 400, 20), Axis.Z, OW, B, air).orElseThrow();
		// overworld max placeable 319 - 9 = 310; z - stepZ
		assertEquals(new Pos(10, 310, 19), p.min());
		p = CreatePortalSim.place(new Pos(10, 200, 20), Axis.X, CreatePortalSim.Level.NETHER, B, air).orElseThrow();
		// nether: logical height 128 -> max placeable 127 -> 118
		assertEquals(118, p.min().y());
		assertEquals(127, CreatePortalSim.Level.NETHER.maxPlaceableY());
	}

	@Test
	void forcedPortalIsClampedToTheBorder() {
		var air = world(0, 0, (x, z) -> false);
		Border small = Border.ofCenter(0, 0, 20); // -10 .. 10
		var p = CreatePortalSim.place(new Pos(10, 64, 10), Axis.X, OW, small, air).orElseThrow();
		assertEquals(new Pos(9, 70, 9), p.min());
	}

	@Test
	void tooShortDimensionFails() {
		var air = world(0, 0, (x, z) -> false);
		CreatePortalSim.Level shortLevel = new CreatePortalSim.Level(0, 63, 64); // max placeable 63, 54 < 70
		assertEquals(Optional.empty(), CreatePortalSim.place(new Pos(0, 30, 0), Axis.X, shortLevel, B, air));
	}

	@Test
	void columnsOutsideTheBorderAreSkipped() {
		var blocks = world(63, OW.minY(), (x, z) -> true);
		// x must be < 0 and x + 1 < 0, so the closest column is x = -2
		Border west = new Border(-1000, -1000, 0, 1000);
		var p = CreatePortalSim.place(new Pos(0, 70, 0), Axis.X, OW, west, blocks).orElseThrow();
		assertEquals(new Pos(-2, 64, 0), p.min());
	}

	@Test
	void nearestFullSpotWinsOverHigherGround() {
		// a 7x7 bottomless hole under the origin, flat ground at 63 elsewhere
		var blocks = world(63, OW.minY(), (x, z) -> !(x >= -3 && x <= 3 && z >= -3 && z <= 3));
		var p = CreatePortalSim.place(new Pos(0, 64, 0), Axis.X, OW, B, blocks).orElseThrow();
		assertEquals(CreatePortalSim.Kind.FULL, p.kind());
		assertEquals(64, p.min().y());
		// frame needs x-1..x+2 and z-1..z+1 on ground: nearest is 2 blocks out of the hole in some direction
		assertTrue(Math.abs(p.min().x()) >= 4 || Math.abs(p.min().z()) >= 5, p.min().toString());
	}
}

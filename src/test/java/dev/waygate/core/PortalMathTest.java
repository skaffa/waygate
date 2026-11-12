package dev.waygate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortalMathTest {
	static final Border B = Border.VANILLA_DEFAULT;

	@Test
	void teleportScaleIsFromOverTo() {
		assertEquals(0.125, PortalMath.teleportScale(1, 8));
		assertEquals(8.0, PortalMath.teleportScale(8, 1));
		// datapack scales: overworld 2, nether 16 still gives 1/8; overworld 1, nether 3 gives 1/3
		assertEquals(0.125, PortalMath.teleportScale(2, 16));
		assertEquals(1.0 / 3.0, PortalMath.teleportScale(1, 3));
	}

	@Test
	void overworldToNetherFloors() {
		assertEquals(new Pos(100, 64, 0), PortalMath.exit(801.0, 64, 0.5, 0.125, B));
		// negative coordinates floor down, like BlockPos.containing
		assertEquals(new Pos(-1, 64, -1), PortalMath.exit(-0.5, 64, -7.9, 0.125, B));
		assertEquals(new Pos(-2, 64, 0), PortalMath.exit(-8.01, 64, 7.99, 0.125, B));
	}

	@Test
	void netherToOverworldMultiplies() {
		assertEquals(new Pos(808, 70, -804), PortalMath.exit(101.0, 70.0, -100.5, 8, B));
	}

	@Test
	void yIsNeitherScaledNorClamped() {
		assertEquals(300, PortalMath.exit(0, 300.7, 0, 0.125, B).y());
		assertEquals(-100, PortalMath.exit(0, -99.5, 0, 8, B).y());
	}

	@Test
	void borderDefaults() {
		assertEquals(-29999984, B.minX());
		assertEquals(29999984, B.maxX());
		assertTrue(B.isWithin(29999983, 0));
		assertFalse(B.isWithin(29999984, 0));
	}

	@Test
	void borderClampsXZToMaxMinusEpsilon() {
		Border small = Border.ofCenter(0, 0, 200); // -100 .. 100
		// x 1000*8 clamps to 100 - 1e-5 -> floors to 99; z clamps to -100
		assertEquals(new Pos(99, 64, -100), PortalMath.exit(1000, 64, -1000, 8, small));
		// inside the border nothing changes
		assertEquals(new Pos(40, 64, -41), PortalMath.exit(5, 64, -5.1, 8, small));
		// off-centre border
		Border off = Border.ofCenter(1000, -500, 100); // x 950..1050, z -550..-450
		assertEquals(new Pos(950, 64, -451), PortalMath.exit(0, 64, 0, 1, off));
	}

	@Test
	void borderIsClampedToAbsoluteMax() {
		Border huge = Border.ofCenter(29_000_000, 0, 5.999997E7F);
		assertEquals(29999984, huge.maxX());
	}

	@Test
	void exitRangeOfNetherPortalSpansSixteenBlocks() {
		// 2-wide nether portal along X at x 10..11, z 5
		PortalRect r = new PortalRect(new Pos(10, 64, 5), Axis.X, 2, 3);
		PortalMath.ExitRange range = PortalMath.exitRange(r, 8, B);
		// player centre x 10.3..11.7 -> 82.4..93.6; z 5.075..5.925 -> 40.6..47.4
		assertEquals(new Pos(82, 64, 40), range.low());
		assertEquals(new Pos(93, 65, 47), range.high());
	}

	@Test
	void exitRangeOfOverworldPortalIsSmall() {
		PortalRect r = new PortalRect(new Pos(800, 64, 3), Axis.Z, 2, 3);
		PortalMath.ExitRange range = PortalMath.exitRange(r, 0.125, B);
		// x 800.075..800.925 -> 100; z 3.3..4.7 -> 0
		assertEquals(new Pos(100, 64, 0), range.low());
		assertEquals(new Pos(100, 65, 0), range.high());
		assertTrue(range.contains(PortalMath.centerExit(r, 0.125, B)));
	}

	@Test
	void centerExitUsesBottomMiddle() {
		PortalRect r = new PortalRect(new Pos(800, 64, 0), Axis.X, 2, 3);
		assertEquals(801.0, r.centerX());
		assertEquals(0.5, r.centerZ());
		assertEquals(new Pos(100, 64, 0), PortalMath.centerExit(r, 0.125, B));
	}

	@Test
	void portalTargets() {
		assertEquals(Dim.NETHER, Dim.OVERWORLD.portalTarget());
		assertEquals(Dim.OVERWORLD, Dim.NETHER.portalTarget());
		assertEquals(Dim.NETHER, Dim.OTHER.portalTarget());
	}

	@Test
	void rectBlocksAndContains() {
		PortalRect r = new PortalRect(new Pos(0, 10, 0), Axis.Z, 3, 4);
		assertEquals(12, r.blocks().size());
		assertTrue(r.contains(new Pos(0, 13, 2)));
		assertFalse(r.contains(new Pos(1, 10, 0)));
		assertFalse(r.contains(new Pos(0, 14, 0)));
		assertFalse(r.contains(new Pos(0, 10, 3)));
	}
}

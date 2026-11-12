package dev.waygate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortalMemoryTest {
	static final String OW = "minecraft:overworld";
	static final String NE = "minecraft:the_nether";

	static PortalRect rect(int x, int y, int z, Axis a, int w, int h) {
		return new PortalRect(new Pos(x, y, z), a, w, h);
	}

	@Test
	void groupsBlocksIntoRectangles() {
		List<Pos> blocks = new ArrayList<>(rect(0, 64, 0, Axis.X, 2, 3).blocks());
		blocks.addAll(rect(10, 70, 5, Axis.Z, 3, 4).blocks());
		List<PortalRect> g = PortalGrouping.group(blocks, p -> p.x() < 5 ? Axis.X : Axis.Z);
		assertEquals(2, g.size());
		assertTrue(g.contains(rect(0, 64, 0, Axis.X, 2, 3)));
		assertTrue(g.contains(rect(10, 70, 5, Axis.Z, 3, 4)));
	}

	@Test
	void groupingGuessesTheAxisWhenUnknown() {
		List<PortalRect> g = PortalGrouping.group(rect(4, 10, 8, Axis.Z, 2, 3).blocks(), p -> null);
		assertEquals(List.of(rect(4, 10, 8, Axis.Z, 2, 3)), g);
		g = PortalGrouping.group(rect(4, 10, 8, Axis.X, 5, 3).blocks(), p -> null);
		assertEquals(List.of(rect(4, 10, 8, Axis.X, 5, 3)), g);
	}

	@Test
	void sideBySidePortalsWithDifferentAxesStaySeparate() {
		// an X portal ending at x 1 and a Z portal starting at x 2 next to it
		List<Pos> blocks = new ArrayList<>(rect(0, 64, 0, Axis.X, 2, 3).blocks());
		blocks.addAll(rect(2, 64, 0, Axis.Z, 2, 3).blocks());
		List<PortalRect> g = PortalGrouping.group(blocks, p -> p.x() < 2 ? Axis.X : Axis.Z);
		assertEquals(2, g.size());
	}

	@Test
	void parallelPortalsOneBlockApartStaySeparate() {
		List<Pos> blocks = new ArrayList<>(rect(0, 64, 0, Axis.X, 2, 3).blocks());
		blocks.addAll(rect(0, 64, 1, Axis.X, 2, 3).blocks());
		assertEquals(2, PortalGrouping.group(blocks, p -> Axis.X).size());
	}

	@Test
	void upsertReplacesOverlapsAndKeepsTheName() {
		PortalMemory m = new PortalMemory();
		PortalMemory.Known half = m.upsert(OW, rect(0, 64, 0, Axis.X, 1, 3), 100, true);
		m.name(half, "Home");
		// the rest of the portal comes into view: the full rectangle replaces the half one
		PortalMemory.Known full = m.upsert(OW, rect(0, 64, 0, Axis.X, 2, 3), 200, true);
		assertEquals(1, m.dim(OW).portals.size());
		assertEquals("Home", full.name);
		assertEquals(100, full.firstSeen);
		assertEquals(200, full.lastSeen);
	}

	@Test
	void identicalUpsertOnlyTouches() {
		PortalMemory m = new PortalMemory();
		PortalMemory.Known k = m.upsert(NE, rect(5, 70, 5, Axis.Z, 2, 3), 1, false);
		m.markSaved();
		PortalMemory.Known again = m.upsert(NE, rect(5, 70, 5, Axis.Z, 2, 3), 2, false);
		assertTrue(k == again);
		assertEquals(2, k.lastSeen);
		assertFalse(m.dirty());
		m.upsert(NE, rect(5, 70, 5, Axis.Z, 2, 3), 3, true);
		assertTrue(k.seen);
		assertTrue(m.dirty());
	}

	@Test
	void separatePortalsAreKeptApart() {
		PortalMemory m = new PortalMemory();
		m.upsert(OW, rect(0, 64, 0, Axis.X, 2, 3), 1, true);
		m.upsert(OW, rect(0, 64, 1, Axis.X, 2, 3), 1, true);
		m.upsert(OW, rect(0, 64, 0, Axis.X, 2, 3), 1, true);
		m.upsert(NE, rect(0, 64, 0, Axis.X, 2, 3), 1, true);
		assertEquals(2, m.dim(OW).portals.size());
		assertEquals(1, m.dim(NE).portals.size());
		assertEquals(3, m.portalCount());
	}

	@Test
	void crossingPortalsOverlap() {
		assertTrue(PortalMemory.overlaps(rect(0, 64, 0, Axis.X, 3, 3), rect(1, 64, -1, Axis.Z, 3, 3)));
		assertFalse(PortalMemory.overlaps(rect(0, 64, 0, Axis.X, 3, 3), rect(5, 64, -1, Axis.Z, 3, 3)));
		assertFalse(PortalMemory.overlaps(rect(0, 64, 0, Axis.X, 2, 3), rect(0, 67, 0, Axis.X, 2, 3)));
	}

	@Test
	void atAndRemoveAndBlankName() {
		PortalMemory m = new PortalMemory();
		PortalMemory.Known k = m.upsert(OW, rect(0, 64, 0, Axis.X, 2, 3), 1, true);
		assertEquals(k, m.at(OW, new Pos(1, 66, 0)).orElseThrow());
		assertTrue(m.at(OW, new Pos(2, 66, 0)).isEmpty());
		assertTrue(m.at(NE, new Pos(1, 66, 0)).isEmpty());
		m.name(k, "  Base  ");
		assertEquals("Base", k.name);
		assertEquals("Base", k.label());
		m.name(k, " ");
		assertNull(k.name);
		assertEquals("0 64 0", k.label());
		assertTrue(m.remove(OW, k));
		assertEquals(0, m.portalCount());
	}

	@Test
	void learningReportsChanges() {
		PortalMemory.DimInfo d = new PortalMemory().dim(NE);
		assertTrue(d.border().isEmpty());
		assertTrue(d.learnScale(8));
		assertFalse(d.learnScale(8));
		assertTrue(d.learnBorder(Border.VANILLA_DEFAULT));
		assertFalse(d.learnBorder(Border.VANILLA_DEFAULT));
		assertEquals(Border.VANILLA_DEFAULT, d.border().orElseThrow());
		assertTrue(d.learnHeights(0, 255, 128));
		assertFalse(d.learnHeights(0, 255, 128));
		assertEquals(127, d.level().orElseThrow().maxPlaceableY());
	}

	@Test
	void storeRoundTrip(@TempDir Path dir) throws IOException {
		PortalStore store = new PortalStore(dir);
		PortalMemory m = new PortalMemory();
		PortalMemory.Known k = m.upsert(OW, rect(-100, 64, 30, Axis.Z, 4, 5), 123, true);
		m.name(k, "Big one");
		m.upsert(NE, rect(-12, 40, 4, Axis.X, 2, 3), 456, false);
		m.dim(NE).learnScale(8);
		m.dim(NE).learnBorder(Border.ofCenter(0, 0, 1000));
		m.dim(NE).learnHeights(0, 255, 128);
		store.save("mp:play.example.net", m);
		assertFalse(m.dirty());

		PortalMemory back = store.load("mp:play.example.net");
		assertEquals(2, back.portalCount());
		PortalMemory.Known kb = back.dim(OW).portals.get(0);
		assertEquals(rect(-100, 64, 30, Axis.Z, 4, 5), kb.rect());
		assertEquals("Big one", kb.name);
		assertTrue(kb.seen);
		assertFalse(back.dim(NE).portals.get(0).seen);
		assertEquals(8.0, back.dim(NE).scale);
		assertEquals(Border.ofCenter(0, 0, 1000), back.dim(NE).border().orElseThrow());
		assertEquals(128, back.dim(NE).logicalHeight);
		assertNull(back.dim(OW).scale);
		// another world is separate
		assertEquals(0, store.load("sp:New World").portalCount());
	}

	@Test
	void brokenFileIsMovedAside(@TempDir Path dir) throws IOException {
		PortalStore store = new PortalStore(dir);
		PortalMemory m = new PortalMemory();
		m.upsert(OW, rect(0, 64, 0, Axis.X, 2, 3), 1, true);
		m.upsert(NE, rect(0, 64, 0, Axis.X, 2, 3), 1, true);
		store.save("w", m);
		Path owFile = store.dimFile("w", OW);
		Files.writeString(owFile, "{ not json");
		PortalMemory back = store.load("w");
		assertEquals(1, back.portalCount());
		assertTrue(Files.exists(owFile.resolveSibling(owFile.getFileName() + ".broken")));
	}

	@Test
	void safeNamesDoNotCollide() {
		assertNotEquals(PortalStore.safeName("mp:a/b"), PortalStore.safeName("mp:a:b"));
		assertTrue(PortalStore.safeName("minecraft:the_nether").startsWith("minecraft_the_nether-"));
		assertTrue(PortalStore.safeName("x".repeat(500)).length() < 80);
	}

	@Test
	void forgetDeletesTheWorld(@TempDir Path dir) throws IOException {
		PortalStore store = new PortalStore(dir);
		PortalMemory m = new PortalMemory();
		m.upsert(OW, rect(0, 64, 0, Axis.X, 2, 3), 1, true);
		store.save("w", m);
		store.forget("w");
		assertFalse(Files.exists(store.worldDir("w")));
		assertEquals(0, store.load("w").portalCount());
	}
}

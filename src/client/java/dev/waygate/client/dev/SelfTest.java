package dev.waygate.client.dev;

import dev.waygate.client.Predictor;
import dev.waygate.client.ServerPeek;
import dev.waygate.client.Waygate;
import dev.waygate.core.Axis;
import dev.waygate.core.CreatePortalSim;
import dev.waygate.core.PortalMemory;
import dev.waygate.core.PortalRect;
import dev.waygate.core.Pos;
import dev.waygate.core.Prediction;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Dev-only acceptance test, run with {@code ./gradlew runClient -Pselftest=portals}
 * on the test server (player is op) or in a singleplayer world. It builds portals with commands in both
 * dimensions, lets the player see them, then walks the player through each portal and checks that the
 * server sends the player where Waygate predicted:
 * <ol>
 * <li>A (overworld) and N (nether): a correct pair, tested both ways;</li>
 * <li>P (overworld) links to C (nether) although B leads back to P: a mislink, with the warning;</li>
 * <li>C links back to Q, not P: one way;</li>
 * <li>E (overworld) has no partner: a new portal is created (exact spot in singleplayer).</li>
 * </ol>
 * Each run uses a fresh area, so old portals never interfere. Logs {@code SELFTEST PASS} or
 * {@code SELFTEST FAIL}, then quits.
 */
public final class SelfTest {
	private static final String MODE = System.getProperty("waygate.selftest", "");
	private static final String OW = "minecraft:overworld";
	private static final String NE = "minecraft:the_nether";
	/** Waits on a condition give up after this many ticks (the test server lags badly). */
	private static final int TIMEOUT = 3600;

	private record Step(String name, Runnable action, BooleanSupplier until) {
	}

	private static final List<Step> STEPS = new ArrayList<>();
	private static final List<String> FAILURES = new ArrayList<>();
	private static int index = -1;
	private static int waited;
	private static int delay = 100;
	private static boolean done;

	/** Nether base x of this run; overworld coordinates are 8 times it. */
	private static int b;
	private static PortalRect portalA, portalN, portalP, portalQ, portalB, portalC, portalE;

	/** What Waygate predicted for the current trip. */
	private static Predictor.View predicted;
	private static String tripName;
	private static PortalRect expect;
	private static String expectDim;

	private SelfTest() {
	}

	public static void tick(Minecraft mc) {
		if (!"portals".equals(MODE) || done || mc.player == null || mc.level == null) {
			return;
		}
		if (STEPS.isEmpty()) {
			plan();
		}
		if (delay-- > 0) {
			return;
		}
		if (index >= 0) {
			Step s = STEPS.get(index);
			if (s.until() != null && !s.until().getAsBoolean()) {
				if (++waited > TIMEOUT) {
					fail("timeout in step '" + s.name() + "'");
					finish(mc);
				}
				return;
			}
		}
		index++;
		waited = 0;
		if (index >= STEPS.size()) {
			finish(mc);
			return;
		}
		Step s = STEPS.get(index);
		Waygate.LOG.info("[selftest] {}", s.name());
		s.action().run();
		delay = 2;
	}

	// ---- the test ----

	private static void plan() {
		Minecraft mc = Minecraft.getInstance();
		// a fresh area every run: nether x 2000, 3000, ... (overworld 8x)
		b = 2000 + 1000 * (int) ((System.currentTimeMillis() / 1000) % 40);
		int o = 8 * b;
		portalA = rect(o, 100, 0);
		portalN = rect(b, 100, 0);
		portalP = rect(o + 800, 100, 0);
		portalQ = rect(o + 720, 100, 4);
		portalB = rect(b + 112, 100, 0);
		portalC = rect(b + 90, 100, 0);
		portalE = rect(o + 1600, 100, 0);
		Waygate.LOG.info("[selftest] rig at nether x {} / overworld x {}", b, o);

		step("setup", () -> {
			Waygate.forgetAll();
			cmd("gamemode survival");
			cmd("effect give @s minecraft:resistance infinite 4 true");
			cmd("effect give @s minecraft:fire_resistance infinite 0 true");
			cmd("effect give @s minecraft:saturation infinite 0 true");
			cmd("time set day");
		}, DevCommands::idle);
		step("build overworld", () -> {
			for (PortalRect r : List.of(portalA, portalP, portalQ, portalE)) {
				build(OW, r);
			}
		}, DevCommands::idle);
		step("build nether", () -> {
			for (PortalRect r : List.of(portalN, portalB, portalC)) {
				build(NE, r);
			}
			// where E's new portal will be made: loaded, so singleplayer can simulate it exactly
			cmd("execute in " + NE + " run forceload add " + (b + 168) + " -32 " + (b + 232) + " 32");
		}, DevCommands::idle);

		// let the client see every portal (on a server Waygate only knows what it has seen)
		look(NE, b + 1, portalN);
		look(NE, b + 101, portalB, portalC);
		look(OW, o + 1, portalA);
		look(OW, o + 761, portalP, portalQ);
		look(OW, o + 1601, portalE);

		trip("pair A -> N", OW, portalA, NE, portalN, null);
		trip("pair N -> A", NE, portalN, OW, portalA, null);
		trip("mislink P -> C", OW, portalP, NE, portalC, Prediction.WarningKind.MISLINK);
		trip("one way C -> Q", NE, portalC, OW, portalQ, null);
		trip("no partner E -> new", OW, portalE, NE, null, null);
	}

	private static PortalRect rect(int x, int y, int z) {
		return new PortalRect(new Pos(x, y, z), Axis.X, 2, 3);
	}

	/** Frame, portal, glass floor in front, and the chunks force-loaded. */
	private static void build(String dim, PortalRect r) {
		Pos m = r.min();
		String in = "execute in " + dim + " run ";
		cmd(in + "forceload add " + (m.x() - 16) + " " + (m.z() - 16) + " " + (m.x() + 16) + " " + (m.z() + 16));
		cmd(in + "fill " + (m.x() - 1) + " " + (m.y() - 1) + " " + m.z() + " " + (m.x() + 2) + " " + (m.y() + 3) + " " + m.z() + " minecraft:obsidian");
		cmd(in + "fill " + m.x() + " " + m.y() + " " + m.z() + " " + (m.x() + 1) + " " + (m.y() + 2) + " " + m.z()
				+ " minecraft:nether_portal[axis=x]");
		cmd(in + "fill " + (m.x() - 3) + " " + (m.y() - 1) + " " + (m.z() + 1) + " " + (m.x() + 4) + " " + (m.y() - 1) + " " + (m.z() + 6)
				+ " minecraft:glass");
	}

	/** Stand in front of these portals until Waygate remembers all of them. */
	private static void look(String dim, int x, PortalRect... portals) {
		step("look at " + portals.length + " portal(s) in " + dim + " near x " + x,
				() -> cmd("execute in " + dim + " run tp @s " + x + ".5 100 6.5 180 0"),
				() -> inDim(dim) && remembered(dim, portals));
	}

	private static boolean remembered(String dim, PortalRect... portals) {
		PortalMemory m = Waygate.memory();
		for (PortalRect r : portals) {
			if (m.at(dim, r.min()).filter(k -> k.rect().equals(r)).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Steps into {@code from}, records Waygate's prediction, waits for the dimension change and checks where
	 * the server put the player. {@code expect} null: a new portal should be created.
	 */
	private static void trip(String name, String fromDim, PortalRect from, String toDim, PortalRect expectRect,
			Prediction.WarningKind warning) {
		double x = from.centerX();
		double z = from.centerZ();
		// stand in front, ask Waygate (singleplayer: until the server answered)
		step(name + ": predict", () -> {
			tripName = name;
			expect = expectRect;
			expectDim = toDim;
			predicted = null;
			cmd("execute in " + fromDim + " run tp @s " + x + " 100 " + (z + 6) + " 180 0");
		}, () -> {
			Minecraft mc = Minecraft.getInstance();
			if (!inDim(fromDim)) {
				return false;
			}
			Predictor.View v = Predictor.predict(mc.level, new Predictor.Focus(from, true), x, from.min().y(), z);
			boolean simReady = v.result().linked().isPresent() || !v.exact() || (v.sim() != null);
			if (v.pending() || !simReady) {
				return false;
			}
			predicted = v;
			return true;
		});
		step(name + ": check prediction", () -> checkPrediction(name, warning), null);
		String stepIn = "execute in " + fromDim + " run tp @s " + x + " 100 " + z + " 180 0";
		step(name + ": step in", () -> cmd(stepIn), stepInWatch(name, fromDim, toDim, stepIn, x, z));
		// let the arrival settle, then record it and step out before the portal sends the player back
		step(name + ": settle", () -> { }, settle());
		step(name + ": check arrival", SelfTest::checkArrival, null);
		step(name + ": step out", () -> {
			Minecraft mc = Minecraft.getInstance();
			cmd(String.format(Locale.ROOT, "execute in %s run tp @s %.2f %.2f %.2f 180 0", toDim, mc.player.getX(), mc.player.getY(),
					Math.floor(mc.player.getZ()) + 2.0));
		}, settle());
	}

	/**
	 * Done when the player is in {@code toDim}. A survival player needs 80 ticks in the portal; if nothing
	 * happened after 200 server ticks, log what the client sees and step out and back in (up to 3 times).
	 */
	private static BooleanSupplier stepInWatch(String name, String fromDim, String toDim, String stepIn, double x, double z) {
		long[] start = {-1};
		int[] tries = {0};
		boolean[] out = {false};
		return () -> {
			Minecraft mc = Minecraft.getInstance();
			if (inDim(toDim)) {
				return true;
			}
			long now = mc.level.getGameTime();
			if (start[0] < 0) {
				start[0] = now;
			}
			long t = now - start[0];
			if (tries[0] >= 3) {
				return false;
			}
			if (!out[0] && t >= 200) {
				Waygate.LOG.warn("[selftest] {}: still in {} after {} server ticks at {} {} {}, block at feet {}, gamemode {}, try {}", name,
						Waygate.dimId(mc.level), t, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
						mc.level.getBlockState(mc.player.blockPosition()), mc.gameMode.getPlayerMode(), tries[0]);
				cmd("execute in " + fromDim + " run tp @s " + x + " 100 " + (z + 3) + " 180 0");
				out[0] = true;
			} else if (out[0] && t >= 260) {
				tries[0]++;
				start[0] = now;
				out[0] = false;
				cmd(stepIn);
			}
			return false;
		};
	}

	/**
	 * Waits 40 server ticks (game time comes from the server, so this holds on a lagging server). A player
	 * who just arrived has a 10-tick portal cooldown that every tick spent inside a portal resets, so the
	 * player must be out of the portal for that long before stepping back in.
	 */
	private static BooleanSupplier settle() {
		long[] start = {-1};
		return () -> {
			Minecraft mc = Minecraft.getInstance();
			long now = mc.level.getGameTime();
			if (start[0] < 0) {
				start[0] = now;
			}
			return now - start[0] >= 40;
		};
	}

	private static void checkPrediction(String name, Prediction.WarningKind warning) {
		Prediction.Result r = predicted.result();
		String what = r.linked().map(l -> "links to " + l).orElse("new portal near " + r.exit());
		Waygate.LOG.info("[selftest] {}: Waygate predicts {} (exact={}, warnings={})", name, what, predicted.exact(), r.warnings());
		if (expect == null) {
			if (r.linked().isPresent()) {
				fail(name + ": predicted a link to " + r.linked().get() + ", expected a new portal");
			}
		} else if (r.linked().filter(expect::equals).isEmpty()) {
			fail(name + ": predicted " + what + ", expected a link to " + expect);
		}
		if (warning != null && !r.has(warning)) {
			fail(name + ": expected a " + warning + " warning, got " + r.warnings());
		}
		if (warning == null && r.has(Prediction.WarningKind.MISLINK)) {
			fail(name + ": unexpected mislink warning");
		}
	}

	private static void checkArrival() {
		Minecraft mc = Minecraft.getInstance();
		double px = mc.player.getX();
		double py = mc.player.getY();
		double pz = mc.player.getZ();
		String arrived = String.format(Locale.ROOT, "%s %.2f %.2f %.2f", Waygate.dimId(mc.level), px, py, pz);
		Waygate.LOG.info("[selftest] {}: arrived at {}", tripName, arrived);
		if (!inDim(expectDim)) {
			fail(tripName + ": arrived in the wrong dimension: " + arrived);
			return;
		}
		Prediction.Result r = predicted.result();
		if (expect != null) {
			if (!inside(expect, px, py, pz)) {
				fail(tripName + ": arrived at " + arrived + ", outside the predicted portal " + expect);
			} else {
				pass(tripName + ": arrived in " + expect + " as predicted");
			}
			return;
		}
		ServerPeek.Sim sim = predicted.sim();
		if (sim != null && sim.loaded() && sim.placement().isPresent()) {
			CreatePortalSim.Placement p = sim.placement().get();
			if (!inside(p.rect(), px, py, pz)) {
				fail(tripName + ": arrived at " + arrived + ", not in the simulated new portal at " + p.min() + " (" + p.kind() + ")");
			} else {
				pass(tripName + ": arrived in the new portal at " + p.min() + " (" + p.kind() + ") exactly as simulated");
			}
			return;
		}
		// multiplayer: the spot depends on terrain the client cannot see; it is within the 16-block spiral
		Pos exit = r.exit();
		if (Math.abs(px - exit.x()) > 19 || Math.abs(pz - exit.z()) > 19) {
			fail(tripName + ": arrived at " + arrived + ", more than 16 blocks from the predicted spot " + exit);
		} else {
			pass(tripName + ": arrived " + String.format(Locale.ROOT, "%.1f", Math.hypot(px - exit.x(), pz - exit.z()))
					+ " blocks from the predicted spot " + exit + " (new portal)");
		}
	}

	/** The player's feet are within the portal's blocks (one block of slack for the collision-free shift). */
	private static boolean inside(PortalRect r, double x, double y, double z) {
		double x0 = r.min().x() - 1;
		double z0 = r.min().z() - 1;
		double x1 = r.min().x() + (r.axis() == Axis.X ? r.width() : 1) + 1;
		double z1 = r.min().z() + (r.axis() == Axis.Z ? r.width() : 1) + 1;
		return x >= x0 && x <= x1 && z >= z0 && z <= z1 && y >= r.min().y() - 1 && y <= r.min().y() + r.height();
	}

	// ---- plumbing ----

	private static boolean inDim(String dim) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null && Waygate.dimId(mc.level).equals(dim);
	}

	private static void step(String name, Runnable action, BooleanSupplier until) {
		STEPS.add(new Step(name, action, until));
	}

	private static void cmd(String c) {
		DevCommands.run(Minecraft.getInstance(), c);
	}

	private static void pass(String msg) {
		Waygate.LOG.info("[selftest] ok: {}", msg);
	}

	private static void fail(String msg) {
		FAILURES.add(msg);
		Waygate.LOG.error("[selftest] FAIL: {}", msg);
	}

	private static void finish(Minecraft mc) {
		done = true;
		if (FAILURES.isEmpty()) {
			Waygate.LOG.info("SELFTEST PASS ({} steps)", STEPS.size());
		} else {
			Waygate.LOG.error("SELFTEST FAIL ({} problems): {}", FAILURES.size(), FAILURES);
		}
		Waygate.save();
		mc.stop();
	}
}

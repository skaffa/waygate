package dev.waygate.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import dev.waygate.core.Axis;
import dev.waygate.core.Border;
import dev.waygate.core.CreatePortalSim;
import dev.waygate.core.Dim;
import dev.waygate.core.Pairing;
import dev.waygate.core.PortalMath;
import dev.waygate.core.PortalMemory;
import dev.waygate.core.PortalRect;
import dev.waygate.core.PortalSearch;
import dev.waygate.core.Pos;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** The {@code /waygate} command. */
final class Commands {
	/** Screen to open on the next tick (the chat screen is still closing while a command runs). */
	static volatile java.util.function.Supplier<net.minecraft.client.gui.screens.Screen> openNext;

	/** A command waiting for the integrated server's answer, run again next tick. */
	private static Runnable retry;
	private static int retries;

	private Commands() {
	}

	static void tick() {
		Runnable r = retry;
		retry = null;
		if (r != null) {
			r.run();
		}
	}

	static void register(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("waygate")
				.executes(Commands::help)
				.then(literal("help").executes(Commands::help))
				.then(literal("plan")
						.executes(c -> plan(c, null))
						.then(argument("x", IntegerArgumentType.integer())
								.then(argument("y", IntegerArgumentType.integer())
										.then(argument("z", IntegerArgumentType.integer())
												.executes(c -> plan(c, null))
												.then(literal("x").executes(c -> plan(c, Axis.X)))
												.then(literal("z").executes(c -> plan(c, Axis.Z)))))))
				.then(literal("name").then(argument("name", StringArgumentType.greedyString())
						.executes(c -> name(c, StringArgumentType.getString(c, "name")))))
				.then(literal("unname").executes(c -> name(c, null)))
				.then(literal("list").executes(Commands::list))
				.then(literal("pairs").executes(c -> {
					openNext = () -> new PairsScreen(null);
					return 1;
				}))
				.then(literal("config").executes(c -> {
					openNext = () -> new WaygateScreen(null);
					return 1;
				}))
				.then(literal("forget")
						.then(literal("here").executes(Commands::forgetHere))
						.then(literal("all").executes(c -> {
							int n = Waygate.forgetAll();
							say(c, "forgot " + n + " portals of this world", ChatFormatting.GRAY);
							return 1;
						})))
				.then(literal("debug").executes(WaygateClient::debug)));
	}

	private static int help(CommandContext<FabricClientCommandSource> c) {
		say(c, "Waygate: where a nether portal leads, before you step in.", ChatFormatting.LIGHT_PURPLE);
		say(c, "/waygate plan [x y z [x|z]] - where to build the partner of the portal you look at (or one you plan)", ChatFormatting.GRAY);
		say(c, "/waygate name <name>, /waygate unname - name the portal you stand in or look at", ChatFormatting.GRAY);
		say(c, "/waygate list - remembered portals of this world", ChatFormatting.GRAY);
		say(c, "/waygate pairs - which portal pairs link both ways", ChatFormatting.GRAY);
		say(c, "/waygate forget here|all - forget the portal you look at, or every portal of this world", ChatFormatting.GRAY);
		say(c, "/waygate config - settings (key U switches the panel: auto / always / off)", ChatFormatting.GRAY);
		return 1;
	}

	// ---- plan ----

	private static int plan(CommandContext<FabricClientCommandSource> c, Axis axisArg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return 0;
		}
		PortalRect p;
		String what;
		if (hasArg(c, "x")) {
			int x = IntegerArgumentType.getInteger(c, "x");
			int y = IntegerArgumentType.getInteger(c, "y");
			int z = IntegerArgumentType.getInteger(c, "z");
			Axis axis = axisArg != null ? axisArg : facingAxis(mc);
			p = new PortalRect(new Pos(x, y, z), axis, 2, 3);
			what = "a 2x3 portal at " + p.min() + " (" + axis.name().toLowerCase(Locale.ROOT) + " axis)";
		} else {
			Predictor.Focus f = Predictor.focus(mc);
			if (f != null) {
				p = f.rect();
				what = "the portal " + Predictor.label(Waygate.dimId(mc.level), p);
			} else {
				BlockPos b = mc.player.blockPosition();
				Axis axis = facingAxis(mc);
				p = new PortalRect(new Pos(b.getX(), b.getY(), b.getZ()), axis, 2, 3);
				what = "a 2x3 portal where you stand (" + axis.name().toLowerCase(Locale.ROOT) + " axis)";
			}
		}

		String from = Waygate.dimId(mc.level);
		String to = Waygate.targetDimId(from);
		Dim fromDim = Waygate.dim(from);
		if (fromDim == Dim.OTHER) {
			say(c, "Portals here lead to the Nether, but nothing leads back here: no partner can be planned.", ChatFormatting.YELLOW);
			return 1;
		}
		PortalMemory memory = Waygate.memory();
		PortalMemory.DimInfo fd = memory.dim(from);
		PortalMemory.DimInfo td = memory.dim(to);
		double fs = fd.scale != null ? fd.scale : fromDim.defaultScale();
		double ts = td.scale != null ? td.scale : Waygate.dim(to).defaultScale();
		Border fb = fd.border().orElse(Border.VANILLA_DEFAULT);
		Border tb = td.border().orElse(Border.VANILLA_DEFAULT);
		CreatePortalSim.Level tl = td.level().orElse(Waygate.dim(to) == Dim.NETHER ? CreatePortalSim.Level.NETHER : CreatePortalSim.Level.OVERWORLD);

		// portals near the target and near this portal: exact in singleplayer, else remembered
		Pos exit = PortalMath.centerExit(p, PortalMath.teleportScale(fs, ts), tb);
		int rTo = PortalSearch.radius(Waygate.dim(to) == Dim.NETHER) + 4;
		int rFrom = PortalSearch.radius(fromDim == Dim.NETHER) + 4;
		Pos home = new Pos((int) Math.floor(p.centerX()), p.min().y(), (int) Math.floor(p.centerZ()));
		List<PortalRect> toPortals = portals(to, exit, rTo);
		List<PortalRect> fromPortals = portals(from, home, rFrom);
		if (toPortals == null || fromPortals == null) {
			// the server answers within a tick or two; try again by itself
			if (retries < 40) {
				retries++;
				retry = () -> plan(c, axisArg);
			} else {
				retries = 0;
				say(c, "The server did not answer; try /waygate plan again.", ChatFormatting.GRAY);
			}
			return 1;
		}
		retries = 0;
		Pairing.Side a = new Pairing.Side(fromDim, fs, fb, fromPortals);
		Pairing.Side b = new Pairing.Side(Waygate.dim(to), ts, tb, toPortals);
		Pairing.Plan plan = Pairing.plan(p, a, b, tl);

		say(c, "Plan for " + what + ":", ChatFormatting.LIGHT_PURPLE);
		say(c, String.format(Locale.ROOT, "Build its partner in the %s: lowest portal block at %s, running %s (opening 2x3 or larger, extending %s and up).",
				WaygateHud.dimName(to), plan.target(), p.axis() == Axis.X ? "east-west" : "north-south", p.axis() == Axis.X ? "east" : "south"), ChatFormatting.WHITE);
		if (!plan.target().equals(exit)) {
			say(c, "Y was moved from " + exit.y() + " to fit the " + WaygateHud.dimName(to) + "'s build limits.", ChatFormatting.GRAY);
		}
		if (plan.clean()) {
			say(c, "✔ It would link both ways" + (ServerPeek.active() ? "." : " (with the portals Waygate knows)."), ChatFormatting.GREEN);
		}
		for (int i : plan.competitors()) {
			say(c, "⚠ " + Predictor.label(to, toPortals.get(i)) + " is as close or closer to the target: this portal would link there. Build the partner closer, or remove it.",
					ChatFormatting.RED);
		}
		for (int i : plan.backBlockers()) {
			say(c, "⚠ On the way back the partner would link to " + Predictor.label(from, fromPortals.get(i)) + " instead of this portal.",
					ChatFormatting.RED);
		}
		List<Integer> others = new ArrayList<>(plan.inRange());
		others.removeAll(plan.competitors());
		if (!others.isEmpty()) {
			List<String> names = others.stream().map(i -> Predictor.label(to, toPortals.get(i))).toList();
			say(c, "Also in search range (" + PortalSearch.radius(Waygate.dim(to) == Dim.NETHER) + " blocks): " + String.join(", ", names)
					+ " - build the partner exactly at the target so they do not win.", ChatFormatting.YELLOW);
		}
		if (!ServerPeek.active()) {
			say(c, "Multiplayer: based on remembered portals only; unknown portals may interfere.", ChatFormatting.GRAY);
		}
		return 1;
	}

	/** Portals of {@code dim} around {@code center}: from the server in singleplayer (null while asking), else memory. */
	private static List<PortalRect> portals(String dim, Pos center, int radius) {
		if (ServerPeek.active()) {
			ServerPeek.Portals p = ServerPeek.portals(dim, center, radius);
			return p == null ? null : p.rects();
		}
		return Waygate.memory().dim(dim).rects();
	}

	/** Portal plane for a player walking forward: facing north/south means the portal runs along X. */
	private static Axis facingAxis(Minecraft mc) {
		Direction d = mc.player.getDirection();
		return d.getAxis() == Direction.Axis.Z ? Axis.X : Axis.Z;
	}

	private static boolean hasArg(CommandContext<FabricClientCommandSource> c, String name) {
		try {
			IntegerArgumentType.getInteger(c, name);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	// ---- name, list, forget ----

	private static Optional<PortalMemory.Known> focused(Minecraft mc) {
		Predictor.Focus f = Predictor.focus(mc);
		if (f == null) {
			return Optional.empty();
		}
		String dim = Waygate.dimId(mc.level);
		PortalMemory memory = Waygate.memory();
		Optional<PortalMemory.Known> k = memory.at(dim, f.rect().min());
		return k.isPresent() ? k : Optional.of(memory.upsert(dim, f.rect(), System.currentTimeMillis(), true));
	}

	private static int name(CommandContext<FabricClientCommandSource> c, String name) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return 0;
		}
		Optional<PortalMemory.Known> k = focused(mc);
		if (k.isEmpty()) {
			say(c, "Stand in a portal or look at one first.", ChatFormatting.YELLOW);
			return 0;
		}
		Waygate.memory().name(k.get(), name);
		Waygate.save();
		say(c, name == null ? "Portal at " + k.get().label() + " has no name now." : "Named the portal at "
				+ k.get().x + " " + k.get().y + " " + k.get().z + ": " + k.get().label(), ChatFormatting.GREEN);
		return 1;
	}

	private static int forgetHere(CommandContext<FabricClientCommandSource> c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return 0;
		}
		Predictor.Focus f = Predictor.focus(mc);
		String dim = Waygate.dimId(mc.level);
		Optional<PortalMemory.Known> k = f == null ? Optional.empty() : Waygate.memory().at(dim, f.rect().min());
		if (k.isEmpty()) {
			say(c, "Look at a remembered portal first.", ChatFormatting.YELLOW);
			return 0;
		}
		Waygate.memory().remove(dim, k.get());
		say(c, "Forgot " + k.get().label() + " (it comes back if you see it again).", ChatFormatting.GRAY);
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return 0;
		}
		PortalMemory memory = Waygate.memory();
		String here = Waygate.dimId(mc.level);
		int total = 0;
		for (String dim : List.of(Waygate.OVERWORLD, Waygate.NETHER)) {
			PortalMemory.DimInfo d = memory.dim(dim);
			say(c, WaygateHud.dimName(dim) + ": " + d.portals.size() + " portal" + (d.portals.size() == 1 ? "" : "s"), ChatFormatting.LIGHT_PURPLE);
			List<PortalMemory.Known> sorted = new ArrayList<>(d.portals);
			if (dim.equals(here)) {
				sorted.sort(Comparator.comparingDouble(k -> mc.player.distanceToSqr(k.x + 0.5, k.y, k.z + 0.5)));
			} else {
				sorted.sort(Comparator.comparingInt((PortalMemory.Known k) -> k.x).thenComparingInt(k -> k.z));
			}
			for (PortalMemory.Known k : sorted) {
				MutableComponent line = Component.literal("  " + k.label()).withStyle(ChatFormatting.WHITE);
				if (k.name != null) {
					line.append(Component.literal("  " + k.x + " " + k.y + " " + k.z).withStyle(ChatFormatting.GRAY));
				}
				line.append(Component.literal("  " + k.width + "x" + k.height).withStyle(ChatFormatting.DARK_GRAY));
				if (dim.equals(here)) {
					line.append(Component.literal(String.format(Locale.ROOT, "  %.0f blocks away",
							Math.sqrt(mc.player.distanceToSqr(k.x + 0.5, k.y, k.z + 0.5)))).withStyle(ChatFormatting.GRAY));
				}
				if (!k.seen) {
					line.append(Component.literal("  (from the server, not seen)").withStyle(ChatFormatting.DARK_GRAY));
				}
				c.getSource().sendFeedback(line);
				total++;
			}
		}
		if (total == 0) {
			say(c, "No portals remembered yet: walk near one.", ChatFormatting.GRAY);
		}
		return 1;
	}

	static void say(CommandContext<FabricClientCommandSource> c, String msg, ChatFormatting color) {
		c.getSource().sendFeedback(Component.literal(msg).withStyle(color));
		Waygate.LOG.info("[waygate] {}", msg);
	}
}

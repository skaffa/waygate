package dev.waygate.client.dev;

import dev.waygate.client.Waygate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Dev only: runs the lines of the file named by {@code -Dwaygate.devscript} once the player is in a world
 * ({@code ./gradlew runClient -Pdevscript=<file>}). Lines: {@code wait <ticks>}, {@code screenshot <name>},
 * {@code quit}, {@code # comment}; anything else is sent as a command (client commands like {@code waygate
 * debug} included).
 */
public final class DevScript {
	private static final String FILE = System.getProperty("waygate.devscript", "");
	private static List<String> lines;
	private static int index;
	private static int waitTicks = 60;
	private static BooleanSupplier until;
	private static int untilTicks;

	private DevScript() {
	}

	public static void tick(Minecraft mc) {
		if (FILE.isEmpty() || mc.player == null || mc.level == null) {
			return;
		}
		if (lines == null) {
			try {
				lines = new ArrayList<>(Files.readAllLines(Path.of(FILE)));
			} catch (IOException e) {
				Waygate.LOG.error("[devscript] cannot read {}", FILE, e);
				lines = List.of();
			}
		}
		if (waitTicks-- > 0) {
			return;
		}
		if (until != null) {
			if (!until.getAsBoolean()) {
				if (++untilTicks % 200 == 0) {
					Waygate.LOG.info("[devscript] still waiting ({} ticks)", untilTicks);
				}
				return;
			}
			until = null;
		}
		while (index < lines.size()) {
			String line = lines.get(index++).strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			Waygate.LOG.info("[devscript] {}", line);
			if (line.equals("sync")) {
				// wait until the server has run every command sent so far
				until = DevCommands::idle;
				untilTicks = 0;
				return;
			}
			if (line.startsWith("waitpos ")) {
				// waitpos <dimension> <x> <z>: until the player is in that dimension within 2 blocks of x, z
				String[] a = line.substring(8).strip().split("\\s+");
				double x = Double.parseDouble(a[1]);
				double z = Double.parseDouble(a[2]);
				until = () -> mc.level != null && mc.player != null && Waygate.dimId(mc.level).equals(a[0])
						&& Math.abs(mc.player.getX() - x) < 2 && Math.abs(mc.player.getZ() - z) < 2;
				untilTicks = 0;
				waitTicks = 20;
				return;
			}
			if (line.startsWith("waitdim ")) {
				String dim = line.substring(8).strip();
				until = () -> mc.level != null && Waygate.dimId(mc.level).equals(dim) && mc.player != null;
				untilTicks = 0;
				waitTicks = 20;
				return;
			}
			if (line.startsWith("wait ")) {
				waitTicks = Integer.parseInt(line.substring(5).strip());
				return;
			}
			if (line.startsWith("screenshot ")) {
				Screenshot.grab(mc.gameDirectory, line.substring(11).strip() + ".png", mc.gameRenderer.mainRenderTarget(),
						1, msg -> Waygate.LOG.info("[devscript] {}", msg.getString()));
				waitTicks = 10;
				return;
			}
			if (line.equals("clearchat")) {
				mc.gui.hud.getChat().clearMessages(false);
				continue;
			}
			if (line.equals("quit")) {
				Waygate.save();
				mc.stop();
				return;
			}
			DevCommands.run(mc, line);
			waitTicks = 2;
			return;
		}
	}
}

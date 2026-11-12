package dev.waygate.client.dev;

import net.minecraft.client.Minecraft;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dev only: runs a server command for the test scripts. In singleplayer it runs as the integrated server
 * with the player as {@code @s} (test worlds may have cheats off); on a server it is sent as the player,
 * who must be op.
 */
public final class DevCommands {
	/** Singleplayer commands sent but not run yet. */
	private static final AtomicInteger QUEUED = new AtomicInteger();

	private DevCommands() {
	}

	public static void run(Minecraft mc, String command) {
		String cmd = command.startsWith("/") ? command.substring(1) : command;
		var server = mc.getSingleplayerServer();
		if (server != null && !cmd.startsWith("waygate")) {
			var id = mc.player.getUUID();
			QUEUED.incrementAndGet();
			server.execute(() -> {
				try {
					var sp = server.getPlayerList().getPlayer(id);
					var src = server.createCommandSourceStack();
					if (sp != null) {
						src = src.withEntity(sp).withPosition(sp.position()).withLevel(sp.level());
					}
					server.getCommands().performPrefixedCommand(src, cmd);
				} finally {
					QUEUED.decrementAndGet();
				}
			});
		} else {
			mc.player.connection.sendCommand(cmd);
		}
	}

	/** True once every singleplayer command sent so far has run (always true on a server). */
	public static boolean idle() {
		return QUEUED.get() == 0;
	}
}

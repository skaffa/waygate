package dev.waygate.client;

import dev.waygate.core.Dim;
import dev.waygate.core.PortalMemory;
import dev.waygate.core.PortalStore;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** Shared client state: the memory of the current world and where it is saved. */
public final class Waygate {
	public static final String MOD_ID = "waygate";
	public static final Logger LOG = LoggerFactory.getLogger("Waygate");
	/** All Waygate files live in {@code config/waygate/}. */
	public static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	public static final PortalStore STORE = new PortalStore(DIR.resolve("portals"));

	public static final String OVERWORLD = Level.OVERWORLD.identifier().toString();
	public static final String NETHER = Level.NETHER.identifier().toString();

	private static String worldKey;
	private static PortalMemory memory = new PortalMemory();

	private Waygate() {
	}

	/** Singleplayer save folder or server address. Null when not in a world. */
	static String worldKey(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			return "sp:" + (root.getFileName() == null ? root.toString() : root.getFileName().toString());
		}
		if (mc.getCurrentServer() != null) {
			return "mp:" + mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
		}
		return mc.level == null ? null : "other";
	}

	/** The memory of the world the player is in; loads it when the world changed. */
	public static PortalMemory memory() {
		Minecraft mc = Minecraft.getInstance();
		String key = worldKey(mc);
		if (key != null && !key.equals(worldKey)) {
			save();
			worldKey = key;
			memory = STORE.load(key);
			LOG.info("Loaded {} remembered portals for {}", memory.portalCount(), key);
		}
		return memory;
	}

	static String currentWorldKey() {
		return worldKey;
	}

	public static void save() {
		if (worldKey == null || !memory.dirty()) {
			return;
		}
		try {
			STORE.save(worldKey, memory);
		} catch (IOException e) {
			LOG.warn("Could not save portals for {}", worldKey, e);
		}
	}

	/** Saves and forgets the current world (on disconnect). */
	static void close() {
		save();
		worldKey = null;
		memory = new PortalMemory();
	}

	/** Deletes all remembered portals and dimension facts of the current world. */
	static int forgetAll() {
		PortalMemory m = memory();
		int n = m.portalCount();
		try {
			if (worldKey != null) {
				STORE.forget(worldKey);
			}
		} catch (IOException e) {
			LOG.warn("Could not delete portals for {}", worldKey, e);
		}
		memory = new PortalMemory();
		return n;
	}

	public static String dimId(Level level) {
		return level.dimension().identifier().toString();
	}

	public static Dim dim(String dimId) {
		if (NETHER.equals(dimId)) {
			return Dim.NETHER;
		}
		return OVERWORLD.equals(dimId) ? Dim.OVERWORLD : Dim.OTHER;
	}

	/** Id of the dimension a portal in {@code dimId} leads to (hard-coded in vanilla). */
	public static String targetDimId(String dimId) {
		return NETHER.equals(dimId) ? OVERWORLD : NETHER;
	}

	public static void say(String msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal("Waygate: " + msg).withStyle(ChatFormatting.LIGHT_PURPLE));
		}
	}
}

package dev.waygate.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Settings, saved as {@code config/waygate/config.json}. Minecraft-free. */
public final class WaygateConfig {
	public enum HudMode {
		/** Only while standing in or looking at a portal. */
		AUTO,
		/** Also shows where the player's own spot leads, without a portal. */
		ALWAYS,
		OFF;

		public HudMode next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	public HudMode hudMode = HudMode.AUTO;
	/** Show the range of exits a whole portal can reach. */
	public boolean showRange = true;
	public boolean markers = true;
	/** Markers for known portals within this many blocks. */
	public int markerRange = 64;
	/** A portal the player looks at counts up to this many blocks away. */
	public int lookDistance = 32;
	/** Panel position: offset from the top-left corner of the screen. */
	public int hudX = 6;
	public int hudY = 6;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public WaygateConfig clamp() {
		if (hudMode == null) {
			hudMode = HudMode.AUTO;
		}
		markerRange = Math.clamp(markerRange, 8, 256);
		lookDistance = Math.clamp(lookDistance, 4, 128);
		hudX = Math.clamp(hudX, 0, 4000);
		hudY = Math.clamp(hudY, 0, 4000);
		return this;
	}

	/** Missing file: defaults. Broken file: moved aside as {@code config.json.broken}, defaults used. */
	public static WaygateConfig load(Path file) {
		if (!Files.isRegularFile(file)) {
			return new WaygateConfig();
		}
		try {
			WaygateConfig c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), WaygateConfig.class);
			return c == null ? new WaygateConfig() : c.clamp();
		} catch (IOException | JsonParseException e) {
			try {
				Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// keep defaults anyway
			}
			return new WaygateConfig();
		}
	}

	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(clamp()), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}
}

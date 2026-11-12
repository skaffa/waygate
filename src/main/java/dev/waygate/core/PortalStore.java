package dev.waygate.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;

/**
 * Saves {@link PortalMemory} as {@code <dir>/<world>/<dimension>.json} (dir is {@code config/waygate/portals}).
 * A file that cannot be read is moved aside as {@code .broken} and that dimension starts empty.
 */
public final class PortalStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path dir;

	public PortalStore(Path dir) {
		this.dir = dir;
	}

	/**
	 * Safe folder or file name: keeps letters, digits, {@code . _ -}, replaces the rest with {@code _}, and
	 * adds a short hash of the original so different names never share a folder.
	 */
	public static String safeName(String s) {
		String clean = s.replaceAll("[^A-Za-z0-9._-]", "_");
		if (clean.length() > 60) {
			clean = clean.substring(0, 60);
		}
		return clean + "-" + Integer.toHexString(s.hashCode() & 0xfffffff).toLowerCase(Locale.ROOT);
	}

	public Path worldDir(String worldKey) {
		return dir.resolve(safeName(worldKey));
	}

	/** File for one dimension. The file stores the real dimension id inside. */
	public Path dimFile(String worldKey, String dimId) {
		return worldDir(worldKey).resolve(safeName(dimId) + ".json");
	}

	/** On disk: the dimension id and its info. */
	static final class DimFile {
		String dimension;
		PortalMemory.DimInfo info;
	}

	public PortalMemory load(String worldKey) {
		PortalMemory m = new PortalMemory();
		Path wd = worldDir(worldKey);
		if (!Files.isDirectory(wd)) {
			return m;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(wd, "*.json")) {
			for (Path f : files) {
				try {
					// read fully first so the file is closed before a broken one is moved aside (Windows)
					DimFile df = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), DimFile.class);
					if (df != null && df.dimension != null && df.info != null) {
						if (df.info.portals == null) {
							df.info.portals = new java.util.ArrayList<>();
						}
						df.info.portals.removeIf(k -> k == null || k.width < 1 || k.height < 1);
						m.dims.put(df.dimension, df.info);
					}
				} catch (JsonParseException | IOException e) {
					moveAside(f);
				}
			}
		} catch (IOException e) {
			// unreadable folder: start empty
		}
		m.markSaved();
		return m;
	}

	public void save(String worldKey, PortalMemory m) throws IOException {
		Files.createDirectories(worldDir(worldKey));
		for (Map.Entry<String, PortalMemory.DimInfo> e : m.dims.entrySet()) {
			DimFile df = new DimFile();
			df.dimension = e.getKey();
			df.info = e.getValue();
			Path f = dimFile(worldKey, e.getKey());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(df, w);
			}
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ex) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		m.markSaved();
	}

	/** Deletes everything remembered for this world. */
	public void forget(String worldKey) throws IOException {
		Path wd = worldDir(worldKey);
		if (!Files.isDirectory(wd)) {
			return;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(wd)) {
			for (Path f : files) {
				Files.deleteIfExists(f);
			}
		}
		Files.deleteIfExists(wd);
	}

	private static void moveAside(Path f) {
		try {
			Files.move(f, f.resolveSibling(f.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			// leave it; it will be overwritten on the next save
		}
	}
}

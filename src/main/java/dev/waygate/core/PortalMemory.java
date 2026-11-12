package dev.waygate.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything Waygate remembers about one world (singleplayer save or server): per dimension the portals
 * the player has seen (or the integrated server reported), and the dimension's scale, border and height
 * once learned. Plain fields so Gson can store it; Minecraft-free.
 */
public final class PortalMemory {
	/** One remembered portal. */
	public static final class Known {
		public int x;
		public int y;
		public int z;
		public Axis axis = Axis.X;
		public int width = 2;
		public int height = 3;
		public String name;
		/** True once the player has seen it; false when only the integrated server reported it. */
		public boolean seen;
		public long firstSeen;
		public long lastSeen;

		public Known() {
		}

		Known(PortalRect r, long now, boolean seen) {
			set(r);
			this.seen = seen;
			this.firstSeen = now;
			this.lastSeen = now;
		}

		void set(PortalRect r) {
			x = r.min().x();
			y = r.min().y();
			z = r.min().z();
			axis = r.axis();
			width = r.width();
			height = r.height();
		}

		public PortalRect rect() {
			return new PortalRect(new Pos(x, y, z), axis == null ? Axis.X : axis, Math.max(1, width), Math.max(1, height));
		}

		/** Name if given, else the bottom corner. */
		public String label() {
			return name != null && !name.isBlank() ? name : x + " " + y + " " + z;
		}
	}

	/** What is known about one dimension. Null fields are not learned yet. */
	public static final class DimInfo {
		public Double scale;
		public Double borderMinX;
		public Double borderMinZ;
		public Double borderMaxX;
		public Double borderMaxZ;
		public Integer minY;
		public Integer maxY;
		public Integer logicalHeight;
		public List<Known> portals = new ArrayList<>();

		public Optional<Border> border() {
			if (borderMinX == null || borderMinZ == null || borderMaxX == null || borderMaxZ == null) {
				return Optional.empty();
			}
			return Optional.of(new Border(borderMinX, borderMinZ, borderMaxX, borderMaxZ));
		}

		/** Returns true when anything changed. */
		public boolean learnBorder(Border b) {
			if (border().filter(b::equals).isPresent()) {
				return false;
			}
			borderMinX = b.minX();
			borderMinZ = b.minZ();
			borderMaxX = b.maxX();
			borderMaxZ = b.maxZ();
			return true;
		}

		/** Returns true when anything changed. */
		public boolean learnScale(double s) {
			if (scale != null && scale == s) {
				return false;
			}
			scale = s;
			return true;
		}

		/** Returns true when anything changed. */
		public boolean learnHeights(int min, int max, int logical) {
			if (minY != null && minY == min && maxY != null && maxY == max && logicalHeight != null && logicalHeight == logical) {
				return false;
			}
			minY = min;
			maxY = max;
			logicalHeight = logical;
			return true;
		}

		public Optional<CreatePortalSim.Level> level() {
			if (minY == null || maxY == null || logicalHeight == null) {
				return Optional.empty();
			}
			return Optional.of(new CreatePortalSim.Level(minY, maxY, logicalHeight));
		}

		public List<PortalRect> rects() {
			List<PortalRect> out = new ArrayList<>(portals.size());
			for (Known k : portals) {
				out.add(k.rect());
			}
			return out;
		}
	}

	/** Dimension id ({@code minecraft:the_nether}) to its info. */
	public Map<String, DimInfo> dims = new LinkedHashMap<>();

	/** Lasts only while the world is open: set when something worth saving changed. */
	private transient boolean dirty;

	public DimInfo dim(String id) {
		return dims.computeIfAbsent(id, k -> new DimInfo());
	}

	public boolean dirty() {
		return dirty;
	}

	public void markDirty() {
		dirty = true;
	}

	public void markSaved() {
		dirty = false;
	}

	/** Two rectangles share a block. */
	public static boolean overlaps(PortalRect a, PortalRect b) {
		if (a.axis() != b.axis()) {
			// a crossing portal shares a block only where both contain it
			for (Pos p : a.blocks()) {
				if (b.contains(p)) {
					return true;
				}
			}
			return false;
		}
		boolean x = a.axis() == Axis.X;
		int aAcross = x ? a.min().z() : a.min().x();
		int bAcross = x ? b.min().z() : b.min().x();
		if (aAcross != bAcross) {
			return false;
		}
		int aAlong = x ? a.min().x() : a.min().z();
		int bAlong = x ? b.min().x() : b.min().z();
		boolean along = aAlong < bAlong + b.width() && bAlong < aAlong + a.width();
		boolean up = a.min().y() < b.min().y() + b.height() && b.min().y() < a.min().y() + a.height();
		return along && up;
	}

	/**
	 * Remembers a portal. Remembered portals that overlap it are replaced (keeping the name and first-seen
	 * time); an identical one only gets its last-seen time and seen flag updated.
	 */
	public Known upsert(String dimId, PortalRect rect, long now, boolean seen) {
		DimInfo d = dim(dimId);
		Known merged = null;
		List<Known> replaced = new ArrayList<>();
		for (Known k : d.portals) {
			if (k.rect().equals(rect)) {
				if (seen && !k.seen) {
					k.seen = true;
					dirty = true;
				}
				k.lastSeen = now;
				return k;
			}
			if (overlaps(k.rect(), rect)) {
				replaced.add(k);
			}
		}
		merged = new Known(rect, now, seen);
		for (Known k : replaced) {
			if (merged.name == null && k.name != null) {
				merged.name = k.name;
			}
			merged.firstSeen = Math.min(merged.firstSeen, k.firstSeen);
			merged.seen |= k.seen;
		}
		d.portals.removeAll(replaced);
		d.portals.add(merged);
		dirty = true;
		return merged;
	}

	public boolean remove(String dimId, Known k) {
		DimInfo d = dims.get(dimId);
		if (d != null && d.portals.remove(k)) {
			dirty = true;
			return true;
		}
		return false;
	}

	/** The remembered portal containing this block, if any. */
	public Optional<Known> at(String dimId, Pos p) {
		DimInfo d = dims.get(dimId);
		if (d == null) {
			return Optional.empty();
		}
		return d.portals.stream().filter(k -> k.rect().contains(p)).findFirst();
	}

	/** Names a portal; a blank name removes it. */
	public void name(Known k, String name) {
		k.name = name == null || name.isBlank() ? null : name.strip();
		dirty = true;
	}

	public int portalCount() {
		return dims.values().stream().mapToInt(d -> d.portals.size()).sum();
	}
}

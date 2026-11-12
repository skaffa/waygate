package dev.waygate.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A rectangle of nether portal blocks: {@code min} is the bottom corner with the lowest coordinate along
 * {@code axis}, {@code width} blocks along the axis, {@code height} blocks up. Like vanilla
 * {@code BlockUtil.FoundRectangle}.
 */
public record PortalRect(Pos min, Axis axis, int width, int height) {
	public PortalRect {
		if (width < 1 || height < 1) {
			throw new IllegalArgumentException("portal must be at least 1x1: " + width + "x" + height);
		}
	}

	/** Every portal block. Each one is a separate {@code nether_portal} POI in vanilla. */
	public List<Pos> blocks() {
		List<Pos> out = new ArrayList<>(width * height);
		for (int w = 0; w < width; w++) {
			for (int h = 0; h < height; h++) {
				out.add(block(w, h));
			}
		}
		return out;
	}

	public Pos block(int w, int h) {
		return min.offset(w * axis.stepX, h, w * axis.stepZ);
	}

	public boolean contains(Pos p) {
		int along = axis == Axis.X ? p.x() - min.x() : p.z() - min.z();
		int across = axis == Axis.X ? p.z() - min.z() : p.x() - min.x();
		int up = p.y() - min.y();
		return across == 0 && along >= 0 && along < width && up >= 0 && up < height;
	}

	/** Bottom-centre of the portal opening, where a player standing in its middle is. */
	public double centerX() {
		return axis == Axis.X ? min.x() + width / 2.0 : min.x() + 0.5;
	}

	public double centerZ() {
		return axis == Axis.Z ? min.z() + width / 2.0 : min.z() + 0.5;
	}

	@Override
	public String toString() {
		return min + " " + axis + " " + width + "x" + height;
	}
}

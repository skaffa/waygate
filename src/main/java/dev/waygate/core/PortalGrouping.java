package dev.waygate.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Turns loose portal blocks (scanned blocks or server POIs) into portal rectangles. */
public final class PortalGrouping {
	/** Vanilla's largest portal side ({@code getLargestRectangleAround(..., 21, ..., 21, ...)}). */
	public static final int MAX_SIDE = 21;

	private PortalGrouping() {
	}

	/**
	 * Groups blocks into portals: blocks with the same axis that touch along that axis or vertically form
	 * one portal, whose rectangle is their bounding box.
	 * @param axisOf the block's {@code HORIZONTAL_AXIS}, or null when unknown (then it is guessed from the
	 *               shape, X for a single column)
	 */
	public static List<PortalRect> group(Collection<Pos> blocks, Function<Pos, Axis> axisOf) {
		Set<Pos> left = new HashSet<>(blocks);
		List<Pos> order = new ArrayList<>(blocks);
		order.sort(Comparator.comparingInt(Pos::y).thenComparingInt(Pos::x).thenComparingInt(Pos::z));
		List<PortalRect> out = new ArrayList<>();
		for (Pos start : order) {
			if (!left.remove(start)) {
				continue;
			}
			Axis axis = axisOf.apply(start);
			List<Pos> comp = new ArrayList<>();
			ArrayDeque<Pos> queue = new ArrayDeque<>();
			queue.add(start);
			comp.add(start);
			while (!queue.isEmpty()) {
				Pos p = queue.poll();
				for (Pos n : neighbours(p, axis)) {
					if (left.contains(n) && sameAxis(axisOf.apply(n), axis)) {
						left.remove(n);
						comp.add(n);
						queue.add(n);
					}
				}
			}
			out.add(bounds(comp, axis));
		}
		return out;
	}

	private static boolean sameAxis(Axis a, Axis b) {
		return a == null || b == null || a == b;
	}

	/** Up, down, and along the axis (both horizontal directions when the axis is unknown). */
	private static List<Pos> neighbours(Pos p, Axis axis) {
		List<Pos> n = new ArrayList<>(6);
		n.add(p.offset(0, 1, 0));
		n.add(p.offset(0, -1, 0));
		if (axis != Axis.Z) {
			n.add(p.offset(1, 0, 0));
			n.add(p.offset(-1, 0, 0));
		}
		if (axis != Axis.X) {
			n.add(p.offset(0, 0, 1));
			n.add(p.offset(0, 0, -1));
		}
		return n;
	}

	private static PortalRect bounds(List<Pos> comp, Axis axis) {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (Pos p : comp) {
			minX = Math.min(minX, p.x());
			minY = Math.min(minY, p.y());
			minZ = Math.min(minZ, p.z());
			maxX = Math.max(maxX, p.x());
			maxY = Math.max(maxY, p.y());
			maxZ = Math.max(maxZ, p.z());
		}
		if (axis == null) {
			axis = maxZ > minZ ? Axis.Z : Axis.X;
		}
		int width = axis == Axis.X ? maxX - minX + 1 : maxZ - minZ + 1;
		return new PortalRect(new Pos(minX, minY, minZ), axis, width, maxY - minY + 1);
	}
}

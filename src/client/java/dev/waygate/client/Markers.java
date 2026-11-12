package dev.waygate.client;

import dev.waygate.core.Axis;
import dev.waygate.core.PortalMemory;
import dev.waygate.core.PortalRect;
import dev.waygate.core.Prediction;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Outlines and labels for remembered portals near the player, submitted as vanilla gizmos every client
 * tick (like Gauge): green when the link works both ways, red when the way back goes elsewhere, purple
 * when no portal is found on the other side, grey while unknown.
 */
final class Markers {
	private static final float TEXT = 0.5f;
	private static final float SMALL = 0.4f;
	private static final int UNKNOWN = 0xFFA0A0A0;
	private static final int NEW = 0xFFC080FF;

	private Markers() {
	}

	static void tick(Minecraft mc) {
		if (!Waygate.config.markers || mc.level == null || mc.player == null) {
			return;
		}
		String dim = Waygate.dimId(mc.level);
		PortalMemory.DimInfo d = Waygate.memory().dims.get(dim);
		if (d == null) {
			return;
		}
		double range = Waygate.config.markerRange;
		Map<PortalRect, Prediction.Result> results = Predictor.markerResults();
		String to = Waygate.targetDimId(dim);
		for (PortalMemory.Known k : d.portals) {
			PortalRect r = k.rect();
			double cy = r.min().y() + r.height() / 2.0;
			if (mc.player.distanceToSqr(r.centerX(), cy, r.centerZ()) > range * range) {
				continue;
			}
			Prediction.Result res = results.get(r);
			int color;
			String line2;
			if (res == null) {
				color = UNKNOWN;
				line2 = "";
			} else if (res.linked().isEmpty()) {
				color = NEW;
				line2 = "→ new portal near " + res.exit();
			} else if (res.returnsHere()) {
				color = WaygateHud.GOOD;
				line2 = "⇄ " + Predictor.label(to, res.linked().get());
			} else {
				color = WaygateHud.BAD;
				line2 = "→ " + Predictor.label(to, res.linked().get()) + " (one way)";
			}
			Gizmos.cuboid(box(r).inflate(0.03), GizmoStyle.stroke(color, 2.0f));
			Vec3 top = new Vec3(r.centerX(), r.min().y() + r.height() + 1.8, r.centerZ());
			// labels show through walls: they are how you find a portal again
			Gizmos.billboardText(k.label(), top, TextGizmo.Style.forColorAndCentered(color).withScale(TEXT)).setAlwaysOnTop();
			if (!line2.isEmpty()) {
				Gizmos.billboardText(line2, top.add(0, -0.5, 0), TextGizmo.Style.forColorAndCentered(0xFFE0E0E0).withScale(SMALL))
						.setAlwaysOnTop();
			}
		}
	}

	/** The portal's blocks as one box (one block thick). */
	static AABB box(PortalRect r) {
		double x0 = r.min().x();
		double y0 = r.min().y();
		double z0 = r.min().z();
		double x1 = x0 + (r.axis() == Axis.X ? r.width() : 1);
		double z1 = z0 + (r.axis() == Axis.Z ? r.width() : 1);
		return new AABB(x0, y0, z0, x1, y0 + r.height(), z1);
	}
}

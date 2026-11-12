package dev.waygate.client;

import dev.waygate.core.Border;
import dev.waygate.core.Dim;
import dev.waygate.core.Pairing;
import dev.waygate.core.PortalMath;
import dev.waygate.core.PortalMemory;
import dev.waygate.core.PortalRect;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Every remembered portal with where it leads and whether the link works both ways. Broken links are
 * red. Uses remembered portals (in singleplayer that includes the ones the server reported).
 */
final class PairsScreen extends Screen {
	private static final int ROW = 11;
	private static final int TOP = 34;

	private final Screen parent;
	private final List<Line> lines = new ArrayList<>();
	private int scroll;
	private int broken;
	private int pairs;

	private record Line(String from, String to, String status, int color) {
	}

	PairsScreen(Screen parent) {
		super(Component.literal("Waygate: portal pairs"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		lines.clear();
		broken = 0;
		pairs = 0;
		PortalMemory m = Waygate.memory();
		Pairing.Side ow = side(m, Waygate.OVERWORLD, Dim.OVERWORLD);
		Pairing.Side ne = side(m, Waygate.NETHER, Dim.NETHER);
		add(Waygate.OVERWORLD, Waygate.NETHER, ow, ne);
		add(Waygate.NETHER, Waygate.OVERWORLD, ne, ow);
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	private static Pairing.Side side(PortalMemory m, String id, Dim dim) {
		PortalMemory.DimInfo d = m.dim(id);
		return new Pairing.Side(dim, d.scale != null ? d.scale : dim.defaultScale(), d.border().orElse(Border.VANILLA_DEFAULT), d.rects());
	}

	private void add(String fromId, String toId, Pairing.Side from, Pairing.Side to) {
		for (Pairing.Link l : Pairing.check(from, to)) {
			PortalRect p = from.portals().get(l.portal());
			String a = WaygateHud.dimName(fromId) + " " + Predictor.label(fromId, p);
			String b;
			String status;
			int color;
			switch (l.status()) {
				case PAIRED -> {
					b = Predictor.label(toId, to.portals().get(l.target()));
					status = "⇄ both ways";
					color = WaygateHud.GOOD;
					pairs++;
				}
				case ONE_WAY -> {
					b = Predictor.label(toId, to.portals().get(l.target()));
					status = "→ one way: back to " + (l.back() >= 0 ? Predictor.label(fromId, from.portals().get(l.back())) : "a new portal");
					color = WaygateHud.BAD;
					broken++;
				}
				default -> {
					double s = PortalMath.teleportScale(from.scale(), to.scale());
					b = "new portal near " + PortalMath.centerExit(p, s, to.border());
					status = "no partner";
					color = WaygateHud.ACCENT;
					broken++;
				}
			}
			if (l.split()) {
				status += ", part leads elsewhere";
				color = l.status() == Pairing.Status.PAIRED ? WaygateHud.WARN : color;
			}
			if (l.ambiguous()) {
				status += ", tie";
			}
			lines.add(new Line(a, "→ " + WaygateHud.dimName(toId) + " " + b, status, color));
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		int visible = Math.max(1, (height - TOP - 34) / (2 * ROW + 3));
		scroll = Math.clamp(scroll - (int) Math.signum(sy) * 2, 0, Math.max(0, lines.size() - visible));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(font, title, width / 2, 8, WaygateHud.ACCENT);
		String summary = lines.isEmpty() ? "No portals remembered yet."
				: pairs + " link both ways, " + broken + " do not" + (ServerPeek.active() ? "" : " (remembered portals only)");
		g.centeredText(font, Component.literal(summary), width / 2, 20, WaygateHud.LABEL);
		// two lines per portal: "from -> to", then the status, so it fits any window width
		int x = 10;
		int perEntry = 2 * ROW + 3;
		int visible = Math.max(1, (height - TOP - 34) / perEntry);
		int y = TOP;
		for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++) {
			Line l = lines.get(i);
			g.text(font, l.from(), x, y, WaygateHud.VALUE);
			g.text(font, l.to(), x + font.width(l.from()) + 6, y, WaygateHud.VALUE);
			g.text(font, "   " + l.status(), x, y + ROW, l.color());
			y += perEntry;
		}
		if (lines.size() > visible) {
			g.text(font, (scroll + 1) + "-" + Math.min(lines.size(), scroll + visible) + " of " + lines.size() + " (scroll)", x, height - 40,
					WaygateHud.NOTE);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}

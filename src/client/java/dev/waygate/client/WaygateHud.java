package dev.waygate.client;

import dev.waygate.core.CreatePortalSim;
import dev.waygate.core.PortalMath;
import dev.waygate.core.PortalRect;
import dev.waygate.core.PortalSearch;
import dev.waygate.core.Pos;
import dev.waygate.core.Prediction;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The portal panel: where the portal leads, what it links to, the way back, and warnings. */
final class WaygateHud {
	static final int BG = 0xC0120A1C;
	static final int ACCENT = 0xFFC080FF;
	static final int LABEL = 0xFF9A90A8;
	static final int VALUE = 0xFFFFFFFF;
	static final int GOOD = 0xFF70E090;
	static final int WARN = 0xFFFFC050;
	static final int BAD = 0xFFFF6060;
	static final int NOTE = 0xFF8A8494;
	private static final int PAD = 5;
	private static final int ROW = 10;
	private static final int MAX_W = 250;

	private WaygateHud() {
	}

	/** One row: label (may be empty) and value. */
	record Row(String label, String value, int color) {
	}

	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Predictor.View v = Predictor.current();
		if (v == null || mc.player == null || mc.gui.hud.isHidden() || mc.gui.screen() != null) {
			return;
		}
		String title = title(v);
		List<Row> rows = rows(v);
		draw(g, mc.font, Waygate.config.hudX, Waygate.config.hudY, title, rows);
	}

	static String title(Predictor.View v) {
		String to = dimName(v.to());
		if (v.focus() == null) {
			return "From here → " + to;
		}
		String name = Waygate.memory().at(v.from(), v.focus().rect().min()).map(k -> k.name).orElse(null);
		return (name != null ? name : "Portal") + " → " + to;
	}

	static List<Row> rows(Predictor.View v) {
		Prediction.Result r = v.result();
		List<Row> rows = new ArrayList<>();
		rows.add(new Row("Target", r.exit().toString(), VALUE));
		PortalMath.ExitRange range = r.range();
		boolean wide = range.low().x() != range.high().x() || range.low().z() != range.high().z();
		if (Waygate.config.showRange && wide && v.focus() != null) {
			rows.add(new Row("Portal range", range.low().x() + ".." + range.high().x() + ", " + range.low().z() + ".." + range.high().z(), LABEL));
		}

		if (r.linked().isPresent()) {
			PortalSearch.Hit h = r.hit().orElseThrow();
			PortalRect l = r.linked().get();
			rows.add(new Row("Links to", Predictor.label(v.to(), l) + dist(h.distance()), r.returnsHere() || v.focus() == null ? GOOD : WARN));
		} else {
			rows.add(new Row("Links to", newPortal(v), WARN));
		}

		if (v.focus() != null && r.linked().isPresent()) {
			if (r.returnsHere()) {
				rows.add(new Row("Way back", "returns here", GOOD));
			} else if (r.back().isPresent()) {
				rows.add(new Row("Way back", "→ " + Predictor.label(v.from(), r.back().get()) + " (not here)", BAD));
			} else {
				rows.add(new Row("Way back", "→ a new portal (not here)", BAD));
			}
		}

		for (Prediction.Warning w : r.warnings()) {
			String text = switch (w.kind()) {
				case MISLINK -> String.format(Locale.ROOT, "Mislink: %s leads back here, but %s is closer to the target (%.1f vs %.1f blocks)",
						Predictor.label(v.to(), w.other()), Predictor.label(v.to(), r.linked().orElseThrow()), w.distance(), w.otherDistance());
				case ONE_WAY -> null; // shown as "Way back"
				case SPLIT -> "Part of this portal leads elsewhere (" + w.count() + " different exits across it)";
				case AMBIGUOUS -> "Tie with " + Predictor.label(v.to(), w.other()) + ": the link depends on save order";
				case CLAMPED -> "Target moved inside the world border";
			};
			if (text != null) {
				rows.add(new Row("", "⚠ " + text, w.kind() == Prediction.WarningKind.MISLINK ? BAD : WARN));
			}
		}
		if (!v.borderKnown() && !v.exact()) {
			rows.add(new Row("", "Border of " + dimName(v.to()) + " not known yet (visit it once)", NOTE));
		}

		String source;
		if (v.exact()) {
			source = v.pending() ? "Asking the server…" : "Exact (singleplayer)";
		} else {
			source = "Multiplayer: remembered portals only (" + v.known() + " known in " + dimName(v.to())
					+ ") — unknown portals may exist";
		}
		rows.add(new Row("", source, NOTE));
		return rows;
	}

	private static String newPortal(Predictor.View v) {
		Pos exit = v.result().exit();
		ServerPeek.Sim sim = v.sim();
		if (sim == null || !sim.loaded()) {
			return "new portal near " + exit;
		}
		if (sim.placement().isEmpty()) {
			return "no portal can be created (no room)";
		}
		CreatePortalSim.Placement p = sim.placement().get();
		return switch (p.kind()) {
			case FULL, PARTIAL -> "new portal at " + p.min();
			case FORCED -> "new portal on a platform at " + p.min();
		};
	}

	private static String dist(double d) {
		return String.format(Locale.ROOT, " (%.0f blocks)", d);
	}

	static String dimName(String id) {
		if (Waygate.NETHER.equals(id)) {
			return "Nether";
		}
		if (Waygate.OVERWORLD.equals(id)) {
			return "Overworld";
		}
		return id;
	}

	static void draw(GuiGraphicsExtractor g, Font font, int x, int y, String title, List<Row> rows) {
		int labelW = 0;
		for (Row r : rows) {
			labelW = Math.max(labelW, font.width(r.label()));
		}
		int gap = labelW > 0 ? 8 : 0;
		// lay out: labelled rows in two columns, unlabelled rows wrapped over the full width
		List<Object[]> lines = new ArrayList<>();
		int innerW = Math.max(font.width(title), 120);
		for (Row r : rows) {
			if (r.label().isEmpty()) {
				for (String part : wrap(font, r.value(), MAX_W)) {
					lines.add(new Object[] {"", part, r.color()});
					innerW = Math.max(innerW, font.width(part));
				}
			} else {
				lines.add(new Object[] {r.label(), r.value(), r.color()});
				innerW = Math.max(innerW, labelW + gap + font.width(r.value()));
			}
		}
		int headerH = 13;
		int h = headerH + 3 + lines.size() * ROW + PAD;
		g.fill(x, y, x + innerW + 2 * PAD, y + h, BG);
		g.fill(x, y, x + 2, y + h, ACCENT);
		g.text(font, title, x + PAD, y + 4, ACCENT);
		g.fill(x + PAD, y + headerH, x + PAD + innerW, y + headerH + 1, 0x40C080FF);
		int ly = y + headerH + 4;
		for (Object[] l : lines) {
			String label = (String) l[0];
			if (!label.isEmpty()) {
				g.text(font, label, x + PAD, ly, LABEL);
				g.text(font, (String) l[1], x + PAD + labelW + gap, ly, (int) l[2]);
			} else {
				g.text(font, (String) l[1], x + PAD, ly, (int) l[2]);
			}
			ly += ROW;
		}
	}

	static List<String> wrap(Font font, String text, int maxW) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String next = line.isEmpty() ? word : line + " " + word;
			if (font.width(next) > maxW && !line.isEmpty()) {
				out.add(line.toString());
				line = new StringBuilder("  " + word);
			} else {
				line = new StringBuilder(next);
			}
		}
		if (!line.isEmpty()) {
			out.add(line.toString());
		}
		return out;
	}
}

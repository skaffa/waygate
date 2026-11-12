package dev.waygate.client;

import dev.waygate.core.WaygateConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** All Waygate settings on one screen. Changes apply and save immediately. */
public final class WaygateScreen extends Screen {
	private static final int ROW = 22;
	private static final int H = 20;

	private final Screen parent;
	private final List<Label> labels = new ArrayList<>();
	private int colW;

	private record Label(String text, int x, int y, IntSupplier value) {
	}

	public WaygateScreen(Screen parent) {
		super(Component.literal("Waygate"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		labels.clear();
		WaygateConfig c = Waygate.config;
		colW = Math.min(320, width - 40);
		int x = width / 2 - colW / 2;
		int y = 32;

		labels.add(new Label("Portal panel", x, y + 6, null));
		addRenderableWidget(Button.builder(Component.literal(modeName(c.hudMode)), b -> {
			c.hudMode = c.hudMode.next();
			b.setMessage(Component.literal(modeName(c.hudMode)));
			Waygate.saveConfig();
		}).bounds(x + colW - colW / 2, y, colW / 2, H).tooltip(Tooltip.create(Component.literal(
				"Auto: while you stand in or look at a portal. Always: also shows where your own spot would lead. Key U switches."))).build());
		y += ROW;
		y = toggle(x, y, "Show portal range", () -> c.showRange, v -> c.showRange = v,
				"The spread of exits across a wide portal (a nether portal can reach 16 overworld blocks per block of width)");
		y = toggle(x, y, "Portal markers", () -> c.markers, v -> c.markers = v,
				"Outline and label remembered portals near you: green both ways, red one way, purple no partner");
		y = number(x, y, "Marker range", () -> c.markerRange, v -> c.markerRange = v, 8, "Markers for portals within this many blocks");
		y = number(x, y, "Look distance", () -> c.lookDistance, v -> c.lookDistance = v, 4, "A portal you look at counts up to this far away");
		y = number(x, y, "Panel X", () -> c.hudX, v -> c.hudX = v, 10, "Panel position from the left edge (GUI pixels)");
		number(x, y, "Panel Y", () -> c.hudY, v -> c.hudY = v, 10, "Panel position from the top edge (GUI pixels)");

		addRenderableWidget(Button.builder(Component.literal("Portal pairs…"), b -> minecraft.gui.setScreen(new PairsScreen(this)))
				.bounds(width / 2 - 100, height - 50, 200, H).tooltip(Tooltip.create(Component.literal("Which remembered portals link both ways"))).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, H).build());
	}

	private static String modeName(WaygateConfig.HudMode m) {
		return switch (m) {
			case AUTO -> "Auto";
			case ALWAYS -> "Always";
			case OFF -> "Off";
		};
	}

	private int toggle(int x, int y, String label, BooleanSupplier get, Consumer<Boolean> set, String tip) {
		labels.add(new Label(label, x, y + 6, null));
		addRenderableWidget(Button.builder(onOff(get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			Waygate.saveConfig();
			b.setMessage(onOff(get.getAsBoolean()));
		}).bounds(x + colW - colW / 2, y, colW / 2, H).tooltip(Tooltip.create(Component.literal(tip))).build());
		return y + ROW;
	}

	/** Label, value in the middle, and - / + buttons. */
	private int number(int x, int y, String label, IntSupplier get, Consumer<Integer> set, int step, String tip) {
		labels.add(new Label(label, x, y + 6, get));
		int bw = 24;
		Tooltip t = Tooltip.create(Component.literal(tip));
		addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			set.accept(get.getAsInt() - step);
			Waygate.config.clamp();
			Waygate.saveConfig();
		}).bounds(x + colW - colW / 2, y, bw, H).tooltip(t).build());
		addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			set.accept(get.getAsInt() + step);
			Waygate.config.clamp();
			Waygate.saveConfig();
		}).bounds(x + colW - bw, y, bw, H).tooltip(t).build());
		return y + ROW;
	}

	private static Component onOff(boolean v) {
		return Component.literal(v ? "On" : "Off");
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(font, title, width / 2, 12, WaygateHud.ACCENT);
		for (Label l : labels) {
			g.text(font, Component.literal(l.text()), l.x(), l.y(), WaygateHud.LABEL);
			if (l.value() != null) {
				int mid = l.x() + colW - colW / 4;
				g.centeredText(font, Component.literal(Integer.toString(l.value().getAsInt())), mid, l.y(), WaygateHud.VALUE);
			}
		}
	}

	@Override
	public void onClose() {
		Waygate.saveConfig();
		minecraft.gui.setScreen(parent);
	}
}

package dev.waygate.core;

/** The dimensions that matter for nether portals. Anything that is not the nether sends players to the nether. */
public enum Dim {
	OVERWORLD,
	NETHER,
	OTHER;

	/** Vanilla {@code NetherPortalBlock.getPortalDestination}: the nether leads to the overworld, everything else to the nether. */
	public Dim portalTarget() {
		return this == NETHER ? OVERWORLD : NETHER;
	}

	/** Vanilla default {@code coordinate_scale}. Datapacks can change it; prefer the real value. */
	public double defaultScale() {
		return this == NETHER ? 8.0 : 1.0;
	}
}

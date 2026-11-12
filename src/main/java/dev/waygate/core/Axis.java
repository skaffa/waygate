package dev.waygate.core;

/** Horizontal axis of a nether portal (vanilla {@code HORIZONTAL_AXIS}). */
public enum Axis {
	X(1, 0),
	Z(0, 1);

	/** Step of the positive direction along this axis (EAST for X, SOUTH for Z). */
	public final int stepX;
	public final int stepZ;

	Axis(int stepX, int stepZ) {
		this.stepX = stepX;
		this.stepZ = stepZ;
	}

	/** Step of {@code direction.getClockWise()} for the positive direction: EAST to SOUTH, SOUTH to WEST. */
	public int clockStepX() {
		return this == X ? 0 : -1;
	}

	public int clockStepZ() {
		return this == X ? 1 : 0;
	}
}

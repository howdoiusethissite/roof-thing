package com.roofthing.roof;

/**
 * One rectangular chunk of building to roof. Corners are the outermost wall blocks (inclusive),
 * and {@code wallTopY} is the Y of the topmost wall block. The roof starts directly above it.
 */
public record RoofSection(int x0, int z0, int x1, int z1, int wallTopY, RoofStyle style, int overhang, int ext0, int ext1) {

	public RoofSection(int x0, int z0, int x1, int z1, int wallTopY, RoofStyle style, int overhang) {
		this(x0, z0, x1, z1, wallTopY, style, overhang, 0, 0);
	}

	public RoofSection {
		int minX = Math.min(x0, x1);
		int maxX = Math.max(x0, x1);
		int minZ = Math.min(z0, z1);
		int maxZ = Math.max(z0, z1);
		x0 = minX;
		x1 = maxX;
		z0 = minZ;
		z1 = maxZ;
	}

	/** Footprint including the overhang. */
	int fx0() {
		return x0 - overhang;
	}

	int fx1() {
		return x1 + overhang;
	}

	int fz0() {
		return z0 - overhang;
	}

	int fz1() {
		return z1 + overhang;
	}

	int footprintWidthX() {
		return fx1() - fx0() + 1;
	}

	int footprintWidthZ() {
		return fz1() - fz0() + 1;
	}

	/** Y of the lowest roof layer. With a 1 block overhang the eave sits level with the wall top. */
	int baseY() {
		return wallTopY + 1 - overhang;
	}

	boolean isGable() {
		return style != RoofStyle.HIP;
	}

	/** For gables: true when the ridge runs along the X axis (slopes fall toward -Z and +Z). */
	boolean ridgeAlongX() {
		boolean longerX = footprintWidthX() >= footprintWidthZ();
		// Square footprints default to a ridge along Z so the result is predictable.
		if (footprintWidthX() == footprintWidthZ()) {
			longerX = false;
		}
		return style == RoofStyle.GABLE_ROTATED ? !longerX : longerX;
	}

	/** Returns a copy whose gable ends run on past the footprint, to reach a neighbouring roof. */
	RoofSection withExtension(int low, int high) {
		return new RoofSection(x0, z0, x1, z1, wallTopY, style, overhang, low, high);
	}

	// Extents including any extension (which only ever applies along a gable's ridge).
	int minX() {
		return isGable() && ridgeAlongX() ? fx0() - ext0 : fx0();
	}

	int maxX() {
		return isGable() && ridgeAlongX() ? fx1() + ext1 : fx1();
	}

	int minZ() {
		return isGable() && !ridgeAlongX() ? fz0() - ext0 : fz0();
	}

	int maxZ() {
		return isGable() && !ridgeAlongX() ? fz1() + ext1 : fz1();
	}

	boolean contains(int x, int z) {
		return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
	}

	/** Contains test ignoring any extension. */
	boolean containsBase(int x, int z) {
		return x >= fx0() && x <= fx1() && z >= fz0() && z <= fz1();
	}

	/** Gable only: the (lower of the) centre column(s) across the slope, where the ridge sits. */
	int ridgeColumnX() {
		return fx0() + (footprintWidthX() - 1) / 2;
	}

	int ridgeColumnZ() {
		return fz0() + (footprintWidthZ() - 1) / 2;
	}

	/** Gable only: Y of the ridge. */
	int ridgeY() {
		int slopeWidth = ridgeAlongX() ? footprintWidthZ() : footprintWidthX();
		return baseY() + (slopeWidth - 1) / 2;
	}

	/** Roof surface Y (the block that holds the roof piece) for a column inside the footprint. */
	int surfaceY(int x, int z) {
		int dx = Math.min(x - fx0(), fx1() - x);
		int dz = Math.min(z - fz0(), fz1() - z);
		int layer;
		if (style == RoofStyle.HIP) {
			layer = Math.min(dx, dz);
		} else if (ridgeAlongX()) {
			layer = dz;
		} else {
			layer = dx;
		}
		return baseY() + layer;
	}

	/**
	 * Height used when asking "is the neighbour lower?". Outside a gable's ridge-direction ends the
	 * roof is treated as continuing flat so the gable end stays a clean vertical cut.
	 */
	int neighbourY(int x, int z) {
		if (contains(x, z)) {
			return surfaceY(x, z);
		}
		if (!isGable()) {
			return Integer.MIN_VALUE;
		}
		if (ridgeAlongX()) {
			if (z >= fz0() && z <= fz1()) {
				return surfaceY(Math.max(minX(), Math.min(maxX(), x)), z);
			}
		} else if (x >= fx0() && x <= fx1()) {
			return surfaceY(x, Math.max(minZ(), Math.min(maxZ(), z)));
		}
		return Integer.MIN_VALUE;
	}
}

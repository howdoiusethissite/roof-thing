package com.roofthing.roof;

import java.util.Locale;

public enum RoofStyle {
	/** Ridge runs along the longer side of the footprint. */
	GABLE,
	/** Ridge runs along the shorter side of the footprint. */
	GABLE_ROTATED,
	/** Every edge slopes inward and the roof comes to a ridge or a point. */
	HIP;

	public RoofStyle next() {
		RoofStyle[] all = values();
		return all[(ordinal() + 1) % all.length];
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static RoofStyle byId(String id) {
		for (RoofStyle style : values()) {
			if (style.id().equals(id)) {
				return style;
			}
		}
		return null;
	}
}

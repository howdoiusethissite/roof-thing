package com.roofthing;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import com.roofthing.roof.RoofSection;
import com.roofthing.roof.RoofStyle;

/** What one player has marked so far. Kept in memory only. */
public final class Selection {
	public static final int MIN_OVERHANG = 0;
	public static final int MAX_OVERHANG = 3;

	public final List<RoofSection> sections = new ArrayList<>();
	public BlockPos pendingCorner;
	public Identifier dimension;
	public RoofStyle style = RoofStyle.GABLE;
	public int overhang = 1;

	public void clear() {
		sections.clear();
		pendingCorner = null;
	}

	public boolean isEmpty() {
		return sections.isEmpty() && pendingCorner == null;
	}
}

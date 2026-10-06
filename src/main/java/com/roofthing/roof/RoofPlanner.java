package com.roofthing.roof;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns roof sections into a height map and then into per-block "low quadrant" masks.
 *
 * <p>Every column under the roof gets exactly one block. Sections are merged by taking the
 * highest surface of any section for each column, which makes wings, valleys and hips fall out
 * naturally: a lower wing roof simply disappears into the higher main roof where they cross.
 *
 * <p>For each block we look at which of its four quadrants (seen from above) slope downward.
 * One low quadrant is an inner corner, two adjacent are a straight stair, three are an outer
 * corner, and four (or a saddle) is a flat ridge slab.
 */
public final class RoofPlanner {
	public static final int NO_ROOF = Integer.MIN_VALUE;

	/** Quadrant bit: +1 for sx > 0, +2 for sz > 0. */
	public static int quadrantBit(int sx, int sz) {
		return 1 << ((sx > 0 ? 1 : 0) | (sz > 0 ? 2 : 0));
	}

	public record Pos(int x, int y, int z) {
	}

	private RoofPlanner() {
	}

	/** How far a gable end may run on to reach a neighbouring roof. */
	private static final int MAX_EXTENSION = 64;

	public static Map<Pos, Integer> plan(List<RoofSection> original) {
		Map<Pos, Integer> result = new LinkedHashMap<>();
		if (original.isEmpty()) {
			return result;
		}
		List<RoofSection> sections = extendIntoNeighbours(original);

		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (RoofSection s : sections) {
			minX = Math.min(minX, s.minX());
			maxX = Math.max(maxX, s.maxX());
			minZ = Math.min(minZ, s.minZ());
			maxZ = Math.max(maxZ, s.maxZ());
		}

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				int y = ownY(sections, x, z);
				if (y == NO_ROOF) {
					continue;
				}
				result.put(new Pos(x, y, z), lowMask(sections, x, z, y));
			}
		}
		return result;
	}

	/**
	 * A gable wing built against another section would otherwise stop at its own footprint and
	 * leave a gap. Run its ridge on until the other roof is at least as high, so the two roofs
	 * meet in a proper valley.
	 */
	private static List<RoofSection> extendIntoNeighbours(List<RoofSection> sections) {
		List<RoofSection> out = new ArrayList<>();
		for (RoofSection s : sections) {
			if (!s.isGable()) {
				out.add(s);
				continue;
			}
			out.add(s.withExtension(extensionDepth(s, sections, false), extensionDepth(s, sections, true)));
		}
		return out;
	}

	private static int extensionDepth(RoofSection s, List<RoofSection> all, boolean high) {
		int depth = 0;
		for (int d = 1; d <= MAX_EXTENSION; d++) {
			int x;
			int z;
			if (s.ridgeAlongX()) {
				x = high ? s.fx1() + d : s.fx0() - d;
				z = s.ridgeColumnZ();
			} else {
				x = s.ridgeColumnX();
				z = high ? s.fz1() + d : s.fz0() - d;
			}
			boolean inside = false;
			int best = NO_ROOF;
			for (RoofSection t : all) {
				if (t != s && t.containsBase(x, z)) {
					inside = true;
					best = Math.max(best, t.surfaceY(x, z));
				}
			}
			if (!inside) {
				break;
			}
			depth = d;
			if (best >= s.ridgeY()) {
				break;
			}
		}
		return depth;
	}

	private static int ownY(List<RoofSection> sections, int x, int z) {
		int best = NO_ROOF;
		for (RoofSection s : sections) {
			if (s.contains(x, z)) {
				best = Math.max(best, s.surfaceY(x, z));
			}
		}
		return best;
	}

	private static int neighbourY(List<RoofSection> sections, int x, int z) {
		int best = NO_ROOF;
		for (RoofSection s : sections) {
			best = Math.max(best, s.neighbourY(x, z));
		}
		return best;
	}

	private static int lowMask(List<RoofSection> sections, int x, int z, int y) {
		int mask = 0;
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sz = -1; sz <= 1; sz += 2) {
				boolean low = neighbourY(sections, x + sx, z) < y
					|| neighbourY(sections, x, z + sz) < y
					|| neighbourY(sections, x + sx, z + sz) < y;
				if (low) {
					mask |= quadrantBit(sx, sz);
				}
			}
		}
		return mask;
	}
}

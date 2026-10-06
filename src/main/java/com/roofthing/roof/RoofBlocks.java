package com.roofthing.roof;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.roofthing.roof.RoofPlanner.Pos;

/** Maps planned quadrant masks onto real stair and slab block states. */
public final class RoofBlocks {
	private RoofBlocks() {
	}

	public record Result(Map<BlockPos, BlockState> states, int stairs, int slabs) {
	}

	private record Candidate(Direction facing, StairsShape shape, int tallMask) {
	}

	private static final class Cell {
		final Pos pos;
		final List<Candidate> candidates;
		int chosen;

		Cell(Pos pos, List<Candidate> candidates) {
			this.pos = pos;
			this.candidates = candidates;
		}

		Candidate current() {
			return candidates.get(chosen);
		}
	}

	public static Result resolve(Map<Pos, Integer> plan, StairBlock stair, SlabBlock slab) {
		List<Candidate> all = candidates(stair);
		Map<Pos, Cell> stairCells = new LinkedHashMap<>();
		List<Pos> slabCells = new ArrayList<>();

		for (Map.Entry<Pos, Integer> e : plan.entrySet()) {
			int low = e.getValue();
			int tall = 15 ^ low;
			if (isSlabMask(low)) {
				slabCells.add(e.getKey());
				continue;
			}
			List<Candidate> matching = new ArrayList<>();
			for (Candidate c : all) {
				if (c.tallMask == tall) {
					matching.add(c);
				}
			}
			if (matching.isEmpty()) {
				slabCells.add(e.getKey());
			} else {
				stairCells.put(e.getKey(), new Cell(e.getKey(), matching));
			}
		}

		makeVanillaStable(stairCells);

		Map<BlockPos, BlockState> states = new LinkedHashMap<>();
		for (Cell cell : stairCells.values()) {
			Candidate c = cell.current();
			BlockState state = stair.defaultBlockState()
				.setValue(StairBlock.FACING, c.facing)
				.setValue(StairBlock.HALF, Half.BOTTOM)
				.setValue(StairBlock.SHAPE, c.shape)
				.setValue(StairBlock.WATERLOGGED, false);
			states.put(new BlockPos(cell.pos.x(), cell.pos.y(), cell.pos.z()), state);
		}
		BlockState slabState = slab.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM).setValue(SlabBlock.WATERLOGGED, false);
		for (Pos p : slabCells) {
			states.put(new BlockPos(p.x(), p.y(), p.z()), slabState);
		}
		return new Result(states, stairCells.size(), slabCells.size());
	}

	/** Four low quadrants is a flat top; two diagonal low quadrants (a saddle) has no stair shape. */
	private static boolean isSlabMask(int low) {
		if (low == 0 || low == 15) {
			return true;
		}
		int a = RoofPlanner.quadrantBit(-1, -1) | RoofPlanner.quadrantBit(1, 1);
		int b = RoofPlanner.quadrantBit(1, -1) | RoofPlanner.quadrantBit(-1, 1);
		return low == a || low == b;
	}

	private static List<Candidate> candidates(StairBlock stair) {
		List<Candidate> list = new ArrayList<>();
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			for (StairsShape shape : StairsShape.values()) {
				BlockState state = stair.defaultBlockState()
					.setValue(StairBlock.FACING, facing)
					.setValue(StairBlock.HALF, Half.BOTTOM)
					.setValue(StairBlock.SHAPE, shape);
				list.add(new Candidate(facing, shape, tallMask(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))));
			}
		}
		return list;
	}

	/** Which quadrants of the block are full height, measured from the real collision shape. */
	private static int tallMask(VoxelShape shape) {
		int mask = 0;
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sz = -1; sz <= 1; sz += 2) {
				double x0 = sx > 0 ? 0.5 : 0.0;
				double z0 = sz > 0 ? 0.5 : 0.0;
				VoxelShape quadrantTop = Shapes.box(x0, 0.75, z0, x0 + 0.5, 1.0, z0 + 0.5);
				if (Shapes.joinIsNotEmpty(shape, quadrantTop, BooleanOp.AND)) {
					mask |= RoofPlanner.quadrantBit(sx, sz);
				}
			}
		}
		return mask;
	}

	/**
	 * Corner pieces can be built from two different facings. Vanilla re-derives stair shapes from
	 * neighbours whenever one changes, so pick the facings vanilla agrees with. Otherwise the
	 * roof would visibly "snap" the first time a block next to it updated.
	 */
	private static void makeVanillaStable(Map<Pos, Cell> cells) {
		for (int pass = 0; pass < 8; pass++) {
			boolean changed = false;
			for (Cell cell : cells.values()) {
				if (cell.candidates.size() < 2) {
					continue;
				}
				int bestIndex = cell.chosen;
				int bestErrors = errorsAround(cells, cell);
				for (int i = 0; i < cell.candidates.size(); i++) {
					if (i == cell.chosen) {
						continue;
					}
					int previous = cell.chosen;
					cell.chosen = i;
					int errors = errorsAround(cells, cell);
					cell.chosen = previous;
					if (errors < bestErrors) {
						bestErrors = errors;
						bestIndex = i;
					}
				}
				if (bestIndex != cell.chosen) {
					cell.chosen = bestIndex;
					changed = true;
				}
			}
			if (!changed) {
				return;
			}
		}
	}

	private static int errorsAround(Map<Pos, Cell> cells, Cell cell) {
		int errors = vanillaMismatch(cells, cell) ? 1 : 0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			Cell n = cells.get(new Pos(cell.pos.x() + d.getStepX(), cell.pos.y(), cell.pos.z() + d.getStepZ()));
			if (n != null && vanillaMismatch(cells, n)) {
				errors++;
			}
		}
		return errors;
	}

	private static boolean vanillaMismatch(Map<Pos, Cell> cells, Cell cell) {
		return vanillaShape(cells, cell) != cell.current().shape;
	}

	/** Mirror of the private StairBlock.getStairsShape, evaluated against the planned roof. */
	static StairsShape vanillaShape(Map<Pos, Cell> cells, Cell cell) {
		Direction facing = cell.current().facing;
		Pos pos = cell.pos;

		Cell behind = at(cells, pos, facing);
		if (behind != null) {
			Direction behindFacing = behind.current().facing;
			if (behindFacing.getAxis() != facing.getAxis() && canTakeShape(cells, cell, behindFacing.getOpposite())) {
				return behindFacing == facing.getCounterClockWise() ? StairsShape.OUTER_LEFT : StairsShape.OUTER_RIGHT;
			}
		}

		Cell front = at(cells, pos, facing.getOpposite());
		if (front != null) {
			Direction frontFacing = front.current().facing;
			if (frontFacing.getAxis() != facing.getAxis() && canTakeShape(cells, cell, frontFacing)) {
				return frontFacing == facing.getCounterClockWise() ? StairsShape.INNER_LEFT : StairsShape.INNER_RIGHT;
			}
		}
		return StairsShape.STRAIGHT;
	}

	private static boolean canTakeShape(Map<Pos, Cell> cells, Cell cell, Direction neighbour) {
		Cell n = at(cells, cell.pos, neighbour);
		return n == null || n.current().facing != cell.current().facing;
	}

	private static Cell at(Map<Pos, Cell> cells, Pos pos, Direction d) {
		return cells.get(new Pos(pos.x() + d.getStepX(), pos.y(), pos.z() + d.getStepZ()));
	}
}

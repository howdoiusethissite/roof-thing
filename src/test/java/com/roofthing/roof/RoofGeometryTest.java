package com.roofthing.roof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.state.properties.StairsShape;

class RoofGeometryTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	static RoofBlocks.Result build(RoofSection... sections) {
		Map<RoofPlanner.Pos, Integer> plan = RoofPlanner.plan(List.of(sections));
		return RoofBlocks.resolve(plan, (StairBlock) Blocks.OAK_STAIRS, (SlabBlock) Blocks.OAK_SLAB);
	}

	/** Vanilla's own shape rule, called through reflection on the real StairBlock. */
	static int vanillaMismatches(Map<BlockPos, BlockState> states) throws Exception {
		Method m = StairBlock.class.getDeclaredMethod("getStairsShape", BlockState.class, BlockGetter.class, BlockPos.class);
		m.setAccessible(true);
		BlockGetter getter = new BlockGetter() {
			@Override
			public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
				return null;
			}

			@Override
			public BlockState getBlockState(BlockPos pos) {
				BlockState s = states.get(pos);
				return s == null ? Blocks.AIR.defaultBlockState() : s;
			}

			@Override
			public FluidState getFluidState(BlockPos pos) {
				return Fluids.EMPTY.defaultFluidState();
			}

			@Override
			public int getHeight() {
				return 384;
			}

			@Override
			public int getMinY() {
				return -64;
			}
		};
		int bad = 0;
		for (Map.Entry<BlockPos, BlockState> e : states.entrySet()) {
			if (e.getValue().getBlock() instanceof StairBlock) {
				StairsShape vanilla = (StairsShape) m.invoke(null, e.getValue(), getter, e.getKey());
				if (vanilla != e.getValue().getValue(StairBlock.SHAPE)) {
					bad++;
				}
			}
		}
		return bad;
	}

	static String topView(RoofBlocks.Result r) {
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		TreeMap<Long, String> cells = new TreeMap<>();
		for (Map.Entry<BlockPos, BlockState> e : r.states().entrySet()) {
			BlockPos p = e.getKey();
			minX = Math.min(minX, p.getX());
			maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ());
			maxZ = Math.max(maxZ, p.getZ());
		}
		StringBuilder sb = new StringBuilder();
		for (int z = minZ; z <= maxZ; z++) {
			for (int x = minX; x <= maxX; x++) {
				BlockState found = null;
				int fy = 0;
				for (Map.Entry<BlockPos, BlockState> e : r.states().entrySet()) {
					BlockPos p = e.getKey();
					if (p.getX() == x && p.getZ() == z) {
						found = e.getValue();
						fy = p.getY();
					}
				}
				sb.append(found == null ? "  .  " : glyph(found, fy));
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	static String glyph(BlockState s, int y) {
		if (s.getBlock() instanceof SlabBlock) {
			return " =" + String.format("%2d", y) + " ";
		}
		char f = switch (s.getValue(StairBlock.FACING)) {
			case NORTH -> '^';
			case SOUTH -> 'v';
			case EAST -> '>';
			default -> '<';
		};
		String k = switch (s.getValue(StairBlock.SHAPE)) {
			case STRAIGHT -> " ";
			case INNER_LEFT, INNER_RIGHT -> "i";
			default -> "o";
		};
		return " " + f + k + String.format("%d", y % 100 / 10 == 0 ? y % 10 : y) + " ";
	}

	@Test
	void gableEvenWidthMeetsInTheMiddle() throws Exception {
		// wall 6 wide (x 0..5) and 10 long (z 0..9), overhang 1 -> footprint 8 wide, ridge along Z
		RoofBlocks.Result r = build(new RoofSection(0, 0, 5, 9, 70, RoofStyle.GABLE, 1));
		System.out.println("GABLE even width (y shown at the end of each cell)\n" + topView(r));
		assertEquals(0, r.slabs(), "an even width needs no ridge slabs");
		assertEquals(8 * 12, r.stairs());
		assertEquals(0, vanillaMismatches(r.states()));
	}

	@Test
	void gableOddWidthGetsSlabRidge() throws Exception {
		RoofBlocks.Result r = build(new RoofSection(0, 0, 4, 8, 70, RoofStyle.GABLE, 1));
		System.out.println("GABLE odd width\n" + topView(r));
		assertEquals(11, r.slabs(), "ridge is one slab per block of length (9 + 2 overhang)");
		assertEquals(0, vanillaMismatches(r.states()));
	}

	@Test
	void hipRoof() throws Exception {
		RoofBlocks.Result r = build(new RoofSection(0, 0, 8, 12, 70, RoofStyle.HIP, 1));
		System.out.println("HIP\n" + topView(r));
		assertEquals(0, vanillaMismatches(r.states()));
	}

	@Test
	void hipSquarePyramid() throws Exception {
		RoofBlocks.Result r = build(new RoofSection(0, 0, 4, 4, 70, RoofStyle.HIP, 1));
		System.out.println("HIP square\n" + topView(r));
		assertEquals(1, r.slabs());
		assertEquals(0, vanillaMismatches(r.states()));
	}

	@Test
	void wingMeetsMainRoof() throws Exception {
		// main block 0..10 x 0..6 (ridge along X), wing sticking out toward +Z at x 3..7, z 7..14 (ridge along Z)
		RoofBlocks.Result r = build(
			new RoofSection(0, 0, 10, 6, 70, RoofStyle.GABLE, 1),
			new RoofSection(3, 7, 7, 14, 70, RoofStyle.GABLE, 1));
		System.out.println("MAIN + WING\n" + topView(r));
		assertTrue(r.states().size() > 0);
		assertEquals(0, vanillaMismatches(r.states()));
	}

	@Test
	void lowerWingRunsIntoMainRoof() throws Exception {
		// wing wall touches the main wall (z 7..14); the wing roof must run on until it meets the main slope
		RoofBlocks.Result r = build(
			new RoofSection(0, 0, 10, 6, 70, RoofStyle.GABLE, 1),
			new RoofSection(3, 7, 7, 14, 70, RoofStyle.GABLE, 1));
		// no stair in the wing may sit at a height that leaves a gap, so every column of the wing footprint is filled
		for (int x = 2; x <= 8; x++) {
			for (int z = 6; z <= 15; z++) {
				BlockPos found = null;
				for (BlockPos p : r.states().keySet()) {
					if (p.getX() == x && p.getZ() == z) {
						found = p;
					}
				}
				assertTrue(found != null, "hole at " + x + "," + z);
			}
		}
		assertEquals(0, vanillaMismatches(r.states()));
	}

	@Test
	void hipMainWithGableWing() throws Exception {
		RoofBlocks.Result r = build(
			new RoofSection(0, 0, 12, 8, 70, RoofStyle.HIP, 1),
			new RoofSection(4, 9, 8, 16, 70, RoofStyle.GABLE, 1));
		System.out.println("HIP MAIN + GABLE WING\n" + topView(r));
		assertEquals(0, vanillaMismatches(r.states()));
	}
}

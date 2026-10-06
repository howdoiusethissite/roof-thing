package com.roofthing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.roofthing.roof.RoofBlocks;
import com.roofthing.roof.RoofPlanner;
import com.roofthing.roof.RoofSection;
import com.roofthing.roof.RoofStyle;

/** Wand and command logic: collecting corners, building the roof and undoing it. */
public final class RoofService {
	/** Longest allowed side of one section, wall to wall. */
	public static final int MAX_SPAN = 64;
	private static final int MAX_UNDO = 5;

	private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
	private static final Map<UUID, Deque<UndoRecord>> UNDO = new HashMap<>();

	private record Placed(BlockPos pos, BlockState before, BlockState after) {
	}

	private record UndoRecord(ResourceKey<Level> dimension, List<Placed> placed, Item stairItem, Item slabItem) {
	}

	private RoofService() {
	}

	public static Selection selection(ServerPlayer player) {
		return SELECTIONS.computeIfAbsent(player.getUUID(), id -> new Selection());
	}

	public static void forget(UUID id) {
		SELECTIONS.remove(id);
		UNDO.remove(id);
	}

	// ---------------------------------------------------------------- wand input

	public static void clickBlock(ServerPlayer player, ServerLevel level, BlockPos pos, boolean sneaking) {
		Selection sel = selection(player);
		Identifier dim = level.dimension().identifier();
		if (sel.dimension != null && !sel.dimension.equals(dim)) {
			sel.clear();
		}
		sel.dimension = dim;

		if (sneaking) {
			removeLast(player, sel);
			return;
		}

		if (sel.pendingCorner == null) {
			sel.pendingCorner = pos.immutable();
			info(player, "Corner 1 set at " + fmt(pos) + ". Right-click the opposite corner of the wall top.");
			return;
		}

		BlockPos a = sel.pendingCorner;
		int spanX = Math.abs(a.getX() - pos.getX()) + 1;
		int spanZ = Math.abs(a.getZ() - pos.getZ()) + 1;
		if (spanX > MAX_SPAN || spanZ > MAX_SPAN) {
			fail(player, "That section is " + spanX + " x " + spanZ + ". The limit is " + MAX_SPAN + " blocks per side.");
			return;
		}

		// The first corner decides the height, so click the top wall block both times.
		RoofSection section = new RoofSection(a.getX(), a.getZ(), pos.getX(), pos.getZ(), a.getY(), sel.style, sel.overhang);
		sel.sections.add(section);
		sel.pendingCorner = null;
		info(player, "Section " + sel.sections.size() + " added: " + spanX + " x " + spanZ + ", " + describe(sel.style)
			+ ", overhang " + sel.overhang + ". Add more sections for wings, or right-click the air to build.");
	}

	public static void clickAir(ServerPlayer player, ServerLevel level) {
		if (player.isShiftKeyDown()) {
			Selection sel = selection(player);
			sel.style = sel.style.next();
			info(player, "Roof style: " + describe(sel.style) + " (applies to the next section you add).");
			return;
		}
		build(player, level);
	}

	private static void removeLast(ServerPlayer player, Selection sel) {
		if (sel.pendingCorner != null) {
			sel.pendingCorner = null;
			info(player, "Corner cleared.");
		} else if (!sel.sections.isEmpty()) {
			sel.sections.removeLast();
			info(player, "Removed the last section. " + sel.sections.size() + " left.");
		} else {
			info(player, "Nothing to remove.");
		}
	}

	// ---------------------------------------------------------------- building

	public static void build(ServerPlayer player, ServerLevel level) {
		Selection sel = selection(player);
		if (sel.sections.isEmpty()) {
			fail(player, sel.pendingCorner != null
				? "Pick the opposite corner first."
				: "Nothing marked yet. Right-click two opposite corners of a wall top with the wand.");
			return;
		}
		if (sel.dimension != null && !sel.dimension.equals(level.dimension().identifier())) {
			fail(player, "Your marked sections are in a different dimension.");
			return;
		}

		ItemStack stairStack = findStairs(player);
		if (stairStack.isEmpty()) {
			fail(player, "Put some stairs in your inventory (or off-hand) to roof with.");
			return;
		}
		StairBlock stair = (StairBlock) ((BlockItem) stairStack.getItem()).getBlock();
		Optional<SlabBlock> slabOpt = matchingSlab(stair);
		if (slabOpt.isEmpty()) {
			fail(player, "Couldn't find a slab that matches " + stairStack.getHoverName().getString() + ".");
			return;
		}
		SlabBlock slab = slabOpt.get();

		Map<RoofPlanner.Pos, Integer> plan = RoofPlanner.plan(sel.sections);
		RoofBlocks.Result resolved = RoofBlocks.resolve(plan, stair, slab);

		List<Placed> toPlace = new ArrayList<>();
		int stairsNeeded = 0;
		int slabsNeeded = 0;
		int skipped = 0;
		for (Map.Entry<BlockPos, BlockState> e : resolved.states().entrySet()) {
			BlockPos pos = e.getKey();
			BlockState before = level.getBlockState(pos);
			BlockState after = e.getValue();
			if (before.equals(after)) {
				continue;
			}
			boolean usable = !level.isOutsideBuildHeight(pos) && level.isLoaded(pos) && level.mayInteract(player, pos)
				&& (before.isAir() || before.canBeReplaced());
			if (!usable) {
				skipped++;
				continue;
			}
			toPlace.add(new Placed(pos.immutable(), before, after));
			if (after.getBlock() instanceof StairBlock) {
				stairsNeeded++;
			} else {
				slabsNeeded++;
			}
		}

		if (toPlace.isEmpty()) {
			fail(player, "Nothing to place. The roof area is blocked or already roofed.");
			return;
		}

		Item stairItem = stairStack.getItem();
		Item slabItem = slab.asItem();
		boolean creative = player.isCreative();
		if (!creative) {
			int haveStairs = count(player, stairItem);
			int haveSlabs = count(player, slabItem);
			if (haveStairs < stairsNeeded || haveSlabs < slabsNeeded) {
				List<String> missing = new ArrayList<>();
				if (haveStairs < stairsNeeded) {
					missing.add((stairsNeeded - haveStairs) + " more " + new ItemStack(stairItem).getHoverName().getString());
				}
				if (haveSlabs < slabsNeeded) {
					missing.add((slabsNeeded - haveSlabs) + " more " + new ItemStack(slabItem).getHoverName().getString());
				}
				fail(player, "This roof needs " + stairsNeeded + " stairs and " + slabsNeeded + " slabs. You need " + String.join(" and ", missing) + ".");
				return;
			}
		}

		for (Placed p : toPlace) {
			level.setBlock(p.pos, p.after, Block.UPDATE_CLIENTS);
		}
		if (!creative) {
			take(player, stairItem, stairsNeeded);
			take(player, slabItem, slabsNeeded);
		}

		Deque<UndoRecord> history = UNDO.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());
		history.addFirst(new UndoRecord(level.dimension(), toPlace, stairItem, slabItem));
		while (history.size() > MAX_UNDO) {
			history.removeLast();
		}

		int sections = sel.sections.size();
		sel.clear();
		String tail = skipped > 0 ? " (" + skipped + " spots were blocked and left alone)" : "";
		ok(player, "Roofed " + sections + (sections == 1 ? " section" : " sections") + " with " + stairsNeeded + " stairs and "
			+ slabsNeeded + " slabs" + (creative ? "" : " from your inventory") + tail + ". Use /roof undo to take it back.");
	}

	public static void undo(ServerPlayer player) {
		Deque<UndoRecord> history = UNDO.get(player.getUUID());
		if (history == null || history.isEmpty()) {
			fail(player, "Nothing to undo.");
			return;
		}
		UndoRecord record = history.removeFirst();
		ServerLevel level = player.level().getServer().getLevel(record.dimension);
		if (level == null) {
			fail(player, "That dimension isn't loaded.");
			return;
		}

		int stairs = 0;
		int slabs = 0;
		int changed = 0;
		for (Placed p : record.placed) {
			// Only touch blocks that are still exactly what we placed.
			if (!level.getBlockState(p.pos).equals(p.after)) {
				continue;
			}
			level.setBlock(p.pos, p.before, Block.UPDATE_CLIENTS);
			changed++;
			if (p.after.getBlock() instanceof StairBlock) {
				stairs++;
			} else {
				slabs++;
			}
		}
		if (!player.isCreative()) {
			give(player, level, record.stairItem, stairs);
			give(player, level, record.slabItem, slabs);
		}
		ok(player, "Removed " + changed + " roof blocks" + (player.isCreative() ? "." : " and gave the materials back."));
	}

	// ---------------------------------------------------------------- inventory

	/** Off-hand stairs win, then the first stairs found in the inventory. */
	private static ItemStack findStairs(ServerPlayer player) {
		ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
		if (isStairs(off)) {
			return off;
		}
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (isStairs(stack)) {
				return stack;
			}
		}
		return ItemStack.EMPTY;
	}

	private static boolean isStairs(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof StairBlock;
	}

	private static Optional<SlabBlock> matchingSlab(StairBlock stair) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(stair);
		String path = id.getPath();
		if (!path.endsWith("_stairs")) {
			return Optional.empty();
		}
		Identifier slabId = Identifier.fromNamespaceAndPath(id.getNamespace(), path.substring(0, path.length() - "_stairs".length()) + "_slab");
		return BuiltInRegistries.BLOCK.getOptional(slabId)
			.filter(b -> b instanceof SlabBlock)
			.map(b -> (SlabBlock) b);
	}

	private static int count(ServerPlayer player, Item item) {
		int total = 0;
		ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
		if (off.is(item)) {
			total += off.getCount();
		}
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static void take(ServerPlayer player, Item item, int amount) {
		int left = amount;
		ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
		if (off.is(item) && left > 0) {
			int n = Math.min(left, off.getCount());
			off.shrink(n);
			left -= n;
		}
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (left <= 0) {
				break;
			}
			if (stack.is(item)) {
				int n = Math.min(left, stack.getCount());
				stack.shrink(n);
				left -= n;
			}
		}
		player.getInventory().setChanged();
	}

	private static void give(ServerPlayer player, ServerLevel level, Item item, int amount) {
		int left = amount;
		int max = new ItemStack(item).getMaxStackSize();
		while (left > 0) {
			ItemStack stack = new ItemStack(item, Math.min(left, max));
			left -= stack.getCount();
			if (!player.getInventory().add(stack) && !stack.isEmpty()) {
				player.spawnAtLocation(level, stack);
			}
		}
	}

	// ---------------------------------------------------------------- messages

	public static String describe(RoofStyle style) {
		return switch (style) {
			case GABLE -> "gable (ridge along the long side)";
			case GABLE_ROTATED -> "gable (ridge along the short side)";
			case HIP -> "hip (slopes on all four sides)";
		};
	}

	private static String fmt(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	public static void info(ServerPlayer player, String message) {
		player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
	}

	public static void ok(ServerPlayer player, String message) {
		player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
	}

	public static void fail(ServerPlayer player, String message) {
		player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
	}
}

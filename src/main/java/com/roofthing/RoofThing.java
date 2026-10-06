package com.roofthing;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

import com.roofthing.roof.RoofStyle;

public class RoofThing implements ModInitializer {
	public static final String MOD_ID = "roofthing";

	public static Item ROOF_WAND;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ResourceKey<Item> wandKey = ResourceKey.create(Registries.ITEM, id("roof_wand"));
		ROOF_WAND = Registry.register(BuiltInRegistries.ITEM, wandKey,
			new RoofWandItem(new Item.Properties().setId(wandKey).stacksTo(1)));

		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
			.register(output -> output.accept(ROOF_WAND));

		ServerTickEvents.END_SERVER_TICK.register(SelectionPreview::tick);
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(roofCommand()));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> roofCommand() {
		return Commands.literal("roof")
			.then(Commands.literal("build").executes(c -> {
				ServerPlayer p = c.getSource().getPlayerOrException();
				RoofService.build(p, p.level());
				return 1;
			}))
			.then(Commands.literal("undo").executes(c -> {
				RoofService.undo(c.getSource().getPlayerOrException());
				return 1;
			}))
			.then(Commands.literal("clear").executes(c -> {
				ServerPlayer p = c.getSource().getPlayerOrException();
				RoofService.selection(p).clear();
				RoofService.info(p, "Selection cleared.");
				return 1;
			}))
			.then(Commands.literal("style")
				.then(Commands.argument("style", StringArgumentType.word())
					.suggests((c, b) -> {
						for (RoofStyle s : RoofStyle.values()) {
							b.suggest(s.id());
						}
						return b.buildFuture();
					})
					.executes(c -> {
						ServerPlayer p = c.getSource().getPlayerOrException();
						RoofStyle style = RoofStyle.byId(StringArgumentType.getString(c, "style"));
						if (style == null) {
							RoofService.fail(p, "Styles: gable, gable_rotated, hip.");
							return 0;
						}
						RoofService.selection(p).style = style;
						RoofService.info(p, "Roof style: " + RoofService.describe(style) + " (applies to the next section you add).");
						return 1;
					})))
			.then(Commands.literal("overhang")
				.then(Commands.argument("blocks", IntegerArgumentType.integer(Selection.MIN_OVERHANG, Selection.MAX_OVERHANG))
					.executes(c -> {
						ServerPlayer p = c.getSource().getPlayerOrException();
						int blocks = IntegerArgumentType.getInteger(c, "blocks");
						RoofService.selection(p).overhang = blocks;
						RoofService.info(p, "Overhang: " + blocks + " (applies to the next section you add).");
						return 1;
					})))
			.then(Commands.literal("info").executes(c -> {
				ServerPlayer p = c.getSource().getPlayerOrException();
				Selection s = RoofService.selection(p);
				RoofService.info(p, s.sections.size() + " section(s) marked" + (s.pendingCorner != null ? ", one corner pending" : "")
					+ ". Next section: " + RoofService.describe(s.style) + ", overhang " + s.overhang + ".");
				return 1;
			}));
	}
}

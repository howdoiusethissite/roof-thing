package com.roofthing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import com.roofthing.roof.RoofSection;

/** Draws the marked wall tops with particles, only for the player holding the wand. */
final class SelectionPreview {
	private SelectionPreview() {
	}

	static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean holding = player.getItemInHand(InteractionHand.MAIN_HAND).is(RoofThing.ROOF_WAND)
				|| player.getItemInHand(InteractionHand.OFF_HAND).is(RoofThing.ROOF_WAND);
			if (!holding || !(player.level() instanceof ServerLevel level)) {
				continue;
			}
			Selection sel = RoofService.selection(player);
			if (sel.dimension != null && !sel.dimension.equals(level.dimension().identifier())) {
				continue;
			}
			for (RoofSection s : sel.sections) {
				outline(level, player, s);
			}
			if (sel.pendingCorner != null) {
				BlockPos c = sel.pendingCorner;
				for (int i = 0; i < 4; i++) {
					level.sendParticles(player, ParticleTypes.END_ROD, true, true, c.getX() + 0.5, c.getY() + 1.1 + i * 0.4, c.getZ() + 0.5, 1, 0, 0, 0, 0);
				}
			}
		}
	}

	private static void outline(ServerLevel level, ServerPlayer player, RoofSection s) {
		double y = s.wallTopY() + 1.1;
		double minX = s.x0();
		double maxX = s.x1() + 1.0;
		double minZ = s.z0();
		double maxZ = s.z1() + 1.0;
		for (double x = minX; x <= maxX; x += 1.0) {
			particle(level, player, x, y, minZ);
			particle(level, player, x, y, maxZ);
		}
		for (double z = minZ; z <= maxZ; z += 1.0) {
			particle(level, player, minX, y, z);
			particle(level, player, maxX, y, z);
		}
	}

	private static void particle(ServerLevel level, ServerPlayer player, double x, double y, double z) {
		level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, true, x, y, z, 1, 0, 0, 0, 0);
	}
}

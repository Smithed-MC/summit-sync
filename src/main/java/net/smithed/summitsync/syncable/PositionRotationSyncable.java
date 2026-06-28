package net.smithed.summitsync.syncable;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Collections;

import static net.smithed.summitsync.SummitSync.LOGGER;

public class PositionRotationSyncable extends Syncable {
	public PositionRotationSyncable() {
		super("position");
	}

	@Override
	public void onJoin(ServerPlayer player, CompoundTag tag) {
		if (tag.contains("x") && tag.contains("y") && tag.contains("z")) {
			try {
				double x = tag.getDouble("x").orElse(0.0);
				double y = tag.getDouble("y").orElse(0.0);
				double z = tag.getDouble("z").orElse(0.0);
				float pitch = tag.getFloat("pitch").orElse(0.0f);
				float yaw = tag.getFloat("yaw").orElse(0.0f);
				String dimensionStr = tag.getString("dimension").orElse("minecraft:overworld");

				var server = ((ServerLevel) player.level()).getServer();
				if (server != null) {
					Identifier dimensionLoc = Identifier.parse(dimensionStr);
					ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, dimensionLoc);
					ServerLevel targetLevel = server.getLevel(dimensionKey);
					if (targetLevel != null) {
						player.teleportTo(targetLevel, x, y, z, Collections.emptySet(), yaw, pitch, true);
					} else {
						player.teleportTo(x, y, z);
						player.setXRot(pitch);
						player.setYRot(yaw);
					}
				}
			} catch (Exception exc) {
				LOGGER.error("Failed to sync position on join for player {}", player.getScoreboardName(), exc);
			}
		}
	}

	@Override
	public void onLeave(ServerPlayer player, CompoundTag tag) {
		try {
			tag.putDouble("x", player.getX());
			tag.putDouble("y", player.getY());
			tag.putDouble("z", player.getZ());
			tag.putFloat("pitch", player.getXRot());
			tag.putFloat("yaw", player.getYRot());
			tag.putString("dimension", player.level().dimension().identifier().toString());
		} catch (Exception exc) {
			LOGGER.error("Failed to sync position on leave for player {}", player.getScoreboardName(), exc);
		}
	}
}

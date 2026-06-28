package net.smithed.summitsync.syncable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

public abstract class Syncable {
	public final String keyPrefix;

	protected Syncable(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

	public String getKey(ServerPlayer player) {
		return getKey(player.getStringUUID());
	}

	public String getKey(String uuid) {
		return "player:" + keyPrefix + ":" + uuid;
	}

	public abstract void onJoin(ServerPlayer player, CompoundTag tag);
	public abstract void onLeave(ServerPlayer player, CompoundTag tag);
}

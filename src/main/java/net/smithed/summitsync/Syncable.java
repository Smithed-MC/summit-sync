package net.smithed.summitsync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

public abstract class Syncable {
	public final String keyPrefix;

	protected Syncable(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

	public String getKey(ServerPlayer player) {
		return "player:" + keyPrefix + ":" + player.getStringUUID();
	}

	public abstract void onJoin(ServerPlayer player, CompoundTag tag);
	public abstract void onLeave(ServerPlayer player, CompoundTag tag);
}

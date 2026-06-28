package net.smithed.summitsync.syncable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static net.smithed.summitsync.SummitSync.LOGGER;

public class AdvancementSyncable extends Syncable {
	public AdvancementSyncable() {
		super("advancements");
	}

	@Override
	public void onJoin(ServerPlayer player, CompoundTag tag) {
		String data = tag.getString("json").orElse(null);
		if (data == null) {
			return;
		}
		try {
			var server = ((ServerLevel) player.level()).getServer();
			if (server == null) return;
			Path path = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR)
					.resolve(player.getStringUUID() + ".json");

			Files.createDirectories(path.getParent());
			Files.writeString(path, data, StandardCharsets.UTF_8);

			player.getAdvancements().reload(server.getAdvancements());
		} catch (Exception exc) {
			LOGGER.error("Failed to sync advancements on join for player {}", player.getScoreboardName(), exc);
		}
	}

	@Override
	public void onLeave(ServerPlayer player, CompoundTag tag) {
		try {
			var server = ((ServerLevel) player.level()).getServer();
			if (server == null) return;

			player.getAdvancements().save();

			Path path = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR)
					.resolve(player.getStringUUID() + ".json");

			if (Files.exists(path)) {
				String data = Files.readString(path, StandardCharsets.UTF_8);
				tag.putString("json", data);
			}
		} catch (Exception exc) {
			LOGGER.error("Failed to sync advancements on leave for player {}", player.getScoreboardName(), exc);
		}
	}
}

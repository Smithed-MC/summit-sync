package net.smithed.summitsync.syncable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;
import net.smithed.summitsync.SyncSettingsManager;

import java.util.Set;

import static net.smithed.summitsync.SummitSync.LOGGER;

public class ScoreboardSyncable extends Syncable {
	public ScoreboardSyncable() {
		super("scoreboard");
	}

	@Override
	public void onJoin(ServerPlayer player, CompoundTag tag) {
		ServerLevel level = (ServerLevel) player.level();
		Scoreboard scoreboard = level.getServer().getScoreboard();

		Set<String> objectivesToSync = SyncSettingsManager.getScoresToSync();
		for (String objName : objectivesToSync) {
			Objective objective = scoreboard.getObjective(objName);
			if (objective != null && tag.contains(objName)) {
				int val = tag.getInt(objName).orElse(0);
				scoreboard.getOrCreatePlayerScore(player, objective).set(val);
			} else if (objective == null) {
				LOGGER.warn("Scoreboard objective '{}' to sync not found on this server. Skipping.", objName);
			}
		}
	}

	@Override
	public void onLeave(ServerPlayer player, CompoundTag tag) {
		ServerLevel level = (ServerLevel) player.level();
		Scoreboard scoreboard = level.getServer().getScoreboard();

		Set<String> objectivesToSync = SyncSettingsManager.getScoresToSync();
		for (String objName : objectivesToSync) {
			Objective objective = scoreboard.getObjective(objName);
			if (objective != null) {
				ReadOnlyScoreInfo scoreInfo = scoreboard.getPlayerScoreInfo(player, objective);
				if (scoreInfo != null) {
					tag.putInt(objName, scoreInfo.value());
				}
			}
		}
	}
}

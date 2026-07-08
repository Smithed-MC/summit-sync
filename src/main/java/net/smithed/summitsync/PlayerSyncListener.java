package net.smithed.summitsync;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.smithed.summitsync.syncable.Syncable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class PlayerSyncListener {
    private static final Identifier PLAYER_JOIN = Identifier.fromNamespaceAndPath("summit.sync", "player/join");
    private static final Identifier PLAYER_LEAVE = Identifier.fromNamespaceAndPath("summit.sync", "player/leave");

    public static void register(List<Syncable> syncables) {
        ServerPlayerEvents.JOIN.register((player) -> {
            for (Syncable syncable : syncables) {
                try {
                    String data = SummitSync.redis().get(syncable.getKey(player));
                    if (data == null) {
                        data = PostgresManager.getLatestData(syncable.getKey(player), player.getStringUUID());
                    }
                    if (data != null) {
                        CompoundTag tag = TagParser.parseCompoundFully(data);
                        syncable.onJoin(player, tag);
                    }
                } catch (Exception exc) {
                    SummitSync.LOGGER.error("Failed to load {} for player {}", syncable.keyPrefix, player.getScoreboardName(), exc);
                }
            }

            var args = new CompoundTag();
            args.putString("uuid", player.getStringUUID());
            FunctionExecutor.invokeFunctionEvent(player, args, PLAYER_JOIN);
        });

        ServerPlayerEvents.LEAVE.register((player) -> {
            for (Syncable syncable : syncables) {
                var args = new CompoundTag();
                args.putString("uuid", player.getStringUUID());
                FunctionExecutor.invokeFunctionEvent(player, args, PLAYER_LEAVE);

                try {
                    CompoundTag tag = new CompoundTag();
                    syncable.onLeave(player, tag);
                    String dataStr = tag.toString();
                    SummitSync.redis().set(syncable.getKey(player), dataStr);
                    PostgresManager.queueSaveTask(syncable.getKey(player), player.getStringUUID(), dataStr);
                } catch (Exception exc) {
                    SummitSync.LOGGER.error("Failed to save {} for player {}", syncable.keyPrefix, player.getScoreboardName(), exc);
                }
            }
        });
    }
}

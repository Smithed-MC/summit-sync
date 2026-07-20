package net.smithed.summitsync;

import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

public class DatabaseSyncManager {
    private static ConcurrentLinkedDeque<Runnable> syncQueue = new ConcurrentLinkedDeque<>();

    public static void tickDatabases(MinecraftServer server) {
        List<SyncSettingsManager.DatabaseSettings> databases = SyncSettingsManager.getDatabasesToSync();
        for (SyncSettingsManager.DatabaseSettings dbSettings : databases) {
            Identifier dbKey = dbSettings.key;
            try {
                var storage = server.getCommandStorage();
                CompoundTag rootTag = storage.get(dbKey);
                if (rootTag.isEmpty()) continue;

                CompoundTag dirtyTag = rootTag.getCompound("dirty").orElse(null);
                if (dirtyTag == null || dirtyTag.isEmpty()) continue;

                CompoundTag dbTag = rootTag.getCompound("database").orElseGet(CompoundTag::new);

                for (String uuid : dirtyTag.keySet()) {
                    net.minecraft.nbt.Tag dataTag = dbTag.get(uuid);
                    if (dataTag != null) {
                        String dataStr = dataTag.toString();

                        // Publish to Redis
                        try {
                            org.json.JSONObject msg = new org.json.JSONObject();
                            msg.put("key", dbKey.toString());
                            msg.put("uuid", uuid);
                            msg.put("data", dataStr);
                            msg.put("sender", SummitSync.SERVER_ID);
                            SummitSync.redis().publish("summit-sync:database-update", msg.toString());
                        } catch (Exception e) {
                            SummitSync.LOGGER.error("Failed to publish Redis update for database {} and uuid {}", dbKey.toString(), uuid, e);
                        }

                        // Replicate to Postgres
                        PostgresManager.queueSaveTask(dbKey.toString(), uuid, dataStr);
                    }
                }

                // Clear the dirty list
                rootTag.put("dirty", new CompoundTag());
                storage.set(dbKey, rootTag);
            } catch (Exception e) {
                SummitSync.LOGGER.error("Error ticking database {}", dbKey.toString(), e);
            }
        }

        while (!syncQueue.isEmpty()) {
            syncQueue.pop().run();
        }
    }

    public static void loadDatabasesFromPostgres(MinecraftServer server) {
        List<SyncSettingsManager.DatabaseSettings> databases = SyncSettingsManager.getDatabasesToSync();
        for (SyncSettingsManager.DatabaseSettings dbSettings : databases) {
            Identifier dbKey = dbSettings.key;
            try {
                Map<String, String> dbData = PostgresManager.getLatestDataForDatabase(dbKey.toString());
                if (!dbData.isEmpty()) {
                    var storage = server.getCommandStorage();
                    CompoundTag rootTag = storage.get(dbKey);
                    CompoundTag dbTag = rootTag.getCompound("database").orElseGet(CompoundTag::new);
                    ListTag keysList = rootTag.getList("keys").orElseGet(ListTag::new);

                    for (Map.Entry<String, String> entry : dbData.entrySet()) {
                        String uuid = entry.getKey();
                        String dataStr = entry.getValue();
                        try {
                            dbTag.put(uuid, TagParser.create(net.minecraft.nbt.NbtOps.INSTANCE).parseFully(dataStr));

                            // Check if keysList has this uuid
                            boolean exists = false;
                            for (int i = 0; i < keysList.size(); i++) {
                                CompoundTag keyEntry = keysList.getCompound(i).orElse(null);
                                if (keyEntry != null && uuid.equals(keyEntry.getString("uuid").orElse(""))) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                CompoundTag keyEntry = new CompoundTag();
                                keyEntry.putString("uuid", uuid);
                                keysList.add(keyEntry);
                            }
                        } catch (Exception e) {
                            SummitSync.LOGGER.error("Failed to parse NBT for database {} and uuid {}", dbKey.toString(), uuid, e);
                        }
                    }
                    rootTag.put("database", dbTag);
                    rootTag.put("keys", keysList);
                    storage.set(dbKey, rootTag);
                    SummitSync.LOGGER.info("Loaded {} database entries from Postgres for key {}", dbData.size(), dbKey.toString());

                    if (dbSettings.onInitialize != null) {
                        server.getFunctions().get(dbSettings.onInitialize).ifPresent(
                                (func) ->
                                        FunctionExecutor.execute(
                                                func,
                                                server.createCommandSourceStack(),
                                                server.getCommands().getDispatcher(),
                                                new CompoundTag()
                                        )
                        );
                    }
                }
            } catch (Exception e) {
                SummitSync.LOGGER.error("Failed to load database {} from Postgres on startup", dbKey.toString(), e);
            }
        }
    }

    public static @NonNull Thread createSubscriberThread() {
        Thread subscribeThread = new Thread(() -> {
            while (true) {
                try {
                    SummitSync.redis().subscribe(new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            try {
                                var msg = new org.json.JSONObject(message);
                                String sender = msg.optString("sender");
                                if (SummitSync.SERVER_ID.equals(sender)) {
                                    return;
                                }

                                var server = SummitSync.getServer();
                                if (server == null) {
                                    return;
                                }

                                if ("summit-sync:chat-message".equals(channel)) {
                                    server.execute(() -> {
                                        ChatSyncManager.handleIncomingChatMessage(server, msg);
                                    });
                                } else if ("summit-sync:database-update".equals(channel)) {
                                    String dbKey = msg.getString("key");
                                    String uuid = msg.getString("uuid");
                                    String dataStr = msg.getString("data");
                                    server.execute(() -> {
                                        updateLocalStorageAndSync(server, dbKey, uuid, dataStr);
                                    });
                                }
                            } catch (Exception e) {
                                SummitSync.LOGGER.error("Failed to handle Redis pub/sub message on channel: " + channel, e);
                            }
                        }
                    }, "summit-sync:database-update", "summit-sync:chat-message");
                } catch (Exception e) {
                    SummitSync.LOGGER.error("Redis subscription failed, retrying in 5 seconds...", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        });
        subscribeThread.setDaemon(true);
        return subscribeThread;
    }

    private static void updateLocalStorageAndSync(MinecraftServer server, String dbKey, String uuid, String dataStr) {
        SummitSync.LOGGER.info("Received database update via Redis pub/sub: key={}, uuid={}, data={}", dbKey, uuid, dataStr);
        try {
            var storage = server.getCommandStorage();
            Identifier storageId = Identifier.parse(dbKey);
            CompoundTag rootTag = storage.get(storageId);
            var dbTag = rootTag.getCompound("database").orElseGet(CompoundTag::new);
            var keysList = rootTag.getList("keys").orElseGet(ListTag::new);

            // Update database tag
            dbTag.put(uuid, TagParser.create(net.minecraft.nbt.NbtOps.INSTANCE).parseFully(dataStr));

            // Ensure missing key is added to the list
            boolean exists = false;
            for (int i = 0; i < keysList.size(); i++) {
                CompoundTag keyEntry = keysList.getCompound(i).orElse(null);
                if (keyEntry != null && uuid.equals(keyEntry.getString("uuid").orElse(""))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                CompoundTag keyEntry = new CompoundTag();
                keyEntry.putString("uuid", uuid);
                keysList.add(keyEntry);
            }

            rootTag.put("database", dbTag);
            rootTag.put("keys", keysList);
            storage.set(storageId, rootTag);

            // Find matching sync settings for this database to run on_sync function
            List<SyncSettingsManager.DatabaseSettings> databases = SyncSettingsManager.getDatabasesToSync();
            for (SyncSettingsManager.DatabaseSettings dbSettings : databases) {
                if (dbKey.equals(dbSettings.key.toString()) && dbSettings.onSync != null) {
                    Identifier functionId = dbSettings.onSync;
                    var functions = server.getFunctions();
                    var funcOpt = functions.get(functionId);
                    if (funcOpt.isPresent()) {
                        var func = funcOpt.get();
                        var args = new CompoundTag();
                        args.putString("uuid", uuid);

                        syncQueue.push(() ->
                                FunctionExecutor.execute(
                                        func,
                                        server.createCommandSourceStack(),
                                        server.getCommands().getDispatcher(),
                                        args
                                )
                        );
                    }
                }
            }
        } catch (Exception e) {
            SummitSync.LOGGER.error("Failed to update local storage and sync for database " + dbKey + " and uuid " + uuid, e);
        }
    }
}

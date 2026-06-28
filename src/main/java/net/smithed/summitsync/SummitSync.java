package net.smithed.summitsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.smithed.summitsync.syncable.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.RedisClient;
import io.github.cdimascio.dotenv.Dotenv;

import java.util.List;

public class SummitSync implements ModInitializer {
    public static final String MOD_ID = "summit-sync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final String SERVER_ID = java.util.UUID.randomUUID().toString();

    private static RedisClient _client;
    private static MinecraftServer _server;

    // List of all sync modules
    private static final List<Syncable> SYNCABLES = List.of(
        new AdvancementSyncable(),
        new AttributesSyncable(),
        new EffectsSyncable(),
        new EnderChestSyncable(),
        new InventorySyncable(),
        new PositionRotationSyncable(),
        new TagsSyncable(),
        new ScoreboardSyncable()
    );

    @Override
    public void onInitialize() {
        SyncSettingsManager.register();

        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        _client = RedisClient.create(dotenv.get("REDIS_URL"));
        PostgresManager.init(dotenv);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            _server = server;
            DatabaseSyncManager.loadDatabasesFromPostgres(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            _server = null;
            PostgresManager.shutdown();
        });

        ServerTickEvents.END_SERVER_TICK.register(DatabaseSyncManager::tickDatabases);

        // Background Redis Subscriber
        Thread subscribeThread = DatabaseSyncManager.createSubscriberThread();
        subscribeThread.start();

        // Register player listeners
        PlayerSyncListener.register(SYNCABLES);

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            SyncCommand.register(dispatcher, SYNCABLES);
        });
    }

    public static RedisClient redis() {
        return _client;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static MinecraftServer getServer() {
        return _server;
    }
}

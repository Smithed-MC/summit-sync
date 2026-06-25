package net.smithed.summitsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.RedisClient;
import io.github.cdimascio.dotenv.Dotenv;

import java.util.List;

public class SummitSync implements ModInitializer {
	public static final String MOD_ID = "summit-sync";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static RedisClient _client;

	// List of all sync modules
	private static final List<Syncable> SYNCABLES = List.of(
			new AdvancementSyncable(),
			new AttributesSyncable(),
			new EnderChestSyncable(),
			new InventorySyncable(),
			new PositionRotationSyncable()
	);

	@Override
	public void onInitialize() {
		Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
		_client = RedisClient.create(dotenv.get("REDIS_URL"));

		ServerPlayerEvents.JOIN.register((player) -> {
			for (Syncable syncable : SYNCABLES) {
				try {
					String data = redis().get(syncable.getKey(player));
					if (data != null) {
						CompoundTag tag = TagParser.parseCompoundFully(data);
						syncable.onJoin(player, tag);
					}
				} catch (Exception exc) {
					LOGGER.error("Failed to load {} for player {}", syncable.keyPrefix, player.getScoreboardName(), exc);
				}
			}
		});

		ServerPlayerEvents.LEAVE.register((player) -> {
			for (Syncable syncable : SYNCABLES) {
				try {
					CompoundTag tag = new CompoundTag();
					syncable.onLeave(player, tag);
					redis().set(syncable.getKey(player), tag.toString());
				} catch (Exception exc) {
					LOGGER.error("Failed to save {} for player {}", syncable.keyPrefix, player.getScoreboardName(), exc);
				}
			}
		});
	}

	public static RedisClient redis() {
		return _client;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

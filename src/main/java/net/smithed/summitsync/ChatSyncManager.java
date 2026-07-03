package net.smithed.summitsync;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;

public class ChatSyncManager {

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            try {
                // Get the formatted message on the sender's side
                Component formattedComponent = getStyledChatOverride(message);
                if (formattedComponent == null) {
                    // Fallback to standard vanilla chat format: <Player> message
                    formattedComponent = Component.translatable("chat.type.text", sender.getDisplayName(), message.signedContent());
                }

                // Serialize the Component to JSON using the registry-aware codec
                var jsonElement = ComponentSerialization.CODEC
                    .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, formattedComponent)
                    .getOrThrow(RuntimeException::new);

                String jsonStr = jsonElement.toString();

                JSONObject msg = new JSONObject();
                msg.put("sender", SummitSync.SERVER_ID);
                msg.put("formattedMessage", jsonStr);

                CompletableFuture.runAsync(() -> {
                    try {
                        SummitSync.redis().publish("summit-sync:chat-message", msg.toString());
                    } catch (Exception e) {
                        SummitSync.LOGGER.error("Failed to publish chat message to Redis", e);
                    }
                });
            } catch (Exception e) {
                SummitSync.LOGGER.error("Failed to create Redis chat message payload", e);
            }
        });
    }

    public static void handleIncomingChatMessage(MinecraftServer server, JSONObject msg) {
        try {
            String formattedMessageJson = msg.getString("formattedMessage");

            // Deserialize the Component from JSON using the registry-aware codec
            com.google.gson.JsonElement parsedElement = com.google.gson.JsonParser.parseString(formattedMessageJson);
            Component formattedComponent = ComponentSerialization.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, parsedElement)
                .getOrThrow(RuntimeException::new);

            // Broadcast the pre-formatted component as a system message to avoid signing issues
            server.getPlayerList().broadcastSystemMessage(formattedComponent, false);
        } catch (Exception e) {
            SummitSync.LOGGER.error("Failed to broadcast cross-server chat message", e);
        }
    }

    private static Component getStyledChatOverride(PlayerChatMessage message) {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("styledchat")) {
            try {
                java.lang.reflect.Method getArgMethod = message.getClass().getMethod("styledChat_getArg", String.class);
                Component override = (Component) getArgMethod.invoke(message, "override");
                if (override != null && override != Component.empty() && !override.getString().isEmpty()) {
                    return override;
                }
            } catch (Exception e) {
                // ignore
            }
        }

        return null;
    }
}

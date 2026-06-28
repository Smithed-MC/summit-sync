package net.smithed.summitsync;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.smithed.summitsync.syncable.Syncable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SyncCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("summit-sync-command");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, List<Syncable> syncables) {
        dispatcher.register(Commands.literal("summit-sync")
            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
            .then(Commands.argument("username", StringArgumentType.word())
                .then(Commands.literal("list")
                    .executes(context -> listUserData(context, syncables))
                    .then(Commands.argument("index", IntegerArgumentType.integer())
                        .executes(context -> listUserData(context, syncables))
                    )
                )
                .then(Commands.literal("rollback")
                    .then(Commands.argument("index", IntegerArgumentType.integer())
                        .executes(context -> rollbackUserData(context, syncables))
                    )
                )
            )
        );
    }

    private static int listUserData(CommandContext<CommandSourceStack> context, List<Syncable> syncables) {
        CommandSourceStack source = context.getSource();
        String username = StringArgumentType.getString(context, "username");

        int index = 0;
        try {
            index = IntegerArgumentType.getInteger(context, "index");
        } catch (IllegalArgumentException e) {
            // Index omitted, default to 0
        }

        var server = source.getServer();
        String uuid = null;

        var onlinePlayer = server.getPlayerList().getPlayerByName(username);
        if (onlinePlayer != null) {
            uuid = onlinePlayer.getStringUUID();
        } else {
            var resolver = server.services().profileResolver();
            if (resolver != null) {
                Optional<com.mojang.authlib.GameProfile> profileOpt = resolver.fetchByName(username);
                if (profileOpt.isPresent()) {
                    uuid = profileOpt.get().id().toString();
                }
            }
        }

        if (uuid == null) {
            source.sendFailure(Component.literal("Could not find player: " + username));
            return 0;
        }

        final String finalUuid = uuid;
        final int finalIndex = index;
        source.sendSuccess(() -> {
            if (finalIndex == 0) {
                return Component.literal("Saved data for " + username + " (" + finalUuid + "):");
            } else {
                return Component.literal("Saved data for " + username + " (" + finalUuid + ") [history offset " + finalIndex + "]:");
            }
        }, false);
        Map<String, PostgresManager.SaveEntry> latestData = PostgresManager.getLatestDataForUserWithOffset(uuid, index);
        if (latestData.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  No data found in PostgreSQL for offset " + finalIndex + "."), false);
        } else {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (Map.Entry<String, PostgresManager.SaveEntry> entry : latestData.entrySet()) {
                String key = entry.getKey();
                PostgresManager.SaveEntry saveEntry = entry.getValue();
                String data = saveEntry.data();

                String displayName = key;
                if (key.startsWith("player:") && key.endsWith(":" + finalUuid)) {
                    displayName = key.substring(7, key.length() - finalUuid.length() - 1);
                }

                String formattedDate = "unknown";
                if (saveEntry.timestamp() != null) {
                    formattedDate = saveEntry.timestamp().toLocalDateTime().format(formatter);
                }

                final String finalDisplayName = displayName;
                final String finalData = data;
                final String finalDate = formattedDate;

                int limit = 15000;
                if (finalData.length() <= limit) {
                    MutableComponent itemComponent = Component.literal(finalDisplayName)
                        .withStyle(style -> {
                            Component hoverComponent;
                            try {
                                CompoundTag tag = TagParser.parseCompoundFully(finalData);
                                hoverComponent = NbtUtils.toPrettyComponent(tag);
                            } catch (Exception e) {
                                hoverComponent = Component.literal(finalData);
                            }
                            return style
                                .withColor(ChatFormatting.GREEN)
                                .withUnderlined(true)
                                .withHoverEvent(new HoverEvent.ShowText(hoverComponent))
                                .withClickEvent(new ClickEvent.CopyToClipboard(finalData));
                        });

                    MutableComponent lineComponent = Component.literal("  - ").withStyle(ChatFormatting.GRAY)
                        .append(itemComponent)
                        .append(Component.literal(" (saved " + finalDate + ")").withStyle(ChatFormatting.GRAY));

                    source.sendSuccess(() -> lineComponent, false);
                } else {
                    MutableComponent lineComponent = Component.literal("  - " + finalDisplayName + " (saved " + finalDate + ") - Parts: ")
                        .withStyle(ChatFormatting.GRAY);

                    int len = finalData.length();
                    int chunkCount = (len + limit - 1) / limit;
                    for (int i = 0; i < chunkCount; i++) {
                        int start = i * limit;
                        int end = Math.min((i + 1) * limit, len);
                        String chunkData = finalData.substring(start, end);
                        int partNum = i + 1;

                        MutableComponent partComponent = Component.literal("[Part " + partNum + "]")
                            .withStyle(style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withUnderlined(true)
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy Part " + partNum + " of " + chunkCount + " (" + chunkData.length() + " chars)")))
                                .withClickEvent(new ClickEvent.CopyToClipboard(chunkData))
                            );

                        if (i > 0) {
                            lineComponent.append(Component.literal(" ").withStyle(ChatFormatting.GRAY));
                        }
                        lineComponent.append(partComponent);
                    }

                    source.sendSuccess(() -> lineComponent, false);
                }
            }
        }
        return 1;
    }

    private static int rollbackUserData(CommandContext<CommandSourceStack> context, List<Syncable> syncables) {
        CommandSourceStack source = context.getSource();
        String username = StringArgumentType.getString(context, "username");
        int index = IntegerArgumentType.getInteger(context, "index");

        var server = source.getServer();
        String uuid = null;
        ServerPlayer player = server.getPlayerList().getPlayerByName(username);
        if (player != null) {
            uuid = player.getStringUUID();
        } else {
            var resolver = server.services().profileResolver();
            if (resolver != null) {
                Optional<com.mojang.authlib.GameProfile> profileOpt = resolver.fetchByName(username);
                if (profileOpt.isPresent()) {
                    uuid = profileOpt.get().id().toString();
                }
            }
        }

        if (uuid == null) {
            source.sendFailure(Component.literal("Could not find player: " + username));
            return 0;
        }

        final String finalUuid = uuid;
        final int finalIndex = index;
        Map<String, PostgresManager.SaveEntry> latestData = PostgresManager.getLatestDataForUserWithOffset(uuid, index);
        if (latestData.isEmpty()) {
            source.sendFailure(Component.literal("No data found in PostgreSQL for rollback offset " + finalIndex));
            return 0;
        }

        for (Syncable syncable : syncables) {
            String dbKey = syncable.getKey(finalUuid);
            PostgresManager.SaveEntry entry = latestData.get(dbKey);
            if (entry != null) {
                String dataStr = entry.data();
                try {
                    SummitSync.redis().set(syncable.getKey(finalUuid), dataStr);
                    PostgresManager.queueSaveTask(syncable.getKey(finalUuid), finalUuid, dataStr);

                    if (player != null) {
                        CompoundTag tag = TagParser.parseCompoundFully(dataStr);
                        syncable.onJoin(player, tag);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to apply rollback for {} on category {}", username, syncable.keyPrefix, e);
                }
            }
        }

        source.sendSuccess(() -> Component.literal("Successfully rolled back data for " + username + " to offset " + finalIndex), true);
        return 1;
    }
}

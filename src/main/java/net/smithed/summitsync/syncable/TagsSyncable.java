package net.smithed.summitsync.syncable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;

public class TagsSyncable extends Syncable {
    public TagsSyncable() {
        super("tags");
    }

    @Override
    public void onJoin(ServerPlayer player, CompoundTag tag) {
        tag.getList("root").ifPresent(tagList -> {
            player.entityTags().clear();
            for (var entry : tagList) {
                entry.asString().ifPresent(player::addTag);
            }
        });

    }

    @Override
    public void onLeave(ServerPlayer player, CompoundTag tag) {
        var tagList = new ListTag();

        player.entityTags().forEach(t -> tagList.add(StringTag.valueOf(t)));

        tag.put("root", tagList);
    }
}

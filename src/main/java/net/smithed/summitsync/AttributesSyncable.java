package net.smithed.summitsync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import static net.smithed.summitsync.SummitSync.LOGGER;

public class AttributesSyncable extends Syncable {
    public AttributesSyncable() {
        super("attributes");
    }

    @Override
    public void onJoin(ServerPlayer player, CompoundTag tag) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            var input = TagValueInput.create(reporter, player.level().registryAccess(), tag);

            tag.getFloat("health").ifPresent(player::setHealth);

            input.child("food_data").ifPresent((f) -> {
                player.getFoodData().readAdditionalSaveData(f);
            });

            input.read("attributes", AttributeInstance.Packed.LIST_CODEC).ifPresent(player.getAttributes()::apply);
        }
    }

    @Override
    public void onLeave(ServerPlayer player, CompoundTag tag) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            var output = TagValueOutput.createWithContext(reporter, player.level().registryAccess());

            tag.putFloat("health", player.getHealth());

            player.getFoodData().addAdditionalSaveData(output.child("food_data"));

            output.store("attributes", AttributeInstance.Packed.LIST_CODEC, player.getAttributes().pack());

            tag.merge(output.buildResult());
        }
    }
}

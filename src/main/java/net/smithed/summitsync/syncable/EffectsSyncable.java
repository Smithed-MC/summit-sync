package net.smithed.summitsync.syncable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.smithed.summitsync.SummitSync;

import java.util.List;

public class EffectsSyncable extends Syncable {
    public EffectsSyncable() {
        super("effects");
    }

    @Override
    public void onJoin(ServerPlayer player, CompoundTag tag) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(SummitSync.LOGGER)) {
            var input = TagValueInput.create(reporter, player.level().registryAccess(), tag);

            List<MobEffectInstance> effects = input.read("root", MobEffectInstance.CODEC.listOf()).orElse(List.of());

            for (MobEffectInstance effect : player.getActiveEffects()) {
                player.connection.send(new ClientboundRemoveMobEffectPacket(player.getId(), effect.getEffect()));
            }

            player.getActiveEffects().clear();

            for (MobEffectInstance effect : effects) {
                player.getActiveEffectsMap().put(effect.getEffect(), effect);
                player.updateEffectVisibility();
                player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), effect, false));
            }
        }
    }

    @Override
    public void onLeave(ServerPlayer player, CompoundTag tag) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(SummitSync.LOGGER)) {
            var output = TagValueOutput.createWithContext(reporter, player.level().registryAccess());

            output.store("root", MobEffectInstance.CODEC.listOf(), List.copyOf(player.getActiveEffects()));

            tag.merge(output.buildResult());
        }
    }
}

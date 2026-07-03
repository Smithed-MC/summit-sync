package net.smithed.summitsync.syncable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import static net.smithed.summitsync.SummitSync.LOGGER;

public class InventorySyncable extends Syncable {
    public InventorySyncable() {
        super("inventory");
    }

    @Override
    public void onJoin(ServerPlayer player, CompoundTag tag) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            var input = TagValueInput.create(reporter, player.level().registryAccess(), tag);

            input.list("root", ItemStackWithSlot.CODEC).ifPresent(itemStackWithSlots -> player.getInventory().load(itemStackWithSlots));
            player.equipment.setAll(input.read("equipment", EntityEquipment.CODEC).orElseGet(EntityEquipment::new));

            tag.getInt("selected_slot").ifPresent(s -> {
                        player.getInventory().setSelectedSlot(s);
                        player.connection.send(new ClientboundSetHeldSlotPacket(s));
                    }
            );

        }
    }

    @Override
    public void onLeave(ServerPlayer player, CompoundTag tag) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            var output = TagValueOutput.createWithContext(reporter, player.level().registryAccess());
            player.getInventory().save(output.list("root", ItemStackWithSlot.CODEC));
            output.store("equipment", EntityEquipment.CODEC, player.equipment);
            tag.merge(output.buildResult());

            tag.putInt("selected_slot", player.getInventory().getSelectedSlot());
        }
    }
}

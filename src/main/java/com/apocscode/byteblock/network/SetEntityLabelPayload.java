package com.apocscode.byteblock.network;

import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.RobotEntity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server packet to rename (set the custom display name of) a robot or drone.
 * Also updates the JavaOS computer label so {@code os.getComputerLabel()} returns the
 * same string in Lua.
 */
public record SetEntityLabelPayload(int entityId, String label) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetEntityLabelPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "set_entity_label"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetEntityLabelPayload> STREAM_CODEC =
        StreamCodec.of(SetEntityLabelPayload::write, SetEntityLabelPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SetEntityLabelPayload p) {
        buf.writeInt(p.entityId);
        buf.writeUtf(p.label, 32);
    }

    private static SetEntityLabelPayload read(RegistryFriendlyByteBuf buf) {
        return new SetEntityLabelPayload(buf.readInt(), buf.readUtf(32));
    }

    public static void handle(SetEntityLabelPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            Entity entity = player.level().getEntity(payload.entityId);
            if (entity == null) return;
            if (player.distanceToSqr(entity) > 256) return;
            String label = payload.label;
            if (label.length() > 32) label = label.substring(0, 32);

            if (label.isBlank()) {
                entity.setCustomName(null);
                entity.setCustomNameVisible(false);
            } else {
                entity.setCustomName(Component.literal(label));
                entity.setCustomNameVisible(true);
            }

            // Mirror into JavaOS so Lua sees it.
            if (entity instanceof RobotEntity robot && robot.getOS() != null) {
                robot.getOS().setLabel(label);
            } else if (entity instanceof DroneEntity drone) {
                // Drone has no JavaOS; nothing to mirror.
                drone.setCustomNameVisible(!label.isBlank());
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

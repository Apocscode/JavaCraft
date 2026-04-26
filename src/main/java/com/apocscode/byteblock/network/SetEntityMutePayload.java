package com.apocscode.byteblock.network;

import com.apocscode.byteblock.entity.RobotEntity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server packet to toggle the muted state of a robot. When muted,
 * the robot stops emitting voice chirps and tool/handtool sounds. Persists
 * on the entity NBT under the {@code "Muted"} key.
 */
public record SetEntityMutePayload(int entityId, boolean muted) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetEntityMutePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "set_entity_mute"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetEntityMutePayload> STREAM_CODEC =
        StreamCodec.of(SetEntityMutePayload::write, SetEntityMutePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SetEntityMutePayload p) {
        buf.writeInt(p.entityId);
        buf.writeBoolean(p.muted);
    }

    private static SetEntityMutePayload read(RegistryFriendlyByteBuf buf) {
        return new SetEntityMutePayload(buf.readInt(), buf.readBoolean());
    }

    public static void handle(SetEntityMutePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            Entity entity = player.level().getEntity(payload.entityId);
            if (entity == null) return;
            if (player.distanceToSqr(entity) > 64) return; // 8 blocks
            if (entity instanceof RobotEntity robot) {
                robot.setMuted(payload.muted);
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

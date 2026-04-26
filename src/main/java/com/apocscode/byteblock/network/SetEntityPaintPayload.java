package com.apocscode.byteblock.network;

import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.EntityPaint;
import com.apocscode.byteblock.entity.RobotEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server packet asking to update the {@link EntityPaint} of a robot or
 * drone entity. Server validates ownership + distance before applying, then
 * re-broadcasts via {@link EntityPaintSyncPayload} to all tracking clients.
 */
public record SetEntityPaintPayload(int entityId, CompoundTag paint) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetEntityPaintPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "set_entity_paint"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetEntityPaintPayload> STREAM_CODEC =
        StreamCodec.of(SetEntityPaintPayload::write, SetEntityPaintPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SetEntityPaintPayload p) {
        buf.writeInt(p.entityId);
        buf.writeNbt(p.paint == null ? new CompoundTag() : p.paint);
    }

    private static SetEntityPaintPayload read(RegistryFriendlyByteBuf buf) {
        int id = buf.readInt();
        CompoundTag t = buf.readNbt();
        return new SetEntityPaintPayload(id, t == null ? new CompoundTag() : t);
    }

    public static void handle(SetEntityPaintPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            Entity ent = player.level().getEntity(p.entityId);
            if (ent == null) return;
            if (player.distanceToSqr(ent) > 64) return;
            EntityPaint paint = EntityPaint.load(p.paint);
            if (ent instanceof RobotEntity r) {
                if (r.getOwnerId() != null && !r.getOwnerId().equals(player.getUUID())) return;
                r.setPaint(paint);
            } else if (ent instanceof DroneEntity d) {
                if (d.getOwnerId() != null && !d.getOwnerId().equals(player.getUUID())) return;
                d.setPaint(paint);
            } else {
                return;
            }
            // Broadcast to all tracking clients.
            if (player.level() instanceof ServerLevel sl) {
                PacketDistributor.sendToPlayersTrackingEntity(ent,
                        new EntityPaintSyncPayload(p.entityId, p.paint));
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

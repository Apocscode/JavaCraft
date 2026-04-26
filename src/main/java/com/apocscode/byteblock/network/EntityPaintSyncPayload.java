package com.apocscode.byteblock.network;

import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.EntityPaint;
import com.apocscode.byteblock.entity.RobotEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client packet pushing an updated {@link EntityPaint} to all clients
 * tracking the given entity. Sent in response to a successful
 * {@link SetEntityPaintPayload} on the server.
 */
public record EntityPaintSyncPayload(int entityId, CompoundTag paint) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<EntityPaintSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "entity_paint_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EntityPaintSyncPayload> STREAM_CODEC =
        StreamCodec.of(EntityPaintSyncPayload::write, EntityPaintSyncPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, EntityPaintSyncPayload p) {
        buf.writeInt(p.entityId);
        buf.writeNbt(p.paint == null ? new CompoundTag() : p.paint);
    }

    private static EntityPaintSyncPayload read(RegistryFriendlyByteBuf buf) {
        int id = buf.readInt();
        CompoundTag t = buf.readNbt();
        return new EntityPaintSyncPayload(id, t == null ? new CompoundTag() : t);
    }

    public static void handle(EntityPaintSyncPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null || player.level() == null) return;
            Entity ent = player.level().getEntity(p.entityId);
            if (ent == null) return;
            EntityPaint paint = EntityPaint.load(p.paint);
            if (ent instanceof RobotEntity r) r.setPaint(paint);
            else if (ent instanceof DroneEntity d) d.setPaint(paint);
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

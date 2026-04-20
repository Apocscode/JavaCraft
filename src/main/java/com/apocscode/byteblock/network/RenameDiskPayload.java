package com.apocscode.byteblock.network;

import com.apocscode.byteblock.block.entity.DriveBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server packet to rename a disk in a drive block.
 */
public record RenameDiskPayload(BlockPos pos, String label) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RenameDiskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "rename_disk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RenameDiskPayload> STREAM_CODEC =
        StreamCodec.of(RenameDiskPayload::write, RenameDiskPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, RenameDiskPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeUtf(payload.label, 32);
    }

    private static RenameDiskPayload read(RegistryFriendlyByteBuf buf) {
        return new RenameDiskPayload(buf.readBlockPos(), buf.readUtf(32));
    }

    public static void handle(RenameDiskPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64) return;
            Level level = player.level();
            if (level.getBlockEntity(payload.pos) instanceof DriveBlockEntity drive && drive.hasDisk()) {
                String safe = payload.label.length() > 32 ? payload.label.substring(0, 32) : payload.label;
                drive.setDiskLabel(safe);
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

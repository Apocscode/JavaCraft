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
 * Client → Server packet to write a file to a disk in a drive block.
 */
public record WriteToDiskPayload(BlockPos pos, String path, String content, String label) implements CustomPacketPayload {

    private static final int MAX_PATH = 256;
    private static final int MAX_CONTENT = 32768;
    private static final int MAX_LABEL = 32;

    public static final CustomPacketPayload.Type<WriteToDiskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "write_to_disk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WriteToDiskPayload> STREAM_CODEC =
        StreamCodec.of(WriteToDiskPayload::write, WriteToDiskPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, WriteToDiskPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeUtf(payload.path, MAX_PATH);
        buf.writeUtf(payload.content, MAX_CONTENT);
        buf.writeUtf(payload.label, MAX_LABEL);
    }

    private static WriteToDiskPayload read(RegistryFriendlyByteBuf buf) {
        return new WriteToDiskPayload(
            buf.readBlockPos(),
            buf.readUtf(MAX_PATH),
            buf.readUtf(MAX_CONTENT),
            buf.readUtf(MAX_LABEL)
        );
    }

    public static void handle(WriteToDiskPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64) return;
            Level level = player.level();
            if (level.getBlockEntity(payload.pos) instanceof DriveBlockEntity drive && drive.hasDisk()) {
                drive.writeToDisk(payload.path, payload.content);
                if (payload.label != null && !payload.label.isEmpty()) {
                    drive.setDiskLabel(payload.label);
                }
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

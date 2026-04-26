package com.apocscode.byteblock.network;

import com.apocscode.byteblock.block.entity.ComputerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server packet uploading the current GPS-tool data (as JSON) to a
 * Computer block's virtual filesystem at /gps/route.json. Also broadcasts the
 * payload on Bluetooth channel 9100 so wireless listeners can pick it up.
 */
public record UploadGpsToComputerPayload(BlockPos pos, String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UploadGpsToComputerPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "upload_gps_route"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UploadGpsToComputerPayload> STREAM_CODEC =
        StreamCodec.of(UploadGpsToComputerPayload::write, UploadGpsToComputerPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, UploadGpsToComputerPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeUtf(p.json, 8192);
    }

    private static UploadGpsToComputerPayload read(RegistryFriendlyByteBuf buf) {
        return new UploadGpsToComputerPayload(buf.readBlockPos(), buf.readUtf(8192));
    }

    public static void handle(UploadGpsToComputerPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(p.pos)) > 64) return;
            Level level = player.level();
            if (level.getBlockEntity(p.pos) instanceof ComputerBlockEntity computer) {
                computer.writeGpsRoute(p.json);
                // Also broadcast wirelessly so any listener on channel 9100 sees it (B3).
                com.apocscode.byteblock.network.BluetoothNetwork.broadcast(
                        level, p.pos, 9100, "gps_tool:" + p.json);
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

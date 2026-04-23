package com.apocscode.byteblock.network;

import com.apocscode.byteblock.block.entity.RedstoneRelayBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> Server packet to configure per-face relay channel bindings.
 */
public record RelayConfigPayload(BlockPos pos, int[] channels, boolean[] bundledFaces) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RelayConfigPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "relay_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RelayConfigPayload> STREAM_CODEC =
            StreamCodec.of(RelayConfigPayload::write, RelayConfigPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, RelayConfigPayload payload) {
        buf.writeBlockPos(payload.pos);
        for (int i = 0; i < 6; i++) {
            int channel = (payload.channels != null && i < payload.channels.length) ? payload.channels[i] : 0;
            buf.writeInt(Math.max(0, Math.min(256, channel)));
        }
        for (int i = 0; i < 6; i++) {
            boolean bundled = payload.bundledFaces != null && i < payload.bundledFaces.length && payload.bundledFaces[i];
            buf.writeBoolean(bundled);
        }
    }

    private static RelayConfigPayload read(RegistryFriendlyByteBuf buf) {
        int[] channels = new int[6];
        boolean[] bundledFaces = new boolean[6];
        BlockPos pos = buf.readBlockPos();
        for (int i = 0; i < 6; i++) channels[i] = Math.max(0, Math.min(256, buf.readInt()));
        for (int i = 0; i < 6; i++) bundledFaces[i] = buf.readBoolean();
        return new RelayConfigPayload(pos, channels, bundledFaces);
    }

    public static void handle(RelayConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64) return;
            Level level = player.level();
            if (level.getBlockEntity(payload.pos) instanceof RedstoneRelayBlockEntity relay) {
                relay.setFaceConfigs(payload.channels, payload.bundledFaces);
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package com.apocscode.byteblock.network;

import com.apocscode.byteblock.block.entity.ByteChestBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server packet to rename a ByteChest via the shift-click rename UI.
 * Empty label restores the default name.
 */
public record RenameByteChestPayload(BlockPos pos, String label) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RenameByteChestPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "rename_byte_chest"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RenameByteChestPayload> STREAM_CODEC =
        StreamCodec.of(RenameByteChestPayload::write, RenameByteChestPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, RenameByteChestPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeUtf(payload.label, 32);
    }

    private static RenameByteChestPayload read(RegistryFriendlyByteBuf buf) {
        return new RenameByteChestPayload(buf.readBlockPos(), buf.readUtf(32));
    }

    public static void handle(RenameByteChestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64) return;
            Level level = player.level();
            if (level.getBlockEntity(payload.pos) instanceof ByteChestBlockEntity chest) {
                String safe = payload.label.length() > 32 ? payload.label.substring(0, 32) : payload.label;
                chest.setLabel(safe);
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

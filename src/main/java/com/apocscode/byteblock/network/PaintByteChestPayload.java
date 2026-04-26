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

/** C2S: set a ByteChest's paint tint (0xRRGGBB). */
public record PaintByteChestPayload(BlockPos pos, int tint) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PaintByteChestPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "paint_byte_chest"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PaintByteChestPayload> STREAM_CODEC =
        StreamCodec.of(PaintByteChestPayload::write, PaintByteChestPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, PaintByteChestPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeInt(p.tint);
    }

    private static PaintByteChestPayload read(RegistryFriendlyByteBuf buf) {
        return new PaintByteChestPayload(buf.readBlockPos(), buf.readInt());
    }

    public static void handle(PaintByteChestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64) return;
            Level level = player.level();
            if (level.getBlockEntity(payload.pos) instanceof ByteChestBlockEntity chest) {
                chest.setTint(payload.tint & 0xFFFFFF);
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

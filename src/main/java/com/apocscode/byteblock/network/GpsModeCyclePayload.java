package com.apocscode.byteblock.network;

import com.apocscode.byteblock.item.GpsToolItem;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: cycle the GPS tool mode (scroll while shifting).
 * Direction: +1 forward, -1 backward.
 */
public record GpsModeCyclePayload(int direction) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GpsModeCyclePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "gps_mode_cycle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GpsModeCyclePayload> STREAM_CODEC =
        StreamCodec.of(GpsModeCyclePayload::write, GpsModeCyclePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, GpsModeCyclePayload payload) {
        buf.writeVarInt(payload.direction);
    }

    private static GpsModeCyclePayload read(RegistryFriendlyByteBuf buf) {
        return new GpsModeCyclePayload(buf.readVarInt());
    }

    public static void handle(GpsModeCyclePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player == null) return;
            ItemStack stack = player.getMainHandItem();
            InteractionHand hand = InteractionHand.MAIN_HAND;
            if (!(stack.getItem() instanceof GpsToolItem)) {
                stack = player.getOffhandItem();
                hand = InteractionHand.OFF_HAND;
                if (!(stack.getItem() instanceof GpsToolItem)) return;
            }
            GpsToolItem.Mode next = GpsToolItem.cycleMode(stack, payload.direction);
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("GPS mode: " + next.display)
                    .withStyle(net.minecraft.ChatFormatting.AQUA),
                true);
            // Force re-sync of the held item (setItemInHand triggers inventory update)
            player.setItemInHand(hand, stack);
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

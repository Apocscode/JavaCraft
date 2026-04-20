package com.apocscode.byteblock.network;

import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server packet to configure a button panel's mode, duration, or channel.
 * buttonIndex = 0-15 for per-button config, or -1 for channel-only update.
 */
public record ButtonConfigPayload(BlockPos pos, int buttonIndex, int modeOrdinal,
                                  int duration, int channel) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ButtonConfigPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "button_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ButtonConfigPayload> STREAM_CODEC =
        StreamCodec.of(ButtonConfigPayload::write, ButtonConfigPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, ButtonConfigPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeInt(payload.buttonIndex);
        buf.writeInt(payload.modeOrdinal);
        buf.writeInt(payload.duration);
        buf.writeInt(payload.channel);
    }

    private static ButtonConfigPayload read(RegistryFriendlyByteBuf buf) {
        return new ButtonConfigPayload(
            buf.readBlockPos(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt()
        );
    }

    public static void handle(ButtonConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64) return;
            Level level = player.level();
            if (level.getBlockEntity(payload.pos) instanceof ButtonPanelBlockEntity panel) {
                // Update channel
                if (payload.channel >= 1 && payload.channel <= 256) {
                    panel.setChannel(payload.channel);
                }
                // Update per-button config
                if (payload.buttonIndex >= 0 && payload.buttonIndex < 16) {
                    panel.setMode(payload.buttonIndex,
                            ButtonPanelBlockEntity.ButtonMode.fromOrdinal(payload.modeOrdinal));
                    panel.setDuration(payload.buttonIndex, payload.duration);
                }
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

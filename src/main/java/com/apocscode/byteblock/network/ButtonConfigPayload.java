package com.apocscode.byteblock.network;

import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity;
import com.apocscode.byteblock.block.entity.IButtonPanel;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> Server packet to configure a button panel.
 *
 * buttonIndex:
 *   -1 = panel-level update only (applies channel + panelLabel).
 *   0-15 = per-button update (applies mode, duration, buttonLabel, buttonColor) plus channel/panelLabel.
 *
 * Sentinels:
 *   panelLabel/buttonLabel == "\0NOP\0" means "do not modify".
 *   buttonColor == Integer.MIN_VALUE means "do not modify"; -1 means "clear override".
 */
public record ButtonConfigPayload(BlockPos pos,
                                  int buttonIndex,
                                  int modeOrdinal,
                                  int duration,
                                  int channel,
                                  String panelLabel,
                                  String buttonLabel,
                                  int buttonColor) implements CustomPacketPayload {

    public static final String NOP = "\0NOP\0";
    public static final int COLOR_NOP = Integer.MIN_VALUE;

    public static final CustomPacketPayload.Type<ButtonConfigPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "button_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ButtonConfigPayload> STREAM_CODEC =
        StreamCodec.of(ButtonConfigPayload::write, ButtonConfigPayload::read);

    /** Legacy-compatible constructor (mode+duration+channel only; no label/color change). */
    public ButtonConfigPayload(BlockPos pos, int buttonIndex, int modeOrdinal, int duration, int channel) {
        this(pos, buttonIndex, modeOrdinal, duration, channel, NOP, NOP, COLOR_NOP);
    }

    private static void write(RegistryFriendlyByteBuf buf, ButtonConfigPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeInt(p.buttonIndex);
        buf.writeInt(p.modeOrdinal);
        buf.writeInt(p.duration);
        buf.writeInt(p.channel);
        buf.writeUtf(p.panelLabel == null ? "" : p.panelLabel, 64);
        buf.writeUtf(p.buttonLabel == null ? "" : p.buttonLabel, 64);
        buf.writeInt(p.buttonColor);
    }

    private static ButtonConfigPayload read(RegistryFriendlyByteBuf buf) {
        return new ButtonConfigPayload(
            buf.readBlockPos(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readUtf(64),
            buf.readUtf(64),
            buf.readInt()
        );
    }

    public static void handle(ButtonConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64) return;
            Level level = player.level();
            if (level.getBlockEntity(payload.pos) instanceof IButtonPanel panel) {
                // Channel
                if (payload.channel >= 1 && payload.channel <= 256) {
                    panel.setChannel(payload.channel);
                }
                // Panel-level label
                if (payload.panelLabel != null && !NOP.equals(payload.panelLabel)) {
                    panel.setLabel(payload.panelLabel);
                }
                // Per-button fields
                if (payload.buttonIndex >= 0 && payload.buttonIndex < 16) {
                    panel.setMode(payload.buttonIndex,
                            ButtonPanelBlockEntity.ButtonMode.fromOrdinal(payload.modeOrdinal));
                    panel.setDuration(payload.buttonIndex, payload.duration);
                    if (payload.buttonLabel != null && !NOP.equals(payload.buttonLabel)) {
                        panel.setButtonLabel(payload.buttonIndex, payload.buttonLabel);
                    }
                    if (payload.buttonColor != COLOR_NOP) {
                        panel.setButtonColor(payload.buttonIndex, payload.buttonColor);
                    }
                }
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

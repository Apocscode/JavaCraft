package com.apocscode.byteblock.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: push a HUD widget list to a player's Smart Glasses.
 *
 * Payload layout is a CompoundTag with a "W" ListTag of widget entries.
 * Each widget entry is a CompoundTag with these optional keys:
 *   t     — type: "text" | "bar" | "light" | "gauge" | "spark" | "alert" | "title"
 *   id    — widget id (string, used for updates)
 *   label — display label (string)
 *   v     — primary value (string representation; numbers pre-formatted)
 *   num   — numeric value (double)  [for bar / gauge / spark]
 *   min   — numeric min (double)    [for bar / gauge]
 *   max   — numeric max (double)    [for bar / gauge]
 *   color — RGB int tint            [for light / bar fill / title]
 *   spark — ListTag of double samples [for spark]
 *
 * A clear-all is sent as an empty "W" list.
 */
public record GlassesHudPayload(CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GlassesHudPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "glasses_hud"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GlassesHudPayload> STREAM_CODEC =
        StreamCodec.of(GlassesHudPayload::write, GlassesHudPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, GlassesHudPayload payload) {
        buf.writeNbt(payload.data);
    }

    private static GlassesHudPayload read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        return new GlassesHudPayload(tag == null ? new CompoundTag() : tag);
    }

    public static void handle(GlassesHudPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.apocscode.byteblock.client.GlassesHudState.onReceive(payload.data);
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

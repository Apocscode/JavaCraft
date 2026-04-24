package com.apocscode.byteblock.network;

import com.apocscode.byteblock.computer.GlassesHudAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → Server: forwards a glasses.flush() call originating from a
 * client-side Lua runtime. Carries the computer's position, channel and the
 * serialized widget list. The server validates the player is near the
 * computer, then dispatches via GlassesHudAPI.push to all glasses-wearing
 * players in range on the matching channel.
 */
public record GlassesPushRequestPayload(BlockPos pos, int channel, CompoundTag data)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GlassesPushRequestPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "glasses_push_req"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GlassesPushRequestPayload> STREAM_CODEC =
        StreamCodec.of(GlassesPushRequestPayload::write, GlassesPushRequestPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, GlassesPushRequestPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeVarInt(p.channel);
        buf.writeNbt(p.data);
    }

    private static GlassesPushRequestPayload read(RegistryFriendlyByteBuf buf) {
        return new GlassesPushRequestPayload(buf.readBlockPos(), buf.readVarInt(), buf.readNbt());
    }

    public static void handle(GlassesPushRequestPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) return;
            if (player.distanceToSqr(Vec3.atCenterOf(p.pos)) > 256) return; // 16-block sanity gate
            List<GlassesHudAPI.Widget> widgets = deserialize(p.data);
            GlassesHudAPI.push(player.level(), p.pos, p.channel, widgets);
        });
    }

    private static List<GlassesHudAPI.Widget> deserialize(CompoundTag data) {
        List<GlassesHudAPI.Widget> out = new ArrayList<>();
        if (data == null || !data.contains("W", Tag.TAG_LIST)) return out;
        ListTag list = data.getList("W", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            GlassesHudAPI.Widget w = new GlassesHudAPI.Widget(
                c.getString("t").isEmpty() ? "text" : c.getString("t"),
                c.getString("id"));
            w.label = c.getString("label");
            w.value = c.getString("v");
            w.num = c.getDouble("num");
            w.min = c.getDouble("min");
            w.max = c.getDouble("max");
            w.color = c.getInt("color");
            if (c.contains("spark", Tag.TAG_LIST)) {
                ListTag sl = c.getList("spark", Tag.TAG_DOUBLE);
                double[] arr = new double[sl.size()];
                for (int j = 0; j < sl.size(); j++) arr[j] = ((DoubleTag) sl.get(j)).getAsDouble();
                w.spark = arr;
            }
            if (c.contains("bg")) w.bgColor = c.getInt("bg");
            if (c.contains("num2")) w.num2 = c.getDouble("num2");
            if (c.contains("exp")) w.expireMs = c.getLong("exp");
            if (c.contains("h")) w.height = c.getInt("h");
            if (c.contains("pts", Tag.TAG_LIST)) {
                ListTag pl = c.getList("pts", Tag.TAG_DOUBLE);
                double[] arr = new double[pl.size()];
                for (int j = 0; j < pl.size(); j++) arr[j] = ((DoubleTag) pl.get(j)).getAsDouble();
                w.points = arr;
            }
            out.add(w);
        }
        return out;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

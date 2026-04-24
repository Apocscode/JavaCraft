package com.apocscode.byteblock.computer;

import com.apocscode.byteblock.network.BluetoothNetwork;
import com.apocscode.byteblock.init.ModItems;
import com.apocscode.byteblock.item.GlassesItem;
import com.apocscode.byteblock.network.GlassesHudPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side dispatcher that pushes widget lists to Smart Glasses worn by
 * players near a computer on a matching BT channel. Invoked from the
 * glasses.* Lua API.
 */
public final class GlassesHudAPI {

    public static final class Widget {
        public String type = "text";
        public String id = "";
        public String label = "";
        public String value = "";
        public double num = 0.0;
        public double min = 0.0;
        public double max = 1.0;
        public int color = 0xFFFFFF;
        public double[] spark = null;
        // Tier 2 optional fields
        public int bgColor = 0;
        public double num2 = 0.0;
        public long expireMs = 0L;
        public int height = 0;
        public double[] points = null;

        public Widget(String type, String id) { this.type = type; this.id = id; }

        public CompoundTag toNbt() {
            CompoundTag c = new CompoundTag();
            c.putString("t", type);
            if (!id.isEmpty())    c.putString("id", id);
            if (!label.isEmpty()) c.putString("label", label);
            if (!value.isEmpty()) c.putString("v", value);
            c.putDouble("num", num);
            c.putDouble("min", min);
            c.putDouble("max", max);
            c.putInt("color", color & 0xFFFFFF);
            if (spark != null && spark.length > 0) {
                ListTag sl = new ListTag();
                for (double v : spark) sl.add(DoubleTag.valueOf(v));
                c.put("spark", sl);
            }
            if (bgColor != 0)      c.putInt("bg", bgColor & 0xFFFFFF);
            if (num2 != 0.0)       c.putDouble("num2", num2);
            if (expireMs > 0)      c.putLong("exp", expireMs);
            if (height > 0)        c.putInt("h", height);
            if (points != null && points.length > 0) {
                ListTag pl = new ListTag();
                for (double v : points) pl.add(DoubleTag.valueOf(v));
                c.put("pts", pl);
            }
            return c;
        }
    }

    private GlassesHudAPI() {}

    /** Push a list of widgets to all glasses-wearing players within BT range and on the matching channel. */
    public static int push(Level level, BlockPos pos, int channel, List<Widget> widgets) {
        if (!(level instanceof ServerLevel sl) || pos == null) return 0;
        CompoundTag data = new CompoundTag();
        ListTag list = new ListTag();
        if (widgets != null) for (Widget w : widgets) list.add(w.toNbt());
        data.put("W", list);

        int rangeSq = BluetoothNetwork.BLOCK_RANGE * BluetoothNetwork.BLOCK_RANGE;
        int sent = 0;
        int playerCount = 0, noGlasses = 0, wrongCh = 0, outOfRange = 0;
        for (ServerPlayer p : sl.players()) {
            playerCount++;
            ItemStack head = p.getItemBySlot(EquipmentSlot.HEAD);
            if (head.isEmpty() || head.getItem() != ModItems.GLASSES.get()) { noGlasses++; continue; }
            int wearerCh = GlassesItem.getChannel(head);
            if (wearerCh != channel) {
                wrongCh++;
                org.slf4j.LoggerFactory.getLogger("ByteBlock/Glasses").info(
                    "push: player {} wearing glasses on ch {}, computer broadcasting on ch {}",
                    p.getName().getString(), wearerCh, channel);
                continue;
            }
            double d2 = p.blockPosition().distSqr(pos);
            if (d2 > rangeSq) {
                outOfRange++;
                org.slf4j.LoggerFactory.getLogger("ByteBlock/Glasses").info(
                    "push: player {} out of range (dist^2={}, max={})", p.getName().getString(), d2, rangeSq);
                continue;
            }
            PacketDistributor.sendToPlayer(p, new GlassesHudPayload(data));
            sent++;
        }
        if (sent == 0 && playerCount > 0) {
            org.slf4j.LoggerFactory.getLogger("ByteBlock/Glasses").info(
                "push: 0 wearers matched (players={}, noGlasses={}, wrongCh={}, outOfRange={}, ch={}, pos={})",
                playerCount, noGlasses, wrongCh, outOfRange, channel, pos);
        }
        return sent;
    }

    /** Convenience: clear all widgets on matching wearers. */
    public static int clear(Level level, BlockPos pos, int channel) {
        return push(level, pos, channel, new ArrayList<>());
    }
}

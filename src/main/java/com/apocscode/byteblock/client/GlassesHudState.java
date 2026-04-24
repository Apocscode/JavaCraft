package com.apocscode.byteblock.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side cache of the last HUD widget list pushed by a computer.
 * Also tracks whether the overlay is toggled on via the H key.
 */
public final class GlassesHudState {

    public static final class Widget {
        public String type = "text";
        public String id = "";
        public String label = "";
        public String value = "";
        public double num = 0.0;
        public double min = 0.0;
        public double max = 1.0;
        public int color = 0xFFFFFF;
        public double[] spark = new double[0];
    }

    private static final List<Widget> widgets = new ArrayList<>();
    private static volatile boolean visible = true;
    private static volatile long lastReceiveMs = 0L;

    private GlassesHudState() {}

    public static List<Widget> getWidgets() {
        synchronized (widgets) { return new ArrayList<>(widgets); }
    }

    public static boolean isVisible() { return visible; }

    public static void toggleVisible() { visible = !visible; }

    public static long getAgeMs() {
        long t = lastReceiveMs;
        return t == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }

    public static void clear() {
        synchronized (widgets) { widgets.clear(); }
    }

    public static void onReceive(CompoundTag data) {
        List<Widget> next = new ArrayList<>();
        if (data != null && data.contains("W", Tag.TAG_LIST)) {
            ListTag list = data.getList("W", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                Widget w = new Widget();
                w.type  = c.contains("t") ? c.getString("t") : "text";
                w.id    = c.contains("id") ? c.getString("id") : "";
                w.label = c.contains("label") ? c.getString("label") : "";
                w.value = c.contains("v") ? c.getString("v") : "";
                w.num   = c.contains("num") ? c.getDouble("num") : 0.0;
                w.min   = c.contains("min") ? c.getDouble("min") : 0.0;
                w.max   = c.contains("max") ? c.getDouble("max") : 1.0;
                w.color = c.contains("color") ? c.getInt("color") : 0xFFFFFF;
                if (c.contains("spark", Tag.TAG_LIST)) {
                    ListTag sl = c.getList("spark", Tag.TAG_DOUBLE);
                    double[] arr = new double[sl.size()];
                    for (int j = 0; j < sl.size(); j++) arr[j] = sl.getDouble(j);
                    w.spark = arr;
                }
                next.add(w);
            }
        }
        synchronized (widgets) {
            widgets.clear();
            widgets.addAll(next);
        }
        lastReceiveMs = System.currentTimeMillis();
    }
}

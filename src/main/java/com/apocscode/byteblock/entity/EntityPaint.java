package com.apocscode.byteblock.entity;

import net.minecraft.nbt.CompoundTag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-entity paint customization — a small named-slot color map serialized as NBT.
 * Each slot is a packed ARGB int (alpha kept for completeness; renderer typically
 * ignores it). Renderers read {@link #get(String, int)} with their hard-coded
 * default colors as the fallback so unset slots keep the factory look.
 *
 * <p>Slot keys are renderer-defined strings (e.g. "body", "trim", "arms"). Use the
 * static SLOTS_* constants for a recommended set, but any string is allowed.
 */
public final class EntityPaint {
    /** Recommended slot keys for the robot. */
    public static final String[] SLOTS_ROBOT = {
            "body", "trim", "arms", "head", "eye", "antenna", "tracks"
    };
    /** Recommended slot keys for the drone. */
    public static final String[] SLOTS_DRONE = {
            "body", "trim", "arms", "blades", "underglow"
    };

    private final Map<String, Integer> slots = new LinkedHashMap<>();
    /** Face preset key, e.g. "classic", "happy", "custom". Default = "classic". */
    private String faceId = "classic";
    /** Custom 8x8 face bitmap (1 bit per pixel, row-major LSB = pixel (0,0)). Used when faceId == "custom". */
    private long faceBits = 0L;

    public EntityPaint() {}

    /** Returns the painted color for a slot, or {@code defaultRgb} if not set. */
    public int get(String slot, int defaultRgb) {
        Integer v = slots.get(slot);
        return v == null ? defaultRgb : v;
    }

    /** Set a slot's color to a packed 0xRRGGBB int. Pass null/0 to clear. */
    public void set(String slot, Integer rgb) {
        if (rgb == null) slots.remove(slot);
        else slots.put(slot, rgb & 0xFFFFFF);
    }

    public String getFaceId() { return faceId; }
    public void setFaceId(String id) { this.faceId = id == null || id.isEmpty() ? "classic" : id; }
    public long getFaceBits() { return faceBits; }
    public void setFaceBits(long bits) { this.faceBits = bits; }

    public boolean isEmpty() { return slots.isEmpty() && "classic".equals(faceId) && faceBits == 0L; }
    public Map<String, Integer> view() { return java.util.Collections.unmodifiableMap(slots); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        for (var e : slots.entrySet()) tag.putInt(e.getKey(), e.getValue());
        if (faceId != null && !"classic".equals(faceId)) tag.putString("$face", faceId);
        if (faceBits != 0L) tag.putLong("$faceBits", faceBits);
        return tag;
    }

    public static EntityPaint load(CompoundTag tag) {
        EntityPaint p = new EntityPaint();
        if (tag == null) return p;
        for (String key : tag.getAllKeys()) {
            if ("$face".equals(key)) p.faceId = tag.getString(key);
            else if ("$faceBits".equals(key)) p.faceBits = tag.getLong(key);
            else p.slots.put(key, tag.getInt(key));
        }
        return p;
    }

    public EntityPaint copy() {
        EntityPaint p = new EntityPaint();
        p.slots.putAll(this.slots);
        p.faceId = this.faceId;
        p.faceBits = this.faceBits;
        return p;
    }

    /**
     * Helper for renderers: take a stored RGB triplet and substitute the painted
     * color, preserving the relative brightness of the original component (so
     * shaded sub-parts keep visual depth even with a flat paint).
     */
    public static int r(int rgb) { return (rgb >> 16) & 0xFF; }
    public static int g(int rgb) { return (rgb >> 8) & 0xFF; }
    public static int b(int rgb) { return rgb & 0xFF; }
    public static int rgb(int r, int g, int b) {
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}

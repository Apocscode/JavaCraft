package com.apocscode.byteblock.computer;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable right-click context menu rendered on PixelBuffer.
 * Show with show(), check clicks with handleClick(), render with render().
 */
public class ContextMenu {

    public record Item(String label, String action, boolean separator, boolean disabled) {
        public Item(String label, String action) { this(label, action, false, false); }
        public static Item sep() { return new Item("", "", true, false); }
        public static Item disabled(String label) { return new Item(label, "", false, true); }
    }

    private final List<Item> items = new ArrayList<>();
    private int x, y;
    private int menuWidth, menuHeight;
    private int hoverIndex = -1;
    private boolean visible;

    private static final int BG        = 0xFFF0F0F0;
    private static final int HOVER_BG  = 0xFF3399FF;
    private static final int BORDER    = 0xFF999999;
    private static final int TEXT_CLR  = 0xFF222222;
    private static final int TEXT_HOV  = 0xFFFFFFFF;
    private static final int TEXT_DIS  = 0xFF999999;
    private static final int SEP_CLR   = 0xFFCCCCCC;
    private static final int SHADOW    = 0xFF555555;

    private static final int ITEM_H    = 18;
    private static final int SEP_H     = 6;
    private static final int PAD_X     = 8;
    private static final int MIN_W     = 120;

    public void show(int px, int py, List<Item> newItems) {
        items.clear();
        items.addAll(newItems);

        int maxTextW = 0;
        for (Item item : items) {
            if (!item.separator()) {
                maxTextW = Math.max(maxTextW, item.label().length() * BitmapFont.CHAR_W);
            }
        }
        menuWidth = Math.max(MIN_W, maxTextW + PAD_X * 2);

        int h = 4; // top/bottom padding
        for (Item item : items) h += item.separator() ? SEP_H : ITEM_H;
        menuHeight = h;

        x = Math.max(0, Math.min(px, PixelBuffer.SCREEN_W - menuWidth));
        y = Math.max(0, Math.min(py, PixelBuffer.SCREEN_H - menuHeight));

        hoverIndex = -1;
        visible = true;
    }

    public void hide() { visible = false; items.clear(); }
    public boolean isVisible() { return visible; }

    /** Update hover state. Returns true if mouse is inside menu bounds. */
    public boolean handleMove(int px, int py) {
        if (!visible) return false;
        hoverIndex = hitTest(px, py);
        return px >= x && px < x + menuWidth && py >= y && py < y + menuHeight;
    }

    /** Handle a click. Returns the action string if a valid item was clicked, else null. */
    public String handleClick(int px, int py) {
        if (!visible) return null;
        int idx = hitTest(px, py);
        if (idx >= 0 && idx < items.size()) {
            Item item = items.get(idx);
            if (!item.separator() && !item.disabled()) {
                String action = item.action();
                hide();
                return action;
            }
        }
        if (px < x || px >= x + menuWidth || py < y || py >= y + menuHeight) {
            hide();
        }
        return null;
    }

    public void render(PixelBuffer pb) {
        if (!visible) return;
        // Shadow
        pb.fillRect(x + 2, y + 2, menuWidth, menuHeight, SHADOW);
        // Background + border
        pb.fillRect(x, y, menuWidth, menuHeight, BG);
        pb.drawRect(x, y, menuWidth, menuHeight, BORDER);

        int itemY = y + 2;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.separator()) {
                pb.drawHLine(x + 4, x + menuWidth - 5, itemY + SEP_H / 2, SEP_CLR);
                itemY += SEP_H;
            } else {
                if (i == hoverIndex && !item.disabled()) {
                    pb.fillRect(x + 1, itemY, menuWidth - 2, ITEM_H, HOVER_BG);
                }
                int tc = item.disabled() ? TEXT_DIS : (i == hoverIndex ? TEXT_HOV : TEXT_CLR);
                pb.drawString(x + PAD_X, itemY + 2, item.label(), tc);
                itemY += ITEM_H;
            }
        }
    }

    private int hitTest(int px, int py) {
        if (px < x || px >= x + menuWidth || py < y || py >= y + menuHeight) return -1;
        int itemY = y + 2;
        for (int i = 0; i < items.size(); i++) {
            int h = items.get(i).separator() ? SEP_H : ITEM_H;
            if (py >= itemY && py < itemY + h) return i;
            itemY += h;
        }
        return -1;
    }
}

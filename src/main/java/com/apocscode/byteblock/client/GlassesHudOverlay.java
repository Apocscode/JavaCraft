package com.apocscode.byteblock.client;

import com.apocscode.byteblock.ByteBlock;
import com.apocscode.byteblock.init.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.List;

/**
 * Top-left translucent overlay rendered whenever the player is wearing
 * Smart Glasses and the HUD has been pushed widgets from a paired computer.
 */
@EventBusSubscriber(modid = ByteBlock.MODID, value = Dist.CLIENT)
public final class GlassesHudOverlay {

    private static final int PANEL_X = 4;
    private static final int PANEL_Y = 4;
    private static final int PANEL_W = 150;
    private static final int ROW_H   = 12;
    private static final int PAD     = 4;
    private static final int BG_COL  = 0xB0000814;   // translucent dark blue
    private static final int ACC_COL = 0xFF2AA7FF;   // cyan accent
    private static final long STALE_MS = 10_000L;

    private GlassesHudOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        if (mc.player == null) return;
        if (!GlassesHudState.isVisible()) return;

        ItemStack head = mc.player.getItemBySlot(EquipmentSlot.HEAD);
        if (head.isEmpty() || head.getItem() != ModItems.GLASSES.get()) return;

        List<GlassesHudState.Widget> widgets = GlassesHudState.getWidgets();
        if (widgets.isEmpty()) return;

        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;

        int rows = widgets.size();
        // Compute total height: each widget contributes its own height, or ROW_H by default.
        int totalRows = 0;
        for (GlassesHudState.Widget w : widgets) {
            totalRows += widgetRowHeight(w);
        }
        int panelH = PAD * 2 + totalRows + 12; // header row
        gg.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_W, PANEL_Y + panelH, BG_COL);
        gg.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_W, PANEL_Y + 1, ACC_COL);
        gg.fill(PANEL_X, PANEL_Y + panelH - 1, PANEL_X + PANEL_W, PANEL_Y + panelH, ACC_COL);

        String header = "BYTEBLOCK HUD";
        if (GlassesHudState.getAgeMs() > STALE_MS) {
            header += " (stale)";
        }
        gg.drawString(font, Component.literal(header).withStyle(ChatFormatting.AQUA),
            PANEL_X + PAD, PANEL_Y + PAD, 0xFFFFFFFF, false);

        int y = PANEL_Y + PAD + 10;
        long now = System.currentTimeMillis();
        for (GlassesHudState.Widget w : widgets) {
            if (w.expireMs > 0 && now > w.expireMs) continue;
            int h = widgetRowHeight(w);
            renderWidget(gg, font, PANEL_X + PAD, y, PANEL_W - PAD * 2, h, w);
            y += h;
        }
    }

    private static int widgetRowHeight(GlassesHudState.Widget w) {
        if (w.height > 0) return w.height;
        return ROW_H;
    }

    private static void renderWidget(GuiGraphics gg, Font font, int x, int y, int w, int h, GlassesHudState.Widget widget) {
        switch (widget.type) {
            case "bar", "gauge" -> {
                String lbl = widget.label.isEmpty() ? widget.id : widget.label;
                gg.drawString(font, lbl, x, y, 0xFFFFFFFF, false);
                int barX = x + Math.min(w / 2, font.width(lbl) + 6);
                int barW = x + w - barX - 1;
                int barY = y + 1;
                int barH = 8;
                if (barW > 8) {
                    gg.fill(barX, barY, barX + barW, barY + barH, 0xFF222222);
                    double range = widget.max - widget.min;
                    double pct = range <= 0 ? 0 : (widget.num - widget.min) / range;
                    if (pct < 0) pct = 0; else if (pct > 1) pct = 1;
                    int fillW = (int) Math.round(barW * pct);
                    if (fillW > 0) {
                        gg.fill(barX, barY, barX + fillW, barY + barH, 0xFF000000 | (widget.color & 0xFFFFFF));
                    }
                }
            }
            case "light" -> {
                int dot = 0xFF000000 | (widget.color & 0xFFFFFF);
                gg.fill(x, y + 2, x + 7, y + 9, dot);
                String lbl = widget.label.isEmpty() ? widget.id : widget.label;
                gg.drawString(font, lbl, x + 10, y, 0xFFFFFFFF, false);
                if (!widget.value.isEmpty()) {
                    gg.drawString(font, widget.value,
                        x + w - font.width(widget.value), y, 0xFFCCCCCC, false);
                }
            }
            case "alert" -> {
                int col = 0xFF000000 | (widget.color & 0xFFFFFF);
                String lbl = (widget.label.isEmpty() ? widget.id : widget.label);
                if (!widget.value.isEmpty()) lbl = lbl + ": " + widget.value;
                gg.fill(x, y, x + w, y + ROW_H - 2, 0x66000000 | (widget.color & 0xFFFFFF));
                gg.drawString(font, lbl, x + 2, y + 1, col, false);
            }
            case "title" -> {
                int col = 0xFF000000 | (widget.color & 0xFFFFFF);
                gg.drawString(font, widget.label, x, y, col, false);
            }
            case "spark" -> {
                String lbl = widget.label.isEmpty() ? widget.id : widget.label;
                gg.drawString(font, lbl, x, y, 0xFFFFFFFF, false);
                int sx = x + Math.min(w / 2, font.width(lbl) + 6);
                int sw = x + w - sx;
                int sh = 8;
                gg.fill(sx, y + 1, sx + sw, y + 1 + sh, 0xFF111111);
                double[] s = widget.spark;
                if (s.length >= 2 && sw > 2) {
                    double min = s[0], max = s[0];
                    for (double v : s) { if (v < min) min = v; if (v > max) max = v; }
                    double range = max - min; if (range < 1e-9) range = 1;
                    int col = 0xFF000000 | (widget.color & 0xFFFFFF);
                    for (int i = 0; i < s.length; i++) {
                        int px = sx + (int) Math.round((double) i * (sw - 1) / (s.length - 1));
                        double pct = (s[i] - min) / range;
                        int py = y + 1 + (sh - 1) - (int) Math.round(pct * (sh - 1));
                        gg.fill(px, py, px + 1, py + 1, col);
                    }
                }
            }
            case "pie" -> {
                int size = Math.min(h - 2, 22);
                int cx = x + size / 2 + 2;
                int cy = y + size / 2 + 1;
                int r  = size / 2;
                int col = 0xFF000000 | (widget.color & 0xFFFFFF);
                int bg  = 0xFF222222;
                double pct = Math.max(0, Math.min(1, widget.num));
                double endAngle = pct * Math.PI * 2.0;
                // Naive filled disc with angle threshold.
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        double d = dx * dx + dy * dy;
                        if (d > r * r) continue;
                        double ang = Math.atan2(dx, -dy); // 0 = up, clockwise
                        if (ang < 0) ang += Math.PI * 2.0;
                        int c = ang <= endAngle ? col : bg;
                        gg.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, c);
                    }
                }
                String lbl = widget.label.isEmpty() ? String.format("%d%%", (int)Math.round(pct * 100)) : widget.label;
                gg.drawString(font, lbl, cx + r + 4, y + (size - 8) / 2, 0xFFFFFFFF, false);
                if (!widget.value.isEmpty()) {
                    gg.drawString(font, widget.value, cx + r + 4, y + (size - 8) / 2 + 10, 0xFFCCCCCC, false);
                }
            }
            case "compass" -> {
                int size = Math.min(h - 2, 22);
                int cx = x + size / 2 + 2;
                int cy = y + size / 2 + 1;
                int r  = size / 2;
                int col = 0xFF000000 | (widget.color & 0xFFFFFF);
                // Circle outline
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        double d = dx * dx + dy * dy;
                        if (d >= (r - 1) * (r - 1) && d <= r * r) {
                            gg.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, 0xFF444444);
                        }
                    }
                }
                // North marker
                gg.fill(cx - 1, cy - r, cx + 1, cy - r + 2, 0xFFFFFFFF);
                // Needle
                double rad = Math.toRadians(widget.num);
                int nx = (int) Math.round(Math.sin(rad) * (r - 2));
                int ny = -(int) Math.round(Math.cos(rad) * (r - 2));
                drawLine(gg, cx, cy, cx + nx, cy + ny, col);
                String lbl = String.format("%.0f\u00b0", widget.num);
                gg.drawString(font, lbl, cx + r + 4, y + 2, 0xFFFFFFFF, false);
                if (!widget.label.isEmpty()) {
                    gg.drawString(font, widget.label, cx + r + 4, y + 12, 0xFFCCCCCC, false);
                }
            }
            case "timer" -> {
                double sec = Math.max(0, widget.num);
                int mm = (int) (sec / 60);
                int ss = (int) (sec % 60);
                String t = String.format("%02d:%02d", mm, ss);
                int col = 0xFF000000 | (widget.color & 0xFFFFFF);
                String lbl = widget.label.isEmpty() ? widget.id : widget.label;
                gg.drawString(font, lbl, x, y, 0xFFFFFFFF, false);
                gg.drawString(font, t, x + w - font.width(t), y, col, false);
            }
            case "minimap" -> {
                int size = Math.max(40, h - 4);
                gg.fill(x, y, x + size, y + size, 0xFF0A0A16);
                gg.fill(x, y, x + size, y + 1, 0xFF2AA7FF);
                gg.fill(x, y + size - 1, x + size, y + size, 0xFF2AA7FF);
                gg.fill(x, y, x + 1, y + size, 0xFF2AA7FF);
                gg.fill(x + size - 1, y, x + size, y + size, 0xFF2AA7FF);
                double cx = widget.num;
                double cz = widget.num2;
                double scale = widget.max > 0 ? widget.max : 64;
                int mx = x + size / 2;
                int my = y + size / 2;
                // Center marker (you-are-here)
                int selfCol = 0xFF000000 | (widget.color & 0xFFFFFF);
                gg.fill(mx - 1, my - 1, mx + 2, my + 2, selfCol);
                // Points: triples (x, z, color)
                double[] pts = widget.points;
                for (int i = 0; i + 2 < pts.length; i += 3) {
                    double dx = pts[i] - cx;
                    double dz = pts[i + 1] - cz;
                    int px = mx + (int) Math.round(dx / scale * (size / 2 - 2));
                    int py = my + (int) Math.round(dz / scale * (size / 2 - 2));
                    if (px < x + 1 || px >= x + size - 1 || py < y + 1 || py >= y + size - 1) continue;
                    int c = 0xFF000000 | ((int) pts[i + 2] & 0xFFFFFF);
                    gg.fill(px, py, px + 2, py + 2, c);
                }
                // Label next to minimap
                if (!widget.label.isEmpty()) {
                    gg.drawString(font, widget.label, x + size + 4, y + 2, 0xFFFFFFFF, false);
                }
            }
            case "graph" -> {
                String lbl = widget.label.isEmpty() ? widget.id : widget.label;
                gg.drawString(font, lbl, x, y, 0xFFFFFFFF, false);
                int gy = y + 10;
                int gh = h - 12;
                gg.fill(x, gy, x + w, gy + gh, 0xFF0A0A16);
                double[] s = widget.spark;
                if (s.length >= 2 && w > 2 && gh > 2) {
                    double min = s[0], max = s[0];
                    for (double v : s) { if (v < min) min = v; if (v > max) max = v; }
                    double range = max - min; if (range < 1e-9) range = 1;
                    int col = 0xFF000000 | (widget.color & 0xFFFFFF);
                    int prevX = -1, prevY = -1;
                    for (int i = 0; i < s.length; i++) {
                        int px = x + (int) Math.round((double) i * (w - 1) / (s.length - 1));
                        double pct = (s[i] - min) / range;
                        int py = gy + (gh - 1) - (int) Math.round(pct * (gh - 1));
                        if (prevX >= 0) drawLine(gg, prevX, prevY, px, py, col);
                        prevX = px; prevY = py;
                    }
                    // Min/max labels
                    gg.drawString(font, String.format("%.1f", max), x + w - font.width(String.format("%.1f", max)),
                        gy, 0xFF888888, false);
                    gg.drawString(font, String.format("%.1f", min), x + w - font.width(String.format("%.1f", min)),
                        gy + gh - 8, 0xFF888888, false);
                }
            }
            default -> { // "text"
                String lbl = widget.label.isEmpty() ? widget.id : widget.label;
                String text = lbl.isEmpty() ? widget.value : (lbl + ": " + widget.value);
                gg.drawString(font, text, x, y, 0xFFFFFFFF, false);
            }
        }
    }

    private static void drawLine(GuiGraphics gg, int x0, int y0, int x1, int y1, int col) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int steps = 0;
        while (steps++ < 512) {
            gg.fill(x0, y0, x0 + 1, y0 + 1, col);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 <  dx) { err += dx; y0 += sy; }
        }
    }
}

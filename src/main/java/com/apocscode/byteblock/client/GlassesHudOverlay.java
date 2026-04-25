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
        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;

        // Render free-form canvas widgets first (absolute screen coords, no panel).
        long now = System.currentTimeMillis();
        boolean hasPanelWidgets = false;
        for (GlassesHudState.Widget w : widgets) {
            if (w.expireMs > 0 && now > w.expireMs) continue;
            if ("canvas".equals(w.type)) {
                renderCanvas(gg, font, w);
            } else {
                hasPanelWidgets = true;
            }
        }

        // No panel widgets received yet — show a "no signal" placeholder so the wearer
        // knows the HUD is alive and waiting for a computer to push widgets.
        if (!hasPanelWidgets) {
            if (widgets.isEmpty()) {
                int ch = com.apocscode.byteblock.item.GlassesItem.getChannel(head);
                int panelH = PAD * 2 + 28;
                gg.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_W, PANEL_Y + panelH, BG_COL);
                gg.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_W, PANEL_Y + 1, ACC_COL);
                gg.fill(PANEL_X, PANEL_Y + panelH - 1, PANEL_X + PANEL_W, PANEL_Y + panelH, ACC_COL);
                gg.drawString(font, Component.literal("BYTEBLOCK HUD").withStyle(ChatFormatting.AQUA),
                    PANEL_X + PAD, PANEL_Y + PAD, 0xFFFFFFFF, false);
                gg.drawString(font, Component.literal("ch " + ch + " — no signal").withStyle(ChatFormatting.GRAY),
                    PANEL_X + PAD, PANEL_Y + PAD + 12, 0xFFCCCCCC, false);
                gg.drawString(font, Component.literal("run glasses_test.lua").withStyle(ChatFormatting.DARK_GRAY),
                    PANEL_X + PAD, PANEL_Y + PAD + 22, 0xFF888888, false);
            }
            return;
        }

        // Panel layout pass — count panel widgets only (skip canvases already drawn).
        int totalRows = 0;
        for (GlassesHudState.Widget w : widgets) {
            if ("canvas".equals(w.type)) continue;
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
        for (GlassesHudState.Widget w : widgets) {
            if (w.expireMs > 0 && now > w.expireMs) continue;
            if ("canvas".equals(w.type)) continue;
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

    // ════════════════════════════════════════════════════════════════════════
    //  Canvas widget — true-color free-form 2D rendering at absolute screen
    //  coordinates. Driven by an opcode stream packed in widget.points:
    //
    //    1  pixel    : 1, x, y, color
    //    2  line     : 2, x1, y1, x2, y2, color
    //    3  rect     : 3, x, y, w, h, color, filled(0|1)
    //    4  circle   : 4, cx, cy, r, color, filled(0|1)
    //    5  triangle : 5, x1, y1, x2, y2, x3, y3, color, filled(0|1)
    //    6  poly     : 6, n, x1, y1, ..., xn, yn, color, filled(0|1)
    //    7  text     : 7, x, y, color, len, c1, c2, ..., cN  (chars as code points)
    //    8  gradient : 8, x, y, w, h, c1, c2, vertical(0|1)
    //    9  bezier   : 9, x1, y1, cx, cy, x2, y2, color
    //   10  image    : 10, w, h, x, y, scale, p1, p2, ... (p = ARGB packed; -1 = transparent)
    // ════════════════════════════════════════════════════════════════════════
    private static void renderCanvas(GuiGraphics gg, Font font, GlassesHudState.Widget w) {
        double[] s = w.points;
        if (s == null || s.length == 0) return;
        int i = 0, n = s.length;
        int guard = 0;
        while (i < n && guard++ < 10000) {
            int op = (int) s[i];
            switch (op) {
                case 1 -> { // pixel
                    if (i + 3 >= n) return;
                    int x = (int) s[i + 1], y = (int) s[i + 2];
                    int c = 0xFF000000 | ((int) s[i + 3] & 0xFFFFFF);
                    gg.fill(x, y, x + 1, y + 1, c);
                    i += 4;
                }
                case 2 -> { // line
                    if (i + 5 >= n) return;
                    int c = 0xFF000000 | ((int) s[i + 5] & 0xFFFFFF);
                    drawLine(gg, (int) s[i + 1], (int) s[i + 2], (int) s[i + 3], (int) s[i + 4], c);
                    i += 6;
                }
                case 3 -> { // rect
                    if (i + 6 >= n) return;
                    int x = (int) s[i + 1], y = (int) s[i + 2];
                    int rw = (int) s[i + 3], rh = (int) s[i + 4];
                    int c = 0xFF000000 | ((int) s[i + 5] & 0xFFFFFF);
                    boolean filled = ((int) s[i + 6]) != 0;
                    if (filled) {
                        gg.fill(x, y, x + rw, y + rh, c);
                    } else {
                        gg.fill(x, y, x + rw, y + 1, c);
                        gg.fill(x, y + rh - 1, x + rw, y + rh, c);
                        gg.fill(x, y, x + 1, y + rh, c);
                        gg.fill(x + rw - 1, y, x + rw, y + rh, c);
                    }
                    i += 7;
                }
                case 4 -> { // circle
                    if (i + 5 >= n) return;
                    int cx = (int) s[i + 1], cy = (int) s[i + 2], r = (int) s[i + 3];
                    int c = 0xFF000000 | ((int) s[i + 4] & 0xFFFFFF);
                    boolean filled = ((int) s[i + 5]) != 0;
                    int r2 = r * r;
                    int rIn = (r - 1) * (r - 1);
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dx = -r; dx <= r; dx++) {
                            int d = dx * dx + dy * dy;
                            if (d > r2) continue;
                            if (!filled && d < rIn) continue;
                            gg.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, c);
                        }
                    }
                    i += 6;
                }
                case 5 -> { // triangle
                    if (i + 9 >= n) return;
                    int x1 = (int) s[i + 1], y1 = (int) s[i + 2];
                    int x2 = (int) s[i + 3], y2 = (int) s[i + 4];
                    int x3 = (int) s[i + 5], y3 = (int) s[i + 6];
                    int c = 0xFF000000 | ((int) s[i + 7] & 0xFFFFFF);
                    boolean filled = ((int) s[i + 8]) != 0;
                    if (filled) {
                        fillTriangle(gg, x1, y1, x2, y2, x3, y3, c);
                    } else {
                        drawLine(gg, x1, y1, x2, y2, c);
                        drawLine(gg, x2, y2, x3, y3, c);
                        drawLine(gg, x3, y3, x1, y1, c);
                    }
                    i += 9;
                }
                case 6 -> { // poly
                    if (i + 1 >= n) return;
                    int pn = (int) s[i + 1];
                    if (pn < 2 || i + 1 + pn * 2 + 2 >= n) return;
                    int[] xs = new int[pn], ys = new int[pn];
                    for (int k = 0; k < pn; k++) {
                        xs[k] = (int) s[i + 2 + k * 2];
                        ys[k] = (int) s[i + 3 + k * 2];
                    }
                    int c = 0xFF000000 | ((int) s[i + 2 + pn * 2] & 0xFFFFFF);
                    boolean filled = ((int) s[i + 3 + pn * 2]) != 0;
                    if (filled && pn >= 3) {
                        fillPolygon(gg, xs, ys, c);
                    } else {
                        for (int k = 0; k < pn; k++) {
                            int kn = (k + 1) % pn;
                            drawLine(gg, xs[k], ys[k], xs[kn], ys[kn], c);
                        }
                    }
                    i += 4 + pn * 2;
                }
                case 7 -> { // text
                    if (i + 4 >= n) return;
                    int x = (int) s[i + 1], y = (int) s[i + 2];
                    int c = 0xFF000000 | ((int) s[i + 3] & 0xFFFFFF);
                    int len = (int) s[i + 4];
                    if (len < 0 || i + 5 + len > n) return;
                    StringBuilder sb = new StringBuilder(len);
                    for (int k = 0; k < len; k++) sb.append((char) ((int) s[i + 5 + k]));
                    gg.drawString(font, sb.toString(), x, y, c, false);
                    i += 5 + len;
                }
                case 8 -> { // gradient
                    if (i + 7 >= n) return;
                    int gx = (int) s[i + 1], gy = (int) s[i + 2];
                    int gw = (int) s[i + 3], gh = (int) s[i + 4];
                    int c1 = (int) s[i + 5] & 0xFFFFFF;
                    int c2 = (int) s[i + 6] & 0xFFFFFF;
                    boolean vertical = ((int) s[i + 7]) != 0;
                    int steps = vertical ? gh : gw;
                    if (steps < 1) steps = 1;
                    for (int k = 0; k < steps; k++) {
                        double t = (double) k / Math.max(1, steps - 1);
                        int blended = 0xFF000000 | lerpColor(c1, c2, t);
                        if (vertical) gg.fill(gx, gy + k, gx + gw, gy + k + 1, blended);
                        else          gg.fill(gx + k, gy, gx + k + 1, gy + gh, blended);
                    }
                    i += 8;
                }
                case 9 -> { // bezier (quadratic)
                    if (i + 7 >= n) return;
                    int bx1 = (int) s[i + 1], by1 = (int) s[i + 2];
                    int bcx = (int) s[i + 3], bcy = (int) s[i + 4];
                    int bx2 = (int) s[i + 5], by2 = (int) s[i + 6];
                    int c = 0xFF000000 | ((int) s[i + 7] & 0xFFFFFF);
                    int prevX = bx1, prevY = by1;
                    int segs = 32;
                    for (int k = 1; k <= segs; k++) {
                        double t = (double) k / segs;
                        double u = 1.0 - t;
                        int px = (int) Math.round(u * u * bx1 + 2 * u * t * bcx + t * t * bx2);
                        int py = (int) Math.round(u * u * by1 + 2 * u * t * bcy + t * t * by2);
                        drawLine(gg, prevX, prevY, px, py, c);
                        prevX = px; prevY = py;
                    }
                    i += 8;
                }
                case 10 -> { // image
                    if (i + 5 >= n) return;
                    int iw = (int) s[i + 1], ih = (int) s[i + 2];
                    int ix = (int) s[i + 3], iy = (int) s[i + 4];
                    int scale = Math.max(1, (int) s[i + 5]);
                    int pixCount = iw * ih;
                    if (iw <= 0 || ih <= 0 || i + 5 + pixCount >= n) return;
                    for (int py = 0; py < ih; py++) {
                        for (int px = 0; px < iw; px++) {
                            int v = (int) s[i + 6 + py * iw + px];
                            if (v < 0) continue; // -1 = transparent
                            int c = 0xFF000000 | (v & 0xFFFFFF);
                            int dx = ix + px * scale, dy = iy + py * scale;
                            gg.fill(dx, dy, dx + scale, dy + scale, c);
                        }
                    }
                    i += 6 + pixCount;
                }
                default -> { return; } // unknown opcode aborts to avoid runaway
            }
        }
    }

    private static int lerpColor(int c1, int c2, double t) {
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static void fillTriangle(GuiGraphics gg, int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        int minY = Math.min(y1, Math.min(y2, y3));
        int maxY = Math.max(y1, Math.max(y2, y3));
        for (int y = minY; y <= maxY; y++) {
            int[] xs = new int[3];
            int n = 0;
            n = addEdgeX(xs, n, x1, y1, x2, y2, y);
            n = addEdgeX(xs, n, x2, y2, x3, y3, y);
            n = addEdgeX(xs, n, x3, y3, x1, y1, y);
            if (n < 2) continue;
            int xa = Math.min(xs[0], xs[1]);
            int xb = Math.max(xs[0], xs[1]);
            gg.fill(xa, y, xb + 1, y + 1, color);
        }
    }

    private static int addEdgeX(int[] xs, int n, int x1, int y1, int x2, int y2, int y) {
        if (n >= 2) return n;
        if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y)) {
            double t = (double) (y - y1) / (y2 - y1);
            xs[n++] = (int) Math.round(x1 + t * (x2 - x1));
        }
        return n;
    }

    private static void fillPolygon(GuiGraphics gg, int[] xs, int[] ys, int color) {
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int v : ys) { if (v < minY) minY = v; if (v > maxY) maxY = v; }
        int n = xs.length;
        int[] nodeX = new int[n];
        for (int y = minY; y <= maxY; y++) {
            int nodes = 0;
            int j = n - 1;
            for (int k = 0; k < n; k++) {
                if ((ys[k] < y && ys[j] >= y) || (ys[j] < y && ys[k] >= y)) {
                    double t = (double) (y - ys[k]) / (ys[j] - ys[k]);
                    nodeX[nodes++] = (int) Math.round(xs[k] + t * (xs[j] - xs[k]));
                }
                j = k;
            }
            // Sort
            for (int a = 0; a < nodes - 1; a++)
                for (int b = a + 1; b < nodes; b++)
                    if (nodeX[a] > nodeX[b]) { int t = nodeX[a]; nodeX[a] = nodeX[b]; nodeX[b] = t; }
            for (int k = 0; k + 1 < nodes; k += 2) {
                gg.fill(nodeX[k], y, nodeX[k + 1] + 1, y + 1, color);
            }
        }
    }
}

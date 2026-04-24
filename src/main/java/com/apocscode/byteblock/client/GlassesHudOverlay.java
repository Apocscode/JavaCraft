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
        int panelH = PAD * 2 + rows * ROW_H + 12; // header row
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
            renderWidget(gg, font, PANEL_X + PAD, y, PANEL_W - PAD * 2, w);
            y += ROW_H;
        }
    }

    private static void renderWidget(GuiGraphics gg, Font font, int x, int y, int w, GlassesHudState.Widget widget) {
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
            default -> { // "text"
                String lbl = widget.label.isEmpty() ? widget.id : widget.label;
                String text = lbl.isEmpty() ? widget.value : (lbl + ": " + widget.value);
                gg.drawString(font, text, x, y, 0xFFFFFFFF, false);
            }
        }
    }
}

package com.apocscode.byteblock.client;

import com.apocscode.byteblock.entity.EntityPaint;
import com.apocscode.byteblock.network.SetEntityPaintPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared customization screen for paint-able entities (robot, drone). Layout:
 *
 * <pre>
 * +-----------------------------------------------------+
 * |  Customize: <Entity>                          [X]   |
 * |  Parts            Swatches               Actions    |
 * |  [body]  *       [][][][][][][][][][][]  [Reset]    |
 * |  [trim]          [][][][][][][][][][][]  [Apply]    |
 * |  [arms]          [][][][][][][][][][][]  [Cancel]   |
 * |  [head]          [][][][][][][][][][][]             |
 * |  ...             [Hue x 4 brightness rows]          |
 * +-----------------------------------------------------+
 * </pre>
 *
 * Click a part to select it; click a swatch to assign it to the selected part.
 * Apply sends a {@link SetEntityPaintPayload} to the server. Reset clears the
 * full paint to factory defaults.
 */
public abstract class EntityPaintScreen extends Screen {
    private static final int W = 280, H = 200;
    private static final int SWATCH = 14;
    private static final int SWATCH_GAP = 2;

    /** Generated 12-hue × 4-brightness palette. */
    private static final int[] PALETTE = buildPalette();

    private final Entity entity;
    private final String[] slots;
    /** Working copy of paint — only sent on Apply. */
    private final EntityPaint working;
    private String selected;
    private int leftX, topY;

    protected EntityPaintScreen(Entity entity, EntityPaint current, String[] slots, Component title) {
        super(title);
        this.entity = entity;
        this.slots = slots;
        this.working = current == null ? new EntityPaint() : current.copy();
        this.selected = slots.length > 0 ? slots[0] : "body";
    }

    @Override
    protected void init() {
        leftX = (this.width - W) / 2;
        topY = (this.height - H) / 2;

        // Part list — left column
        for (int i = 0; i < slots.length; i++) {
            String slot = slots[i];
            addRenderableWidget(Button.builder(Component.literal(slot),
                    b -> selected = slot)
                .pos(leftX + 8, topY + 24 + i * 18)
                .size(70, 16)
                .build());
        }

        // Action buttons — right column
        addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
            for (String s : slots) working.set(s, null);
        }).pos(leftX + W - 78, topY + 24).size(70, 18).build());

        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> {
            PacketDistributor.sendToServer(new SetEntityPaintPayload(entity.getId(), working.save()));
            this.onClose();
        }).pos(leftX + W - 78, topY + 46).size(70, 18).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
            .pos(leftX + W - 78, topY + 68).size(70, 18).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);

        // Frame
        gui.fill(leftX - 2, topY - 2, leftX + W + 2, topY + H + 2, 0xFF1A1A20);
        gui.fill(leftX, topY, leftX + W, topY + H, 0xFFE0E0E5);

        // Title
        gui.drawString(this.font, this.title, leftX + 6, topY + 6, 0xFF202028, false);

        // Selected indicator next to active part button
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].equals(selected)) {
                gui.drawString(this.font, "*", leftX + 80, topY + 28 + i * 18, 0xFFE05030, false);
                // Show current color preview
                int cur = working.get(selected, 0xFFFFFF);
                gui.fill(leftX + 90, topY + 26 + i * 18, leftX + 110, topY + 38 + i * 18, 0xFF000000 | cur);
            }
        }

        // Swatch grid
        int gridX = leftX + 95;
        int gridY = topY + 100;
        gui.drawString(this.font, "Palette (click swatch to apply to '" + selected + "')",
                gridX, gridY - 12, 0xFF303038, false);
        int cols = 12;
        for (int i = 0; i < PALETTE.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int sx = gridX + col * (SWATCH + SWATCH_GAP);
            int sy = gridY + row * (SWATCH + SWATCH_GAP);
            gui.fill(sx, sy, sx + SWATCH, sy + SWATCH, 0xFF000000 | PALETTE[i]);
        }

        // Help text
        gui.drawString(this.font, "Pick a part on the left, then a color below.",
                leftX + 8, topY + H - 16, 0xFF505058, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int gridX = leftX + 95;
            int gridY = topY + 100;
            int cols = 12;
            for (int i = 0; i < PALETTE.length; i++) {
                int col = i % cols;
                int row = i / cols;
                int sx = gridX + col * (SWATCH + SWATCH_GAP);
                int sy = gridY + row * (SWATCH + SWATCH_GAP);
                if (mouseX >= sx && mouseX < sx + SWATCH && mouseY >= sy && mouseY < sy + SWATCH) {
                    working.set(selected, PALETTE[i]);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static int[] buildPalette() {
        int hues = 12;
        int rows = 4; // brightness rows
        int[] out = new int[hues * rows];
        int idx = 0;
        for (int row = 0; row < rows; row++) {
            float brightness = 0.35f + row * 0.22f;
            for (int h = 0; h < hues; h++) {
                float hue = h / (float) hues;
                int rgb = java.awt.Color.HSBtoRGB(hue, 0.85f, Math.min(1.0f, brightness));
                out[idx++] = rgb & 0xFFFFFF;
            }
        }
        // Append grayscale row at end to round it out (replace last brightness row's first 4 with grays)
        int gray = 0;
        for (int i = 0; i < hues && gray < hues; i++, gray++) {
            int v = (int) (gray * (255.0 / (hues - 1)));
            out[(rows - 1) * hues + gray] = (v << 16) | (v << 8) | v;
        }
        return out;
    }
}

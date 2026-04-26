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
    private static final int W = 560, H = 280;
    private static final int SWATCH = 22;
    private static final int SWATCH_GAP = 4;
    private static final int FACE_THUMB = 40;
    private static final int FACE_GAP_X = 14;
    private static final int FACE_LABEL_PAD = 14;
    private static final int FACE_COLS = 5;

    /** Generated 12-hue × 4-brightness palette. */
    private static final int[] PALETTE = buildPalette();

    private final Entity entity;
    private final String[] slots;
    private final boolean supportsFace;
    /** Working copy of paint — only sent on Apply. */
    private final EntityPaint working;
    private String selected;
    private boolean facesTab;
    private int leftX, topY;

    protected EntityPaintScreen(Entity entity, EntityPaint current, String[] slots,
                                 boolean supportsFace, Component title) {
        super(title);
        this.entity = entity;
        this.slots = slots;
        this.supportsFace = supportsFace;
        this.working = current == null ? new EntityPaint() : current.copy();
        this.selected = slots.length > 0 ? slots[0] : "body";
    }

    @Override
    protected void init() {
        leftX = (this.width - W) / 2;
        topY = (this.height - H) / 2;

        // Tabs (top) — only show Face tab when entity supports it.
        if (supportsFace) {
            addRenderableWidget(Button.builder(Component.literal("Paint"), b -> {
                facesTab = false;
                this.rebuildWidgets();
            }).pos(leftX + 220, topY + 4).size(70, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Face"), b -> {
                facesTab = true;
                this.rebuildWidgets();
            }).pos(leftX + 296, topY + 4).size(70, 18).build());
        }

        if (!facesTab) {
            // Part list — left column
            for (int i = 0; i < slots.length; i++) {
                String slot = slots[i];
                addRenderableWidget(Button.builder(Component.literal(slot),
                        b -> selected = slot)
                    .pos(leftX + 8, topY + 28 + i * 22)
                    .size(110, 20)
                    .build());
            }
        }

        // Action buttons — right column
        addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
            if (facesTab) {
                working.setFaceId("classic");
                working.setFaceBits(0L);
            } else {
                for (String s : slots) working.set(s, null);
            }
        }).pos(leftX + W - 96, topY + 28).size(88, 22).build());

        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> {
            PacketDistributor.sendToServer(new SetEntityPaintPayload(entity.getId(), working.save()));
            this.onClose();
        }).pos(leftX + W - 96, topY + 54).size(88, 22).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
            .pos(leftX + W - 96, topY + 80).size(88, 22).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);

        // Frame
        gui.fill(leftX - 2, topY - 2, leftX + W + 2, topY + H + 2, 0xFF1A1A20);
        gui.fill(leftX, topY, leftX + W, topY + H, 0xFFE0E0E5);

        // Title
        gui.drawString(this.font, this.title, leftX + 6, topY + 6, 0xFF202028, false);

        if (facesTab) {
            renderFacesTab(gui);
            return;
        }

        // Selected indicator next to active part button
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].equals(selected)) {
                gui.drawString(this.font, "▶", leftX + 122, topY + 34 + i * 22, 0xFFE05030, false);
                // Show current color preview
                int cur = working.get(selected, 0xFFFFFF);
                gui.fill(leftX + 134, topY + 30 + i * 22, leftX + 162, topY + 46 + i * 22, 0xFF000000 | cur);
            }
        }

        // Swatch grid — left edge well clear of part-list + color preview
        int gridX = leftX + 175;
        int gridY = topY + 150;
        gui.drawString(this.font, "Palette — click swatch to apply to '" + selected + "'",
                gridX, gridY - 14, 0xFF303038, false);
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
                leftX + 8, topY + H - 14, 0xFF505058, false);
    }

    private void renderFacesTab(GuiGraphics gui) {
        gui.drawString(this.font, "Face presets — click to assign  (current: " + working.getFaceId() + ")",
                leftX + 8, topY + 28, 0xFF303038, false);
        String[] ids = FacePresets.ids();
        int gridX = leftX + 14;
        int gridY = topY + 46;
        int eyeColor = working.get("eye", 0x28DCFF);
        int rowH = FACE_THUMB + FACE_LABEL_PAD;
        for (int i = 0; i < ids.length; i++) {
            int col = i % FACE_COLS;
            int row = i / FACE_COLS;
            int tx = gridX + col * (FACE_THUMB + FACE_GAP_X);
            int ty = gridY + row * (rowH + 6);
            // Frame
            int border = ids[i].equals(working.getFaceId()) ? 0xFFE05030 : 0xFF303038;
            gui.fill(tx - 1, ty - 1, tx + FACE_THUMB + 1, ty + FACE_THUMB + 1, border);
            gui.fill(tx, ty, tx + FACE_THUMB, ty + FACE_THUMB, 0xFF101015);
            // Pixel art (8x8 → FACE_THUMB px, 5px per cell when 40)
            long bits = FacePresets.get(ids[i]);
            int px = FACE_THUMB / 8;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    if (((bits >> (y * 8 + x)) & 1L) == 0L) continue;
                    int gx = tx + x * px;
                    int gy = ty + (7 - y) * px;
                    gui.fill(gx, gy, gx + px, gy + px, 0xFF000000 | eyeColor);
                }
            }
            // Label — truncate to fit thumb width if needed
            String label = ids[i];
            while (this.font.width(label) > FACE_THUMB + FACE_GAP_X - 2 && label.length() > 1) {
                label = label.substring(0, label.length() - 1);
            }
            gui.drawString(this.font, label, tx, ty + FACE_THUMB + 2, 0xFF303038, false);
        }
        gui.drawString(this.font, "Face uses the 'eye' paint color.",
                leftX + 8, topY + H - 16, 0xFF505058, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (facesTab) {
                String[] ids = FacePresets.ids();
                int gridX = leftX + 14;
                int gridY = topY + 46;
                int rowH = FACE_THUMB + FACE_LABEL_PAD;
                for (int i = 0; i < ids.length; i++) {
                    int col = i % FACE_COLS;
                    int row = i / FACE_COLS;
                    int tx = gridX + col * (FACE_THUMB + FACE_GAP_X);
                    int ty = gridY + row * (rowH + 6);
                    if (mouseX >= tx && mouseX < tx + FACE_THUMB && mouseY >= ty && mouseY < ty + FACE_THUMB) {
                        working.setFaceId(ids[i]);
                        working.setFaceBits(0L);
                        return true;
                    }
                }
            } else {
                int gridX = leftX + 175;
                int gridY = topY + 150;
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

package com.apocscode.byteblock.client;

import com.apocscode.byteblock.network.PaintByteChestPayload;
import com.apocscode.byteblock.network.RenameByteChestPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Tabbed configuration screen for ByteChests. Two tabs:
 *
 * <ul>
 *   <li><b>Rename</b> – legacy label EditBox + OK/Cancel.</li>
 *   <li><b>Paint</b>  – 12x4 hue/brightness palette + Reset (white) and Apply.</li>
 * </ul>
 *
 * Opened via shift+right-click on the chest with an empty hand.
 */
public class ByteChestConfigScreen extends Screen {

    private static final int W = 720;
    private static final int H = 300;
    private static final int SWATCH = 32;
    private static final int SWATCH_GAP = 6;
    private static final int COLS = 12;
    private static final int ROWS = 4;
    private static final int GRID_W = COLS * SWATCH + (COLS - 1) * SWATCH_GAP;
    private static final int GRID_H = ROWS * SWATCH + (ROWS - 1) * SWATCH_GAP;

    /** Cached palette (12 hues × 4 brightness rows, last row grayscale). */
    private static final int[] PALETTE = buildPalette();

    private final BlockPos pos;
    private final String initialLabel;
    private final int initialTint;

    private boolean paintTab;
    private int workingTint;
    private EditBox labelField;

    public ByteChestConfigScreen(BlockPos pos, String currentLabel, int currentTint) {
        super(Component.literal("Configure ByteChest"));
        this.pos = pos;
        this.initialLabel = currentLabel == null ? "" : currentLabel;
        this.initialTint = currentTint & 0xFFFFFF;
        this.workingTint = this.initialTint;
    }

    @Override
    protected void init() {
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;

        // Tabs
        addRenderableWidget(Button.builder(Component.literal("Rename"), b -> {
            paintTab = false;
            this.rebuildWidgets();
        }).bounds(left + 14, top + 30, 90, 22).build());

        addRenderableWidget(Button.builder(Component.literal("Paint"), b -> {
            paintTab = true;
            this.rebuildWidgets();
        }).bounds(left + 110, top + 30, 90, 22).build());

        if (!paintTab) {
            // Rename tab
            labelField = new EditBox(this.font, left + 14, top + 80, W - 28, 22,
                    Component.literal("Label"));
            labelField.setMaxLength(32);
            labelField.setValue(initialLabel);
            labelField.setFocused(true);
            addRenderableWidget(labelField);
            setInitialFocus(labelField);

            addRenderableWidget(Button.builder(Component.literal("OK"), b -> commitRename())
                    .bounds(left + 14, top + H - 32, 110, 22)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                    .bounds(left + W - 124, top + H - 32, 110, 22)
                    .build());
        } else {
            // Paint tab — Reset to white and Apply on the right; palette in middle.
            addRenderableWidget(Button.builder(Component.literal("Reset"), b -> workingTint = 0xFFFFFF)
                    .bounds(left + W - 90, top + 76, 76, 22).build());
            addRenderableWidget(Button.builder(Component.literal("Apply"), b -> commitPaint())
                    .bounds(left + 14, top + H - 32, 110, 22)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                    .bounds(left + W - 124, top + H - 32, 110, 22)
                    .build());
        }
    }

    private void commitRename() {
        String label = labelField.getValue() == null ? "" : labelField.getValue().trim();
        PacketDistributor.sendToServer(new RenameByteChestPayload(pos, label));
        onClose();
    }

    private void commitPaint() {
        PacketDistributor.sendToServer(new PaintByteChestPayload(pos, workingTint & 0xFFFFFF));
        onClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (paintTab && button == 0) {
            int left = (this.width - W) / 2;
            int top = (this.height - H) / 2;
            int gridX = left + (W - GRID_W) / 2;
            int gridY = top + 110;
            for (int i = 0; i < PALETTE.length; i++) {
                int col = i % COLS;
                int row = i / COLS;
                int sx = gridX + col * (SWATCH + SWATCH_GAP);
                int sy = gridY + row * (SWATCH + SWATCH_GAP);
                if (mouseX >= sx && mouseX < sx + SWATCH && mouseY >= sy && mouseY < sy + SWATCH) {
                    workingTint = PALETTE[i] & 0xFFFFFF;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!paintTab && labelField != null && labelField.isFocused()) {
            if (keyCode == 257 || keyCode == 335) {
                commitRename();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // Plain dim overlay (avoid Screen.renderBackground which blurs the HUD too).
        g.fill(0, 0, this.width, this.height, 0xA0000000);
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;

        // Panel
        g.fill(left, top, left + W, top + H, 0xFF1E1E1E);
        g.fill(left, top, left + W, top + 1, 0xFF3A3A3A);
        g.fill(left, top + H - 1, left + W, top + H, 0xFF3A3A3A);
        g.fill(left, top, left + 1, top + H, 0xFF3A3A3A);
        g.fill(left + W - 1, top, left + W, top + H, 0xFF3A3A3A);

        g.drawString(this.font, "Configure ByteChest", left + 14, top + 10, 0xFFE0E0E0, false);

        // Active-tab indicator (orange underline)
        int tabX = left + (paintTab ? 110 : 14);
        g.fill(tabX, top + 54, tabX + 90, top + 56, 0xFFE05030);

        super.render(g, mx, my, pt);

        if (paintTab) {
            // Label + current preview swatch
            g.drawString(this.font, "Current tint:", left + 14, top + 82, 0xFFC0C0C0, false);
            g.fill(left + 90, top + 76, left + 90 + 64, top + 76 + 22, 0xFF000000);
            g.fill(left + 92, top + 78, left + 90 + 62, top + 76 + 20, 0xFF000000 | (workingTint & 0xFFFFFF));

            // Palette grid (centered horizontally)
            int gridX = left + (W - GRID_W) / 2;
            int gridY = top + 110;
            for (int i = 0; i < PALETTE.length; i++) {
                int col = i % COLS;
                int row = i / COLS;
                int sx = gridX + col * (SWATCH + SWATCH_GAP);
                int sy = gridY + row * (SWATCH + SWATCH_GAP);
                g.fill(sx, sy, sx + SWATCH, sy + SWATCH, 0xFF000000 | (PALETTE[i] & 0xFFFFFF));
                if ((PALETTE[i] & 0xFFFFFF) == (workingTint & 0xFFFFFF)) {
                    // Highlight ring on the selected swatch.
                    g.fill(sx - 1, sy - 1, sx + SWATCH + 1, sy, 0xFFFFFFFF);
                    g.fill(sx - 1, sy + SWATCH, sx + SWATCH + 1, sy + SWATCH + 1, 0xFFFFFFFF);
                    g.fill(sx - 1, sy, sx, sy + SWATCH, 0xFFFFFFFF);
                    g.fill(sx + SWATCH, sy, sx + SWATCH + 1, sy + SWATCH, 0xFFFFFFFF);
                }
            }
        } else {
            g.drawString(this.font, "Label (max 32 chars):", left + 14, top + 66, 0xFFC0C0C0, false);
        }
    }

    private static int[] buildPalette() {
        int hues = 12;
        int rows = 4;
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
        // Replace last row's first 12 entries with grayscale ramp.
        for (int i = 0; i < hues; i++) {
            int v = (int) (i * (255.0 / (hues - 1)));
            out[(rows - 1) * hues + i] = (v << 16) | (v << 8) | v;
        }
        return out;
    }
}

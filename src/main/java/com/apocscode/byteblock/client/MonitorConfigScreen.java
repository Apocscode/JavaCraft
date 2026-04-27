package com.apocscode.byteblock.client;

import com.apocscode.byteblock.block.entity.MonitorBlockEntity;
import com.apocscode.byteblock.network.MonitorConfigPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Standalone screen for configuring a monitor block's geometry — thickness, tilt, yaw —
 * and assigning a user-friendly label. Opened by right-clicking a monitor with an empty
 * hand while sneaking.
 */
public class MonitorConfigScreen extends Screen {

    private final BlockPos monitorPos;

    private int thicknessPx;
    private float tiltDeg;
    private float yawDeg;
    private String initialLabel;
    private int frameColor;

    private Button thickMinus2, thickMinus1, thickPlus1, thickPlus2;
    private Button tiltMinus15, tiltMinus5, tiltPlus5, tiltPlus15;
    private Button yawMinus15, yawMinus5, yawPlus5, yawPlus15;
    private Button applyBtn;
    private Button paintBtn;
    private EditBox labelBox;

    public MonitorConfigScreen(BlockPos monitorPos, int thicknessPx, float tiltDeg, float yawDeg,
                               String label, int frameColor) {
        super(Component.literal("Monitor Configuration"));
        this.monitorPos = monitorPos;
        this.thicknessPx = clampInt(thicknessPx, 1, 6);
        this.tiltDeg = clampF(tiltDeg, -45f, 45f);
        this.yawDeg = clampF(yawDeg, -45f, 45f);
        this.initialLabel = label == null ? "" : label;
        this.frameColor = 0xFF000000 | (frameColor & 0x00FFFFFF);
    }

    /** Convenience: build a screen pre-populated from the live BE on the client side. */
    public static MonitorConfigScreen forMonitor(MonitorBlockEntity m) {
        return new MonitorConfigScreen(m.getBlockPos(),
                m.getThicknessPx(), m.getTiltDegrees(), m.getYawDegrees(), m.getLabel(), m.getFrameColor());
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;

        // ── Label row (text input) ──
        int rowYLabel = this.height / 2 - 110;
        labelBox = new EditBox(this.font, cx - 90, rowYLabel, 180, 18,
                Component.literal("Label"));
        labelBox.setMaxLength(32);
        labelBox.setHint(Component.literal("Monitor name (e.g. main, dashboard)"));
        labelBox.setValue(initialLabel);
        addRenderableWidget(labelBox);

        // ── Thickness row ──
        int rowYThick = this.height / 2 - 70;
        thickMinus2 = addAdjusterButton(cx - 130, rowYThick, "−2", b -> { thicknessPx = clampInt(thicknessPx - 2, 1, 6); });
        thickMinus1 = addAdjusterButton(cx -  90, rowYThick, "−1", b -> { thicknessPx = clampInt(thicknessPx - 1, 1, 6); });
        thickPlus1  = addAdjusterButton(cx +  50, rowYThick, "+1", b -> { thicknessPx = clampInt(thicknessPx + 1, 1, 6); });
        thickPlus2  = addAdjusterButton(cx +  90, rowYThick, "+2", b -> { thicknessPx = clampInt(thicknessPx + 2, 1, 6); });

        // ── Tilt row ──
        int rowYTilt = this.height / 2 - 25;
        tiltMinus15 = addAdjusterButton(cx - 130, rowYTilt, "−15°", b -> { tiltDeg = clampF(tiltDeg - 15f, -45f, 45f); });
        tiltMinus5  = addAdjusterButton(cx -  90, rowYTilt, "−5°",  b -> { tiltDeg = clampF(tiltDeg - 5f,  -45f, 45f); });
        tiltPlus5   = addAdjusterButton(cx +  50, rowYTilt, "+5°",  b -> { tiltDeg = clampF(tiltDeg + 5f,  -45f, 45f); });
        tiltPlus15  = addAdjusterButton(cx +  90, rowYTilt, "+15°", b -> { tiltDeg = clampF(tiltDeg + 15f, -45f, 45f); });

        // ── Yaw row ──
        int rowYYaw = this.height / 2 + 20;
        yawMinus15 = addAdjusterButton(cx - 130, rowYYaw, "−15°", b -> { yawDeg = clampF(yawDeg - 15f, -45f, 45f); });
        yawMinus5  = addAdjusterButton(cx -  90, rowYYaw, "−5°",  b -> { yawDeg = clampF(yawDeg - 5f,  -45f, 45f); });
        yawPlus5   = addAdjusterButton(cx +  50, rowYYaw, "+5°",  b -> { yawDeg = clampF(yawDeg + 5f,  -45f, 45f); });
        yawPlus15  = addAdjusterButton(cx +  90, rowYYaw, "+15°", b -> { yawDeg = clampF(yawDeg + 15f, -45f, 45f); });

        // ── Apply / Paint / Done ──
        applyBtn = Button.builder(Component.literal("Apply"), b -> apply())
                .bounds(cx - 140, this.height / 2 + 70, 80, 20).build();
        addRenderableWidget(applyBtn);
        paintBtn = Button.builder(Component.literal("Paint"), b -> openPaintPicker())
                .bounds(cx - 50, this.height / 2 + 70, 100, 20).build();
        addRenderableWidget(paintBtn);
        Button doneBtn = Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(cx + 60, this.height / 2 + 70, 80, 20).build();
        addRenderableWidget(doneBtn);
    }

    private void openPaintPicker() {
        if (this.minecraft == null) return;
        this.minecraft.setScreen(new MonitorPaintScreen(this, frameColor, chosen -> {
            this.frameColor = chosen;
        }));
    }

    private Button addAdjusterButton(int x, int y, String label, Button.OnPress onPress) {
        Button b = Button.builder(Component.literal(label), onPress).bounds(x, y, 36, 20).build();
        addRenderableWidget(b);
        return b;
    }

    private void apply() {
        String lbl = labelBox != null ? labelBox.getValue() : initialLabel;
        PacketDistributor.sendToServer(new MonitorConfigPayload(monitorPos, thicknessPx, tiltDeg, yawDeg, lbl, frameColor));
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        int cx = this.width / 2;

        // Title (above label row)
        gfx.drawCenteredString(this.font, this.title, cx, this.height / 2 - 140, 0xFFFFFFFF);
        gfx.drawCenteredString(this.font,
                Component.literal("Monitor at " + monitorPos.toShortString()).withStyle(s -> s.withColor(0xAAAAAA)),
                cx, this.height / 2 - 128, 0xFFAAAAAA);

        // Label caption (left of the EditBox)
        int rowYLabel = this.height / 2 - 110;
        gfx.drawString(this.font, "Label", cx - 200, rowYLabel + 5, 0xFF80E0FF, false);

        // ── Thickness label + value ──
        int rowYThick = this.height / 2 - 70;
        gfx.drawString(this.font, "Thickness", cx - 200, rowYThick + 6, 0xFF80E0FF, false);
        drawValueBox(gfx, cx - 45, rowYThick, 90, 20, thicknessPx + " px");

        // ── Tilt label + value ──
        int rowYTilt = this.height / 2 - 25;
        gfx.drawString(this.font, "Tilt (X)", cx - 200, rowYTilt + 6, 0xFF80E0FF, false);
        drawValueBox(gfx, cx - 45, rowYTilt, 90, 20, String.format(java.util.Locale.ROOT, "%+.1f°", tiltDeg));

        // ── Yaw label + value ──
        int rowYYaw = this.height / 2 + 20;
        gfx.drawString(this.font, "Yaw (Y)", cx - 200, rowYYaw + 6, 0xFF80E0FF, false);
        drawValueBox(gfx, cx - 45, rowYYaw, 90, 20, String.format(java.util.Locale.ROOT, "%+.1f°", yawDeg));

        // ── Frame color swatch (between yaw row and apply buttons) ──
        int rowYColor = this.height / 2 + 48;
        gfx.drawString(this.font, "Frame Color", cx - 200, rowYColor + 4, 0xFF80E0FF, false);
        // Color swatch box
        int swX = cx - 45;
        int swW = 90;
        gfx.fill(swX, rowYColor, swX + swW, rowYColor + 14, 0xFF000000);
        gfx.fill(swX + 1, rowYColor + 1, swX + swW - 1, rowYColor + 13, frameColor);
        gfx.drawString(this.font, String.format("#%06X", frameColor & 0xFFFFFF),
                swX + swW + 6, rowYColor + 4, 0xFFAAAAAA, false);

        // Hint
        gfx.drawCenteredString(this.font,
                Component.literal("Click Apply to send. Right-click monitor again to reopen.")
                        .withStyle(s -> s.withColor(0x808080)),
                cx, this.height / 2 + 100, 0xFF808080);
    }

    private void drawValueBox(GuiGraphics gfx, int x, int y, int w, int h, String value) {
        gfx.fill(x, y, x + w, y + h, 0xFF111122);
        gfx.fill(x, y, x + w, y + 1, 0xFF333355);
        gfx.fill(x, y + h - 1, x + w, y + h, 0xFF333355);
        gfx.fill(x, y, x + 1, y + h, 0xFF333355);
        gfx.fill(x + w - 1, y, x + w, y + h, 0xFF333355);
        gfx.drawCenteredString(this.font, value, x + w / 2, y + 6, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static float clampF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}

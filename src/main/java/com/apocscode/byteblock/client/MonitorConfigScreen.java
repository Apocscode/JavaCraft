package com.apocscode.byteblock.client;

import com.apocscode.byteblock.block.entity.MonitorBlockEntity;
import com.apocscode.byteblock.network.MonitorConfigPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Standalone screen for configuring a monitor block's geometry — thickness, tilt, yaw.
 * Opened by right-clicking a monitor with an empty hand while sneaking.
 */
public class MonitorConfigScreen extends Screen {

    private final BlockPos monitorPos;

    private int thicknessPx;
    private float tiltDeg;
    private float yawDeg;

    private Button thickMinus2, thickMinus1, thickPlus1, thickPlus2;
    private Button tiltMinus15, tiltMinus5, tiltPlus5, tiltPlus15;
    private Button yawMinus15, yawMinus5, yawPlus5, yawPlus15;
    private Button applyBtn;

    public MonitorConfigScreen(BlockPos monitorPos, int thicknessPx, float tiltDeg, float yawDeg) {
        super(Component.literal("Monitor Geometry"));
        this.monitorPos = monitorPos;
        this.thicknessPx = clampInt(thicknessPx, 1, 6);
        this.tiltDeg = clampF(tiltDeg, -45f, 45f);
        this.yawDeg = clampF(yawDeg, -45f, 45f);
    }

    /** Convenience: build a screen pre-populated from the live BE on the client side. */
    public static MonitorConfigScreen forMonitor(MonitorBlockEntity m) {
        return new MonitorConfigScreen(m.getBlockPos(),
                m.getThicknessPx(), m.getTiltDegrees(), m.getYawDegrees());
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;

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

        // ── Apply / Done ──
        applyBtn = Button.builder(Component.literal("Apply"), b -> apply())
                .bounds(cx - 100, this.height / 2 + 70, 90, 20).build();
        addRenderableWidget(applyBtn);
        Button doneBtn = Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(cx + 10, this.height / 2 + 70, 90, 20).build();
        addRenderableWidget(doneBtn);
    }

    private Button addAdjusterButton(int x, int y, String label, Button.OnPress onPress) {
        Button b = Button.builder(Component.literal(label), onPress).bounds(x, y, 36, 20).build();
        addRenderableWidget(b);
        return b;
    }

    private void apply() {
        PacketDistributor.sendToServer(new MonitorConfigPayload(monitorPos, thicknessPx, tiltDeg, yawDeg));
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        int cx = this.width / 2;

        // Title
        gfx.drawCenteredString(this.font, this.title, cx, this.height / 2 - 110, 0xFFFFFFFF);
        gfx.drawCenteredString(this.font,
                Component.literal("Monitor at " + monitorPos.toShortString()).withStyle(s -> s.withColor(0xAAAAAA)),
                cx, this.height / 2 - 95, 0xFFAAAAAA);

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

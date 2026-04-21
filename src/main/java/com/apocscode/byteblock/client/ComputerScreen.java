package com.apocscode.byteblock.client;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Minecraft Screen that renders the ByteBlock computer display.
 * Uses a pixel-level framebuffer (640×400) uploaded to a GPU DynamicTexture
 * for crisp, high-resolution rendering. Maps keyboard + mouse events into OS events.
 */
public class ComputerScreen extends Screen {

    private static final int FB_W = PixelBuffer.SCREEN_W; // 640
    private static final int FB_H = PixelBuffer.SCREEN_H; // 400

    private final JavaOS os;

    // GPU texture pipeline
    private NativeImage nativeImage;
    private DynamicTexture dynamicTexture;
    private ResourceLocation textureLoc;

    // Layout
    private float scale;
    private int renderW, renderH;       // actual pixel size on MC screen
    private int border, headerH;
    private int termX, termY;           // top-left of the framebuffer area on MC screen
    private boolean positioned;

    // Interaction
    private boolean dragging, resizing;
    private double dragOffX, dragOffY;
    private float userScale;

    public ComputerScreen(JavaOS os) {
        super(Component.literal("ByteBlock Computer"));
        this.os = os;
    }

    @Override
    protected void init() {
        super.init();
        // Create GPU texture (once)
        if (nativeImage == null) {
            nativeImage = new NativeImage(NativeImage.Format.RGBA, FB_W, FB_H, false);
            dynamicTexture = new DynamicTexture(nativeImage);
            textureLoc = Minecraft.getInstance().getTextureManager()
                    .register("byteblock_screen", dynamicTexture);
        }
        recalcLayout();
    }

    private void recalcLayout() {
        // Auto-fit: fill ~82% of available screen area (respects MC GUI scale automatically,
        // since this.width/height are already in GUI-scaled coordinates)
        float fitW = this.width * 0.82f;
        float fitH = this.height * 0.80f;
        float autoScale = Math.min(fitW / FB_W, fitH / FB_H);
        float newScale = userScale > 0 ? userScale : Math.max(0.4f, autoScale);

        boolean scaleChanged = (newScale != scale);
        scale = newScale;

        renderW = Math.round(FB_W * scale);
        renderH = Math.round(FB_H * scale);
        border = Math.max(3, Math.round(3 * scale));
        headerH = Math.max(8, Math.round(6 * scale));
        // Re-center when auto-scale changes (GUI scale change) or first time
        if (!positioned || (scaleChanged && userScale <= 0)) {
            termX = (this.width - renderW) / 2;
            termY = (this.height - renderH - headerH - border) / 2 + headerH + border;
            positioned = true;
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Close screen automatically when OS shuts down
        if (os.isShutdown()) {
            this.onClose();
            return;
        }

        renderBackground(gfx, mouseX, mouseY, partialTick);

        // Dynamic scale update — recalculate when window/GUI scale changes
        float fitW = this.width * 0.82f;
        float fitH = this.height * 0.80f;
        float autoScale = Math.min(fitW / FB_W, fitH / FB_H);
        float effectiveScale = userScale > 0 ? userScale : Math.max(0.4f, autoScale);
        if (effectiveScale != scale) recalcLayout();

        // --- Monitor chrome ---
        // Bezel
        gfx.fill(termX - border, termY - headerH - border,
                  termX + renderW + border, termY + renderH + border, 0xFF222222);
        // Inner bezel edge
        gfx.fill(termX - 1, termY - 1,
                  termX + renderW + 1, termY + renderH + 1, 0xFF111111);
        // Header bar
        gfx.fill(termX - border, termY - headerH - border,
                  termX + renderW + border, termY - 1, 0xFF1A1A2E);
        // Header text
        String headerText = os.getLabel() + " [" + os.getComputerId().toString().substring(0, 8) + "]";
        float hScale = Math.max(0.45f, Math.min(0.6f, scale * 0.35f));
        gfx.pose().pushPose();
        gfx.pose().scale(hScale, hScale, 1.0f);
        gfx.drawString(this.font, headerText,
                (int)((termX + 4) / hScale), (int)((termY - headerH + 2) / hScale), 0xAABBCC, false);
        gfx.pose().popPose();
        // Power indicator
        int indicatorColor = os.isRunning() ? 0xFF00FF00 : (os.isBooting() ? 0xFFFFAA00 : 0xFF555555);
        gfx.fill(termX + renderW - 8, termY - headerH + 2,
                  termX + renderW - 2, termY - headerH + 8, indicatorColor);

        // --- Upload framebuffer to GPU texture ---
        uploadPixelBuffer();

        // --- Blit the texture (single draw call!) ---
        gfx.blit(textureLoc,
                termX, termY, 0.0f, 0.0f,
                renderW, renderH, renderW, renderH);

        // --- Cursor overlay ---
        if (os.isRunning() && os.getForegroundProgram() != null) {
            var fg = os.getForegroundProgram();
            if (fg.isLastCursorBlink() && (System.currentTimeMillis() / 500) % 2 == 0) {
                int cx = fg.getLastCursorX();
                int cy = fg.getLastCursorY();
                // Map cell coords to screen coords
                float cellW = (float) renderW / PixelBuffer.TEXT_COLS;
                float cellH = (float) renderH / PixelBuffer.TEXT_ROWS;
                int px = termX + (int)(cx * cellW);
                int py = termY + (int)((cy + 1) * cellH) - 2;
                gfx.fill(px, py, px + (int)cellW, py + 2, 0xFFFFFFFF);
            }
        }

        // --- Resize grip ---
        int gs = Math.max(6, Math.round(5 * scale));
        int gx = termX + renderW + border;
        int gy = termY + renderH + border;
        gfx.fill(gx - gs, gy - gs, gx, gy, 0xFF444444);
        gfx.fill(gx - gs + 1, gy - gs + 1, gx - 1, gy - 1, 0xFF666666);

        // --- Clipboard sync (program → MC system clipboard) ---
        String clipOut = os.consumeClipboard();
        if (clipOut != null && minecraft != null) {
            minecraft.keyboardHandler.setClipboard(clipOut);
        }
    }

    /**
     * Copy PixelBuffer data → NativeImage, then upload to GPU.
     * Converts from ARGB (PixelBuffer) to ABGR (NativeImage's OpenGL format).
     */
    private void uploadPixelBuffer() {
        PixelBuffer pb = os.getPixelBuffer();
        int[] pixels = pb.getPixels();
        for (int y = 0; y < FB_H; y++) {
            int off = y * FB_W;
            for (int x = 0; x < FB_W; x++) {
                int argb = pixels[off + x];
                // Convert ARGB → ABGR for OpenGL
                int a = argb & 0xFF000000;
                int r = (argb >> 16) & 0xFF;
                int g = argb & 0x0000FF00;
                int b = (argb << 16) & 0x00FF0000;
                nativeImage.setPixelRGBA(x, y, a | b | g | r);
            }
        }
        dynamicTexture.upload();
    }

    // --- Input handling ---

    /** Convert MC screen pixel coords to PixelBuffer pixel coords */
    private int[] screenToPixel(double mouseX, double mouseY) {
        int px = (int)((mouseX - termX) * FB_W / renderW);
        int py = (int)((mouseY - termY) * FB_H / renderH);
        if (px >= 0 && px < FB_W && py >= 0 && py < FB_H) {
            return new int[]{px, py};
        }
        return null;
    }

    /** Convert MC screen coords to terminal cell coords (for OS events) */
    private int[] screenToCell(double mouseX, double mouseY) {
        int[] px = screenToPixel(mouseX, mouseY);
        if (px != null) {
            return new int[]{px[0] / PixelBuffer.CELL_W, px[1] / PixelBuffer.CELL_H};
        }
        return null;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 84 && (modifiers & 2) != 0) { // Ctrl+T
            os.pushEvent(new OSEvent(OSEvent.Type.TERMINATE));
            return true;
        }
        if (keyCode == 82 && (modifiers & 2) != 0) { // Ctrl+R
            os.pushEvent(new OSEvent(OSEvent.Type.REBOOT));
            return true;
        }
        if (keyCode == 86 && (modifiers & 2) != 0) { // Ctrl+V
            String clipboard = this.minecraft.keyboardHandler.getClipboard();
            if (clipboard != null && !clipboard.isEmpty()) {
                os.pushEvent(new OSEvent(OSEvent.Type.PASTE, clipboard));
            }
            return true;
        }
        // Escape is forwarded to the OS so programs can handle it (e.g., dismiss dialogs,
        // exit test patterns). DesktopProgram shuts down the OS when nothing is open,
        // which triggers onClose() via the isShutdown() check in render().
        os.pushEvent(new OSEvent(OSEvent.Type.KEY, keyCode, 0, modifiers));
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        os.pushEvent(new OSEvent(OSEvent.Type.KEY_UP, keyCode));
        return true;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (c >= 32 && c < 127) {
            os.pushEvent(new OSEvent(OSEvent.Type.CHAR, String.valueOf(c)));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Resize grip
            int gripSize = Math.max(10, Math.round(8 * scale));
            int bx = termX + renderW + border;
            int by = termY + renderH + border;
            if (mouseX >= bx - gripSize && mouseX <= bx && mouseY >= by - gripSize && mouseY <= by) {
                resizing = true;
                return true;
            }
            // Header drag
            if (mouseX >= termX - border && mouseX <= termX + renderW + border &&
                mouseY >= termY - headerH - border && mouseY <= termY) {
                dragging = true;
                dragOffX = mouseX - termX;
                dragOffY = mouseY - termY;
                return true;
            }
        }
        // Send both cell and pixel coordinates to OS
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            int[] px = screenToPixel(mouseX, mouseY);
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_CLICK, button, cell[0], cell[1]));
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_CLICK_PX, button, px[0], px[1]));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) { dragging = false; return true; }
        if (resizing) { resizing = false; return true; }
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_UP, button, cell[0], cell[1]));
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            termX = (int)(mouseX - dragOffX);
            termY = (int)(mouseY - dragOffY);
            return true;
        }
        if (resizing) {
            float newRenderW = (float)(mouseX - termX - border);
            float newScale = newRenderW / FB_W;
            userScale = Math.max(0.3f, Math.min(3.0f, newScale));
            recalcLayout();
            return true;
        }
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_DRAG, button, cell[0], cell[1]));
            int[] pxCoord = screenToPixel(mouseX, mouseY);
            if (pxCoord != null) {
                os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_DRAG_PX, button, pxCoord[0], pxCoord[1]));
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizAmount, double vertAmount) {
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            int dir = vertAmount > 0 ? -1 : 1;
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_SCROLL, dir, cell[0], cell[1]));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizAmount, vertAmount);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        // Clean up GPU resources
        if (textureLoc != null) {
            Minecraft.getInstance().getTextureManager().release(textureLoc);
            textureLoc = null;
        }
        dynamicTexture = null;
        nativeImage = null;
    }
}

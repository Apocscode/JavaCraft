package com.apocscode.byteblock.client;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.TerminalBuffer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Minecraft Screen that renders the ByteBlock computer terminal.
 * Maps keyboard + mouse events into OS events.
 * Renders the TerminalBuffer as a grid of colored character cells.
 */
public class ComputerScreen extends Screen {

    private static final int BASE_CELL_W = 6;
    private static final int BASE_CELL_H = 9;

    private final JavaOS os;
    private float scale;
    private int cellW, cellH, border, headerH;
    private int termX, termY;
    private boolean dragging, resizing;
    private double dragOffX, dragOffY;
    private float userScale;
    private boolean positioned;

    public ComputerScreen(JavaOS os) {
        super(Component.literal("ByteBlock Computer"));
        this.os = os;
    }

    @Override
    protected void init() {
        super.init();
        recalcLayout();
    }

    private void recalcLayout() {
        scale = userScale > 0 ? userScale : os.getTextScale();
        cellW = Math.round(BASE_CELL_W * scale);
        cellH = Math.round(BASE_CELL_H * scale);
        border = Math.round(6 * scale);
        headerH = Math.round(10 * scale);
        if (!positioned) {
            int gridW = TerminalBuffer.WIDTH * cellW;
            int gridH = TerminalBuffer.HEIGHT * cellH;
            termX = ((this.width - gridW) / 2) & ~1;
            termY = (((this.height - gridH - headerH) / 2) & ~1) + headerH;
            positioned = true;
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Dark background
        renderBackground(gfx, mouseX, mouseY, partialTick);

        // Dynamic scale update
        float effectiveScale = userScale > 0 ? userScale : os.getTextScale();
        if (effectiveScale != scale) recalcLayout();

        int gridW = TerminalBuffer.WIDTH * cellW;
        int gridH = TerminalBuffer.HEIGHT * cellH;

        // Monitor bezel (dark gray border)
        gfx.fill(termX - border, termY - border - headerH,
                  termX + gridW + border, termY + gridH + border, 0xFF333333);

        // Header bar (computer label)
        gfx.fill(termX - 4, termY - headerH,
                  termX + gridW + 4, termY, 0xFF222222);
        String headerText = os.getLabel() + " [" + os.getComputerId().toString().substring(0, 8) + "]";
        gfx.pose().pushPose();
        gfx.pose().scale(scale, scale, 1.0f);
        gfx.drawString(this.font, headerText,
                (int)(termX / scale), (int)((termY - headerH + 4) / scale), 0xAAAAAA, false);
        gfx.pose().popPose();

        // Render terminal grid
        TerminalBuffer terminal = os.getTerminal();

        // Pass 1: Background cells (no scaling needed)
        for (int y = 0; y < TerminalBuffer.HEIGHT; y++) {
            for (int x = 0; x < TerminalBuffer.WIDTH; x++) {
                int px = termX + x * cellW;
                int py = termY + y * cellH;
                int bgColor = TerminalBuffer.PALETTE[terminal.getBg(x, y)];
                gfx.fill(px, py, px + cellW, py + cellH, bgColor);
            }
        }

        // Pass 2: Characters (scaled to fill cells)
        gfx.pose().pushPose();
        gfx.pose().scale(scale, scale, 1.0f);
        for (int y = 0; y < TerminalBuffer.HEIGHT; y++) {
            for (int x = 0; x < TerminalBuffer.WIDTH; x++) {
                char c = terminal.getChar(x, y);
                if (c != ' ') {
                    int fgColor = TerminalBuffer.PALETTE[terminal.getFg(x, y)] & 0x00FFFFFF;
                    int sx = (int)((termX + x * cellW) / scale);
                    int sy = (int)((termY + y * cellH) / scale);
                    gfx.drawString(this.font, String.valueOf(c), sx, sy, fgColor, false);
                }
            }
        }
        gfx.pose().popPose();

        // Cursor blink (scaled to match cells)
        if (terminal.isCursorBlink() && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = termX + terminal.getCursorX() * cellW;
            int cy = termY + terminal.getCursorY() * cellH;
            gfx.fill(cx, cy + cellH - 3, cx + cellW, cy + cellH, 0xFFFFFFFF);
        }

        // Power indicator
        int indicatorColor = os.isRunning() ? 0xFF00FF00 : (os.isBooting() ? 0xFFFFAA00 : 0xFF555555);
        gfx.fill(termX + gridW - 10, termY - headerH + 5,
                  termX + gridW - 4, termY - headerH + 13, indicatorColor);

        // Resize grip (bottom-right corner of bezel)
        int gs = Math.max(6, Math.round(6 * scale));
        int gx = termX + gridW + border;
        int gy = termY + gridH + border;
        gfx.fill(gx - gs, gy - gs, gx, gy, 0xFF555555);
        gfx.fill(gx - gs + 1, gy - gs + 1, gx - 1, gy - 1, 0xFF777777);
    }

    // --- Input handling ---

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Ctrl+T = terminate
        if (keyCode == 84 && (modifiers & 2) != 0) { // T + Ctrl
            os.pushEvent(new OSEvent(OSEvent.Type.TERMINATE));
            return true;
        }
        // Ctrl+R = reboot
        if (keyCode == 82 && (modifiers & 2) != 0) {
            os.pushEvent(new OSEvent(OSEvent.Type.REBOOT));
            return true;
        }
        // Ctrl+S = shutdown
        if (keyCode == 83 && (modifiers & 2) != 0) {
            os.pushEvent(new OSEvent(OSEvent.Type.SHUTDOWN));
            return true;
        }
        // Ctrl+V = paste
        if (keyCode == 86 && (modifiers & 2) != 0) {
            String clipboard = this.minecraft.keyboardHandler.getClipboard();
            if (clipboard != null && !clipboard.isEmpty()) {
                os.pushEvent(new OSEvent(OSEvent.Type.PASTE, clipboard));
            }
            return true;
        }

        // Escape closes the screen (back to game)
        if (keyCode == 256) {
            this.onClose();
            return true;
        }

        os.pushEvent(new OSEvent(OSEvent.Type.KEY, keyCode, 0));
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
            int gridW = TerminalBuffer.WIDTH * cellW;
            int gridH = TerminalBuffer.HEIGHT * cellH;
            // Resize grip: bottom-right corner of bezel
            int gripSize = Math.max(10, Math.round(10 * scale));
            int bx = termX + gridW + border;
            int by = termY + gridH + border;
            if (mouseX >= bx - gripSize && mouseX <= bx && mouseY >= by - gripSize && mouseY <= by) {
                resizing = true;
                return true;
            }
            // Header bar drag
            if (mouseX >= termX - border && mouseX <= termX + gridW + border &&
                mouseY >= termY - headerH - border && mouseY <= termY) {
                dragging = true;
                dragOffX = mouseX - termX;
                dragOffY = mouseY - termY;
                return true;
            }
        }
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_CLICK, button, cell[0], cell[1]));
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
            float newCellW = (float)(mouseX - termX - border) / TerminalBuffer.WIDTH;
            float newScale = newCellW / BASE_CELL_W;
            userScale = Math.max(0.75f, Math.min(4.0f, newScale));
            recalcLayout();
            return true;
        }
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_DRAG, button, cell[0], cell[1]));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizAmount, double vertAmount) {
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            int dir = vertAmount > 0 ? -1 : 1; // scroll up = -1, down = 1
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_SCROLL, dir, cell[0], cell[1]));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizAmount, vertAmount);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause the game while using computer
    }

    /**
     * Convert screen pixel coordinates to terminal cell coordinates.
     * Returns null if outside the terminal grid.
     */
    private int[] screenToCell(double mouseX, double mouseY) {
        int cx = (int)((mouseX - termX) / cellW);
        int cy = (int)((mouseY - termY) / cellH);
        if (cx >= 0 && cx < TerminalBuffer.WIDTH && cy >= 0 && cy < TerminalBuffer.HEIGHT) {
            return new int[]{cx, cy};
        }
        return null;
    }
}

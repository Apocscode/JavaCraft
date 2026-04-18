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

    private static final int CELL_WIDTH = 12;   // pixels per character cell (doubled)
    private static final int CELL_HEIGHT = 18;  // pixels per character cell (doubled)
    private static final int BORDER = 12;       // border around terminal area
    private static final int HEADER_HEIGHT = 20;
    private static final float SCALE = 2.0f;    // text scale factor

    private final JavaOS os;
    private int termX, termY; // Top-left of terminal grid in screen coords

    public ComputerScreen(JavaOS os) {
        super(Component.literal("ByteBlock Computer"));
        this.os = os;
    }

    @Override
    protected void init() {
        super.init();
        // Center the terminal on screen (force even coords for clean 2x scaling)
        int gridW = TerminalBuffer.WIDTH * CELL_WIDTH;
        int gridH = TerminalBuffer.HEIGHT * CELL_HEIGHT;
        termX = ((this.width - gridW) / 2) & ~1;
        termY = (((this.height - gridH - HEADER_HEIGHT) / 2) & ~1) + HEADER_HEIGHT;
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Dark background
        renderBackground(gfx, mouseX, mouseY, partialTick);

        int gridW = TerminalBuffer.WIDTH * CELL_WIDTH;
        int gridH = TerminalBuffer.HEIGHT * CELL_HEIGHT;

        // Monitor bezel (dark gray border)
        gfx.fill(termX - BORDER, termY - BORDER - HEADER_HEIGHT,
                  termX + gridW + BORDER, termY + gridH + BORDER, 0xFF333333);

        // Header bar (computer label)
        gfx.fill(termX - 4, termY - HEADER_HEIGHT,
                  termX + gridW + 4, termY, 0xFF222222);
        String headerText = os.getLabel() + " [" + os.getComputerId().toString().substring(0, 8) + "]";
        gfx.pose().pushPose();
        gfx.pose().scale(SCALE, SCALE, 1.0f);
        gfx.drawString(this.font, headerText,
                (int)(termX / SCALE), (int)((termY - HEADER_HEIGHT + 4) / SCALE), 0xAAAAAA, false);
        gfx.pose().popPose();

        // Render terminal grid
        TerminalBuffer terminal = os.getTerminal();

        // Pass 1: Background cells (no scaling needed)
        for (int y = 0; y < TerminalBuffer.HEIGHT; y++) {
            for (int x = 0; x < TerminalBuffer.WIDTH; x++) {
                int px = termX + x * CELL_WIDTH;
                int py = termY + y * CELL_HEIGHT;
                int bgColor = TerminalBuffer.PALETTE[terminal.getBg(x, y)];
                gfx.fill(px, py, px + CELL_WIDTH, py + CELL_HEIGHT, bgColor);
            }
        }

        // Pass 2: Characters (scaled 2x to fill doubled cells)
        gfx.pose().pushPose();
        gfx.pose().scale(SCALE, SCALE, 1.0f);
        for (int y = 0; y < TerminalBuffer.HEIGHT; y++) {
            for (int x = 0; x < TerminalBuffer.WIDTH; x++) {
                char c = terminal.getChar(x, y);
                if (c != ' ') {
                    int fgColor = TerminalBuffer.PALETTE[terminal.getFg(x, y)] & 0x00FFFFFF;
                    int sx = (int)((termX + x * CELL_WIDTH) / SCALE);
                    int sy = (int)((termY + y * CELL_HEIGHT) / SCALE);
                    gfx.drawString(this.font, String.valueOf(c), sx, sy, fgColor, false);
                }
            }
        }
        gfx.pose().popPose();

        // Cursor blink (scaled to match doubled cells)
        if (terminal.isCursorBlink() && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = termX + terminal.getCursorX() * CELL_WIDTH;
            int cy = termY + terminal.getCursorY() * CELL_HEIGHT;
            gfx.fill(cx, cy + CELL_HEIGHT - 3, cx + CELL_WIDTH, cy + CELL_HEIGHT, 0xFFFFFFFF);
        }

        // Power indicator
        int indicatorColor = os.isRunning() ? 0xFF00FF00 : (os.isBooting() ? 0xFFFFAA00 : 0xFF555555);
        gfx.fill(termX + gridW - 10, termY - HEADER_HEIGHT + 5,
                  termX + gridW - 4, termY - HEADER_HEIGHT + 13, indicatorColor);
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
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_CLICK, button, cell[0], cell[1]));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_UP, button, cell[0], cell[1]));
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
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
        int cx = (int)((mouseX - termX) / CELL_WIDTH);
        int cy = (int)((mouseY - termY) / CELL_HEIGHT);
        if (cx >= 0 && cx < TerminalBuffer.WIDTH && cy >= 0 && cy < TerminalBuffer.HEIGHT) {
            return new int[]{cx, cy};
        }
        return null;
    }
}

package com.apocscode.byteblock.computer;

/**
 * Pixel-level framebuffer for the ByteBlock computer display.
 * 640x400 pixels, ARGB format. Programs draw directly into this buffer,
 * which is uploaded to a GPU texture each frame by ComputerScreen.
 *
 * At 8x16 pixels per character cell, this gives an 80x25 text grid
 * with full pixel-level freedom for graphical programs.
 */
public class PixelBuffer {

    public static final int SCREEN_W = 640;
    public static final int SCREEN_H = 400;
    public static final int CELL_W = BitmapFont.CHAR_W;  // 8
    public static final int CELL_H = BitmapFont.CHAR_H;  // 16
    public static final int TEXT_COLS = SCREEN_W / CELL_W; // 80
    public static final int TEXT_ROWS = SCREEN_H / CELL_H; // 25

    private final int width;
    private final int height;
    private final int[] pixels;

    // Cursor state for text-mode drawing
    private int cursorX, cursorY;
    private int textFg = 0xFFFFFFFF;
    private int textBg = 0xFF000000;

    public PixelBuffer() {
        this(SCREEN_W, SCREEN_H);
    }

    public PixelBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    // ── Core pixel operations ───────────────────────────────

    public void clear(int color) {
        java.util.Arrays.fill(pixels, color);
    }

    public void clear() {
        clear(0xFF000000);
    }

    public void setPixel(int x, int y, int argb) {
        if (x >= 0 && x < width && y >= 0 && y < height) {
            pixels[y * width + x] = argb;
        }
    }

    public int getPixel(int x, int y) {
        if (x >= 0 && x < width && y >= 0 && y < height) {
            return pixels[y * width + x];
        }
        return 0;
    }

    public int[] getPixels() { return pixels; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    // ── Shape drawing ───────────────────────────────────────

    public void fillRect(int x, int y, int w, int h, int color) {
        int x1 = Math.max(0, x);
        int y1 = Math.max(0, y);
        int x2 = Math.min(width, x + w);
        int y2 = Math.min(height, y + h);
        for (int py = y1; py < y2; py++) {
            int off = py * width;
            for (int px = x1; px < x2; px++) {
                pixels[off + px] = color;
            }
        }
    }

    public void drawRect(int x, int y, int w, int h, int color) {
        drawHLine(x, x + w - 1, y, color);
        drawHLine(x, x + w - 1, y + h - 1, color);
        drawVLine(x, y, y + h - 1, color);
        drawVLine(x + w - 1, y, y + h - 1, color);
    }

    public void drawHLine(int x1, int x2, int y, int color) {
        if (y < 0 || y >= height) return;
        int left = Math.max(0, Math.min(x1, x2));
        int right = Math.min(width - 1, Math.max(x1, x2));
        int off = y * width;
        for (int x = left; x <= right; x++) {
            pixels[off + x] = color;
        }
    }

    public void drawVLine(int x, int y1, int y2, int color) {
        if (x < 0 || x >= width) return;
        int top = Math.max(0, Math.min(y1, y2));
        int bot = Math.min(height - 1, Math.max(y1, y2));
        for (int y = top; y <= bot; y++) {
            pixels[y * width + x] = color;
        }
    }

    public void drawLine(int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            setPixel(x0, y0, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    // Rounded rectangle
    public void fillRoundRect(int x, int y, int w, int h, int r, int color) {
        // Clamp radius
        r = Math.min(r, Math.min(w / 2, h / 2));
        // Center fill (no corners)
        fillRect(x + r, y, w - 2 * r, h, color);
        fillRect(x, y + r, r, h - 2 * r, color);
        fillRect(x + w - r, y + r, r, h - 2 * r, color);
        // Rounded corners
        fillCircleQuad(x + r, y + r, r, color, 0);         // top-left
        fillCircleQuad(x + w - r - 1, y + r, r, color, 1); // top-right
        fillCircleQuad(x + r, y + h - r - 1, r, color, 2); // bottom-left
        fillCircleQuad(x + w - r - 1, y + h - r - 1, r, color, 3); // bottom-right
    }

    private void fillCircleQuad(int cx, int cy, int r, int color, int quad) {
        for (int dy = 0; dy <= r; dy++) {
            for (int dx = 0; dx <= r; dx++) {
                if (dx * dx + dy * dy <= r * r) {
                    switch (quad) {
                        case 0 -> setPixel(cx - dx, cy - dy, color);
                        case 1 -> setPixel(cx + dx, cy - dy, color);
                        case 2 -> setPixel(cx - dx, cy + dy, color);
                        case 3 -> setPixel(cx + dx, cy + dy, color);
                    }
                }
            }
        }
    }

    // ── Text rendering (using BitmapFont) ───────────────────

    /**
     * Draw a single character at pixel position using the embedded bitmap font.
     */
    public void drawChar(int px, int py, char c, int fgColor) {
        byte[] glyph = BitmapFont.getGlyph(c);
        if (glyph == null) return;
        for (int row = 0; row < BitmapFont.CHAR_H; row++) {
            int ry = py + row;
            if (ry < 0 || ry >= height) continue;
            for (int col = 0; col < BitmapFont.CHAR_W; col++) {
                int rx = px + col;
                if (rx < 0 || rx >= width) continue;
                if (BitmapFont.isPixelSet(glyph, col, row)) {
                    pixels[ry * width + rx] = fgColor;
                }
            }
        }
    }

    /**
     * Draw a string at pixel position. No wrapping.
     */
    public void drawString(int px, int py, String text, int fgColor) {
        for (int i = 0; i < text.length(); i++) {
            drawChar(px + i * BitmapFont.CHAR_W, py, text.charAt(i), fgColor);
        }
    }

    /**
     * Draw a string with background color (fills cell background first).
     */
    public void drawStringBg(int px, int py, String text, int fgColor, int bgColor) {
        for (int i = 0; i < text.length(); i++) {
            int cx = px + i * BitmapFont.CHAR_W;
            fillRect(cx, py, BitmapFont.CHAR_W, BitmapFont.CHAR_H, bgColor);
            drawChar(cx, py, text.charAt(i), fgColor);
        }
    }

    /**
     * Draw a string centered horizontally within a region.
     */
    public void drawStringCentered(int regionX, int regionW, int py, String text, int fgColor) {
        int tw = text.length() * BitmapFont.CHAR_W;
        int px = regionX + (regionW - tw) / 2;
        drawString(px, py, text, fgColor);
    }

    /**
     * Draw a string right-aligned within a region.
     */
    public void drawStringRight(int regionRight, int py, String text, int fgColor) {
        int tw = text.length() * BitmapFont.CHAR_W;
        drawString(regionRight - tw, py, text, fgColor);
    }

    // ── Text-mode cursor API (terminal compatibility) ───────

    public void setTextColor(int paletteIdx) {
        if (paletteIdx >= 0 && paletteIdx < TerminalBuffer.PALETTE.length) {
            this.textFg = TerminalBuffer.PALETTE[paletteIdx];
        }
    }

    public void setBackgroundColor(int paletteIdx) {
        if (paletteIdx >= 0 && paletteIdx < TerminalBuffer.PALETTE.length) {
            this.textBg = TerminalBuffer.PALETTE[paletteIdx];
        }
    }

    public void setTextColorRGB(int argb) { this.textFg = argb; }
    public void setBackgroundColorRGB(int argb) { this.textBg = argb; }

    /**
     * Write a text-mode string at cell position (column, row).
     * Uses current textFg/textBg colors. Fills cell backgrounds.
     */
    public void writeAt(int col, int row, String text) {
        int px = col * CELL_W;
        int py = row * CELL_H;
        drawStringBg(px, py, text, textFg, textBg);
    }

    /**
     * Fill a rectangle of text cells with a character + current colors.
     */
    public void fillCells(int col1, int row1, int col2, int row2, char c) {
        for (int row = row1; row <= row2; row++) {
            for (int col = col1; col <= col2; col++) {
                int px = col * CELL_W;
                int py = row * CELL_H;
                fillRect(px, py, CELL_W, CELL_H, textBg);
                if (c != ' ') drawChar(px, py, c, textFg);
            }
        }
    }

    // ── TerminalBuffer rasterization (backward compat) ──────

    /**
     * Rasterize a TerminalBuffer onto this pixel buffer.
     * This is the bridge for programs that still use the old text-mode API.
     */
    public void rasterizeTerminal(TerminalBuffer tb) {
        for (int row = 0; row < TerminalBuffer.HEIGHT && row < TEXT_ROWS; row++) {
            for (int col = 0; col < TerminalBuffer.WIDTH && col < TEXT_COLS; col++) {
                int px = col * CELL_W;
                int py = row * CELL_H;
                int bg = TerminalBuffer.PALETTE[tb.getBg(col, row)];
                fillRect(px, py, CELL_W, CELL_H, bg);
                char c = tb.getChar(col, row);
                if (c != ' ') {
                    int fg = TerminalBuffer.PALETTE[tb.getFg(col, row)];
                    drawChar(px, py, c, fg);
                }
            }
        }
    }

    // ── Buffer compositing ──────────────────────────────────

    /**
     * Blit (copy) a region from another PixelBuffer into this buffer.
     * @param src source buffer
     * @param sx source x
     * @param sy source y
     * @param dx dest x in this buffer
     * @param dy dest y in this buffer
     * @param w width to copy
     * @param h height to copy
     */
    public void blit(PixelBuffer src, int sx, int sy, int dx, int dy, int w, int h) {
        for (int row = 0; row < h; row++) {
            int srcY = sy + row;
            int dstY = dy + row;
            if (srcY < 0 || srcY >= src.height || dstY < 0 || dstY >= this.height) continue;
            for (int col = 0; col < w; col++) {
                int srcX = sx + col;
                int dstX = dx + col;
                if (srcX < 0 || srcX >= src.width || dstX < 0 || dstX >= this.width) continue;
                this.pixels[dstY * this.width + dstX] = src.pixels[srcY * src.width + srcX];
            }
        }
    }

    /**
     * Convert screen pixel coordinates to text cell coordinates.
     */
    public static int pixelToCol(int px) { return px / CELL_W; }
    public static int pixelToRow(int py) { return py / CELL_H; }
    public static int colToPixel(int col) { return col * CELL_W; }
    public static int rowToPixel(int row) { return row * CELL_H; }
}

package com.apocscode.byteblock.computer;

/**
 * Color character grid buffer for the computer terminal.
 * 51 columns x 19 rows, each cell has a character, foreground color, and background color.
 * Uses a 16-color palette matching Minecraft's chat color codes.
 */
public class TerminalBuffer {

    public static final int WIDTH = 51;
    public static final int HEIGHT = 19;
    public static final int DEFAULT_FG = 0;   // white
    public static final int DEFAULT_BG = 15;  // black

    // 16-color palette (ARGB) matching CC/MC colors
    public static final int[] PALETTE = {
        0xFFFFFFFF, // 0  white
        0xFFFFAA00, // 1  orange
        0xFFFF55FF, // 2  magenta
        0xFF5555FF, // 3  light blue
        0xFFFFFF55, // 4  yellow
        0xFF55FF55, // 5  lime
        0xFFFF5555, // 6  pink
        0xFF555555, // 7  gray
        0xFFAAAAAA, // 8  light gray
        0xFF55FFFF, // 9  cyan
        0xFFAA00AA, // 10 purple
        0xFF0000AA, // 11 blue
        0xFF7F3300, // 12 brown
        0xFF00AA00, // 13 green
        0xFFFF5555, // 14 red
        0xFF000000  // 15 black
    };

    private final char[][] chars;
    private final int[][] fg;
    private final int[][] bg;
    private int cursorX;
    private int cursorY;
    private int currentFg;
    private int currentBg;
    private boolean cursorBlink;

    public TerminalBuffer() {
        chars = new char[HEIGHT][WIDTH];
        fg = new int[HEIGHT][WIDTH];
        bg = new int[HEIGHT][WIDTH];
        cursorX = 0;
        cursorY = 0;
        currentFg = DEFAULT_FG;
        currentBg = DEFAULT_BG;
        cursorBlink = true;
        clear();
    }

    public void clear() {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                chars[y][x] = ' ';
                fg[y][x] = currentFg;
                bg[y][x] = currentBg;
            }
        }
        cursorX = 0;
        cursorY = 0;
    }

    public void clearLine() {
        for (int x = 0; x < WIDTH; x++) {
            chars[cursorY][x] = ' ';
            fg[cursorY][x] = currentFg;
            bg[cursorY][x] = currentBg;
        }
    }

    public void write(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (cursorX >= WIDTH) break;
            chars[cursorY][cursorX] = text.charAt(i);
            fg[cursorY][cursorX] = currentFg;
            bg[cursorY][cursorX] = currentBg;
            cursorX++;
        }
    }

    public void print(String text) {
        // Split on newlines and process each segment
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            write(lines[i]);
            if (i < lines.length - 1) {
                newLine();
            }
        }
    }

    public void println(String text) {
        print(text);
        newLine();
    }

    public void newLine() {
        cursorX = 0;
        cursorY++;
        if (cursorY >= HEIGHT) {
            scroll();
            cursorY = HEIGHT - 1;
        }
    }

    public void scroll() {
        // Move all rows up by one
        for (int y = 1; y < HEIGHT; y++) {
            System.arraycopy(chars[y], 0, chars[y - 1], 0, WIDTH);
            System.arraycopy(fg[y], 0, fg[y - 1], 0, WIDTH);
            System.arraycopy(bg[y], 0, bg[y - 1], 0, WIDTH);
        }
        // Clear last row
        for (int x = 0; x < WIDTH; x++) {
            chars[HEIGHT - 1][x] = ' ';
            fg[HEIGHT - 1][x] = currentFg;
            bg[HEIGHT - 1][x] = currentBg;
        }
    }

    public void scrollDown() {
        for (int y = HEIGHT - 2; y >= 0; y--) {
            System.arraycopy(chars[y], 0, chars[y + 1], 0, WIDTH);
            System.arraycopy(fg[y], 0, fg[y + 1], 0, WIDTH);
            System.arraycopy(bg[y], 0, bg[y + 1], 0, WIDTH);
        }
        for (int x = 0; x < WIDTH; x++) {
            chars[0][x] = ' ';
            fg[0][x] = currentFg;
            bg[0][x] = currentBg;
        }
    }

    // Fill a rectangular region
    public void fillRect(int x1, int y1, int x2, int y2, char c) {
        for (int y = Math.max(0, y1); y <= Math.min(HEIGHT - 1, y2); y++) {
            for (int x = Math.max(0, x1); x <= Math.min(WIDTH - 1, x2); x++) {
                chars[y][x] = c;
                fg[y][x] = currentFg;
                bg[y][x] = currentBg;
            }
        }
    }

    // Draw a horizontal line
    public void hLine(int x1, int x2, int y, char c) {
        if (y < 0 || y >= HEIGHT) return;
        for (int x = Math.max(0, x1); x <= Math.min(WIDTH - 1, x2); x++) {
            chars[y][x] = c;
            fg[y][x] = currentFg;
            bg[y][x] = currentBg;
        }
    }

    // Write at specific position without moving cursor
    public void writeAt(int x, int y, String text) {
        if (y < 0 || y >= HEIGHT) return;
        for (int i = 0; i < text.length(); i++) {
            int px = x + i;
            if (px < 0) continue;
            if (px >= WIDTH) break;
            chars[y][px] = text.charAt(i);
            fg[y][px] = currentFg;
            bg[y][px] = currentBg;
        }
    }

    // Getters for renderer
    public char getChar(int x, int y) { return chars[y][x]; }
    public int getFg(int x, int y) { return fg[y][x]; }
    public int getBg(int x, int y) { return bg[y][x]; }

    public int getCursorX() { return cursorX; }
    public int getCursorY() { return cursorY; }
    public void setCursorPos(int x, int y) {
        this.cursorX = Math.max(0, Math.min(WIDTH - 1, x));
        this.cursorY = Math.max(0, Math.min(HEIGHT - 1, y));
    }

    public int getCurrentFg() { return currentFg; }
    public int getCurrentBg() { return currentBg; }
    public void setTextColor(int color) { this.currentFg = color & 0xF; }
    public void setBackgroundColor(int color) { this.currentBg = color & 0xF; }

    public boolean isCursorBlink() { return cursorBlink; }
    public void setCursorBlink(boolean blink) { this.cursorBlink = blink; }

    public int getWidth() { return WIDTH; }
    public int getHeight() { return HEIGHT; }
}

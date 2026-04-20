package com.apocscode.byteblock.computer;

/**
 * Procedural 16x16 pixel-art system icons for ByteOS.
 * All icons are drawn directly to a PixelBuffer at the given coordinates.
 */
public final class SystemIcons {

    private SystemIcons() {}

    public static final int SIZE = 16;

    // Icon types
    public enum Icon {
        COMPUTER, FOLDER, FOLDER_OPEN, FILE, FILE_TEXT, FILE_CODE, FILE_IMAGE,
        RECYCLE_BIN, RECYCLE_BIN_FULL, TERMINAL, SETTINGS, PAINT, PUZZLE,
        CALCULATOR, TASK_MANAGER, EXPLORER, NOTEPAD, LUA_MOON,
        SHUTDOWN, RESTART, BACK, FORWARD, UP, SEARCH,
        CUT, COPY, PASTE, DELETE, RENAME, NEW_FILE, NEW_FOLDER,
        DRIVE, DOCUMENTS, DOWNLOADS, PICTURES, NETWORK, BLUETOOTH
    }

    /** Draw a 16x16 icon at (x, y) in the pixel buffer */
    public static void draw(PixelBuffer pb, int x, int y, Icon icon) {
        switch (icon) {
            case COMPUTER       -> drawComputer(pb, x, y);
            case FOLDER         -> drawFolder(pb, x, y, false);
            case FOLDER_OPEN    -> drawFolder(pb, x, y, true);
            case FILE           -> drawFile(pb, x, y, 0xFFFFFFFF);
            case FILE_TEXT      -> drawFileText(pb, x, y);
            case FILE_CODE      -> drawFileCode(pb, x, y);
            case FILE_IMAGE     -> drawFileImage(pb, x, y);
            case RECYCLE_BIN    -> drawRecycleBin(pb, x, y, false);
            case RECYCLE_BIN_FULL -> drawRecycleBin(pb, x, y, true);
            case TERMINAL       -> drawTerminal(pb, x, y);
            case SETTINGS       -> drawSettings(pb, x, y);
            case PAINT          -> drawPaint(pb, x, y);
            case PUZZLE         -> drawPuzzle(pb, x, y);
            case CALCULATOR     -> drawCalculator(pb, x, y);
            case TASK_MANAGER   -> drawTaskManager(pb, x, y);
            case EXPLORER       -> drawExplorer(pb, x, y);
            case NOTEPAD        -> drawFileText(pb, x, y);
            case LUA_MOON       -> drawLuaMoon(pb, x, y);
            case SHUTDOWN       -> drawPower(pb, x, y, 0xFFFF4444);
            case RESTART        -> drawRestart(pb, x, y);
            case BACK           -> drawArrow(pb, x, y, -1, 0);
            case FORWARD        -> drawArrow(pb, x, y, 1, 0);
            case UP             -> drawArrow(pb, x, y, 0, -1);
            case SEARCH         -> drawSearch(pb, x, y);
            case CUT            -> drawCut(pb, x, y);
            case COPY           -> drawCopy(pb, x, y);
            case PASTE          -> drawPaste(pb, x, y);
            case DELETE         -> drawDelete(pb, x, y);
            case RENAME         -> drawFileText(pb, x, y);
            case NEW_FILE       -> drawNewFile(pb, x, y);
            case NEW_FOLDER     -> drawNewFolder(pb, x, y);
            case DRIVE          -> drawDrive(pb, x, y);
            case DOCUMENTS      -> drawDocuments(pb, x, y);
            case DOWNLOADS      -> drawDownloads(pb, x, y);
            case PICTURES       -> drawPictures(pb, x, y);
            case NETWORK        -> drawNetwork(pb, x, y);
            case BLUETOOTH      -> drawBluetooth(pb, x, y);
        }
    }

    /** Get icon for a file extension */
    public static Icon iconForFile(String name) {
        if (name == null) return Icon.FILE;
        if (name.endsWith("/")) return Icon.FOLDER;
        if (name.endsWith(".lua") || name.endsWith(".java") || name.endsWith(".pzl")) return Icon.FILE_CODE;
        if (name.endsWith(".pxl") || name.endsWith(".png") || name.endsWith(".bmp")) return Icon.FILE_IMAGE;
        if (name.endsWith(".txt") || name.endsWith(".cfg") || name.endsWith(".lnk")) return Icon.FILE_TEXT;
        return Icon.FILE;
    }

    /** Get icon for a special folder path */
    public static Icon iconForPath(String path) {
        if (path == null) return Icon.FOLDER;
        return switch (path) {
            case "/" -> Icon.COMPUTER;
            case "/Users/User/Desktop" -> Icon.COMPUTER;
            case "/Users/User/Documents" -> Icon.DOCUMENTS;
            case "/Users/User/Downloads" -> Icon.DOWNLOADS;
            case "/Users/User/Pictures" -> Icon.PICTURES;
            case "/Program Files" -> Icon.DRIVE;
            case "/Windows", "/Windows/System32" -> Icon.SETTINGS;
            case "/Recycle Bin" -> Icon.RECYCLE_BIN;
            case "/Windows/Temp" -> Icon.FOLDER;
            default -> Icon.FOLDER;
        };
    }

    // ── Individual icon renderers ────────────────────────────

    private static void drawComputer(PixelBuffer pb, int x, int y) {
        // Monitor body
        pb.fillRect(x + 2, y + 1, 12, 9, 0xFF2266AA);
        pb.fillRect(x + 3, y + 2, 10, 7, 0xFF1A1A2E);
        // Screen content (blue glow)
        pb.fillRect(x + 4, y + 3, 8, 5, 0xFF0D2137);
        pb.drawHLine(x + 5, x + 10, y + 4, 0xFF4488CC);
        pb.drawHLine(x + 5, x + 8, y + 6, 0xFF4488CC);
        // Stand
        pb.fillRect(x + 6, y + 10, 4, 2, 0xFF888888);
        pb.fillRect(x + 4, y + 12, 8, 2, 0xFF666666);
    }

    private static void drawFolder(PixelBuffer pb, int x, int y, boolean open) {
        int body = 0xFFE8B830;
        int tab = 0xFFD4A020;
        int dark = 0xFFC09018;
        // Tab
        pb.fillRect(x + 1, y + 3, 6, 2, tab);
        // Body
        if (open) {
            pb.fillRect(x + 1, y + 5, 14, 9, body);
            pb.fillRect(x + 1, y + 5, 14, 1, tab);
            pb.fillRect(x + 3, y + 7, 10, 1, 0xFFFFF8E0);
            pb.drawRect(x + 1, y + 5, 14, 9, dark);
        } else {
            pb.fillRect(x + 1, y + 5, 14, 9, body);
            pb.drawRect(x + 1, y + 5, 14, 9, dark);
            pb.drawHLine(x + 2, x + 13, y + 6, 0xFFFFF0C0);
        }
    }

    private static void drawFile(PixelBuffer pb, int x, int y, int bodyColor) {
        pb.fillRect(x + 3, y + 1, 10, 14, bodyColor);
        // Dog-ear fold
        pb.fillRect(x + 9, y + 1, 4, 4, 0xFFCCCCCC);
        for (int i = 0; i < 4; i++) {
            pb.drawHLine(x + 9 + i, x + 12, y + 1 + i, bodyColor);
        }
        pb.drawLine(x + 9, y + 1, x + 12, y + 4, 0xFFAAAAAA);
        pb.drawRect(x + 3, y + 1, 10, 14, 0xFFAAAAAA);
    }

    private static void drawFileText(PixelBuffer pb, int x, int y) {
        drawFile(pb, x, y, 0xFFFFFFFF);
        // Text lines
        pb.drawHLine(x + 5, x + 10, y + 6, 0xFF666666);
        pb.drawHLine(x + 5, x + 9, y + 8, 0xFF666666);
        pb.drawHLine(x + 5, x + 11, y + 10, 0xFF666666);
        pb.drawHLine(x + 5, x + 8, y + 12, 0xFF666666);
    }

    private static void drawFileCode(PixelBuffer pb, int x, int y) {
        drawFile(pb, x, y, 0xFFE8F0FF);
        // < > brackets
        pb.drawLine(x + 7, y + 7, x + 5, y + 9, 0xFF2266CC);
        pb.drawLine(x + 5, y + 9, x + 7, y + 11, 0xFF2266CC);
        pb.drawLine(x + 9, y + 7, x + 11, y + 9, 0xFF2266CC);
        pb.drawLine(x + 11, y + 9, x + 9, y + 11, 0xFF2266CC);
    }

    private static void drawFileImage(PixelBuffer pb, int x, int y) {
        drawFile(pb, x, y, 0xFFF0F0FF);
        // Mini landscape
        pb.fillRect(x + 5, y + 7, 6, 5, 0xFF88CCFF);
        pb.fillRect(x + 5, y + 10, 6, 2, 0xFF44AA44);
        // Sun
        pb.fillRect(x + 9, y + 7, 2, 2, 0xFFFFCC00);
    }

    private static void drawRecycleBin(PixelBuffer pb, int x, int y, boolean full) {
        int rim = 0xFF888888;
        int body = 0xFF666666;
        // Lid
        pb.fillRect(x + 3, y + 1, 10, 2, rim);
        pb.fillRect(x + 6, y + 0, 4, 1, rim);
        // Body (tapered)
        pb.fillRect(x + 4, y + 3, 8, 11, body);
        // Vertical stripes
        pb.drawVLine(x + 6, y + 4, y + 12, 0xFF555555);
        pb.drawVLine(x + 8, y + 4, y + 12, 0xFF555555);
        pb.drawVLine(x + 10, y + 4, y + 12, 0xFF555555);
        if (full) {
            // Paper sticking out
            pb.fillRect(x + 5, y + 1, 3, 3, 0xFFFFFFFF);
            pb.fillRect(x + 9, y + 2, 2, 2, 0xFFEEEECC);
        }
    }

    private static void drawTerminal(PixelBuffer pb, int x, int y) {
        // Black terminal window
        pb.fillRect(x + 1, y + 2, 14, 12, 0xFF1A1A2E);
        pb.drawRect(x + 1, y + 2, 14, 12, 0xFF444466);
        // Title bar
        pb.fillRect(x + 1, y + 2, 14, 2, 0xFF444466);
        // Prompt >_
        pb.drawHLine(x + 3, x + 6, y + 6, 0xFF22CC66);
        pb.drawHLine(x + 4, x + 6, y + 7, 0xFF22CC66);
        pb.drawHLine(x + 5, x + 6, y + 8, 0xFF22CC66);
        pb.fillRect(x + 8, y + 8, 3, 1, 0xFF22CC66);
        // Cursor blink
        pb.fillRect(x + 8, y + 10, 1, 2, 0xFFCCCCCC);
    }

    private static void drawSettings(PixelBuffer pb, int x, int y) {
        int gear = 0xFF888899;
        // Gear center
        pb.fillRect(x + 5, y + 5, 6, 6, gear);
        pb.fillRect(x + 6, y + 6, 4, 4, 0xFF444466);
        // Gear teeth (top, bottom, left, right)
        pb.fillRect(x + 6, y + 2, 4, 3, gear);
        pb.fillRect(x + 6, y + 11, 4, 3, gear);
        pb.fillRect(x + 2, y + 6, 3, 4, gear);
        pb.fillRect(x + 11, y + 6, 3, 4, gear);
        // Diagonal teeth
        pb.fillRect(x + 3, y + 3, 3, 3, gear);
        pb.fillRect(x + 10, y + 3, 3, 3, gear);
        pb.fillRect(x + 3, y + 10, 3, 3, gear);
        pb.fillRect(x + 10, y + 10, 3, 3, gear);
    }

    private static void drawPaint(PixelBuffer pb, int x, int y) {
        // Palette shape
        pb.fillRoundRect(x + 1, y + 2, 14, 12, 4, 0xFFE8D8C0);
        pb.drawRect(x + 1, y + 2, 14, 12, 0xFFAA9988);
        // Color dots
        pb.fillRect(x + 3, y + 4, 3, 3, 0xFFFF4444);
        pb.fillRect(x + 7, y + 4, 3, 3, 0xFF44AA44);
        pb.fillRect(x + 3, y + 9, 3, 3, 0xFF4444FF);
        pb.fillRect(x + 7, y + 9, 3, 3, 0xFFFFCC00);
        // Brush handle
        pb.drawLine(x + 11, y + 3, x + 14, y + 0, 0xFF885533);
        pb.drawLine(x + 12, y + 3, x + 15, y + 0, 0xFF885533);
    }

    private static void drawPuzzle(PixelBuffer pb, int x, int y) {
        int piece = 0xFF44AA88;
        // Main piece body
        pb.fillRect(x + 2, y + 4, 10, 8, piece);
        // Right tab
        pb.fillRect(x + 12, y + 6, 3, 4, piece);
        // Bottom tab
        pb.fillRect(x + 5, y + 12, 4, 3, piece);
        // Top notch
        pb.fillRect(x + 5, y + 4, 4, 2, 0xFF1A1A2E);
        pb.drawRect(x + 2, y + 4, 10, 8, 0xFF339977);
    }

    private static void drawCalculator(PixelBuffer pb, int x, int y) {
        // Body
        pb.fillRoundRect(x + 2, y + 1, 12, 14, 2, 0xFF334455);
        // Screen
        pb.fillRect(x + 4, y + 3, 8, 3, 0xFF88BBAA);
        // Buttons (3x3 grid)
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                pb.fillRect(x + 4 + c * 3, y + 8 + r * 2, 2, 1, 0xFF667788);
            }
        }
    }

    private static void drawTaskManager(PixelBuffer pb, int x, int y) {
        // Window frame
        pb.fillRect(x + 1, y + 1, 14, 14, 0xFF1A2A3A);
        pb.drawRect(x + 1, y + 1, 14, 14, 0xFF446688);
        pb.fillRect(x + 1, y + 1, 14, 2, 0xFF446688);
        // Bar chart
        pb.fillRect(x + 3, y + 10, 2, 4, 0xFF22CC66);
        pb.fillRect(x + 6, y + 7, 2, 7, 0xFF44AAFF);
        pb.fillRect(x + 9, y + 5, 2, 9, 0xFFFF8844);
        pb.fillRect(x + 12, y + 8, 2, 6, 0xFFCC44AA);
    }

    private static void drawExplorer(PixelBuffer pb, int x, int y) {
        // Folder base
        drawFolder(pb, x, y, false);
        // Magnifying glass overlay
        pb.fillRect(x + 9, y + 9, 4, 4, 0xFF88BBFF);
        pb.drawRect(x + 9, y + 9, 4, 4, 0xFF2255AA);
        pb.drawLine(x + 13, y + 13, x + 15, y + 15, 0xFF444444);
    }

    private static void drawLuaMoon(PixelBuffer pb, int x, int y) {
        // Crescent moon
        int moon = 0xFF3344AA;
        pb.fillRoundRect(x + 3, y + 2, 10, 12, 5, moon);
        // Cut-out for crescent
        pb.fillRoundRect(x + 6, y + 2, 10, 12, 5, 0x00000000);
        // Fallback: simple crescent shape
        for (int py = 0; py < 12; py++) {
            for (int px = 0; px < 10; px++) {
                int cx1 = px - 5, cy1 = py - 6;
                int cx2 = px - 2, cy2 = py - 6;
                boolean outer = (cx1 * cx1 + cy1 * cy1) <= 36;
                boolean inner = (cx2 * cx2 + cy2 * cy2) <= 25;
                if (outer && !inner) {
                    pb.setPixel(x + 3 + px, y + 2 + py, moon);
                }
            }
        }
    }

    private static void drawPower(PixelBuffer pb, int x, int y, int color) {
        // Power circle
        for (int angle = 30; angle < 330; angle += 5) {
            double rad = Math.toRadians(angle);
            int px = x + 8 + (int)(5 * Math.cos(rad));
            int py = y + 8 + (int)(5 * Math.sin(rad));
            pb.setPixel(px, py, color);
        }
        // Top line
        pb.drawVLine(x + 8, y + 2, y + 8, color);
    }

    private static void drawRestart(PixelBuffer pb, int x, int y) {
        int color = 0xFF44AAFF;
        // Circular arrow
        for (int angle = 30; angle < 330; angle += 5) {
            double rad = Math.toRadians(angle);
            int px = x + 8 + (int)(5 * Math.cos(rad));
            int py = y + 8 + (int)(5 * Math.sin(rad));
            pb.setPixel(px, py, color);
        }
        // Arrow tip at top
        pb.fillRect(x + 10, y + 2, 3, 1, color);
        pb.fillRect(x + 11, y + 3, 2, 1, color);
    }

    private static void drawArrow(PixelBuffer pb, int x, int y, int dx, int dy) {
        int color = 0xFFCCCCCC;
        if (dx < 0) { // Left
            pb.drawHLine(x + 3, x + 13, y + 8, color);
            pb.drawLine(x + 3, y + 8, x + 7, y + 4, color);
            pb.drawLine(x + 3, y + 8, x + 7, y + 12, color);
        } else if (dx > 0) { // Right
            pb.drawHLine(x + 3, x + 13, y + 8, color);
            pb.drawLine(x + 13, y + 8, x + 9, y + 4, color);
            pb.drawLine(x + 13, y + 8, x + 9, y + 12, color);
        } else if (dy < 0) { // Up
            pb.drawVLine(x + 8, y + 3, y + 13, color);
            pb.drawLine(x + 8, y + 3, x + 4, y + 7, color);
            pb.drawLine(x + 8, y + 3, x + 12, y + 7, color);
        }
    }

    private static void drawSearch(PixelBuffer pb, int x, int y) {
        // Magnifying glass
        int glass = 0xFF88AACC;
        for (int angle = 0; angle < 360; angle += 10) {
            double rad = Math.toRadians(angle);
            int px = x + 7 + (int)(4 * Math.cos(rad));
            int py = y + 6 + (int)(4 * Math.sin(rad));
            pb.setPixel(px, py, glass);
        }
        pb.drawLine(x + 10, y + 9, x + 14, y + 13, 0xFF666688);
        pb.drawLine(x + 11, y + 10, x + 14, y + 14, 0xFF666688);
    }

    private static void drawCut(PixelBuffer pb, int x, int y) {
        int blade = 0xFFCCCCCC;
        // Scissors
        pb.drawLine(x + 4, y + 2, x + 8, y + 8, blade);
        pb.drawLine(x + 12, y + 2, x + 8, y + 8, blade);
        // Handles (circles)
        pb.fillRoundRect(x + 2, y + 9, 5, 5, 2, 0xFFFF4444);
        pb.fillRoundRect(x + 9, y + 9, 5, 5, 2, 0xFF4444FF);
    }

    private static void drawCopy(PixelBuffer pb, int x, int y) {
        // Back page
        pb.fillRect(x + 5, y + 1, 8, 10, 0xFFDDDDDD);
        pb.drawRect(x + 5, y + 1, 8, 10, 0xFFAAAAAA);
        // Front page
        pb.fillRect(x + 2, y + 4, 8, 10, 0xFFFFFFFF);
        pb.drawRect(x + 2, y + 4, 8, 10, 0xFFAAAAAA);
        // Lines
        pb.drawHLine(x + 4, x + 8, y + 7, 0xFF888888);
        pb.drawHLine(x + 4, x + 7, y + 9, 0xFF888888);
    }

    private static void drawPaste(PixelBuffer pb, int x, int y) {
        // Clipboard
        pb.fillRect(x + 2, y + 3, 12, 12, 0xFFD4A868);
        pb.drawRect(x + 2, y + 3, 12, 12, 0xFFAA8844);
        // Clip at top
        pb.fillRect(x + 5, y + 1, 6, 4, 0xFF888888);
        pb.fillRect(x + 6, y + 0, 4, 2, 0xFF888888);
        // Paper
        pb.fillRect(x + 4, y + 5, 8, 8, 0xFFFFFFFF);
        pb.drawHLine(x + 5, x + 10, y + 7, 0xFF888888);
        pb.drawHLine(x + 5, x + 9, y + 9, 0xFF888888);
    }

    private static void drawDelete(PixelBuffer pb, int x, int y) {
        // Red X
        int red = 0xFFEE3333;
        pb.drawLine(x + 3, y + 3, x + 13, y + 13, red);
        pb.drawLine(x + 4, y + 3, x + 14, y + 13, red);
        pb.drawLine(x + 13, y + 3, x + 3, y + 13, red);
        pb.drawLine(x + 14, y + 3, x + 4, y + 13, red);
    }

    private static void drawNewFile(PixelBuffer pb, int x, int y) {
        drawFile(pb, x, y, 0xFFFFFFFF);
        // Green + overlay
        pb.fillRect(x + 9, y + 9, 6, 6, 0xFF22AA44);
        pb.fillRect(x + 11, y + 10, 2, 4, 0xFFFFFFFF);
        pb.fillRect(x + 10, y + 11, 4, 2, 0xFFFFFFFF);
    }

    private static void drawNewFolder(PixelBuffer pb, int x, int y) {
        drawFolder(pb, x, y, false);
        // Green + overlay
        pb.fillRect(x + 9, y + 9, 6, 6, 0xFF22AA44);
        pb.fillRect(x + 11, y + 10, 2, 4, 0xFFFFFFFF);
        pb.fillRect(x + 10, y + 11, 4, 2, 0xFFFFFFFF);
    }

    private static void drawDrive(PixelBuffer pb, int x, int y) {
        // HDD shape
        pb.fillRoundRect(x + 1, y + 4, 14, 8, 2, 0xFF556677);
        pb.drawRect(x + 1, y + 4, 14, 8, 0xFF445566);
        pb.fillRect(x + 3, y + 6, 10, 4, 0xFF334455);
        // Activity LED
        pb.fillRect(x + 12, y + 10, 2, 1, 0xFF22CC66);
    }

    private static void drawDocuments(PixelBuffer pb, int x, int y) {
        // Stack of papers
        pb.fillRect(x + 5, y + 1, 8, 11, 0xFFDDDDDD);
        pb.drawRect(x + 5, y + 1, 8, 11, 0xFFAAAAAA);
        pb.fillRect(x + 3, y + 3, 8, 11, 0xFFEEEEEE);
        pb.drawRect(x + 3, y + 3, 8, 11, 0xFFAAAAAA);
        // Lines
        pb.drawHLine(x + 5, x + 9, y + 6, 0xFF888888);
        pb.drawHLine(x + 5, x + 8, y + 8, 0xFF888888);
        pb.drawHLine(x + 5, x + 9, y + 10, 0xFF888888);
    }

    private static void drawDownloads(PixelBuffer pb, int x, int y) {
        // Down arrow
        pb.drawVLine(x + 8, y + 2, y + 9, 0xFF44AAFF);
        pb.drawVLine(x + 7, y + 2, y + 9, 0xFF44AAFF);
        pb.drawLine(x + 4, y + 7, x + 7, y + 11, 0xFF44AAFF);
        pb.drawLine(x + 11, y + 7, x + 8, y + 11, 0xFF44AAFF);
        // Tray
        pb.drawHLine(x + 2, x + 14, y + 13, 0xFF888888);
        pb.drawVLine(x + 2, y + 10, y + 13, 0xFF888888);
        pb.drawVLine(x + 14, y + 10, y + 13, 0xFF888888);
    }

    private static void drawPictures(PixelBuffer pb, int x, int y) {
        // Frame
        pb.fillRect(x + 1, y + 2, 14, 12, 0xFFAA8844);
        pb.fillRect(x + 2, y + 3, 12, 10, 0xFF88CCFF);
        // Mountains
        pb.drawLine(x + 3, y + 11, x + 7, y + 6, 0xFF44AA44);
        pb.drawLine(x + 7, y + 6, x + 9, y + 9, 0xFF44AA44);
        pb.drawLine(x + 9, y + 9, x + 12, y + 7, 0xFF338833);
        pb.fillRect(x + 3, y + 11, 10, 2, 0xFF44AA44);
        // Sun
        pb.fillRect(x + 10, y + 4, 3, 3, 0xFFFFDD44);
    }

    private static void drawNetwork(PixelBuffer pb, int x, int y) {
        int wire = 0xFF4488CC;
        // Central node
        pb.fillRect(x + 6, y + 6, 4, 4, wire);
        // Top node + line
        pb.fillRect(x + 6, y + 1, 4, 3, wire);
        pb.drawVLine(x + 8, y + 4, y + 6, wire);
        // Bottom node + line
        pb.fillRect(x + 6, y + 12, 4, 3, wire);
        pb.drawVLine(x + 8, y + 10, y + 12, wire);
        // Left node + line
        pb.fillRect(x + 1, y + 6, 3, 4, wire);
        pb.drawHLine(x + 4, x + 6, y + 8, wire);
        // Right node + line
        pb.fillRect(x + 12, y + 6, 3, 4, wire);
        pb.drawHLine(x + 10, x + 12, y + 8, wire);
    }

    private static void drawBluetooth(PixelBuffer pb, int x, int y) {
        int c = 0xFF5588DD;
        // Vertical center line
        pb.drawVLine(x + 7, y + 1, y + 14, c);
        // Top-right arrow: center-top → right-mid-upper → center-mid
        pb.drawLine(x + 7, y + 1, x + 11, y + 5, c);
        pb.drawLine(x + 11, y + 5, x + 7, y + 7, c);
        // Bottom-right arrow: center-mid → right-mid-lower → center-bottom
        pb.drawLine(x + 7, y + 7, x + 11, y + 10, c);
        pb.drawLine(x + 11, y + 10, x + 7, y + 14, c);
        // Left cross-lines
        pb.drawLine(x + 3, y + 4, x + 7, y + 7, c);
        pb.drawLine(x + 3, y + 11, x + 7, y + 7, c);
    }
}

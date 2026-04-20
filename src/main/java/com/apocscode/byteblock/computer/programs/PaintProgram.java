package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pixel art paint program for ByteBlock OS.
 * 16x10 canvas with 16-color palette, draw/fill/erase tools.
 */
public class PaintProgram extends OSProgram {

    private static final int CANVAS_W = 16;
    private static final int CANVAS_H = 10;
    private static final int CANVAS_X = 2;
    private static final int CANVAS_Y = 2;

    private int[][] canvas;
    private int selectedColor = 0; // white
    private String filePath;
    private boolean needsRedraw = true;
    private boolean dirty = false;
    private String statusMsg = "";

    private enum Tool { DRAW, FILL, ERASE }
    private Tool currentTool = Tool.DRAW;

    public PaintProgram() {
        this("/Users/User/Pictures/art.pxl");
    }

    public PaintProgram(String path) {
        super("Paint");
        this.filePath = path;
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        canvas = new int[CANVAS_H][CANVAS_W];
        for (int y = 0; y < CANVAS_H; y++)
            for (int x = 0; x < CANVAS_W; x++)
                canvas[y][x] = 15; // black

        String content = os.getFileSystem().readFile(filePath);
        if (content != null) {
            loadFromString(content);
            statusMsg = "Loaded: " + filePath;
        } else {
            statusMsg = "New canvas: " + filePath;
        }
    }

    @Override
    public boolean tick() { return running; }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case MOUSE_CLICK -> handleClick(event.getInt(1), event.getInt(2));
            case MOUSE_DRAG -> handleClick(event.getInt(1), event.getInt(2));
            case KEY -> handleKey(event.getInt(0));
            default -> {}
        }
    }

    private void handleClick(int mx, int my) {
        needsRedraw = true;

        // Canvas area
        int cx = mx - CANVAS_X;
        int cy = my - CANVAS_Y;
        if (cx >= 0 && cx < CANVAS_W && cy >= 0 && cy < CANVAS_H) {
            switch (currentTool) {
                case DRAW -> { canvas[cy][cx] = selectedColor; dirty = true; }
                case FILL -> { floodFill(cx, cy, canvas[cy][cx], selectedColor); dirty = true; }
                case ERASE -> { canvas[cy][cx] = 15; dirty = true; }
            }
            return;
        }

        // Palette row
        int paletteY = CANVAS_Y + CANVAS_H + 1;
        if (my == paletteY && mx >= CANVAS_X && mx < CANVAS_X + 16) {
            selectedColor = mx - CANVAS_X;
            return;
        }

        // Tool buttons
        int toolY = paletteY + 1;
        if (my == toolY) {
            if (mx >= CANVAS_X && mx < CANVAS_X + 4) currentTool = Tool.DRAW;
            else if (mx >= CANVAS_X + 5 && mx < CANVAS_X + 9) currentTool = Tool.FILL;
            else if (mx >= CANVAS_X + 10 && mx < CANVAS_X + 15) currentTool = Tool.ERASE;
            else if (mx >= CANVAS_X + 16 && mx < CANVAS_X + 21) clearCanvas();
        }
    }

    private void handleKey(int keyCode) {
        needsRedraw = true;
        switch (keyCode) {
            case 290 -> save();           // F1 = Save
            case 291 -> {                 // F2 = Cycle tool
                currentTool = Tool.values()[(currentTool.ordinal() + 1) % Tool.values().length];
            }
            case 292 -> running = false;  // F3 = Exit
        }
    }

    private void floodFill(int startX, int startY, int target, int replace) {
        if (target == replace) return;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{startX, startY});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int x = p[0], y = p[1];
            if (x < 0 || x >= CANVAS_W || y < 0 || y >= CANVAS_H) continue;
            if (canvas[y][x] != target) continue;
            canvas[y][x] = replace;
            stack.push(new int[]{x + 1, y});
            stack.push(new int[]{x - 1, y});
            stack.push(new int[]{x, y + 1});
            stack.push(new int[]{x, y - 1});
        }
    }

    private void clearCanvas() {
        for (int y = 0; y < CANVAS_H; y++)
            for (int x = 0; x < CANVAS_W; x++)
                canvas[y][x] = 15;
        dirty = true;
        statusMsg = "Canvas cleared";
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        sb.append(CANVAS_W).append('x').append(CANVAS_H).append('\n');
        for (int y = 0; y < CANVAS_H; y++) {
            for (int x = 0; x < CANVAS_W; x++) {
                sb.append(Integer.toHexString(canvas[y][x]));
            }
            sb.append('\n');
        }
        os.getFileSystem().writeFile(filePath, sb.toString());
        dirty = false;
        statusMsg = "Saved: " + filePath;
    }

    private void loadFromString(String content) {
        String[] lines = content.split("\n");
        if (lines.length < 2) return;
        String[] dims = lines[0].split("x");
        if (dims.length != 2) return;
        try {
            int w = Integer.parseInt(dims[0].trim());
            int h = Integer.parseInt(dims[1].trim());
            for (int y = 0; y < Math.min(h, CANVAS_H) && y + 1 < lines.length; y++) {
                String line = lines[y + 1];
                for (int x = 0; x < Math.min(w, CANVAS_W) && x < line.length(); x++) {
                    int c = Character.digit(line.charAt(x), 16);
                    if (c >= 0 && c < 16) canvas[y][x] = c;
                }
            }
        } catch (NumberFormatException ignored) {}
    }

    @Override
    public void render(TerminalBuffer buf) {
        buf.setTextColor(0);
        buf.setBackgroundColor(15);
        buf.clear();

        // Title bar
        buf.setBackgroundColor(11);
        buf.setTextColor(0);
        buf.hLine(0, TerminalBuffer.WIDTH - 1, 0, ' ');
        String title = " Paint" + (dirty ? " *" : "") + " - " + filePath;
        buf.writeAt(0, 0, title.substring(0, Math.min(title.length(), TerminalBuffer.WIDTH)));

        // Canvas border
        buf.setTextColor(7);
        buf.setBackgroundColor(15);
        buf.hLine(CANVAS_X - 1, CANVAS_X + CANVAS_W, CANVAS_Y - 1, '-');
        buf.hLine(CANVAS_X - 1, CANVAS_X + CANVAS_W, CANVAS_Y + CANVAS_H, '-');
        for (int y = CANVAS_Y; y < CANVAS_Y + CANVAS_H; y++) {
            buf.writeAt(CANVAS_X - 1, y, "|");
            buf.writeAt(CANVAS_X + CANVAS_W, y, "|");
        }

        // Canvas pixels
        for (int y = 0; y < CANVAS_H; y++) {
            for (int x = 0; x < CANVAS_W; x++) {
                buf.setBackgroundColor(canvas[y][x]);
                buf.writeAt(CANVAS_X + x, CANVAS_Y + y, " ");
            }
        }

        // Palette row
        int paletteY = CANVAS_Y + CANVAS_H + 1;
        buf.setBackgroundColor(15);
        buf.setTextColor(8);
        buf.writeAt(0, paletteY, "C:");
        for (int c = 0; c < 16; c++) {
            buf.setBackgroundColor(c);
            if (c == selectedColor) {
                buf.setTextColor(c == 0 || c == 4 || c == 5 || c == 8 ? 15 : 0);
                buf.writeAt(CANVAS_X + c, paletteY, "X");
            } else {
                buf.writeAt(CANVAS_X + c, paletteY, " ");
            }
        }

        // Tool buttons
        int toolY = paletteY + 1;
        buf.setTextColor(0);
        buf.setBackgroundColor(currentTool == Tool.DRAW ? 13 : 7);
        buf.writeAt(CANVAS_X, toolY, "Draw");
        buf.setBackgroundColor(currentTool == Tool.FILL ? 13 : 7);
        buf.writeAt(CANVAS_X + 5, toolY, "Fill");
        buf.setBackgroundColor(currentTool == Tool.ERASE ? 13 : 7);
        buf.writeAt(CANVAS_X + 10, toolY, "Erase");
        buf.setBackgroundColor(7);
        buf.writeAt(CANVAS_X + 16, toolY, "Clear");

        // Info panel (right of canvas)
        int infoX = CANVAS_X + CANVAS_W + 2;
        buf.setBackgroundColor(15);
        buf.setTextColor(8);
        buf.writeAt(infoX, CANVAS_Y, "Color:");
        buf.setBackgroundColor(selectedColor);
        buf.writeAt(infoX + 7, CANVAS_Y, "  ");
        buf.setBackgroundColor(15);
        buf.setTextColor(0);
        buf.writeAt(infoX, CANVAS_Y + 1, colorName(selectedColor));

        buf.setTextColor(8);
        buf.writeAt(infoX, CANVAS_Y + 3, "Tool:");
        buf.setTextColor(0);
        buf.writeAt(infoX, CANVAS_Y + 4, currentTool.name());

        buf.setTextColor(8);
        buf.writeAt(infoX, CANVAS_Y + 6, "F1:Save");
        buf.writeAt(infoX, CANVAS_Y + 7, "F2:Tool");
        buf.writeAt(infoX, CANVAS_Y + 8, "F3:Exit");

        // Status bar
        int statusY = toolY + 1;
        buf.setBackgroundColor(7);
        buf.setTextColor(0);
        buf.hLine(0, TerminalBuffer.WIDTH - 1, statusY, ' ');
        String statusText = statusMsg.isEmpty() ? (CANVAS_W + "x" + CANVAS_H + " canvas") : statusMsg;
        buf.writeAt(1, statusY, statusText);
    }

    private String colorName(int c) {
        return switch (c) {
            case 0 -> "White";   case 1 -> "Orange";  case 2 -> "Magenta";
            case 3 -> "L.Blue";  case 4 -> "Yellow";  case 5 -> "Lime";
            case 6 -> "Pink";    case 7 -> "Gray";    case 8 -> "L.Gray";
            case 9 -> "Cyan";    case 10 -> "Purple"; case 11 -> "Blue";
            case 12 -> "Brown";  case 13 -> "Green";  case 14 -> "Red";
            case 15 -> "Black";  default -> "?";
        };
    }
}

package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Text editor / IDE for ByteBlock OS.
 * Features: line numbers, cursor navigation, insert/delete, save/load, syntax highlighting.
 */
public class EditProgram extends OSProgram {

    private String filePath;
    private final List<StringBuilder> lines;
    private int cursorRow;
    private int cursorCol;
    private int scrollY;
    private int scrollX;
    private boolean modified;
    private boolean needsRedraw = true;
    private String statusMessage = "";
    private int statusTicks = 0;

    // Editor dimensions (leave room for line numbers on left, status bar at bottom)
    private static final int LINE_NUM_WIDTH = 4;
    private static final int HEADER_HEIGHT = 1;
    private static final int FOOTER_HEIGHT = 1;

    // Syntax highlighting keywords
    private static final String[] KEYWORDS = {
        "public", "private", "protected", "class", "interface", "extends", "implements",
        "import", "package", "return", "if", "else", "for", "while", "do", "switch",
        "case", "break", "continue", "new", "this", "super", "static", "final",
        "void", "int", "long", "float", "double", "boolean", "char", "String",
        "true", "false", "null", "try", "catch", "finally", "throw", "throws",
        "var", "def", "println", "print", "function", "let", "const"
    };

    public EditProgram(String filePath) {
        super("Edit");
        this.filePath = filePath;
        this.lines = new ArrayList<>();
        this.lines.add(new StringBuilder());
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        // Load file if it exists
        String content = os.getFileSystem().readFile(filePath);
        if (content != null) {
            lines.clear();
            for (String line : content.split("\n", -1)) {
                lines.add(new StringBuilder(line));
            }
            if (lines.isEmpty()) lines.add(new StringBuilder());
            statusMessage = "Loaded: " + filePath;
        } else {
            statusMessage = "New file: " + filePath;
        }
        statusTicks = 60;
        modified = false;
    }

    @Override
    public boolean tick() {
        if (statusTicks > 0) {
            statusTicks--;
            if (statusTicks == 0) needsRedraw = true;
        }
        return running;
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case CHAR -> {
                insertChar(event.getString(0).charAt(0));
                needsRedraw = true;
            }
            case KEY -> {
                handleKey(event.getInt(0));
                needsRedraw = true;
            }
            case MOUSE_CLICK -> {
                int mx = event.getInt(1);
                int my = event.getInt(2);
                handleClick(mx, my);
                needsRedraw = true;
            }
            case MOUSE_SCROLL -> {
                int dir = event.getInt(0);
                scrollY = Math.max(0, Math.min(lines.size() - 1, scrollY + dir * 3));
                needsRedraw = true;
            }
            default -> {}
        }
    }

    private void insertChar(char c) {
        lines.get(cursorRow).insert(cursorCol, c);
        cursorCol++;
        modified = true;
    }

    private void handleKey(int keyCode) {
        switch (keyCode) {
            case 259 -> { // Backspace
                if (cursorCol > 0) {
                    lines.get(cursorRow).deleteCharAt(cursorCol - 1);
                    cursorCol--;
                    modified = true;
                } else if (cursorRow > 0) {
                    // Merge with previous line
                    StringBuilder prev = lines.get(cursorRow - 1);
                    cursorCol = prev.length();
                    prev.append(lines.get(cursorRow));
                    lines.remove(cursorRow);
                    cursorRow--;
                    modified = true;
                }
            }
            case 261 -> { // Delete
                if (cursorCol < lines.get(cursorRow).length()) {
                    lines.get(cursorRow).deleteCharAt(cursorCol);
                    modified = true;
                } else if (cursorRow < lines.size() - 1) {
                    lines.get(cursorRow).append(lines.get(cursorRow + 1));
                    lines.remove(cursorRow + 1);
                    modified = true;
                }
            }
            case 257, 335 -> { // Enter
                StringBuilder current = lines.get(cursorRow);
                String after = current.substring(cursorCol);
                current.setLength(cursorCol);
                cursorRow++;
                cursorCol = 0;
                lines.add(cursorRow, new StringBuilder(after));
                modified = true;
            }
            case 258 -> { // Tab — insert 2 spaces
                lines.get(cursorRow).insert(cursorCol, "  ");
                cursorCol += 2;
                modified = true;
            }
            case 263 -> cursorCol = Math.max(0, cursorCol - 1); // Left
            case 262 -> cursorCol = Math.min(lines.get(cursorRow).length(), cursorCol + 1); // Right
            case 265 -> { // Up
                if (cursorRow > 0) {
                    cursorRow--;
                    cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
                }
            }
            case 264 -> { // Down
                if (cursorRow < lines.size() - 1) {
                    cursorRow++;
                    cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
                }
            }
            case 268 -> cursorCol = 0; // Home
            case 269 -> cursorCol = lines.get(cursorRow).length(); // End
            case 266 -> { // Page Up
                int visibleRows = TerminalBuffer.HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT;
                cursorRow = Math.max(0, cursorRow - visibleRows);
                cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
            }
            case 267 -> { // Page Down
                int visibleRows = TerminalBuffer.HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT;
                cursorRow = Math.min(lines.size() - 1, cursorRow + visibleRows);
                cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
            }
        }

        // Ctrl+S = Save (keyCode 83 with ctrl wouldn't reach here; handled via CHAR suppression)
        // We handle save through a special mechanism: if the program gets KEY 19 (Ctrl+S as raw)
        // Actually, Ctrl+S comes as keyCode 83 from the Screen layer before charTyped.
        // Let's use F-keys instead for reliability:
        if (keyCode == 290) save();       // F1 = Save
        if (keyCode == 291) saveAs();     // F2 = Save As (prompt in status)
        if (keyCode == 292) running = false; // F3 = Exit
        if (keyCode == 293) createDesktopShortcut(); // F4 = Desktop shortcut

        ensureCursorVisible();
    }

    private void handleClick(int mx, int my) {
        if (my >= HEADER_HEIGHT && my < TerminalBuffer.HEIGHT - FOOTER_HEIGHT) {
            int row = scrollY + (my - HEADER_HEIGHT);
            if (row < lines.size()) {
                cursorRow = row;
                int col = scrollX + (mx - LINE_NUM_WIDTH);
                cursorCol = Math.max(0, Math.min(lines.get(cursorRow).length(), col));
            }
        }
    }

    private void save() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) content.append("\n");
            content.append(lines.get(i));
        }
        os.getFileSystem().writeFile(filePath, content.toString());
        modified = false;
        statusMessage = "Saved: " + filePath;
        statusTicks = 60;
    }

    private void saveAs() {
        // For now just save to current path
        save();
    }

    private void createDesktopShortcut() {
        save();
        String name = filePath;
        int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash >= 0) name = filePath.substring(lastSlash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        String safeName = name.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
        String content = "name=" + name + "\ntarget=" + filePath + "\nicon=2\ncolor=9\n";
        os.getFileSystem().writeFile("/desktop/" + safeName + ".lnk", content);
        statusMessage = "Shortcut created: " + name;
        statusTicks = 60;
    }

    private void ensureCursorVisible() {
        int visibleRows = TerminalBuffer.HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT;
        int visibleCols = TerminalBuffer.WIDTH - LINE_NUM_WIDTH;
        if (cursorRow < scrollY) scrollY = cursorRow;
        if (cursorRow >= scrollY + visibleRows) scrollY = cursorRow - visibleRows + 1;
        if (cursorCol < scrollX) scrollX = cursorCol;
        if (cursorCol >= scrollX + visibleCols) scrollX = cursorCol - visibleCols + 1;
    }

    @Override
    public void render(TerminalBuffer buf) {
        int visibleRows = TerminalBuffer.HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT;
        int visibleCols = TerminalBuffer.WIDTH - LINE_NUM_WIDTH;

        // Header bar
        buf.setTextColor(0); // white
        buf.setBackgroundColor(11); // blue
        buf.hLine(0, TerminalBuffer.WIDTH - 1, 0, ' ');
        String header = " Edit: " + shortPath(filePath) + (modified ? " *" : "") +
                         "  F1:Save F3:Exit F4:Link";
        buf.writeAt(0, 0, header.substring(0, Math.min(header.length(), TerminalBuffer.WIDTH)));

        // Editor area
        for (int vy = 0; vy < visibleRows; vy++) {
            int lineIdx = scrollY + vy;
            int screenY = HEADER_HEIGHT + vy;

            // Line number
            buf.setTextColor(7); // gray
            buf.setBackgroundColor(15); // black
            if (lineIdx < lines.size()) {
                String num = String.format("%3d ", lineIdx + 1);
                buf.writeAt(0, screenY, num);
            } else {
                buf.writeAt(0, screenY, "  ~ ");
            }

            // Line content with syntax highlighting
            buf.setBackgroundColor(15);
            if (lineIdx < lines.size()) {
                String lineText = lines.get(lineIdx).toString();
                renderHighlightedLine(buf, lineText, LINE_NUM_WIDTH, screenY, scrollX, visibleCols);
            } else {
                buf.setTextColor(7);
                for (int x = LINE_NUM_WIDTH; x < TerminalBuffer.WIDTH; x++) {
                    buf.writeAt(x, screenY, " ");
                }
            }
        }

        // Cursor position
        int curScreenX = LINE_NUM_WIDTH + (cursorCol - scrollX);
        int curScreenY = HEADER_HEIGHT + (cursorRow - scrollY);
        if (curScreenX >= LINE_NUM_WIDTH && curScreenX < TerminalBuffer.WIDTH &&
            curScreenY >= HEADER_HEIGHT && curScreenY < TerminalBuffer.HEIGHT - FOOTER_HEIGHT) {
            buf.setCursorPos(curScreenX, curScreenY);
        }

        // Footer / status bar
        buf.setTextColor(0);
        buf.setBackgroundColor(7); // gray
        buf.hLine(0, TerminalBuffer.WIDTH - 1, TerminalBuffer.HEIGHT - 1, ' ');
        String status;
        if (statusTicks > 0 && !statusMessage.isEmpty()) {
            status = " " + statusMessage;
        } else {
            status = String.format(" Ln %d, Col %d  |  %d lines  |  %s",
                cursorRow + 1, cursorCol + 1, lines.size(), filePath);
        }
        buf.writeAt(0, TerminalBuffer.HEIGHT - 1,
            status.substring(0, Math.min(status.length(), TerminalBuffer.WIDTH)));
    }

    private void renderHighlightedLine(TerminalBuffer buf, String line, int startX, int y,
                                        int scrollOffset, int visibleWidth) {
        // Simple token-based syntax highlighting
        String visible = line.length() > scrollOffset ? 
            line.substring(scrollOffset, Math.min(line.length(), scrollOffset + visibleWidth)) : "";

        // Default: white text
        buf.setTextColor(0);
        for (int i = 0; i < visibleWidth; i++) {
            buf.writeAt(startX + i, y, " ");
        }

        if (visible.isEmpty()) return;

        // Colorize: comments green, strings orange, keywords cyan, numbers yellow
        int i = 0;
        while (i < visible.length()) {
            int px = startX + i;
            if (px >= TerminalBuffer.WIDTH) break;

            // Comment (// to end of line)
            if (i < visible.length() - 1 && visible.charAt(i) == '/' && visible.charAt(i + 1) == '/') {
                buf.setTextColor(13); // green
                buf.writeAt(px, y, visible.substring(i, Math.min(visible.length(), TerminalBuffer.WIDTH - startX)));
                break;
            }
            // Comment (-- to end of line, Lua-style)
            if (i < visible.length() - 1 && visible.charAt(i) == '-' && visible.charAt(i + 1) == '-') {
                buf.setTextColor(13); // green
                buf.writeAt(px, y, visible.substring(i, Math.min(visible.length(), TerminalBuffer.WIDTH - startX)));
                break;
            }
            // String literal
            if (visible.charAt(i) == '"' || visible.charAt(i) == '\'') {
                char quote = visible.charAt(i);
                int end = visible.indexOf(quote, i + 1);
                if (end < 0) end = visible.length() - 1;
                buf.setTextColor(1); // orange
                for (int j = i; j <= end && (startX + j) < TerminalBuffer.WIDTH; j++) {
                    buf.writeAt(startX + j, y, String.valueOf(visible.charAt(j)));
                }
                i = end + 1;
                continue;
            }
            // Number
            if (Character.isDigit(visible.charAt(i))) {
                buf.setTextColor(4); // yellow
                while (i < visible.length() && (Character.isDigit(visible.charAt(i)) || visible.charAt(i) == '.')) {
                    if ((startX + i) < TerminalBuffer.WIDTH)
                        buf.writeAt(startX + i, y, String.valueOf(visible.charAt(i)));
                    i++;
                }
                continue;
            }
            // Keyword
            if (Character.isLetter(visible.charAt(i)) || visible.charAt(i) == '_') {
                int wordStart = i;
                while (i < visible.length() && (Character.isLetterOrDigit(visible.charAt(i)) || visible.charAt(i) == '_')) {
                    i++;
                }
                String word = visible.substring(wordStart, i);
                boolean isKeyword = false;
                for (String kw : KEYWORDS) {
                    if (kw.equals(word)) { isKeyword = true; break; }
                }
                buf.setTextColor(isKeyword ? 9 : 0); // cyan for keywords, white for identifiers
                for (int j = wordStart; j < i && (startX + j) < TerminalBuffer.WIDTH; j++) {
                    buf.writeAt(startX + j, y, String.valueOf(visible.charAt(j)));
                }
                continue;
            }
            // Operators and punctuation
            buf.setTextColor(8); // light gray
            buf.writeAt(px, y, String.valueOf(visible.charAt(i)));
            i++;
        }
    }

    private String shortPath(String path) {
        if (path.length() <= 30) return path;
        return "..." + path.substring(path.length() - 27);
    }
}

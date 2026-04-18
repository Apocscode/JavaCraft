package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Comprehensive text IDE for ByteBlock OS.
 * Features: multi-tab editing, find, run, auto-indent,
 *           syntax highlighting (Java+Lua), go-to-line,
 *           undo, bracket matching, auto-close brackets, clipboard paste.
 */
public class TextIDEProgram extends OSProgram {

    // ── Tab model ───────────────────────────────────────────

    static class Tab {
        String filePath;
        final List<StringBuilder> lines = new ArrayList<>();
        int cursorRow, cursorCol;
        int scrollX, scrollY;
        boolean modified;
        // Undo
        final List<List<String>> undoStack = new ArrayList<>();
        int undoPointer = -1;
        static final int MAX_UNDO = 30;

        Tab(String path) {
            this.filePath = path;
            this.lines.add(new StringBuilder());
        }

        void pushUndo() {
            while (undoStack.size() > undoPointer + 1 && !undoStack.isEmpty())
                undoStack.removeLast();
            List<String> snapshot = new ArrayList<>(lines.size());
            for (StringBuilder sb : lines) snapshot.add(sb.toString());
            undoStack.add(snapshot);
            if (undoStack.size() > MAX_UNDO) undoStack.removeFirst();
            undoPointer = undoStack.size() - 1;
        }

        void undo() {
            if (undoPointer <= 0) return;
            undoPointer--;
            List<String> snapshot = undoStack.get(undoPointer);
            lines.clear();
            for (String s : snapshot) lines.add(new StringBuilder(s));
            cursorRow = Math.min(cursorRow, lines.size() - 1);
            cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
            modified = true;
        }

        String tabLabel() {
            String name = filePath;
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
            if (name.length() > 10) name = name.substring(0, 9) + "\u2026";
            return name + (modified ? "*" : "");
        }
    }

    // ── Mode ────────────────────────────────────────────────

    enum Mode { EDITING, FIND, GOTO_LINE, NEW_FILE }

    // ── State ───────────────────────────────────────────────

    private final List<Tab> tabs = new ArrayList<>();
    private int activeTab = 0;
    private Mode mode = Mode.EDITING;
    private final StringBuilder modeInput = new StringBuilder();
    private boolean needsRedraw = true;
    private String statusMsg = "";
    private int statusTicks = 0;

    // Find state
    private String findQuery = "";

    // Layout
    private static final int TAB_BAR = 1;
    private static final int LINE_W = 4;
    private static final int FOOTER = 1;
    private static final int CODE_TOP = TAB_BAR;
    private static final int CODE_ROWS = TerminalBuffer.HEIGHT - TAB_BAR - FOOTER;

    // Syntax keywords
    private static final Set<String> JAVA_KW = new HashSet<>(Arrays.asList(
        "public", "private", "protected", "class", "interface", "extends", "implements",
        "import", "package", "return", "if", "else", "for", "while", "do", "switch",
        "case", "break", "continue", "new", "this", "super", "static", "final",
        "void", "int", "long", "float", "double", "boolean", "char", "String",
        "true", "false", "null", "try", "catch", "finally", "throw", "throws",
        "var", "abstract", "enum", "instanceof", "synchronized"
    ));
    private static final Set<String> LUA_KW = new HashSet<>(Arrays.asList(
        "local", "function", "end", "if", "then", "else", "elseif", "for", "do",
        "while", "repeat", "until", "return", "break", "not", "and", "or", "in",
        "true", "false", "nil", "goto"
    ));

    // ── Constructor ─────────────────────────────────────────

    public TextIDEProgram() { this("/home/new.txt"); }

    public TextIDEProgram(String filePath) {
        super("IDE");
        this.tabs.add(new Tab(filePath));
    }

    private Tab tab() { return tabs.get(activeTab); }

    // ── Lifecycle ───────────────────────────────────────────

    @Override
    public void init(JavaOS os) {
        this.os = os;
        for (Tab t : tabs) loadTab(t);
        setStatus("IDE ready | F1:Save F2:Find F5:Run F7:New");
    }

    private void loadTab(Tab t) {
        String content = os.getFileSystem().readFile(t.filePath);
        if (content != null) {
            t.lines.clear();
            for (String line : content.split("\n", -1))
                t.lines.add(new StringBuilder(line));
            if (t.lines.isEmpty()) t.lines.add(new StringBuilder());
        }
        t.modified = false;
        t.pushUndo();
    }

    @Override
    public boolean tick() {
        if (statusTicks > 0 && --statusTicks == 0) needsRedraw = true;
        return running;
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case CHAR -> { handleChar(event.getString(0).charAt(0)); needsRedraw = true; }
            case KEY  -> { handleKey(event.getInt(0)); needsRedraw = true; }
            case MOUSE_CLICK -> { handleClick(event.getInt(1), event.getInt(2)); needsRedraw = true; }
            case MOUSE_SCROLL -> {
                tab().scrollY = Math.max(0,
                    Math.min(tab().lines.size() - 1, tab().scrollY + event.getInt(0) * 3));
                needsRedraw = true;
            }
            case PASTE -> { handlePaste(event.getString(0)); needsRedraw = true; }
            default -> {}
        }
    }

    // ── Character input ─────────────────────────────────────

    private void handleChar(char c) {
        if (mode != Mode.EDITING) {
            if (c >= 32) modeInput.append(c);
            return;
        }
        Tab t = tab();
        t.pushUndo();
        t.lines.get(t.cursorRow).insert(t.cursorCol, c);
        t.cursorCol++;
        // Auto-close brackets
        String closer = switch (c) {
            case '(' -> ")";
            case '[' -> "]";
            case '{' -> "}";
            default  -> null;
        };
        if (closer != null)
            t.lines.get(t.cursorRow).insert(t.cursorCol, closer);
        t.modified = true;
    }

    // ── Key input ───────────────────────────────────────────

    private void handleKey(int key) {
        if (mode != Mode.EDITING) { handleModeKey(key); return; }
        Tab t = tab();
        switch (key) {
            case 259 -> {                                           // Backspace
                if (t.cursorCol > 0) {
                    t.pushUndo();
                    t.lines.get(t.cursorRow).deleteCharAt(t.cursorCol - 1);
                    t.cursorCol--;
                    t.modified = true;
                } else if (t.cursorRow > 0) {
                    t.pushUndo();
                    StringBuilder prev = t.lines.get(t.cursorRow - 1);
                    t.cursorCol = prev.length();
                    prev.append(t.lines.get(t.cursorRow));
                    t.lines.remove(t.cursorRow);
                    t.cursorRow--;
                    t.modified = true;
                }
            }
            case 261 -> {                                           // Delete
                if (t.cursorCol < t.lines.get(t.cursorRow).length()) {
                    t.pushUndo();
                    t.lines.get(t.cursorRow).deleteCharAt(t.cursorCol);
                    t.modified = true;
                } else if (t.cursorRow < t.lines.size() - 1) {
                    t.pushUndo();
                    t.lines.get(t.cursorRow).append(t.lines.get(t.cursorRow + 1));
                    t.lines.remove(t.cursorRow + 1);
                    t.modified = true;
                }
            }
            case 257, 335 -> {                                      // Enter (auto-indent)
                t.pushUndo();
                String currentLine = t.lines.get(t.cursorRow).toString();
                String after = currentLine.substring(t.cursorCol);
                t.lines.get(t.cursorRow).setLength(t.cursorCol);
                int indent = 0;
                while (indent < currentLine.length() && currentLine.charAt(indent) == ' ')
                    indent++;
                String pad = " ".repeat(indent);
                t.cursorRow++;
                t.lines.add(t.cursorRow, new StringBuilder(pad + after));
                t.cursorCol = indent;
                t.modified = true;
            }
            case 258 -> {                                           // Tab = 2 spaces
                t.pushUndo();
                t.lines.get(t.cursorRow).insert(t.cursorCol, "  ");
                t.cursorCol += 2;
                t.modified = true;
            }
            case 263 -> t.cursorCol = Math.max(0, t.cursorCol - 1);                       // Left
            case 262 -> t.cursorCol = Math.min(t.lines.get(t.cursorRow).length(),
                         t.cursorCol + 1);                                                 // Right
            case 265 -> { if (t.cursorRow > 0) { t.cursorRow--; clampCol(); } }           // Up
            case 264 -> { if (t.cursorRow < t.lines.size() - 1) { t.cursorRow++; clampCol(); } } // Down
            case 268 -> t.cursorCol = 0;                                                   // Home
            case 269 -> t.cursorCol = t.lines.get(t.cursorRow).length();                  // End
            case 266 -> { t.cursorRow = Math.max(0, t.cursorRow - CODE_ROWS); clampCol(); }        // PgUp
            case 267 -> { t.cursorRow = Math.min(t.lines.size()-1, t.cursorRow + CODE_ROWS); clampCol(); } // PgDn
            // Function keys
            case 290 -> save();                        // F1
            case 291 -> enterMode(Mode.FIND);          // F2
            case 292 -> running = false;               // F3
            case 293 -> createShortcut();              // F4
            case 294 -> runFile();                     // F5
            case 295 -> enterMode(Mode.GOTO_LINE);     // F6
            case 296 -> enterMode(Mode.NEW_FILE);      // F7
            case 297 -> nextTab();                     // F8
            case 298 -> closeTab();                    // F9
            case 299 -> tab().undo();                  // F10 undo
        }
        ensureCursorVisible();
    }

    private void handleModeKey(int key) {
        switch (key) {
            case 256 -> mode = Mode.EDITING;           // Escape
            case 259 -> {                               // Backspace
                if (!modeInput.isEmpty())
                    modeInput.deleteCharAt(modeInput.length() - 1);
            }
            case 257, 335 -> {                          // Enter
                String input = modeInput.toString();
                switch (mode) {
                    case FIND      -> doFind(input);
                    case GOTO_LINE -> doGotoLine(input);
                    case NEW_FILE  -> doNewFile(input);
                    default -> {}
                }
            }
        }
    }

    // ── Mouse / paste ───────────────────────────────────────

    private void handleClick(int mx, int my) {
        Tab t = tab();
        if (my == 0) { handleTabBarClick(mx); return; }
        mode = Mode.EDITING;
        if (my >= CODE_TOP && my < CODE_TOP + CODE_ROWS) {
            int row = t.scrollY + (my - CODE_TOP);
            if (row < t.lines.size()) {
                t.cursorRow = row;
                int col = t.scrollX + (mx - LINE_W);
                t.cursorCol = Math.max(0, Math.min(t.lines.get(t.cursorRow).length(), col));
            }
        }
    }

    private void handleTabBarClick(int mx) {
        int x = 0;
        for (int i = 0; i < tabs.size(); i++) {
            String label = tabs.get(i).tabLabel();
            int w = label.length() + 3;
            if (mx >= x && mx < x + w) { activeTab = i; return; }
            x += w;
        }
    }

    private void handlePaste(String text) {
        if (mode != Mode.EDITING || text == null || text.isEmpty()) return;
        Tab t = tab();
        t.pushUndo();
        for (char c : text.toCharArray()) {
            if (c == '\n') {
                String after = t.lines.get(t.cursorRow).substring(t.cursorCol);
                t.lines.get(t.cursorRow).setLength(t.cursorCol);
                t.cursorRow++;
                t.lines.add(t.cursorRow, new StringBuilder(after));
                t.cursorCol = 0;
            } else if (c >= 32 && c < 127) {
                t.lines.get(t.cursorRow).insert(t.cursorCol, c);
                t.cursorCol++;
            }
        }
        t.modified = true;
    }

    // ── Actions ─────────────────────────────────────────────

    private void save() {
        Tab t = tab();
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < t.lines.size(); i++) {
            if (i > 0) content.append("\n");
            content.append(t.lines.get(i));
        }
        os.getFileSystem().writeFile(t.filePath, content.toString());
        t.modified = false;
        setStatus("Saved: " + t.filePath);
    }

    private void runFile() {
        save();
        Tab t = tab();
        if (t.filePath.endsWith(".lua")) {
            os.launchProgram(new LuaShellProgram(t.filePath));
            setStatus("Running: " + t.filePath);
        } else {
            setStatus("Only .lua files can be run (F5)");
        }
    }

    private void createShortcut() {
        save();
        Tab t = tab();
        String name = t.filePath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        String safe = name.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
        os.getFileSystem().writeFile("/desktop/" + safe + ".lnk",
            "name=" + name + "\ntarget=" + t.filePath + "\nicon=2\ncolor=3\n");
        setStatus("Shortcut: " + name);
    }

    private void enterMode(Mode m) {
        mode = m;
        modeInput.setLength(0);
    }

    private void doFind(String query) {
        if (query.isEmpty()) { mode = Mode.EDITING; return; }
        findQuery = query;
        Tab t = tab();
        int startRow = t.cursorRow;
        int startCol = t.cursorCol + 1;
        for (int i = 0; i < t.lines.size(); i++) {
            int row = (startRow + i) % t.lines.size();
            String line = t.lines.get(row).toString();
            int col = (i == 0) ? line.indexOf(query, startCol) : line.indexOf(query);
            if (col >= 0) {
                t.cursorRow = row;
                t.cursorCol = col;
                ensureCursorVisible();
                return;
            }
        }
        setStatus("Not found: " + query);
    }

    private void doGotoLine(String input) {
        mode = Mode.EDITING;
        try {
            int line = Integer.parseInt(input.trim()) - 1;
            Tab t = tab();
            t.cursorRow = Math.max(0, Math.min(t.lines.size() - 1, line));
            t.cursorCol = 0;
            ensureCursorVisible();
        } catch (NumberFormatException e) {
            setStatus("Invalid line number");
        }
    }

    private void doNewFile(String path) {
        mode = Mode.EDITING;
        if (path.isEmpty()) return;
        if (!path.startsWith("/")) path = "/home/" + path;
        Tab newTab = new Tab(path);
        loadTab(newTab);
        tabs.add(newTab);
        activeTab = tabs.size() - 1;
        setStatus("Opened: " + path);
    }

    private void nextTab() {
        if (tabs.size() > 1) activeTab = (activeTab + 1) % tabs.size();
    }

    private void closeTab() {
        if (tabs.size() <= 1) { setStatus("Can't close last tab"); return; }
        tabs.remove(activeTab);
        if (activeTab >= tabs.size()) activeTab = tabs.size() - 1;
    }

    private void clampCol() {
        Tab t = tab();
        t.cursorCol = Math.min(t.cursorCol, t.lines.get(t.cursorRow).length());
    }

    private void ensureCursorVisible() {
        Tab t = tab();
        int visW = TerminalBuffer.WIDTH - LINE_W;
        if (t.cursorRow < t.scrollY) t.scrollY = t.cursorRow;
        if (t.cursorRow >= t.scrollY + CODE_ROWS) t.scrollY = t.cursorRow - CODE_ROWS + 1;
        if (t.cursorCol < t.scrollX) t.scrollX = t.cursorCol;
        if (t.cursorCol >= t.scrollX + visW) t.scrollX = t.cursorCol - visW + 1;
    }

    // ── Render ──────────────────────────────────────────────

    @Override
    public void render(TerminalBuffer buf) {
        if (!needsRedraw) return;
        needsRedraw = false;
        Tab t = tab();
        int visW = TerminalBuffer.WIDTH - LINE_W;

        // ── Tab bar ──
        buf.setTextColor(0);
        buf.setBackgroundColor(7);
        buf.hLine(0, TerminalBuffer.WIDTH - 1, 0, ' ');
        int tx = 0;
        for (int i = 0; i < tabs.size() && tx < TerminalBuffer.WIDTH - 10; i++) {
            String label = tabs.get(i).tabLabel();
            boolean active = (i == activeTab);
            buf.setBackgroundColor(active ? 11 : 7);
            buf.setTextColor(active ? 0 : 8);
            String tabStr = "[" + label + "]";
            buf.writeAt(tx, 0, clip(tabStr, TerminalBuffer.WIDTH - tx));
            tx += tabStr.length() + 1;
        }
        buf.setBackgroundColor(7);
        buf.setTextColor(8);
        int hintX = TerminalBuffer.WIDTH - 9;
        if (hintX > tx) buf.writeAt(hintX, 0, "F7:+ F8:\u00BB");

        // ── Code area ──
        for (int vy = 0; vy < CODE_ROWS; vy++) {
            int lineIdx = t.scrollY + vy;
            int sy = CODE_TOP + vy;

            // Line number gutter
            buf.setTextColor(7);
            buf.setBackgroundColor(15);
            if (lineIdx < t.lines.size()) {
                buf.writeAt(0, sy, String.format("%3d ", lineIdx + 1));
            } else {
                buf.writeAt(0, sy, "  ~ ");
            }

            // Code content
            buf.setBackgroundColor(15);
            if (lineIdx < t.lines.size()) {
                renderHighlightedLine(buf, t.lines.get(lineIdx).toString(),
                    LINE_W, sy, t.scrollX, visW);
            } else {
                buf.setTextColor(7);
                for (int x = LINE_W; x < TerminalBuffer.WIDTH; x++)
                    buf.writeAt(x, sy, " ");
            }
        }

        // ── Cursor ──
        int csx = LINE_W + (t.cursorCol - t.scrollX);
        int csy = CODE_TOP + (t.cursorRow - t.scrollY);
        if (csx >= LINE_W && csx < TerminalBuffer.WIDTH
                && csy >= CODE_TOP && csy < CODE_TOP + CODE_ROWS)
            buf.setCursorPos(csx, csy);

        // ── Bracket match ──
        renderBracketMatch(buf, t);

        // ── Footer ──
        buf.setTextColor(0);
        buf.setBackgroundColor(7);
        buf.hLine(0, TerminalBuffer.WIDTH - 1, TerminalBuffer.HEIGHT - 1, ' ');
        if (mode == Mode.FIND) {
            buf.writeAt(0, TerminalBuffer.HEIGHT - 1,
                clip(" Find: " + modeInput + "_  Enter:Next Esc:X", TerminalBuffer.WIDTH));
        } else if (mode == Mode.GOTO_LINE) {
            buf.writeAt(0, TerminalBuffer.HEIGHT - 1,
                clip(" Go to line: " + modeInput + "_  Esc:Cancel", TerminalBuffer.WIDTH));
        } else if (mode == Mode.NEW_FILE) {
            buf.writeAt(0, TerminalBuffer.HEIGHT - 1,
                clip(" Open file: " + modeInput + "_  Esc:Cancel", TerminalBuffer.WIDTH));
        } else if (statusTicks > 0) {
            buf.writeAt(0, TerminalBuffer.HEIGHT - 1,
                clip(" " + statusMsg, TerminalBuffer.WIDTH));
        } else {
            String lang = t.filePath.endsWith(".lua") ? "Lua"
                        : (t.filePath.endsWith(".java") ? "Java" : "Text");
            String info = String.format(" Ln %d Col %d | %d lines | %s | F5:Run F10:Undo",
                t.cursorRow + 1, t.cursorCol + 1, t.lines.size(), lang);
            buf.writeAt(0, TerminalBuffer.HEIGHT - 1, clip(info, TerminalBuffer.WIDTH));
        }
    }

    // ── Syntax highlighting ─────────────────────────────────

    private void renderHighlightedLine(TerminalBuffer buf, String line,
                                       int sx, int y, int scrollX, int visW) {
        String visible = line.length() > scrollX
            ? line.substring(scrollX, Math.min(line.length(), scrollX + visW)) : "";

        // Clear
        buf.setTextColor(0);
        buf.setBackgroundColor(15);
        for (int i = 0; i < visW; i++) buf.writeAt(sx + i, y, " ");
        if (visible.isEmpty()) return;

        // Find highlight spans
        int findStart = -1, findEnd = -1;
        if (!findQuery.isEmpty()) {
            int idx = line.indexOf(findQuery, scrollX);
            if (idx >= scrollX && idx < scrollX + visW) {
                findStart = idx - scrollX;
                findEnd = findStart + findQuery.length();
            }
        }

        boolean isLua = tab().filePath.endsWith(".lua");
        Set<String> keywords = isLua ? LUA_KW : JAVA_KW;

        int i = 0;
        while (i < visible.length()) {
            int px = sx + i;
            if (px >= TerminalBuffer.WIDTH) break;

            // Find highlight background
            boolean inFind = (findStart >= 0 && i >= findStart && i < findEnd);
            buf.setBackgroundColor(inFind ? 4 : 15);

            // Comments: // or --
            if (i < visible.length() - 1) {
                String two = visible.substring(i, i + 2);
                if (two.equals("//") || two.equals("--")) {
                    buf.setTextColor(13);
                    for (int j = i; j < visible.length() && (sx + j) < TerminalBuffer.WIDTH; j++) {
                        buf.setBackgroundColor(findStart >= 0 && j >= findStart && j < findEnd ? 4 : 15);
                        buf.writeAt(sx + j, y, String.valueOf(visible.charAt(j)));
                    }
                    return;
                }
            }

            // Strings
            if (visible.charAt(i) == '"' || visible.charAt(i) == '\'') {
                char q = visible.charAt(i);
                buf.setTextColor(1);
                int end = visible.indexOf(q, i + 1);
                if (end < 0) end = visible.length() - 1;
                for (int j = i; j <= end && (sx + j) < TerminalBuffer.WIDTH; j++) {
                    buf.setBackgroundColor(findStart >= 0 && j >= findStart && j < findEnd ? 4 : 15);
                    buf.writeAt(sx + j, y, String.valueOf(visible.charAt(j)));
                }
                i = end + 1;
                continue;
            }

            // Numbers
            if (Character.isDigit(visible.charAt(i))) {
                buf.setTextColor(4);
                while (i < visible.length()
                        && (Character.isDigit(visible.charAt(i)) || visible.charAt(i) == '.')) {
                    buf.setBackgroundColor(findStart >= 0 && i >= findStart && i < findEnd ? 4 : 15);
                    if ((sx + i) < TerminalBuffer.WIDTH)
                        buf.writeAt(sx + i, y, String.valueOf(visible.charAt(i)));
                    i++;
                }
                continue;
            }

            // Words (keywords / function calls)
            if (Character.isLetter(visible.charAt(i)) || visible.charAt(i) == '_') {
                int start = i;
                while (i < visible.length()
                        && (Character.isLetterOrDigit(visible.charAt(i)) || visible.charAt(i) == '_'))
                    i++;
                String word = visible.substring(start, i);
                boolean isFunc = (i < visible.length() && visible.charAt(i) == '(');
                int color;
                if (keywords.contains(word)) color = 9;      // cyan = keyword
                else if (isFunc)             color = 3;       // light blue = function
                else                         color = 0;       // white = identifier
                buf.setTextColor(color);
                for (int j = start; j < i && (sx + j) < TerminalBuffer.WIDTH; j++) {
                    buf.setBackgroundColor(findStart >= 0 && j >= findStart && j < findEnd ? 4 : 15);
                    buf.writeAt(sx + j, y, String.valueOf(visible.charAt(j)));
                }
                continue;
            }

            // Operators / symbols
            buf.setTextColor(8);
            buf.writeAt(px, y, String.valueOf(visible.charAt(i)));
            i++;
        }
        buf.setBackgroundColor(15);
    }

    // ── Bracket matching ────────────────────────────────────

    private void renderBracketMatch(TerminalBuffer buf, Tab t) {
        if (t.cursorRow >= t.lines.size()) return;
        String line = t.lines.get(t.cursorRow).toString();
        if (t.cursorCol >= line.length()) return;
        char c = line.charAt(t.cursorCol);
        char match;
        int dir;
        switch (c) {
            case '(' -> { match = ')'; dir = 1; }
            case ')' -> { match = '('; dir = -1; }
            case '[' -> { match = ']'; dir = 1; }
            case ']' -> { match = '['; dir = -1; }
            case '{' -> { match = '}'; dir = 1; }
            case '}' -> { match = '{'; dir = -1; }
            default  -> { return; }
        }

        int depth = 0;
        int row = t.cursorRow, col = t.cursorCol;
        while (row >= 0 && row < t.lines.size()) {
            String l = t.lines.get(row).toString();
            while (col >= 0 && col < l.length()) {
                char ch = l.charAt(col);
                if (ch == c) depth++;
                else if (ch == match) depth--;
                if (depth == 0) {
                    int sx = LINE_W + (col - t.scrollX);
                    int sy = CODE_TOP + (row - t.scrollY);
                    if (sx >= LINE_W && sx < TerminalBuffer.WIDTH
                            && sy >= CODE_TOP && sy < CODE_TOP + CODE_ROWS) {
                        buf.setTextColor(4);
                        buf.setBackgroundColor(8);
                        buf.writeAt(sx, sy, String.valueOf(ch));
                    }
                    return;
                }
                col += dir;
            }
            row += dir;
            if (row >= 0 && row < t.lines.size()) {
                col = dir > 0 ? 0 : t.lines.get(row).length() - 1;
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────

    private void setStatus(String msg) {
        statusMsg = msg;
        statusTicks = 60;
        needsRedraw = true;
    }

    private String clip(String s, int max) {
        return max <= 0 ? "" : (s.length() <= max ? s : s.substring(0, max));
    }
}

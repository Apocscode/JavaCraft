package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.block.entity.PrinterBlockEntity;
import com.apocscode.byteblock.block.entity.DriveBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

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
        String forcedLang; // null = auto-detect, "Lua" / "Java" / "Text"
        // Undo
        final List<List<String>> undoStack = new ArrayList<>();
        int undoPointer = -1;
        static final int MAX_UNDO = 30;

        Tab(String path) {
            this.filePath = path;
            this.lines.add(new StringBuilder());
        }

        String getLang() {
            if (forcedLang != null) return forcedLang;
            if (filePath.endsWith(".lua")) return "Lua";
            if (filePath.endsWith(".java")) return "Java";
            return "Text";
        }

        void cycleLang() {
            String cur = getLang();
            forcedLang = switch (cur) {
                case "Lua"  -> "Java";
                case "Java" -> "Text";
                default     -> "Lua";
            };
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
            applySnapshot(undoStack.get(undoPointer));
        }

        void redo() {
            if (undoPointer >= undoStack.size() - 1) return;
            undoPointer++;
            applySnapshot(undoStack.get(undoPointer));
        }

        private void applySnapshot(List<String> snapshot) {
            lines.clear();
            for (String s : snapshot) lines.add(new StringBuilder(s));
            cursorRow = Math.min(cursorRow, lines.size() - 1);
            cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
            modified = true;
        }

        String commentPrefix() {
            String lang = getLang();
            if ("Lua".equals(lang)) return "-- ";
            if ("Java".equals(lang)) return "// ";
            return "# ";
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

    enum Mode { EDITING, FIND, GOTO_LINE, NEW_FILE, REPLACE_FIND, REPLACE_WITH }

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
    private String replaceFindBuf = ""; // captured between REPLACE_FIND and REPLACE_WITH

    // Selection (anchor stays fixed, cursor moves)
    private boolean selActive;
    private int selAnchorRow, selAnchorCol;

    // Sidebar file explorer
    private boolean sidebarOpen;
    private static final int SIDEBAR_W = 14;
    private final List<String> sidebarFiles = new ArrayList<>();
    private final List<Boolean> sidebarIsDir = new ArrayList<>();
    private int sidebarScroll;
    private int sidebarCursor;
    private String sidebarCwd = "/Users/User/Documents";

    // Context menu
    private boolean ctxMenuOpen;
    private int ctxMenuX, ctxMenuY;
    private int ctxMenuHover = -1;
    private static final String[] CTX_ITEMS = {
        "Cut       ^X", "Copy      ^C", "Paste     ^V", "Select All ^A"
    };

    // File dropdown menu
    private boolean fileMenuOpen;
    private int fileMenuHover = -1;
    private static final String[] FILE_MENU_ITEMS = {
        "New       F7", "Open...   ^O", "Save      ^S", "Save As...   ", "---",
        "Print        ", "Save to Disk ", "Load frm Disk", "---",
        "Run       F5", "Shortcut  F4", "---", "Close Tab F9"
    };

    // Save As dialog
    private boolean saveAsOpen;
    private final StringBuilder saveAsInput = new StringBuilder();
    private int saveAsCursor;

    // Error reporting
    private int errorLine = -1;
    private String errorMsg = "";

    // Autocomplete
    private boolean autoOpen;
    private final List<String> autoSuggestions = new ArrayList<>();
    private int autoIndex;
    private String autoPrefix = "";
    private static final int AUTO_MAX_VISIBLE = 6;

    // Layout
    private static final int TAB_BAR  = 1;
    private static final int TOOLBAR  = 1;
    private static final int LINE_W   = 4;
    private static final int FOOTER   = 1;
    private static final int CODE_TOP  = TAB_BAR + TOOLBAR;
    private static final int CODE_ROWS = TerminalBuffer.HEIGHT - TAB_BAR - TOOLBAR - FOOTER;

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

    // Autocomplete API word list (common ByteBlock + Lua/Java standard library)
    private static final String[] AUTO_WORDS = {
        // Lua stdlib
        "print", "tostring", "tonumber", "type", "pairs", "ipairs", "next",
        "select", "unpack", "require", "pcall", "xpcall", "error", "assert",
        "setmetatable", "getmetatable", "rawget", "rawset", "rawlen",
        "string", "table", "math", "io", "os",
        "string.format", "string.sub", "string.find", "string.match", "string.gsub",
        "string.len", "string.upper", "string.lower", "string.rep", "string.byte",
        "table.insert", "table.remove", "table.sort", "table.concat",
        "math.floor", "math.ceil", "math.abs", "math.max", "math.min",
        "math.random", "math.sqrt", "math.sin", "math.cos",
        // ByteBlock robot API
        "robot.forward", "robot.back", "robot.up", "robot.down",
        "robot.turnLeft", "robot.turnRight", "robot.dig", "robot.digUp",
        "robot.digDown", "robot.place", "robot.placeUp", "robot.placeDown",
        "robot.detect", "robot.detectUp", "robot.detectDown",
        "robot.inspect", "robot.inspectUp", "robot.inspectDown",
        "robot.select", "robot.getItemCount", "robot.getFuelLevel",
        "robot.attack", "robot.suck", "robot.drop", "robot.compare",
        // ByteBlock drone API
        "drone.goto", "drone.hover", "drone.land", "drone.getPos",
        // Java common
        "System.out.println", "System.out.print",
        "ArrayList", "HashMap", "StringBuilder", "Integer", "Boolean",
        "public", "private", "protected", "static", "final", "void",
        "function", "local", "return", "end", "then", "elseif", "repeat", "until"
    };

    // ── Constructor ─────────────────────────────────────────

    public TextIDEProgram() { this("/Users/User/Documents/new.txt"); }

    public TextIDEProgram(String filePath) {
        super("IDE");
        this.tabs.add(new Tab(filePath));
    }

    private Tab tab() { return tabs.get(activeTab); }

    // ── Lifecycle ───────────────────────────────────────────

    @Override
    public void init(JavaOS os) {
        this.os = os;
        // Ensure test.lua exists for new users
        if (!os.getFileSystem().exists("/Users/User/Documents/test.lua")) {
            os.getFileSystem().writeFile("/Users/User/Documents/test.lua",
                "-- test.lua: IDE feature tester\n"
              + "\n"
              + "local function greet(name)\n"
              + "  print(\"Hello, \" .. name .. \"!\")\n"
              + "end\n"
              + "\n"
              + "local function add(a, b)\n"
              + "  return a + b\n"
              + "end\n"
              + "\n"
              + "-- math test\n"
              + "local x = add(3, 7)\n"
              + "print(\"3 + 7 = \" .. tostring(x))\n"
              + "\n"
              + "-- loop test\n"
              + "for i = 1, 5 do\n"
              + "  greet(\"User\" .. i)\n"
              + "end\n"
              + "\n"
              + "-- table test\n"
              + "local items = {\"pickaxe\", \"torch\", \"cobblestone\"}\n"
              + "for _, item in ipairs(items) do\n"
              + "  print(\"  > \" .. item)\n"
              + "end\n"
              + "\n"
              + "print(\"All tests passed!\")\n");
        }
        for (Tab t : tabs) loadTab(t);
        setStatus("IDE ready | F3:Exit  F1:Save  F5:Run");
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
        // Save As dialog intercepts all input
        if (saveAsOpen) {
            switch (event.getType()) {
                case CHAR -> handleSaveAsChar(event.getString(0).charAt(0));
                case KEY  -> handleSaveAsKey(event.getInt(0), event.getInt(2));
                default -> {}
            }
            needsRedraw = true;
            return;
        }
        switch (event.getType()) {
            case CHAR -> { handleChar(event.getString(0).charAt(0)); needsRedraw = true; }
            case KEY  -> { handleKey(event.getInt(0), event.getInt(2)); needsRedraw = true; }
            case MOUSE_CLICK -> {
                int btn = event.getInt(0);
                int mx = event.getInt(1), my = event.getInt(2);
                if (fileMenuOpen) {
                    handleFileMenuClick(mx, my);
                } else if (btn == 1) { showContextMenu(mx, my); }
                else { ctxMenuOpen = false; handleClick(mx, my); }
                needsRedraw = true;
            }
            case MOUSE_DRAG -> { handleDrag(event.getInt(1), event.getInt(2)); needsRedraw = true; }
            case MOUSE_SCROLL -> {
                int dir = event.getInt(0);
                int mx = event.getInt(1);
                if (sidebarOpen && mx < SIDEBAR_W) {
                    sidebarScroll = Math.max(0, sidebarScroll + dir);
                } else {
                    tab().scrollY = Math.max(0,
                        Math.min(tab().lines.size() - 1, tab().scrollY + dir * 3));
                }
                needsRedraw = true;
            }
            case PASTE -> { handlePaste(event.getString(0)); needsRedraw = true; }
            default -> {}
        }
    }

    // ── Character input ─────────────────────────────────────

    private void handleChar(char c) {
        fileMenuOpen = false;
        if (mode != Mode.EDITING) {
            if (c >= 32) modeInput.append(c);
            return;
        }
        ctxMenuOpen = false;
        Tab t = tab();
        if (hasSelection()) {
            deleteSelection();
        } else {
            t.pushUndo();
        }
        errorLine = -1;
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
        // Trigger autocomplete
        updateAutocomplete();
    }

    // ── Key input ───────────────────────────────────────────

    private void handleKey(int key, int mods) {
        fileMenuOpen = false;
        if (mode != Mode.EDITING) { handleModeKey(key); return; }

        boolean ctrl = (mods & 2) != 0;
        boolean shift = (mods & 1) != 0;

        // Autocomplete navigation (when popup is open)
        if (autoOpen && !ctrl) {
            switch (key) {
                case 265 -> { // Up
                    autoIndex = Math.max(0, autoIndex - 1);
                    return;
                }
                case 264 -> { // Down
                    autoIndex = Math.min(autoSuggestions.size() - 1, autoIndex + 1);
                    return;
                }
                case 258, 257, 335 -> { // Tab, Enter
                    acceptAutoComplete();
                    return;
                }
                case 256 -> { // Escape
                    autoOpen = false;
                    autoSuggestions.clear();
                    return;
                }
            }
        }

        // Ctrl shortcuts
        if (ctrl) {
            autoOpen = false;
            switch (key) {
                case 83 -> save();                        // Ctrl+S
                case 67 -> copySelection();               // Ctrl+C
                case 88 -> cutSelection();                // Ctrl+X
                case 65 -> selectAll();                   // Ctrl+A
                case 69 -> toggleSidebar();               // Ctrl+E
                case 70 -> enterMode(Mode.FIND);          // Ctrl+F
                case 72 -> enterMode(Mode.REPLACE_FIND);  // Ctrl+H find/replace
                case 90 -> { if (shift) tab().redo(); else tab().undo(); } // Ctrl+Z / Ctrl+Shift+Z
                case 89 -> tab().redo();                  // Ctrl+Y redo
                case 79 -> enterMode(Mode.NEW_FILE);      // Ctrl+O
                case 68 -> duplicateLine();               // Ctrl+D
                case 71 -> gistPush();                    // Ctrl+G push to gist
                case 47 -> toggleComment();               // Ctrl+/ comment toggle
            }
            return;
        }

        boolean alt = (mods & 4) != 0;
        if (alt) {
            switch (key) {
                case 265 -> { moveLineUp(); return; }    // Alt+Up
                case 264 -> { moveLineDown(); return; }  // Alt+Down
            }
        }

        ctxMenuOpen = false;
        Tab t = tab();
        switch (key) {
            case 259 -> {                                           // Backspace
                if (hasSelection()) { deleteSelection(); }
                else if (t.cursorCol > 0) {
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
                errorLine = -1;
            }
            case 261 -> {                                           // Delete
                if (hasSelection()) { deleteSelection(); }
                else if (t.cursorCol < t.lines.get(t.cursorRow).length()) {
                    t.pushUndo();
                    t.lines.get(t.cursorRow).deleteCharAt(t.cursorCol);
                    t.modified = true;
                } else if (t.cursorRow < t.lines.size() - 1) {
                    t.pushUndo();
                    t.lines.get(t.cursorRow).append(t.lines.get(t.cursorRow + 1));
                    t.lines.remove(t.cursorRow + 1);
                    t.modified = true;
                }
                errorLine = -1;
            }
            case 257, 335 -> {                                      // Enter (auto-indent)
                if (hasSelection()) deleteSelection();
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
                errorLine = -1;
            }
            case 258 -> {                                           // Tab = 2 spaces
                if (hasSelection()) deleteSelection();
                t.pushUndo();
                t.lines.get(t.cursorRow).insert(t.cursorCol, "  ");
                t.cursorCol += 2;
                t.modified = true;
            }
            // Cursor movement with Shift selection
            case 263 -> { selMove(shift); t.cursorCol = Math.max(0, t.cursorCol - 1); if (!shift) selActive = false; }
            case 262 -> { selMove(shift); t.cursorCol = Math.min(t.lines.get(t.cursorRow).length(), t.cursorCol + 1); if (!shift) selActive = false; }
            case 265 -> { selMove(shift); if (t.cursorRow > 0) { t.cursorRow--; clampCol(); } if (!shift) selActive = false; }
            case 264 -> { selMove(shift); if (t.cursorRow < t.lines.size() - 1) { t.cursorRow++; clampCol(); } if (!shift) selActive = false; }
            case 268 -> { selMove(shift); t.cursorCol = 0; if (!shift) selActive = false; }
            case 269 -> { selMove(shift); t.cursorCol = t.lines.get(t.cursorRow).length(); if (!shift) selActive = false; }
            case 266 -> { selMove(shift); t.cursorRow = Math.max(0, t.cursorRow - CODE_ROWS); clampCol(); if (!shift) selActive = false; }
            case 267 -> { selMove(shift); t.cursorRow = Math.min(t.lines.size()-1, t.cursorRow + CODE_ROWS); clampCol(); if (!shift) selActive = false; }
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
            case 300 -> { tab().cycleLang(); setStatus("Language: " + tab().getLang()); } // F11 cycle lang
            case 301 -> toggleSidebar();               // F12 toggle sidebar
        }
        ensureCursorVisible();
        // Update autocomplete after edits; dismiss on movement/function keys
        if (key == 259 || key == 261) {
            updateAutocomplete(); // Backspace / Delete
        } else if (key >= 262 && key <= 269 || key >= 290) {
            autoOpen = false; // Cursor movement or function keys dismiss popup
        }
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
                    case REPLACE_FIND -> {
                        replaceFindBuf = input;
                        modeInput.setLength(0);
                        mode = Mode.REPLACE_WITH;
                    }
                    case REPLACE_WITH -> {
                        doReplaceAll(replaceFindBuf, input);
                        mode = Mode.EDITING;
                    }
                    default -> {}
                }
            }
        }
    }

    // ── Mouse / paste ───────────────────────────────────────

    private void handleClick(int mx, int my) {
        Tab t = tab();
        if (my == 0) { handleTabBarClick(mx); return; }
        if (my == TAB_BAR) { handleToolbarClick(mx); return; }
        mode = Mode.EDITING;
        int sideOff = sidebarOpen ? SIDEBAR_W : 0;

        // Sidebar click
        if (sidebarOpen && mx < SIDEBAR_W && my >= CODE_TOP && my < CODE_TOP + CODE_ROWS) {
            handleSidebarClick(mx, my);
            return;
        }

        // Context menu click
        if (ctxMenuOpen) {
            handleCtxMenuClick(mx, my);
            return;
        }

        // Code area click
        if (my >= CODE_TOP && my < CODE_TOP + CODE_ROWS && mx >= sideOff + LINE_W) {
            int row = t.scrollY + (my - CODE_TOP);
            if (row < t.lines.size()) {
                t.cursorRow = row;
                int col = t.scrollX + (mx - sideOff - LINE_W);
                t.cursorCol = Math.max(0, Math.min(t.lines.get(t.cursorRow).length(), col));
            }
            selActive = false;
            selAnchorRow = t.cursorRow;
            selAnchorCol = t.cursorCol;
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

    private void handleToolbarClick(int mx) {
        if (mx >= 0  && mx < 7)  { fileMenuOpen = !fileMenuOpen; fileMenuHover = -1; return; } // [File\u25BC]
        if (mx >= 8  && mx < 13) { runFile();                return; } // [Run]
        if (mx >= 14 && mx < 20) { enterMode(Mode.FIND);     return; } // [Find]
        if (mx >= 21 && mx < 27) { enterMode(Mode.NEW_FILE); return; } // [+Tab]
        if (mx >= 28 && mx < 34) { closeTab();               return; } // [-Tab]
        if (mx >= 35 && mx < 42) { toggleSidebar();          return; } // [Files]
        if (mx >= 43 && mx < 46) { running = false;            return; } // [X]
        if (mx >= 72) { tab().cycleLang(); setStatus("Language: " + tab().getLang()); }
    }

    private void handleFileMenuClick(int mx, int my) {
        fileMenuOpen = false;
        // Menu renders at x=0, y=CODE_TOP, each item 1 row
        int menuX = 0, menuW = 16;
        int menuY = CODE_TOP;
        int idx = my - menuY;
        if (mx < menuX || mx >= menuX + menuW || idx < 0 || idx >= FILE_MENU_ITEMS.length) return;
        String item = FILE_MENU_ITEMS[idx];
        if (item.equals("---")) return;
        handleFileMenuAction(idx);
    }

    private void handleFileMenuAction(int idx) {
        switch (idx) {
            case 0 -> enterMode(Mode.NEW_FILE);                    // New
            case 1 -> enterMode(Mode.NEW_FILE);                    // Open
            case 2 -> save();                                       // Save
            case 3 -> {                                             // Save As
                saveAsOpen = true;
                saveAsInput.setLength(0);
                saveAsInput.append(tab().filePath);
                saveAsCursor = saveAsInput.length();
            }
            case 5 -> doPrint();                                    // Print
            case 6 -> saveToDisk();                                 // Save to Disk
            case 7 -> loadFromDisk();                               // Load from Disk
            case 9 -> runFile();                                    // Run
            case 10 -> createShortcut();                            // Shortcut
            case 12 -> closeTab();                                  // Close Tab
        }
    }

    private void handlePaste(String text) {
        if (mode != Mode.EDITING || text == null || text.isEmpty()) return;
        Tab t = tab();
        if (hasSelection()) deleteSelection();
        else t.pushUndo();
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

    private void handleDrag(int mx, int my) {
        Tab t = tab();
        int sideOff = sidebarOpen ? SIDEBAR_W : 0;
        if (my >= CODE_TOP && my < CODE_TOP + CODE_ROWS && mx >= sideOff + LINE_W) {
            int row = t.scrollY + (my - CODE_TOP);
            row = Math.max(0, Math.min(t.lines.size() - 1, row));
            int col = t.scrollX + (mx - sideOff - LINE_W);
            col = Math.max(0, Math.min(t.lines.get(row).length(), col));
            t.cursorRow = row;
            t.cursorCol = col;
            selActive = true;
            ensureCursorVisible();
        }
    }

    // ── Selection ──────────────────────────────────────────

    private void selMove(boolean shift) {
        if (shift && !selActive) {
            selActive = true;
            Tab t = tab();
            selAnchorRow = t.cursorRow;
            selAnchorCol = t.cursorCol;
        }
    }

    private boolean hasSelection() {
        if (!selActive) return false;
        Tab t = tab();
        return selAnchorRow != t.cursorRow || selAnchorCol != t.cursorCol;
    }

    private int[] selStart() {
        Tab t = tab();
        if (selAnchorRow < t.cursorRow || (selAnchorRow == t.cursorRow && selAnchorCol <= t.cursorCol))
            return new int[]{selAnchorRow, selAnchorCol};
        return new int[]{t.cursorRow, t.cursorCol};
    }

    private int[] selEnd() {
        Tab t = tab();
        if (selAnchorRow < t.cursorRow || (selAnchorRow == t.cursorRow && selAnchorCol <= t.cursorCol))
            return new int[]{t.cursorRow, t.cursorCol};
        return new int[]{selAnchorRow, selAnchorCol};
    }

    private String getSelectedText() {
        if (!hasSelection()) return "";
        int[] s = selStart(), e = selEnd();
        Tab t = tab();
        if (s[0] == e[0]) return t.lines.get(s[0]).substring(s[1], e[1]);
        StringBuilder sb = new StringBuilder();
        sb.append(t.lines.get(s[0]).substring(s[1]));
        for (int i = s[0] + 1; i < e[0]; i++) sb.append('\n').append(t.lines.get(i));
        sb.append('\n').append(t.lines.get(e[0]), 0, e[1]);
        return sb.toString();
    }

    private void deleteSelection() {
        if (!hasSelection()) return;
        Tab t = tab();
        t.pushUndo();
        int[] s = selStart(), e = selEnd();
        if (s[0] == e[0]) {
            t.lines.get(s[0]).delete(s[1], e[1]);
        } else {
            String before = t.lines.get(s[0]).substring(0, s[1]);
            String after = t.lines.get(e[0]).substring(e[1]);
            t.lines.get(s[0]).setLength(0);
            t.lines.get(s[0]).append(before).append(after);
            for (int i = e[0]; i > s[0]; i--) t.lines.remove(i);
        }
        t.cursorRow = s[0];
        t.cursorCol = s[1];
        selActive = false;
        t.modified = true;
    }

    private void copySelection() {
        if (!hasSelection()) return;
        os.setClipboard(getSelectedText());
    }

    private void cutSelection() {
        if (!hasSelection()) return;
        copySelection();
        deleteSelection();
    }

    private void selectAll() {
        Tab t = tab();
        selAnchorRow = 0;
        selAnchorCol = 0;
        t.cursorRow = t.lines.size() - 1;
        t.cursorCol = t.lines.get(t.cursorRow).length();
        selActive = true;
    }

    // ── Sidebar ─────────────────────────────────────────────

    private void toggleSidebar() {
        sidebarOpen = !sidebarOpen;
        if (sidebarOpen) refreshSidebar();
    }

    private void refreshSidebar() {
        sidebarFiles.clear();
        sidebarIsDir.clear();
        if (!"/".equals(sidebarCwd)) {
            sidebarFiles.add("..");
            sidebarIsDir.add(true);
        }
        java.util.List<String> items = os.getFileSystem().list(sidebarCwd);
        if (items != null) {
            items.sort(String::compareToIgnoreCase);
            for (String item : items) {
                boolean isDir = os.getFileSystem().list(sidebarCwd + "/" + item) != null;
                sidebarFiles.add(item);
                sidebarIsDir.add(isDir);
            }
        }
        sidebarScroll = 0;
        sidebarCursor = 0;
    }

    private void handleSidebarClick(int mx, int my) {
        int idx = sidebarScroll + (my - CODE_TOP - 2);
        if (my - CODE_TOP < 2 || idx < 0 || idx >= sidebarFiles.size()) return;
        String entry = sidebarFiles.get(idx);
        boolean isDir = sidebarIsDir.get(idx);
        if (entry.equals("..")) {
            int slash = sidebarCwd.lastIndexOf('/');
            sidebarCwd = slash <= 0 ? "/" : sidebarCwd.substring(0, slash);
            refreshSidebar();
        } else if (isDir) {
            sidebarCwd = sidebarCwd.endsWith("/") ? sidebarCwd + entry : sidebarCwd + "/" + entry;
            refreshSidebar();
        } else {
            String path = sidebarCwd.endsWith("/") ? sidebarCwd + entry : sidebarCwd + "/" + entry;
            doNewFile(path);
        }
    }

    private void renderToolbar(TerminalBuffer buf) {
        String lang = tab().getLang();
        // Background fill
        buf.setBackgroundColor(8);
        buf.setTextColor(15);
        buf.hLine(0, TerminalBuffer.WIDTH - 1, TAB_BAR, ' ');
        // [File\u25BC] — blue
        buf.setBackgroundColor(fileMenuOpen ? 9 : 1); buf.setTextColor(15);
        buf.writeAt(0, TAB_BAR, "[File\u25BC]");
        // [Run] — green
        buf.setBackgroundColor(2); buf.setTextColor(15);
        buf.writeAt(8, TAB_BAR, "[Run]");
        // [Find] — gray
        buf.setBackgroundColor(8); buf.setTextColor(11);
        buf.writeAt(14, TAB_BAR, "[Find]");
        // [+Tab] — gray
        buf.setBackgroundColor(8); buf.setTextColor(7);
        buf.writeAt(21, TAB_BAR, "[+Tab]");
        // [-Tab] — gray
        buf.writeAt(28, TAB_BAR, "[-Tab]");
        // [Files] — gray/cyan if open
        buf.setBackgroundColor(sidebarOpen ? 9 : 8); buf.setTextColor(sidebarOpen ? 15 : 7);
        buf.writeAt(35, TAB_BAR, "[Files]");
        // [X] — red close button
        buf.setBackgroundColor(14); buf.setTextColor(15);
        buf.writeAt(43, TAB_BAR, "[X]");
        // Language badge — right-aligned, color by lang
        String badge = "[" + lang + "]";
        int badgeX = TerminalBuffer.WIDTH - badge.length();
        int badgeBg = lang.equals("Lua") ? 5 : lang.equals("Java") ? 6 : 8;
        buf.setBackgroundColor(badgeBg); buf.setTextColor(15);
        buf.writeAt(badgeX, TAB_BAR, badge);
    }

    private void renderFileMenu(TerminalBuffer buf) {
        int menuX = 0, menuW = 16;
        int menuY = CODE_TOP;
        for (int i = 0; i < FILE_MENU_ITEMS.length; i++) {
            int y = menuY + i;
            if (y >= TerminalBuffer.HEIGHT - 1) break;
            String item = FILE_MENU_ITEMS[i];
            if (item.equals("---")) {
                buf.setBackgroundColor(7); buf.setTextColor(8);
                for (int x = menuX; x < menuX + menuW; x++) buf.writeAt(x, y, "\u2500");
            } else {
                buf.setBackgroundColor(0); buf.setTextColor(15);
                StringBuilder sb = new StringBuilder(" ").append(item);
                while (sb.length() < menuW) sb.append(' ');
                buf.writeAt(menuX, y, sb.toString());
            }
        }
        // Border right edge
        buf.setBackgroundColor(8); buf.setTextColor(7);
        for (int i = 0; i < FILE_MENU_ITEMS.length; i++) {
            int y = menuY + i;
            if (y >= TerminalBuffer.HEIGHT - 1) break;
            buf.writeAt(menuX + menuW, y, "\u2502");
        }
        // Bottom border
        int botY = menuY + FILE_MENU_ITEMS.length;
        if (botY < TerminalBuffer.HEIGHT - 1) {
            buf.setBackgroundColor(8); buf.setTextColor(7);
            for (int x = menuX; x <= menuX + menuW; x++) buf.writeAt(x, botY, "\u2500");
        }
    }

    private void renderSidebar(TerminalBuffer buf) {
        int top = CODE_TOP;
        int h = CODE_ROWS;
        // Background + separator
        for (int y = top; y < top + h; y++) {
            buf.setBackgroundColor(15);
            for (int x = 0; x < SIDEBAR_W - 1; x++) buf.writeAt(x, y, " ");
            buf.setTextColor(8);
            buf.writeAt(SIDEBAR_W - 1, y, "\u2502");
        }
        // Header
        buf.setTextColor(9);
        buf.setBackgroundColor(15);
        buf.writeAt(1, top, clip(sidebarCwd, SIDEBAR_W - 2));
        // Separator
        buf.setTextColor(8);
        for (int x = 0; x < SIDEBAR_W - 1; x++) buf.writeAt(x, top + 1, "\u2500");
        // Files
        for (int i = 0; i < h - 2; i++) {
            int idx = sidebarScroll + i;
            int y = top + 2 + i;
            if (idx >= sidebarFiles.size()) continue;
            String entry = sidebarFiles.get(idx);
            boolean isDir = sidebarIsDir.get(idx);
            buf.setBackgroundColor(15);
            buf.setTextColor(isDir ? 9 : (entry.endsWith(".lua") ? 5 : 0));
            String prefix = isDir ? "\u25B8 " : "  ";
            buf.writeAt(0, y, clip(prefix + entry, SIDEBAR_W - 1));
        }
    }

    // ── Context menu ────────────────────────────────────────

    private void showContextMenu(int mx, int my) {
        ctxMenuX = mx;
        ctxMenuY = my;
        ctxMenuOpen = true;
        ctxMenuHover = -1;
    }

    private void handleCtxMenuClick(int mx, int my) {
        int menuW = 16;
        int menuH = CTX_ITEMS.length;
        if (mx >= ctxMenuX && mx < ctxMenuX + menuW && my >= ctxMenuY && my < ctxMenuY + menuH) {
            int idx = my - ctxMenuY;
            switch (idx) {
                case 0 -> cutSelection();
                case 1 -> copySelection();
                case 2 -> {
                    String clip = os.getClipboard();
                    if (clip != null) handlePaste(clip);
                }
                case 3 -> selectAll();
            }
        }
        ctxMenuOpen = false;
    }

    private void renderCtxMenu(TerminalBuffer buf) {
        if (!ctxMenuOpen) return;
        int menuW = 16;
        for (int i = 0; i < CTX_ITEMS.length; i++) {
            int y = ctxMenuY + i;
            if (y >= TerminalBuffer.HEIGHT) break;
            buf.setBackgroundColor(8);
            buf.setTextColor(0);
            int x = ctxMenuX;
            for (int j = 0; j < menuW && x + j < TerminalBuffer.WIDTH; j++) {
                buf.writeAt(x + j, y, " ");
            }
            buf.writeAt(x + 1, y, clip(CTX_ITEMS[i], menuW - 2));
        }
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
        } else if (t.filePath.endsWith(".bsh") || t.filePath.endsWith(".java")) {
            os.launchProgram(new JavaShellProgram(t.filePath));
            setStatus("Running: " + t.filePath);
        } else {
            setStatus("Only .lua / .bsh / .java can be run (F5)");
        }
    }

    // ── Editing helpers ─────────────────────────────────────

    private void duplicateLine() {
        Tab t = tab();
        t.pushUndo();
        if (hasSelection()) {
            // Duplicate selection range whole-line: simpler — duplicate cursor row
        }
        String src = t.lines.get(t.cursorRow).toString();
        t.lines.add(t.cursorRow + 1, new StringBuilder(src));
        t.cursorRow++;
        t.modified = true;
        setStatus("Line duplicated");
    }

    private void moveLineUp() {
        Tab t = tab();
        if (t.cursorRow <= 0) return;
        t.pushUndo();
        StringBuilder cur = t.lines.remove(t.cursorRow);
        t.lines.add(t.cursorRow - 1, cur);
        t.cursorRow--;
        t.modified = true;
        ensureCursorVisible();
    }

    private void moveLineDown() {
        Tab t = tab();
        if (t.cursorRow >= t.lines.size() - 1) return;
        t.pushUndo();
        StringBuilder cur = t.lines.remove(t.cursorRow);
        t.lines.add(t.cursorRow + 1, cur);
        t.cursorRow++;
        t.modified = true;
        ensureCursorVisible();
    }

    private void toggleComment() {
        Tab t = tab();
        String prefix = t.commentPrefix();
        String trimmed = prefix.trim();
        int r0, r1;
        if (hasSelection()) {
            r0 = Math.min(selAnchorRow, t.cursorRow);
            r1 = Math.max(selAnchorRow, t.cursorRow);
        } else { r0 = r1 = t.cursorRow; }
        // Decide: if every non-blank line in range starts with prefix → uncomment, else comment.
        boolean allCommented = true;
        boolean anyContent = false;
        for (int r = r0; r <= r1; r++) {
            String s = t.lines.get(r).toString();
            String ls = s.stripLeading();
            if (ls.isEmpty()) continue;
            anyContent = true;
            if (!ls.startsWith(trimmed)) { allCommented = false; break; }
        }
        t.pushUndo();
        for (int r = r0; r <= r1; r++) {
            StringBuilder sb = t.lines.get(r);
            String s = sb.toString();
            if (s.stripLeading().isEmpty()) continue;
            if (allCommented && anyContent) {
                int idx = s.indexOf(trimmed);
                if (idx >= 0) {
                    int rm = trimmed.length();
                    if (idx + rm < s.length() && s.charAt(idx + rm) == ' ') rm++;
                    sb.delete(idx, idx + rm);
                }
            } else {
                int indent = 0;
                while (indent < s.length() && s.charAt(indent) == ' ') indent++;
                sb.insert(indent, prefix);
            }
        }
        t.modified = true;
        clampCol();
        setStatus((allCommented && anyContent) ? "Uncommented" : "Commented");
    }

    private void doReplaceAll(String findStr, String replaceStr) {
        if (findStr.isEmpty()) { setStatus("Empty find string"); return; }
        Tab t = tab();
        t.pushUndo();
        int count = 0;
        for (int r = 0; r < t.lines.size(); r++) {
            StringBuilder sb = t.lines.get(r);
            String s = sb.toString();
            int idx = 0;
            StringBuilder out = new StringBuilder();
            while (true) {
                int hit = s.indexOf(findStr, idx);
                if (hit < 0) { out.append(s, idx, s.length()); break; }
                out.append(s, idx, hit).append(replaceStr);
                idx = hit + findStr.length();
                count++;
            }
            if (count > 0) sb.replace(0, sb.length(), out.toString());
        }
        if (count > 0) t.modified = true;
        clampCol();
        setStatus("Replaced " + count + " occurrence" + (count == 1 ? "" : "s"));
    }

    private void gistPush() {
        save();
        Tab t = tab();
        String content = String.join("\n", t.lines.stream().map(StringBuilder::toString).toList());
        String name = t.filePath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        String token = readSettingString("gist.token");
        if (token == null || token.isEmpty()) {
            setStatus("Set settings.gist.token first (try: settings set gist.token <PAT>)");
            return;
        }
        setStatus("Pushing to gist...");
        final String fName = name;
        final String fContent = content;
        new Thread(() -> {
            try {
                String body = "{\"public\":false,\"files\":{\"" + jsonEsc(fName) + "\":{\"content\":\"" + jsonEsc(fContent) + "\"}}}";
                java.net.http.HttpClient cli = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.github.com/gists"))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
                java.net.http.HttpResponse<String> resp = cli.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    String b = resp.body();
                    int i = b.indexOf("\"html_url\"");
                    String url = "";
                    if (i >= 0) {
                        int q1 = b.indexOf('"', i + 11);
                        int q2 = b.indexOf('"', q1 + 1);
                        if (q1 > 0 && q2 > q1) url = b.substring(q1 + 1, q2);
                    }
                    setStatus("Gist: " + (url.isEmpty() ? "OK" : url));
                } else {
                    setStatus("Gist push failed: HTTP " + resp.statusCode());
                }
            } catch (Exception e) {
                setStatus("Gist error: " + e.getMessage());
            }
        }, "ide-gist-push").start();
    }

    private String readSettingString(String key) {
        String raw = os.getFileSystem().readFile("/Users/User/.settings");
        if (raw == null) return null;
        // Lightweight extraction: "key":"value"
        String needle = "\"" + key + "\"";
        int i = raw.indexOf(needle);
        if (i < 0) return null;
        int colon = raw.indexOf(':', i + needle.length());
        if (colon < 0) return null;
        int q1 = raw.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        StringBuilder out = new StringBuilder();
        boolean esc = false;
        for (int p = q1 + 1; p < raw.length(); p++) {
            char c = raw.charAt(p);
            if (esc) { out.append(c); esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') break;
            out.append(c);
        }
        return out.toString();
    }

    private static String jsonEsc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.toString();
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
        os.getFileSystem().writeFile("/Users/User/Desktop/" + safe + ".lnk",
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

    private void doSaveAs(String path) {
        if (path.isEmpty()) return;
        if (!path.startsWith("/")) path = "/Users/User/Documents/" + path;
        tab().filePath = path;
        save();
    }

    // ── Save As dialog ──────────────────────────────────────

    private void handleSaveAsKey(int key, int mods) {
        boolean ctrl = (mods & 2) != 0;
        switch (key) {
            case 256 -> saveAsOpen = false;                          // Escape
            case 259 -> {                                             // Backspace
                if (saveAsCursor > 0) {
                    saveAsInput.deleteCharAt(saveAsCursor - 1);
                    saveAsCursor--;
                }
            }
            case 261 -> {                                             // Delete
                if (saveAsCursor < saveAsInput.length())
                    saveAsInput.deleteCharAt(saveAsCursor);
            }
            case 263 -> { if (saveAsCursor > 0) saveAsCursor--; }     // Left
            case 262 -> {                                             // Right
                if (saveAsCursor < saveAsInput.length()) saveAsCursor++;
            }
            case 268 -> saveAsCursor = 0;                             // Home
            case 269 -> saveAsCursor = saveAsInput.length();          // End
            case 257, 335 -> {                                        // Enter
                saveAsOpen = false;
                doSaveAs(saveAsInput.toString());
            }
            default -> {
                if (ctrl && key == 65) {                              // Ctrl+A select all
                    saveAsCursor = saveAsInput.length();
                }
            }
        }
    }

    private void handleSaveAsChar(char c) {
        if (c >= 32) {
            saveAsInput.insert(saveAsCursor, c);
            saveAsCursor++;
        }
    }

    private void renderSaveAsDialog(TerminalBuffer buf) {
        int dlgW = 40;
        int dlgH = 7;
        int dlgX = (TerminalBuffer.WIDTH - dlgW) / 2;
        int dlgY = (TerminalBuffer.HEIGHT - dlgH) / 2;

        // Draw dialog background
        buf.setBackgroundColor(0); buf.setTextColor(15);
        for (int y = dlgY; y < dlgY + dlgH; y++)
            for (int x = dlgX; x < dlgX + dlgW; x++)
                buf.writeAt(x, y, " ");

        // Border top/bottom
        buf.setTextColor(7);
        for (int x = dlgX; x < dlgX + dlgW; x++) {
            buf.writeAt(x, dlgY, "\u2500");
            buf.writeAt(x, dlgY + dlgH - 1, "\u2500");
        }
        // Border sides
        for (int y = dlgY; y < dlgY + dlgH; y++) {
            buf.writeAt(dlgX, y, "\u2502");
            buf.writeAt(dlgX + dlgW - 1, y, "\u2502");
        }
        // Corners
        buf.writeAt(dlgX, dlgY, "\u250C");
        buf.writeAt(dlgX + dlgW - 1, dlgY, "\u2510");
        buf.writeAt(dlgX, dlgY + dlgH - 1, "\u2514");
        buf.writeAt(dlgX + dlgW - 1, dlgY + dlgH - 1, "\u2518");

        // Title
        buf.setTextColor(11);
        buf.writeAt(dlgX + 2, dlgY, " Save As ");

        // Label
        buf.setTextColor(15);
        buf.writeAt(dlgX + 2, dlgY + 2, "File path:");

        // Input field background
        int fieldX = dlgX + 2;
        int fieldY = dlgY + 3;
        int fieldW = dlgW - 4;
        buf.setBackgroundColor(8); buf.setTextColor(15);
        for (int x = fieldX; x < fieldX + fieldW; x++) buf.writeAt(x, fieldY, " ");

        // Input text (scroll if longer than field)
        String text = saveAsInput.toString();
        int scroll = Math.max(0, saveAsCursor - fieldW + 1);
        String visible = text.substring(scroll, Math.min(text.length(), scroll + fieldW));
        buf.writeAt(fieldX, fieldY, visible);

        // Cursor
        int cursorScreenX = fieldX + (saveAsCursor - scroll);
        if (cursorScreenX >= fieldX && cursorScreenX < fieldX + fieldW) {
            buf.setBackgroundColor(15); buf.setTextColor(0);
            char curChar = saveAsCursor < text.length() ? text.charAt(saveAsCursor) : ' ';
            buf.writeAt(cursorScreenX, fieldY, String.valueOf(curChar));
        }

        // Hints
        buf.setBackgroundColor(0); buf.setTextColor(7);
        buf.writeAt(dlgX + 2, dlgY + 5, "Enter:Save  Esc:Cancel");
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
        int visW = TerminalBuffer.WIDTH - (sidebarOpen ? SIDEBAR_W : 0) - LINE_W;
        if (t.cursorRow < t.scrollY) t.scrollY = t.cursorRow;
        if (t.cursorRow >= t.scrollY + CODE_ROWS) t.scrollY = t.cursorRow - CODE_ROWS + 1;
        if (t.cursorCol < t.scrollX) t.scrollX = t.cursorCol;
        if (t.cursorCol >= t.scrollX + visW) t.scrollX = t.cursorCol - visW + 1;
    }

    // ── Render ──────────────────────────────────────────────

    @Override
    public void render(TerminalBuffer buf) {
        Tab t = tab();
        int sideOff = sidebarOpen ? SIDEBAR_W : 0;
        int visW = TerminalBuffer.WIDTH - sideOff - LINE_W;

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

        // ── Toolbar ──
        renderToolbar(buf);

        // ── Sidebar ──
        if (sidebarOpen) renderSidebar(buf);

        // ── Code area ──
        int[] ss = hasSelection() ? selStart() : null;
        int[] se = hasSelection() ? selEnd() : null;
        for (int vy = 0; vy < CODE_ROWS; vy++) {
            int lineIdx = t.scrollY + vy;
            int sy = CODE_TOP + vy;

            // Line number gutter
            boolean isErrorLine = (lineIdx == errorLine);
            buf.setTextColor(isErrorLine ? 14 : 7);
            buf.setBackgroundColor(isErrorLine ? 14 : 15);
            if (lineIdx < t.lines.size()) {
                buf.writeAt(sideOff, sy, String.format("%3d ", lineIdx + 1));
            } else {
                buf.writeAt(sideOff, sy, "  ~ ");
            }

            // Code content
            buf.setBackgroundColor(15);
            if (lineIdx < t.lines.size()) {
                renderHighlightedLine(buf, t.lines.get(lineIdx).toString(),
                    sideOff + LINE_W, sy, t.scrollX, visW, lineIdx, ss, se);
            } else {
                buf.setTextColor(7);
                for (int x = sideOff + LINE_W; x < TerminalBuffer.WIDTH; x++)
                    buf.writeAt(x, sy, " ");
            }
        }

        // ── Cursor ──
        int csx = sideOff + LINE_W + (t.cursorCol - t.scrollX);
        int csy = CODE_TOP + (t.cursorRow - t.scrollY);
        if (csx >= sideOff + LINE_W && csx < TerminalBuffer.WIDTH
                && csy >= CODE_TOP && csy < CODE_TOP + CODE_ROWS)
            buf.setCursorPos(csx, csy);

        // ── Bracket match ──
        renderBracketMatch(buf, t, sideOff);

        // ── Autocomplete popup ──
        renderAutoComplete(buf);

        // ── Context menu overlay ──
        renderCtxMenu(buf);

        // ── File menu overlay ──
        if (fileMenuOpen) renderFileMenu(buf);

        // ── Save As dialog overlay ──
        if (saveAsOpen) renderSaveAsDialog(buf);

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
        } else if (mode == Mode.REPLACE_FIND) {
            buf.writeAt(0, TerminalBuffer.HEIGHT - 1,
                clip(" Replace find: " + modeInput + "_  Enter:Next Esc:Cancel", TerminalBuffer.WIDTH));
        } else if (mode == Mode.REPLACE_WITH) {
            buf.writeAt(0, TerminalBuffer.HEIGHT - 1,
                clip(" Replace \"" + replaceFindBuf + "\" with: " + modeInput + "_  Enter:All Esc:X", TerminalBuffer.WIDTH));
        } else if (errorLine >= 0 && errorMsg != null && !errorMsg.isEmpty()) {
            buf.setTextColor(14);
            buf.writeAt(0, TerminalBuffer.HEIGHT - 1,
                clip(" Error Ln " + (errorLine + 1) + ": " + errorMsg, TerminalBuffer.WIDTH));
        } else if (statusTicks > 0) {
            buf.writeAt(0, TerminalBuffer.HEIGHT - 1,
                clip(" " + statusMsg, TerminalBuffer.WIDTH));
        } else {
            String lang = t.getLang();
            String sel = hasSelection() ? " | Sel" : "";
            String info = String.format(" Ln %d Col %d | %d lines | %s%s | ^S ^C ^V ^E:sidebar",
                t.cursorRow + 1, t.cursorCol + 1, t.lines.size(), lang, sel);
            buf.writeAt(0, TerminalBuffer.HEIGHT - 1, clip(info, TerminalBuffer.WIDTH));
        }
    }

    // ── Syntax highlighting ─────────────────────────────────

    private void renderHighlightedLine(TerminalBuffer buf, String line,
                                       int sx, int y, int scrollX, int visW,
                                       int lineIdx, int[] ss, int[] se) {
        String visible = line.length() > scrollX
            ? line.substring(scrollX, Math.min(line.length(), scrollX + visW)) : "";

        // Selection range for this line (absolute column indices)
        int absSelStart = -1, absSelEnd = -1;
        if (ss != null && lineIdx >= ss[0] && lineIdx <= se[0]) {
            absSelStart = (lineIdx == ss[0]) ? ss[1] : 0;
            absSelEnd = (lineIdx == se[0]) ? se[1] : line.length();
        }

        // Clear
        buf.setTextColor(0);
        for (int ci = 0; ci < visW; ci++) {
            int absCol = scrollX + ci;
            boolean inSel = absSelStart >= 0 && absCol >= absSelStart && absCol < absSelEnd;
            buf.setBackgroundColor(inSel ? 11 : 15);
            buf.writeAt(sx + ci, y, " ");
        }
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

        boolean isLua = tab().getLang().equals("Lua");
        Set<String> keywords = isLua ? LUA_KW : JAVA_KW;

        int i = 0;
        while (i < visible.length()) {
            int px = sx + i;
            if (px >= TerminalBuffer.WIDTH) break;

            // Background: selection > find > default
            int absCol = scrollX + i;
            boolean inSel = absSelStart >= 0 && absCol >= absSelStart && absCol < absSelEnd;
            boolean inFind = (findStart >= 0 && i >= findStart && i < findEnd);
            buf.setBackgroundColor(inSel ? 11 : (inFind ? 4 : 15));

            // Comments: // or --
            if (i < visible.length() - 1) {
                String two = visible.substring(i, i + 2);
                if (two.equals("//") || two.equals("--")) {
                    buf.setTextColor(13);
                    for (int j = i; j < visible.length() && (sx + j) < TerminalBuffer.WIDTH; j++) {
                        int ac = scrollX + j;
                        boolean sel = absSelStart >= 0 && ac >= absSelStart && ac < absSelEnd;
                        boolean fnd = findStart >= 0 && j >= findStart && j < findEnd;
                        buf.setBackgroundColor(sel ? 11 : (fnd ? 4 : 15));
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
                    int ac = scrollX + j;
                    boolean sel = absSelStart >= 0 && ac >= absSelStart && ac < absSelEnd;
                    boolean fnd = findStart >= 0 && j >= findStart && j < findEnd;
                    buf.setBackgroundColor(sel ? 11 : (fnd ? 4 : 15));
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
                    int ac = scrollX + i;
                    boolean sel = absSelStart >= 0 && ac >= absSelStart && ac < absSelEnd;
                    boolean fnd = findStart >= 0 && i >= findStart && i < findEnd;
                    buf.setBackgroundColor(sel ? 11 : (fnd ? 4 : 15));
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
                    int ac = scrollX + j;
                    boolean sel = absSelStart >= 0 && ac >= absSelStart && ac < absSelEnd;
                    boolean fnd = findStart >= 0 && j >= findStart && j < findEnd;
                    buf.setBackgroundColor(sel ? 11 : (fnd ? 4 : 15));
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

    private void renderBracketMatch(TerminalBuffer buf, Tab t, int sideOff) {
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
                    int sx = sideOff + LINE_W + (col - t.scrollX);
                    int sy = CODE_TOP + (row - t.scrollY);
                    if (sx >= sideOff + LINE_W && sx < TerminalBuffer.WIDTH
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

    // ── Peripheral helpers ──────────────────────────────────

    private PrinterBlockEntity findPrinter() {
        Level level = os.getLevel();
        BlockPos pos = os.getBlockPos();
        if (level == null || pos == null) return null;
        for (Direction dir : Direction.values()) {
            BlockEntity be = level.getBlockEntity(pos.relative(dir));
            if (be instanceof PrinterBlockEntity printer) return printer;
        }
        return null;
    }

    private DriveBlockEntity findDrive() {
        java.util.Map<Character, DriveBlockEntity> drives = os.getMountedDrives();
        if (!drives.isEmpty()) {
            return drives.values().iterator().next();
        }
        return null;
    }

    private String getTabContent() {
        Tab t = tab();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(t.lines.get(i));
        }
        return sb.toString();
    }

    private String getTabFileName() {
        Tab t = tab();
        String path = t.filePath;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private void doPrint() {
        PrinterBlockEntity printer = findPrinter();
        if (printer == null) {
            setStatus("No printer found (place adjacent)");
            return;
        }
        String title = getTabFileName();
        String content = getTabContent();
        printer.queuePrint(title, content);
        setStatus("Sent to printer: " + title);
    }

    private void saveToDisk() {
        DriveBlockEntity drive = findDrive();
        if (drive == null) {
            setStatus("No drive found (place adjacent)");
            return;
        }
        if (!drive.hasDisk()) {
            setStatus("No disk in drive");
            return;
        }
        String name = getTabFileName();
        String path = "/" + name;
        String content = getTabContent();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new com.apocscode.byteblock.network.WriteToDiskPayload(drive.getBlockPos(), path, content, name));
        setStatus("Saved to disk: " + name);
    }

    private void loadFromDisk() {
        DriveBlockEntity drive = findDrive();
        if (drive == null) {
            setStatus("No drive found (place adjacent)");
            return;
        }
        if (!drive.hasDisk()) {
            setStatus("No disk in drive");
            return;
        }
        java.util.List<String> diskFiles = drive.listDiskFiles();
        if (diskFiles.isEmpty()) {
            setStatus("Disk is empty");
            return;
        }
        // Load the first file found on disk into a new tab
        String diskPath = diskFiles.get(0);
        String content = drive.readFromDisk(diskPath);
        if (content == null) {
            setStatus("Failed to read from disk");
            return;
        }
        String name = diskPath.startsWith("/") ? diskPath.substring(1) : diskPath;
        String localPath = "/Users/User/Documents/" + name;
        // Write to filesystem and open in a new tab
        os.getFileSystem().writeFile(localPath, content);
        Tab newTab = new Tab(localPath);
        tabs.add(newTab);
        activeTab = tabs.size() - 1;
        loadTab(newTab);
        setStatus("Loaded from disk: " + name);
    }

    // ── Autocomplete ────────────────────────────────────────

    /** Extract the word being typed at the cursor position */
    private String getCurrentWordPrefix() {
        Tab t = tab();
        if (t.cursorRow >= t.lines.size()) return "";
        String line = t.lines.get(t.cursorRow).toString();
        int end = t.cursorCol;
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(line.charAt(start - 1))
                || line.charAt(start - 1) == '_' || line.charAt(start - 1) == '.')) {
            start--;
        }
        return start < end ? line.substring(start, end) : "";
    }

    /** Update autocomplete suggestions based on current word prefix */
    private void updateAutocomplete() {
        String prefix = getCurrentWordPrefix();
        if (prefix.length() < 2) {
            autoOpen = false;
            autoSuggestions.clear();
            autoPrefix = "";
            return;
        }
        autoPrefix = prefix;
        String lower = prefix.toLowerCase();
        autoSuggestions.clear();

        // Match from API words and language keywords
        boolean isLua = tab().getLang().equals("Lua");
        Set<String> kw = isLua ? LUA_KW : JAVA_KW;
        for (String w : kw) {
            if (w.toLowerCase().startsWith(lower) && !w.equals(prefix))
                autoSuggestions.add(w);
        }
        for (String w : AUTO_WORDS) {
            if (w.toLowerCase().startsWith(lower) && !w.equals(prefix)
                    && !autoSuggestions.contains(w))
                autoSuggestions.add(w);
        }
        autoSuggestions.sort(String::compareToIgnoreCase);
        if (autoSuggestions.size() > 20) autoSuggestions.subList(20, autoSuggestions.size()).clear();

        autoOpen = !autoSuggestions.isEmpty();
        autoIndex = 0;
    }

    /** Accept the currently selected autocomplete suggestion */
    private void acceptAutoComplete() {
        if (!autoOpen || autoSuggestions.isEmpty()) return;
        String word = autoSuggestions.get(autoIndex);
        Tab t = tab();
        String line = t.lines.get(t.cursorRow).toString();
        int end = t.cursorCol;
        int start = end - autoPrefix.length();
        if (start < 0) start = 0;
        t.pushUndo();
        t.lines.get(t.cursorRow).replace(start, end, word);
        t.cursorCol = start + word.length();
        t.modified = true;
        autoOpen = false;
        autoSuggestions.clear();
    }

    private void renderAutoComplete(TerminalBuffer buf) {
        if (!autoOpen || autoSuggestions.isEmpty()) return;
        Tab t = tab();
        int sideOff = sidebarOpen ? SIDEBAR_W : 0;
        int popX = sideOff + LINE_W + (t.cursorCol - t.scrollX);
        int popY = CODE_TOP + (t.cursorRow - t.scrollY) + 1; // below cursor line
        int popW = 24;
        int visible = Math.min(autoSuggestions.size(), AUTO_MAX_VISIBLE);

        // Flip above cursor if near bottom
        if (popY + visible > CODE_TOP + CODE_ROWS) {
            popY = CODE_TOP + (t.cursorRow - t.scrollY) - visible;
        }
        // Clamp to screen
        if (popX + popW > TerminalBuffer.WIDTH) popX = TerminalBuffer.WIDTH - popW;
        if (popX < sideOff + LINE_W) popX = sideOff + LINE_W;

        for (int i = 0; i < visible; i++) {
            int idx = i;
            if (idx >= autoSuggestions.size()) break;
            int sy = popY + i;
            if (sy < CODE_TOP || sy >= CODE_TOP + CODE_ROWS) continue;
            boolean selected = (idx == autoIndex);
            buf.setBackgroundColor(selected ? 11 : 8); // blue highlight / dark gray
            buf.setTextColor(selected ? 0 : 9);         // white / cyan
            for (int x = 0; x < popW && popX + x < TerminalBuffer.WIDTH; x++) {
                buf.writeAt(popX + x, sy, " ");
            }
            buf.writeAt(popX + 1, sy, clip(autoSuggestions.get(idx), popW - 2));
        }
    }
}

package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.ContextMenu;
import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.computer.BitmapFont;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.SystemIcons;
import com.apocscode.byteblock.computer.VirtualFileSystem;
import com.apocscode.byteblock.block.entity.PrinterBlockEntity;
import com.apocscode.byteblock.block.entity.DriveBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows-style Notepad — simple single-document text editor.
 * Menu bar (File / Edit / View), word wrap, find, status bar.
 */
public class NotepadProgram extends OSProgram {

    // ── Document model ──────────────────────────────────────
    private final List<StringBuilder> lines = new ArrayList<>();
    private String filePath;   // null = untitled
    private boolean modified;

    // Cursor & scroll
    private int cursorRow, cursorCol;
    private int scrollX, scrollY;

    // Selection (anchor-based)
    private int selAnchorRow = -1, selAnchorCol = -1;

    // Find bar
    private boolean findOpen;
    private final StringBuilder findQuery = new StringBuilder();
    private int findCursor;
    private int findMatchRow = -1, findMatchCol = -1;

    // View options
    private boolean wordWrap;
    private boolean showStatusBar = true;

    // Menu state
    private enum Menu { NONE, FILE, EDIT, VIEW }
    private Menu openMenu = Menu.NONE;
    private int menuHover = -1;
    private final ContextMenu contextMenu = new ContextMenu();

    // Clipboard
    private String clipboard;

    // Status message
    private String statusMsg = "";
    private int statusTicks;

    // Save As dialog state
    private boolean saveAsOpen;
    private final StringBuilder saveAsPath = new StringBuilder();
    private int saveAsCursor;

    // Layout constants (pixel coords)
    private static final int CW = BitmapFont.CHAR_W;   // 8
    private static final int CH = BitmapFont.CHAR_H;    // 16
    private static final int MENU_H = 18;               // menu bar height
    private static final int STATUS_H = 16;             // status bar height

    // ── Constructors ────────────────────────────────────────

    public NotepadProgram() {
        super("Notepad");
        this.filePath = null;
        lines.add(new StringBuilder());
    }

    public NotepadProgram(String filePath) {
        super("Notepad");
        this.filePath = filePath;
        lines.add(new StringBuilder());
    }

    // ── Lifecycle ───────────────────────────────────────────

    @Override
    public void init(JavaOS os) {
        this.os = os;
        if (filePath != null && os.getFileSystem().isFile(filePath)) {
            loadFile(filePath);
        }
    }

    @Override
    public boolean tick() {
        if (statusTicks > 0) statusTicks--;
        return running;
    }

    // ── File I/O ────────────────────────────────────────────

    private void loadFile(String path) {
        String content = os.getFileSystem().readFile(path);
        lines.clear();
        if (content == null || content.isEmpty()) {
            lines.add(new StringBuilder());
        } else {
            for (String line : content.split("\n", -1)) {
                lines.add(new StringBuilder(line));
            }
        }
        filePath = path;
        modified = false;
        cursorRow = 0;
        cursorCol = 0;
        scrollX = 0;
        scrollY = 0;
        clearSelection();
    }

    private boolean saveFile() {
        if (filePath == null) {
            filePath = "/Users/User/Documents/untitled.txt";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        boolean ok = os.getFileSystem().writeFile(filePath, sb.toString());
        if (ok) modified = false;
        return ok;
    }

    private void newFile() {
        filePath = null;
        lines.clear();
        lines.add(new StringBuilder());
        modified = false;
        cursorRow = 0;
        cursorCol = 0;
        scrollX = 0;
        scrollY = 0;
        clearSelection();
    }

    // ── Selection helpers ───────────────────────────────────

    private boolean hasSelection() {
        return selAnchorRow >= 0 && (selAnchorRow != cursorRow || selAnchorCol != cursorCol);
    }

    private void clearSelection() {
        selAnchorRow = -1;
        selAnchorCol = -1;
    }

    private void startSelection() {
        if (selAnchorRow < 0) {
            selAnchorRow = cursorRow;
            selAnchorCol = cursorCol;
        }
    }

    private int[] selStart() {
        if (selAnchorRow < cursorRow || (selAnchorRow == cursorRow && selAnchorCol < cursorCol)) {
            return new int[]{selAnchorRow, selAnchorCol};
        }
        return new int[]{cursorRow, cursorCol};
    }

    private int[] selEnd() {
        if (selAnchorRow < cursorRow || (selAnchorRow == cursorRow && selAnchorCol < cursorCol)) {
            return new int[]{cursorRow, cursorCol};
        }
        return new int[]{selAnchorRow, selAnchorCol};
    }

    private String getSelectedText() {
        if (!hasSelection()) return "";
        int[] s = selStart();
        int[] e = selEnd();
        if (s[0] == e[0]) {
            return lines.get(s[0]).substring(s[1], e[1]);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(lines.get(s[0]).substring(s[1]));
        for (int r = s[0] + 1; r < e[0]; r++) {
            sb.append('\n').append(lines.get(r));
        }
        sb.append('\n').append(lines.get(e[0]).substring(0, e[1]));
        return sb.toString();
    }

    private void deleteSelection() {
        if (!hasSelection()) return;
        int[] s = selStart();
        int[] e = selEnd();
        if (s[0] == e[0]) {
            lines.get(s[0]).delete(s[1], e[1]);
        } else {
            StringBuilder first = lines.get(s[0]);
            StringBuilder last = lines.get(e[0]);
            first.delete(s[1], first.length());
            first.append(last.substring(e[1]));
            for (int r = e[0]; r > s[0]; r--) {
                lines.remove(r);
            }
        }
        cursorRow = s[0];
        cursorCol = s[1];
        modified = true;
        clearSelection();
    }

    private void selectAll() {
        selAnchorRow = 0;
        selAnchorCol = 0;
        cursorRow = lines.size() - 1;
        cursorCol = lines.get(cursorRow).length();
    }

    // ── Clipboard ───────────────────────────────────────────

    private void doCopy() {
        if (hasSelection()) clipboard = getSelectedText();
    }

    private void doCut() {
        if (hasSelection()) {
            clipboard = getSelectedText();
            deleteSelection();
        }
    }

    private void doPaste() {
        if (clipboard == null || clipboard.isEmpty()) return;
        if (hasSelection()) deleteSelection();
        String[] parts = clipboard.split("\n", -1);
        StringBuilder cur = lines.get(cursorRow);
        String after = cur.substring(cursorCol);
        cur.delete(cursorCol, cur.length());
        cur.append(parts[0]);
        if (parts.length == 1) {
            cursorCol = cur.length();
            cur.append(after);
        } else {
            for (int i = 1; i < parts.length; i++) {
                lines.add(cursorRow + i, new StringBuilder(parts[i]));
            }
            cursorRow += parts.length - 1;
            cursorCol = lines.get(cursorRow).length();
            lines.get(cursorRow).append(after);
        }
        modified = true;
    }

    // ── Find ────────────────────────────────────────────────

    private void findNext() {
        if (findQuery.isEmpty()) return;
        String q = findQuery.toString().toLowerCase();
        int startRow = findMatchRow >= 0 ? findMatchRow : cursorRow;
        int startCol = findMatchCol >= 0 ? findMatchCol + 1 : cursorCol;
        for (int i = 0; i < lines.size(); i++) {
            int r = (startRow + i) % lines.size();
            String line = lines.get(r).toString().toLowerCase();
            int from = (i == 0) ? startCol : 0;
            int idx = line.indexOf(q, from);
            if (idx >= 0) {
                findMatchRow = r;
                findMatchCol = idx;
                cursorRow = r;
                cursorCol = idx + q.length();
                selAnchorRow = r;
                selAnchorCol = idx;
                ensureCursorVisible();
                return;
            }
        }
        findMatchRow = -1;
        findMatchCol = -1;
    }

    // ── Scroll ──────────────────────────────────────────────

    private int getVisibleRows() {
        int h = PixelBuffer.SCREEN_H;
        int top = MENU_H;
        int bot = showStatusBar ? STATUS_H : 0;
        if (findOpen) bot += CH;
        return (h - top - bot) / CH;
    }

    private int getVisibleCols() {
        return PixelBuffer.SCREEN_W / CW;
    }

    private void ensureCursorVisible() {
        int visRows = getVisibleRows();
        if (cursorRow < scrollY) scrollY = cursorRow;
        if (cursorRow >= scrollY + visRows) scrollY = cursorRow - visRows + 1;
        int visCols = getVisibleCols();
        if (cursorCol < scrollX) scrollX = cursorCol;
        if (cursorCol >= scrollX + visCols - 1) scrollX = cursorCol - visCols + 2;
        if (scrollX < 0) scrollX = 0;
    }

    // ── Event handling ──────────────────────────────────────

    @Override
    public void handleEvent(OSEvent event) {
        // Context menu takes priority
        if (contextMenu.isVisible()) {
            switch (event.getType()) {
                case MOUSE_CLICK_PX -> {
                    String act = contextMenu.handleClick(event.getInt(1), event.getInt(2));
                    if (act != null && !act.isEmpty()) handleMenuAction(act);
                    contextMenu.hide();
                    return;
                }
                case MOUSE_DRAG_PX -> { contextMenu.handleMove(event.getInt(1), event.getInt(2)); return; }
                case KEY -> { contextMenu.hide(); }
            }
        }

        switch (event.getType()) {
            case KEY -> {
                int keyCode = event.getInt(0);
                int mods = event.getInt(2);
                if (saveAsOpen) {
                    handleSaveAsKey(keyCode);
                } else if (findOpen) {
                    handleFindKey(keyCode, mods);
                } else if (openMenu != Menu.NONE) {
                    handleMenuKey(keyCode);
                } else {
                    handleKey(keyCode, mods);
                }
            }
            case CHAR -> {
                char c = event.getString(0).charAt(0);
                if (saveAsOpen) {
                    handleSaveAsChar(c);
                } else if (findOpen) {
                    handleFindChar(c);
                } else if (openMenu == Menu.NONE) {
                    handleChar(c);
                }
            }
            case MOUSE_CLICK_PX -> {
                int btn = event.getInt(0);
                int px = event.getInt(1);
                int py = event.getInt(2);
                if (btn == 1) {
                    handleRightClick(px, py);
                } else {
                    handleClick(px, py, false);
                }
            }
            case MOUSE_SCROLL -> {
                int delta = event.getInt(0);
                scrollY = Math.max(0, Math.min(scrollY - delta * 3, lines.size() - 1));
            }
            case PASTE -> {
                String text = event.getString(0);
                if (text != null && !text.isEmpty()) {
                    clipboard = text;
                    doPaste();
                }
            }
        }
    }

    private void handleChar(char c) {
        if (c < 32 || c == 127) return;
        if (hasSelection()) deleteSelection();
        lines.get(cursorRow).insert(cursorCol, c);
        cursorCol++;
        modified = true;
        ensureCursorVisible();
    }

    private void handleKey(int keyCode, int mods) {
        boolean ctrl = (mods & 2) != 0;
        boolean shift = (mods & 1) != 0;

        // Ctrl shortcuts
        if (ctrl) {
            switch (keyCode) {
                case 83 -> saveFile();           // Ctrl+S
                case 78 -> newFile();            // Ctrl+N
                case 79 -> openMenu = Menu.FILE; // Ctrl+O → open File menu
                case 70 -> { findOpen = true; findQuery.setLength(0); findCursor = 0; } // Ctrl+F
                case 65 -> selectAll();          // Ctrl+A
                case 67 -> doCopy();             // Ctrl+C
                case 88 -> doCut();              // Ctrl+X
                case 86 -> doPaste();            // Ctrl+V
            }
            return;
        }

        switch (keyCode) {
            case 259 -> { // Backspace
                if (hasSelection()) { deleteSelection(); }
                else if (cursorCol > 0) {
                    lines.get(cursorRow).deleteCharAt(--cursorCol);
                    modified = true;
                } else if (cursorRow > 0) {
                    StringBuilder prev = lines.get(cursorRow - 1);
                    cursorCol = prev.length();
                    prev.append(lines.get(cursorRow));
                    lines.remove(cursorRow);
                    cursorRow--;
                    modified = true;
                }
                if (!shift) clearSelection();
            }
            case 261 -> { // Delete
                if (hasSelection()) { deleteSelection(); }
                else if (cursorCol < lines.get(cursorRow).length()) {
                    lines.get(cursorRow).deleteCharAt(cursorCol);
                    modified = true;
                } else if (cursorRow < lines.size() - 1) {
                    lines.get(cursorRow).append(lines.get(cursorRow + 1));
                    lines.remove(cursorRow + 1);
                    modified = true;
                }
                if (!shift) clearSelection();
            }
            case 257 -> { // Enter
                if (hasSelection()) deleteSelection();
                StringBuilder cur = lines.get(cursorRow);
                String after = cur.substring(cursorCol);
                cur.delete(cursorCol, cur.length());
                lines.add(cursorRow + 1, new StringBuilder(after));
                cursorRow++;
                cursorCol = 0;
                modified = true;
                if (!shift) clearSelection();
            }
            case 262 -> { // Right
                if (shift) startSelection();
                if (cursorCol < lines.get(cursorRow).length()) cursorCol++;
                else if (cursorRow < lines.size() - 1) { cursorRow++; cursorCol = 0; }
                if (!shift) clearSelection();
            }
            case 263 -> { // Left
                if (shift) startSelection();
                if (cursorCol > 0) cursorCol--;
                else if (cursorRow > 0) { cursorRow--; cursorCol = lines.get(cursorRow).length(); }
                if (!shift) clearSelection();
            }
            case 265 -> { // Up
                if (shift) startSelection();
                if (cursorRow > 0) {
                    cursorRow--;
                    cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
                }
                if (!shift) clearSelection();
            }
            case 264 -> { // Down
                if (shift) startSelection();
                if (cursorRow < lines.size() - 1) {
                    cursorRow++;
                    cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
                }
                if (!shift) clearSelection();
            }
            case 268 -> { // Home
                if (shift) startSelection();
                cursorCol = 0;
                if (!shift) clearSelection();
            }
            case 269 -> { // End
                if (shift) startSelection();
                cursorCol = lines.get(cursorRow).length();
                if (!shift) clearSelection();
            }
            case 266 -> { // Page Up
                if (shift) startSelection();
                cursorRow = Math.max(0, cursorRow - getVisibleRows());
                cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
                if (!shift) clearSelection();
            }
            case 267 -> { // Page Down
                if (shift) startSelection();
                cursorRow = Math.min(lines.size() - 1, cursorRow + getVisibleRows());
                cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
                if (!shift) clearSelection();
            }
            case 258 -> { // Tab
                if (hasSelection()) deleteSelection();
                lines.get(cursorRow).insert(cursorCol, "    ");
                cursorCol += 4;
                modified = true;
                clearSelection();
            }
            case 256 -> { // Escape
                clearSelection();
                openMenu = Menu.NONE;
            }
        }
        ensureCursorVisible();
    }

    // ── Find bar keys ───────────────────────────────────────

    private void handleFindKey(int keyCode, int mods) {
        switch (keyCode) {
            case 256 -> { findOpen = false; findMatchRow = -1; findMatchCol = -1; } // Escape
            case 257 -> findNext(); // Enter
            case 259 -> { // Backspace
                if (findCursor > 0) { findQuery.deleteCharAt(--findCursor); }
            }
            case 262 -> { if (findCursor < findQuery.length()) findCursor++; } // Right
            case 263 -> { if (findCursor > 0) findCursor--; } // Left
        }
    }

    private void handleFindChar(char c) {
        if (c >= 32 && c != 127) {
            findQuery.insert(findCursor++, c);
        }
    }

    // ── Save As dialog keys ─────────────────────────────────

    private void handleSaveAsKey(int keyCode) {
        switch (keyCode) {
            case 257 -> { // Enter — confirm save
                String path = saveAsPath.toString().trim();
                if (!path.isEmpty()) {
                    filePath = path;
                    saveFile();
                }
                saveAsOpen = false;
            }
            case 256 -> saveAsOpen = false; // Escape — cancel
            case 259 -> { // Backspace
                if (saveAsCursor > 0) { saveAsPath.deleteCharAt(--saveAsCursor); }
            }
            case 261 -> { // Delete
                if (saveAsCursor < saveAsPath.length()) { saveAsPath.deleteCharAt(saveAsCursor); }
            }
            case 263 -> { if (saveAsCursor > 0) saveAsCursor--; }  // Left
            case 262 -> { if (saveAsCursor < saveAsPath.length()) saveAsCursor++; } // Right
            case 268 -> saveAsCursor = 0; // Home
            case 269 -> saveAsCursor = saveAsPath.length(); // End
        }
    }

    private void handleSaveAsChar(char c) {
        if (c >= 32 && c != 127 && saveAsPath.length() < 120) {
            saveAsPath.insert(saveAsCursor++, c);
        }
    }

    // ── Menu bar interaction ────────────────────────────────

    private static final String[] FILE_ITEMS = {"New", "Open...", "Save", "Save As...", "---", "Print", "Save to Disk", "Load from Disk", "---", "Exit"};
    private static final String[] FILE_ACTIONS = {"new", "open", "save", "save_as", "", "print", "save_disk", "load_disk", "", "exit"};
    private static final String[] EDIT_ITEMS = {"Cut", "Copy", "Paste", "---", "Select All", "Find..."};
    private static final String[] EDIT_ACTIONS = {"cut", "copy", "paste", "", "select_all", "find"};
    private static final String[] VIEW_ITEMS = {"Word Wrap", "Status Bar"};
    private static final String[] VIEW_ACTIONS = {"word_wrap", "status_bar"};

    private void handleClick(int px, int py, boolean shift) {
        // Menu bar click
        if (py < MENU_H) {
            if (px < 40) {
                openMenu = (openMenu == Menu.FILE) ? Menu.NONE : Menu.FILE;
                menuHover = -1;
            } else if (px < 80) {
                openMenu = (openMenu == Menu.EDIT) ? Menu.NONE : Menu.EDIT;
                menuHover = -1;
            } else if (px < 128) {
                openMenu = (openMenu == Menu.VIEW) ? Menu.NONE : Menu.VIEW;
                menuHover = -1;
            } else {
                openMenu = Menu.NONE;
            }
            return;
        }

        // Click within menu dropdown
        if (openMenu != Menu.NONE) {
            String action = getMenuAction(px, py);
            if (action != null && !action.isEmpty()) handleMenuAction(action);
            openMenu = Menu.NONE;
            return;
        }

        // Click in text area
        openMenu = Menu.NONE;
        int textY = MENU_H;
        int row = (py - textY) / CH + scrollY;
        int col = (px / CW) + scrollX;
        row = Math.max(0, Math.min(row, lines.size() - 1));
        col = Math.max(0, Math.min(col, lines.get(row).length()));

        if (shift) {
            startSelection();
        } else {
            clearSelection();
        }
        cursorRow = row;
        cursorCol = col;
    }

    private void handleRightClick(int px, int py) {
        openMenu = Menu.NONE;
        contextMenu.show(px, py, List.of(
            new ContextMenu.Item("Cut", "cut"),
            new ContextMenu.Item("Copy", "copy"),
            new ContextMenu.Item("Paste", "paste"),
            ContextMenu.Item.sep(),
            new ContextMenu.Item("Select All", "select_all")
        ));
    }

    private String getMenuAction(int px, int py) {
        String[] items;
        String[] actions;
        int menuX;
        switch (openMenu) {
            case FILE -> { items = FILE_ITEMS; actions = FILE_ACTIONS; menuX = 0; }
            case EDIT -> { items = EDIT_ITEMS; actions = EDIT_ACTIONS; menuX = 40; }
            case VIEW -> { items = VIEW_ITEMS; actions = VIEW_ACTIONS; menuX = 80; }
            default -> { return null; }
        }
        int menuW = 120;
        int menuTop = MENU_H;
        if (px < menuX || px >= menuX + menuW) return null;
        int idx = (py - menuTop) / CH;
        if (idx < 0 || idx >= items.length) return null;
        return actions[idx];
    }

    private void handleMenuKey(int keyCode) {
        String[] items;
        String[] actions;
        switch (openMenu) {
            case FILE -> { items = FILE_ITEMS; actions = FILE_ACTIONS; }
            case EDIT -> { items = EDIT_ITEMS; actions = EDIT_ACTIONS; }
            case VIEW -> { items = VIEW_ITEMS; actions = VIEW_ACTIONS; }
            default -> { openMenu = Menu.NONE; return; }
        }
        switch (keyCode) {
            case 264 -> { // Down
                menuHover++;
                if (menuHover >= items.length) menuHover = 0;
                if (items[menuHover].equals("---")) menuHover++;
            }
            case 265 -> { // Up
                menuHover--;
                if (menuHover < 0) menuHover = items.length - 1;
                if (items[menuHover].equals("---")) menuHover--;
            }
            case 257 -> { // Enter
                if (menuHover >= 0 && menuHover < actions.length) {
                    handleMenuAction(actions[menuHover]);
                }
                openMenu = Menu.NONE;
            }
            case 263 -> { // Left
                openMenu = switch (openMenu) { case EDIT -> Menu.FILE; case VIEW -> Menu.EDIT; default -> Menu.VIEW; };
                menuHover = 0;
            }
            case 262 -> { // Right
                openMenu = switch (openMenu) { case FILE -> Menu.EDIT; case EDIT -> Menu.VIEW; default -> Menu.FILE; };
                menuHover = 0;
            }
            case 256 -> openMenu = Menu.NONE; // Escape
        }
    }

    private void handleMenuAction(String action) {
        switch (action) {
            case "new" -> newFile();
            case "save" -> saveFile();
            case "save_as" -> {
                saveAsOpen = true;
                saveAsPath.setLength(0);
                String base = filePath != null ? filePath : "/Users/User/Documents/untitled.txt";
                saveAsPath.append(base);
                saveAsCursor = saveAsPath.length();
            }
            case "exit" -> { running = false; }
            case "cut" -> doCut();
            case "copy" -> doCopy();
            case "paste" -> doPaste();
            case "select_all" -> selectAll();
            case "find" -> { findOpen = true; findQuery.setLength(0); findCursor = 0; }
            case "word_wrap" -> wordWrap = !wordWrap;
            case "status_bar" -> showStatusBar = !showStatusBar;
            case "print" -> doPrint();
            case "save_disk" -> saveToDisk();
            case "load_disk" -> loadFromDisk();
        }
    }

    // ── Peripheral helpers ──────────────────────────────────

    private void setStatus(String msg) {
        statusMsg = msg;
        statusTicks = 60; // ~3 seconds at 20 tps
    }

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

    private String getDocumentContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private String getFileName() {
        if (filePath == null) return "Untitled";
        int slash = filePath.lastIndexOf('/');
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }

    private void doPrint() {
        PrinterBlockEntity printer = findPrinter();
        if (printer == null) {
            setStatus("No printer found (place adjacent)");
            return;
        }
        String title = getFileName();
        String content = getDocumentContent();
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
        String name = getFileName();
        String path = "/" + name;
        String content = getDocumentContent();
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
        // Load the first file found on disk
        String diskPath = diskFiles.get(0);
        String content = drive.readFromDisk(diskPath);
        if (content == null) {
            setStatus("Failed to read from disk");
            return;
        }
        lines.clear();
        if (content.isEmpty()) {
            lines.add(new StringBuilder());
        } else {
            for (String line : content.split("\n", -1)) {
                lines.add(new StringBuilder(line));
            }
        }
        String name = diskPath.startsWith("/") ? diskPath.substring(1) : diskPath;
        filePath = "/Users/User/Documents/" + name;
        modified = false;
        cursorRow = 0;
        cursorCol = 0;
        scrollX = 0;
        scrollY = 0;
        clearSelection();
        setStatus("Loaded from disk: " + name);
    }

    // ── Rendering ───────────────────────────────────────────

    @Override
    public void render(TerminalBuffer buf) {
        // Text-mode fallback — just show the file content
        buf.clear();
        for (int r = 0; r < Math.min(buf.getHeight(), lines.size()); r++) {
            buf.writeAt(0, r, lines.get(r).toString());
        }
    }

    @Override
    public void renderGraphics(PixelBuffer pb) {
        int W = PixelBuffer.SCREEN_W;
        int H = PixelBuffer.SCREEN_H;
        pb.clear(0xFFFFFFFF);

        // ── Menu bar ──
        drawMenuBar(pb, W);

        // ── Text area ──
        int textTop = MENU_H;
        int textBot = showStatusBar ? H - STATUS_H : H;
        if (findOpen) textBot -= CH;
        int visRows = (textBot - textTop) / CH;
        int visCols = W / CW;

        // Selection range
        int[] ss = hasSelection() ? selStart() : null;
        int[] se = hasSelection() ? selEnd() : null;

        for (int vy = 0; vy < visRows; vy++) {
            int row = scrollY + vy;
            if (row >= lines.size()) break;
            int py = textTop + vy * CH;
            String line = lines.get(row).toString();

            // Draw selection highlight
            if (ss != null && row >= ss[0] && row <= se[0]) {
                int selS = (row == ss[0]) ? ss[1] : 0;
                int selE = (row == se[0]) ? se[1] : line.length();
                int sx = (selS - scrollX) * CW;
                int ex = (selE - scrollX) * CW;
                if (ex > sx) {
                    pb.fillRect(Math.max(0, sx), py, Math.min(W, ex) - Math.max(0, sx), CH, 0xFF3399FF);
                }
            }

            // Draw text
            for (int vx = 0; vx < visCols; vx++) {
                int col = scrollX + vx;
                if (col >= line.length()) break;
                int px = vx * CW;
                // Determine fg color — selected text is white, normal is black
                boolean inSel = ss != null && row >= ss[0] && row <= se[0]
                    && (row > ss[0] || col >= ss[1])
                    && (row < se[0] || col < se[1]);
                int fg = inSel ? 0xFFFFFFFF : 0xFF000000;
                pb.drawChar(px, py, line.charAt(col), fg);
            }
        }

        // Cursor
        int curVx = cursorCol - scrollX;
        int curVy = cursorRow - scrollY;
        if (curVx >= 0 && curVx < visCols && curVy >= 0 && curVy < visRows) {
            int cx = curVx * CW;
            int cy = textTop + curVy * CH;
            pb.fillRect(cx, cy, 2, CH, 0xFF000000);
            setCursorInfo(curVx, curVy + (MENU_H / CH), true);
        } else {
            setCursorInfo(0, 0, false);
        }

        // ── Find bar ──
        if (findOpen) {
            int fy = textBot;
            pb.fillRect(0, fy, W, CH, 0xFFE8E8E8);
            pb.drawHLine(0, W - 1, fy, 0xFFCCCCCC);
            pb.drawString(4, fy + 1, "Find:", 0xFF333333);
            pb.fillRect(44, fy + 1, 200, CH - 2, 0xFFFFFFFF);
            pb.drawRect(44, fy + 1, 200, CH - 2, 0xFF999999);
            pb.drawString(48, fy + 1, findQuery.toString(), 0xFF000000);
            int fcx = 48 + findCursor * CW;
            pb.fillRect(fcx, fy + 2, 1, CH - 4, 0xFF000000);
            // Find match indicator
            if (findMatchRow >= 0) {
                pb.drawString(252, fy + 1, "Found at Ln " + (findMatchRow + 1), 0xFF228822);
            } else if (!findQuery.isEmpty()) {
                pb.drawString(252, fy + 1, "Not found", 0xFF882222);
            }
        }

        // ── Status bar ──
        if (showStatusBar) {
            int sy = H - STATUS_H;
            pb.fillRect(0, sy, W, STATUS_H, 0xFFF0F0F0);
            pb.drawHLine(0, W - 1, sy, 0xFFCCCCCC);
            if (statusTicks > 0 && !statusMsg.isEmpty()) {
                // Show temporary status message
                pb.drawString(4, sy + 1, statusMsg, 0xFF0066CC);
            } else {
                // File name
                String fname = filePath != null ? filePath.substring(filePath.lastIndexOf('/') + 1) : "Untitled";
                if (modified) fname += " *";
                pb.drawString(4, sy + 1, fname, 0xFF444444);
                // Line/col
                String pos = "Ln " + (cursorRow + 1) + ", Col " + (cursorCol + 1);
                pb.drawStringRight(W - 4, sy + 1, pos, 0xFF444444);
                // Line count
                String info = lines.size() + " lines";
                pb.drawStringCentered(0, W, sy + 1, info, 0xFF888888);
            }
        }

        // ── Menu dropdowns (over everything) ──
        if (openMenu != Menu.NONE) {
            drawMenuDropdown(pb);
        }

        // ── Save As dialog ──
        if (saveAsOpen) {
            drawSaveAsDialog(pb, W, H);
        }

        // ── Context menu ──
        contextMenu.render(pb);
    }

    private void drawSaveAsDialog(PixelBuffer pb, int W, int H) {
        // Dim background
        pb.fillRect(0, 0, W, H, 0x88000000);

        int dlgW = 400;
        int dlgH = 80;
        int dx = (W - dlgW) / 2;
        int dy = (H - dlgH) / 2;

        // Dialog background
        pb.fillRect(dx + 2, dy + 2, dlgW, dlgH, 0x44000000); // shadow
        pb.fillRect(dx, dy, dlgW, dlgH, 0xFFF0F0F0);
        pb.drawRect(dx, dy, dlgW, dlgH, 0xFF666666);

        // Title bar
        pb.fillRect(dx, dy, dlgW, 20, 0xFF3366CC);
        pb.drawString(dx + 6, dy + 3, "Save As", 0xFFFFFFFF);

        // Label
        pb.drawString(dx + 10, dy + 28, "File path:", 0xFF333333);

        // Text input field
        int fieldX = dx + 10;
        int fieldY = dy + 42;
        int fieldW = dlgW - 20;
        int fieldH = CH + 2;
        pb.fillRect(fieldX, fieldY, fieldW, fieldH, 0xFFFFFFFF);
        pb.drawRect(fieldX, fieldY, fieldW, fieldH, 0xFF999999);

        // Draw path text (scrolled if too long)
        int maxChars = (fieldW - 8) / CW;
        String text = saveAsPath.toString();
        int textOffset = 0;
        if (saveAsCursor > maxChars - 2) {
            textOffset = saveAsCursor - maxChars + 2;
        }
        String visible = text.substring(Math.min(textOffset, text.length()),
            Math.min(textOffset + maxChars, text.length()));
        pb.drawString(fieldX + 4, fieldY + 2, visible, 0xFF000000);

        // Cursor
        int curX = fieldX + 4 + (saveAsCursor - textOffset) * CW;
        pb.fillRect(curX, fieldY + 1, 2, fieldH - 2, 0xFF000000);

        // Hint text
        pb.drawString(dx + 10, dy + 62, "Enter \u2192 Save    Esc \u2192 Cancel", 0xFF888888);
    }

    private void drawMenuBar(PixelBuffer pb, int W) {
        pb.fillRect(0, 0, W, MENU_H, 0xFFF0F0F0);
        pb.drawHLine(0, W - 1, MENU_H - 1, 0xFFCCCCCC);

        String[] labels = {"File", "Edit", "View"};
        Menu[] menus = {Menu.FILE, Menu.EDIT, Menu.VIEW};
        int[] xx = {4, 44, 84};
        int[] ww = {36, 36, 44};

        for (int i = 0; i < labels.length; i++) {
            boolean active = (openMenu == menus[i]);
            if (active) {
                pb.fillRect(xx[i] - 2, 0, ww[i], MENU_H, 0xFFDDE8FF);
            }
            pb.drawString(xx[i], 2, labels[i], active ? 0xFF0055CC : 0xFF333333);
        }
    }

    private void drawMenuDropdown(PixelBuffer pb) {
        String[] items;
        int menuX;
        switch (openMenu) {
            case FILE -> { items = FILE_ITEMS; menuX = 0; }
            case EDIT -> { items = EDIT_ITEMS; menuX = 40; }
            case VIEW -> { items = VIEW_ITEMS; menuX = 80; }
            default -> { return; }
        }
        int menuW = 120;
        int menuH = items.length * CH + 4;
        int menuY = MENU_H;

        // Shadow
        pb.fillRect(menuX + 2, menuY + 2, menuW, menuH, 0x44000000);
        // Background
        pb.fillRect(menuX, menuY, menuW, menuH, 0xFFF8F8F8);
        pb.drawRect(menuX, menuY, menuW, menuH, 0xFFCCCCCC);

        for (int i = 0; i < items.length; i++) {
            int iy = menuY + 2 + i * CH;
            if (items[i].equals("---")) {
                pb.drawHLine(menuX + 4, menuX + menuW - 4, iy + CH / 2, 0xFFCCCCCC);
                continue;
            }
            boolean hover = (i == menuHover);
            if (hover) {
                pb.fillRect(menuX + 1, iy, menuW - 2, CH, 0xFF3366CC);
            }
            int fg = hover ? 0xFFFFFFFF : 0xFF333333;
            pb.drawString(menuX + 8, iy + 1, items[i], fg);
            // Shortcuts on right
            String shortcut = getShortcutText(openMenu, i);
            if (shortcut != null) {
                pb.drawStringRight(menuX + menuW - 8, iy + 1, shortcut, hover ? 0xFFCCDDFF : 0xFF999999);
            }
            // Checkmarks for View toggles
            if (openMenu == Menu.VIEW) {
                boolean checked = (i == 0 && wordWrap) || (i == 1 && showStatusBar);
                if (checked) {
                    pb.drawString(menuX + menuW - 20, iy + 1, "\u2713", fg);
                }
            }
        }
    }

    private String getShortcutText(Menu menu, int index) {
        if (menu == Menu.FILE) {
            return switch (index) {
                case 0 -> "Ctrl+N";
                case 2 -> "Ctrl+S";
                default -> null;
            };
        }
        if (menu == Menu.EDIT) {
            return switch (index) {
                case 0 -> "Ctrl+X";
                case 1 -> "Ctrl+C";
                case 2 -> "Ctrl+V";
                case 4 -> "Ctrl+A";
                case 5 -> "Ctrl+F";
                default -> null;
            };
        }
        return null;
    }
}

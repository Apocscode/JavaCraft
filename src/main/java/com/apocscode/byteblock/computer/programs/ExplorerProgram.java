package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.SystemIcons;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.computer.VirtualFileSystem;
import com.apocscode.byteblock.computer.BitmapFont;
import com.apocscode.byteblock.computer.ContextMenu;
import com.apocscode.byteblock.block.entity.PrinterBlockEntity;
import com.apocscode.byteblock.block.entity.DriveBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Map;

/**
 * File explorer for ByteBlock OS.
 * Shows directory listing with icons, navigate by clicking or arrow keys,
 * double-click / Enter to open files or enter directories.
 */
public class ExplorerProgram extends OSProgram {

    private String currentPath = "/";
    private List<String> entries;
    private int selectedIndex = 0;
    private int scrollOffset = 0;
    private boolean needsRedraw = true;
    private String statusMessage = "";

    private static final int HEADER_HEIGHT = 2;
    private static final int FOOTER_HEIGHT = 1;

    // Navigation history
    private final java.util.Deque<String> backStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<String> forwardStack = new java.util.ArrayDeque<>();

    // Clipboard
    private String clipboardPath;
    private boolean clipboardIsCut;

    // Double-click detection
    private long lastClickTime;
    private int lastClickIndex = -1;

    // Inline rename
    private boolean renaming;
    private final StringBuilder renameBuffer = new StringBuilder();
    private int renameCursor;

    // Right-click context menu
    private final ContextMenu contextMenu = new ContextMenu();

    // Layout constants (pixel mode)
    private static final int TB_H = 36; // toolbar height (2 rows)
    private static final int AB_H = 18; // address bar height
    private static final int NAV_W = 130; // sidebar width
    private static final int ROW_H = 20; // file row height

    public ExplorerProgram() {
        super("Explorer");
    }

    public ExplorerProgram(String startPath) {
        super("Explorer");
        if (startPath != null && !startPath.isEmpty()) {
            this.currentPath = startPath;
        }
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        refreshListing();
    }

    @Override
    public boolean tick() {
        return running;
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case KEY -> {
                handleKey(event.getInt(0), event.getInt(2));
                needsRedraw = true;
            }
            case MOUSE_CLICK_PX -> {
                int btn = event.getInt(0);
                int px = event.getInt(1), py = event.getInt(2);
                if (contextMenu.isVisible()) {
                    String action = contextMenu.handleClick(px, py);
                    if (action != null) handleContextAction(action);
                    needsRedraw = true;
                    return;
                }
                if (btn == 1) {
                    handleRightClick(px, py);
                } else {
                    handleClickPx(px, py);
                }
                needsRedraw = true;
            }
            case MOUSE_CLICK -> needsRedraw = true;
            case MOUSE_SCROLL -> {
                if (contextMenu.isVisible()) return;
                int dir = event.getInt(0);
                scrollOffset = Math.max(0, Math.min(
                    entries != null ? Math.max(0, entries.size() - 5) : 0,
                    scrollOffset + dir * 3));
                needsRedraw = true;
            }
            case MOUSE_DRAG -> {
                int dpx = event.getInt(1) * PixelBuffer.CELL_W + PixelBuffer.CELL_W / 2;
                int dpy = event.getInt(2) * PixelBuffer.CELL_H + PixelBuffer.CELL_H / 2;
                contextMenu.handleMove(dpx, dpy);
                needsRedraw = true;
            }
            case CHAR -> {
                if (renaming) {
                    handleRenameChar(event.getString(0).charAt(0));
                }
                needsRedraw = true;
            }
            default -> {}
        }
    }

    private void handleKey(int keyCode, int mods) {
        boolean ctrl = (mods & 2) != 0;
        // Inline rename mode
        if (renaming) {
            handleRenameKey(keyCode);
            return;
        }
        int maxVisible = getMaxVisible();
        if (ctrl) {
            switch (keyCode) {
                case 67 -> doCopy();   // Ctrl+C
                case 88 -> doCut();    // Ctrl+X
                case 86 -> doPaste();  // Ctrl+V
                case 65 -> {}          // Ctrl+A (future: multi-select)
            }
            return;
        }
        switch (keyCode) {
            case 265 -> { // Up
                selectedIndex = Math.max(0, selectedIndex - 1);
                ensureVisible(maxVisible);
            }
            case 264 -> { // Down
                if (entries != null)
                    selectedIndex = Math.min(entries.size() - 1, selectedIndex + 1);
                ensureVisible(maxVisible);
            }
            case 257, 335 -> openSelected(); // Enter
            case 259 -> navigateUp(); // Backspace = go up
            case 261 -> deleteSelected(); // Delete
            case 291 -> startRename(); // F2 = rename
            case 292 -> running = false; // F3 = close
            case 256 -> { // Escape
                selectedIndex = -1;
                statusMessage = "";
            }
        }
    }

    private int getMaxVisible() {
        int CH = BitmapFont.CHAR_H;
        int contentY = TB_H + AB_H;
        int contentH = PixelBuffer.SCREEN_H - contentY - CH;
        return (contentH - CH) / ROW_H;
    }

    private void handleClickPx(int px, int py) {
        int CH = BitmapFont.CHAR_H;
        int H = PixelBuffer.SCREEN_H;
        int contentY = TB_H + AB_H;
        int contentH = H - contentY - CH;

        // Cancel rename on click elsewhere
        if (renaming) { finishRename(); }

        // ── Toolbar row 1 (navigation): y 0..17 ──
        if (py < 18) {
            if (px >= 4 && px < 28)  { navigateBack(); return; }
            if (px >= 32 && px < 56) { navigateForward(); return; }
            if (px >= 60 && px < 84) { navigateUp(); return; }
            return;
        }
        // ── Toolbar row 2 (actions): y 18..TB_H ──
        if (py < TB_H) {
            if (px >= 4   && px < 28)  { doCut(); return; }
            if (px >= 32  && px < 56)  { doCopy(); return; }
            if (px >= 60  && px < 84)  { doPaste(); return; }
            if (px >= 96  && px < 120) { deleteSelected(); return; }
            if (px >= 124 && px < 148) { startRename(); return; }
            if (px >= 160 && px < 184) { createNewFolder(); return; }
            if (px >= 188 && px < 212) { createNewFile(); return; }
            return;
        }

        // ── Address bar ──
        if (py >= TB_H && py < contentY) { return; }

        // ── Navigation pane (left sidebar) ──
        if (px < NAV_W && py >= contentY && py < contentY + contentH) {
            String[][] sideItems = buildSidebarItems();
            for (int i = 0; i < sideItems.length; i++) {
                int iy = contentY + 4 + i * 20;
                if (py >= iy - 2 && py < iy + 18) {
                    String path = sideItems[i][1];
                    if (!currentPath.equals(path)) {
                        backStack.push(currentPath);
                        forwardStack.clear();
                        currentPath = path;
                        selectedIndex = 0;
                        scrollOffset = 0;
                        refreshListing();
                    }
                    return;
                }
            }
            return;
        }

        // ── File listing area ──
        int fileX = NAV_W + 1;
        if (px >= fileX && py >= contentY && py < contentY + contentH) {
            int headerH = CH;
            int itemY = contentY + headerH;
            if (py < itemY) return; // clicked column header
            int visibleIdx = (py - itemY) / ROW_H;
            int idx = scrollOffset + visibleIdx;
            if (entries != null && idx < entries.size()) {
                long now = System.currentTimeMillis();
                if (idx == lastClickIndex && now - lastClickTime < 400) {
                    // Double-click → open
                    openSelected();
                    lastClickTime = 0;
                    lastClickIndex = -1;
                } else {
                    // Single click → select
                    selectedIndex = idx;
                    lastClickTime = now;
                    lastClickIndex = idx;
                }
            } else {
                // Clicked empty area → deselect
                selectedIndex = -1;
                lastClickIndex = -1;
            }
        }
    }

    private void handleRightClick(int px, int py) {
        int CH = BitmapFont.CHAR_H;
        int H = PixelBuffer.SCREEN_H;
        int contentY = TB_H + AB_H;
        int contentH = H - contentY - CH;
        int fileX = NAV_W + 1;
        int headerH = CH;
        int itemY = contentY + headerH;

        // Right-click in file listing
        if (px >= fileX && py >= itemY && py < contentY + contentH) {
            int visibleIdx = (py - itemY) / ROW_H;
            int idx = scrollOffset + visibleIdx;
            if (entries != null && idx < entries.size()) {
                selectedIndex = idx;
                String name = entries.get(idx);
                boolean isDir = name.endsWith("/");
                contextMenu.show(px, py, List.of(
                    new ContextMenu.Item("Open", "open"),
                    ContextMenu.Item.sep(),
                    new ContextMenu.Item("Cut", "cut"),
                    new ContextMenu.Item("Copy", "copy"),
                    new ContextMenu.Item("Paste", clipboardPath != null ? "paste" : "",
                        false, clipboardPath == null),
                    ContextMenu.Item.sep(),
                    new ContextMenu.Item("Print", isDir ? "" : "print", false, isDir),
                    new ContextMenu.Item("Save to Disk", isDir ? "" : "save_disk", false, isDir),
                    ContextMenu.Item.sep(),
                    new ContextMenu.Item("Rename", "rename"),
                    new ContextMenu.Item("Delete", "delete"),
                    ContextMenu.Item.sep(),
                    new ContextMenu.Item("Properties", "properties")
                ));
            } else {
                // Right-click on empty space
                contextMenu.show(px, py, List.of(
                    new ContextMenu.Item("Paste", clipboardPath != null ? "paste" : "",
                        false, clipboardPath == null),
                    ContextMenu.Item.sep(),
                    new ContextMenu.Item("New Folder", "new_folder"),
                    new ContextMenu.Item("New Text File", "new_file"),
                    ContextMenu.Item.sep(),
                    new ContextMenu.Item("Refresh", "refresh")
                ));
            }
        } else {
            // Right-click outside listing
            contextMenu.show(px, py, List.of(
                new ContextMenu.Item("Paste", clipboardPath != null ? "paste" : "",
                    false, clipboardPath == null),
                ContextMenu.Item.sep(),
                new ContextMenu.Item("New Folder", "new_folder"),
                new ContextMenu.Item("New Text File", "new_file"),
                ContextMenu.Item.sep(),
                new ContextMenu.Item("Refresh", "refresh")
            ));
        }
    }

    private void handleContextAction(String action) {
        switch (action) {
            case "open" -> openSelected();
            case "cut" -> doCut();
            case "copy" -> doCopy();
            case "paste" -> doPaste();
            case "rename" -> startRename();
            case "delete" -> deleteSelected();
            case "new_folder" -> createNewFolder();
            case "new_file" -> createNewFile();
            case "refresh" -> refreshListing();
            case "properties" -> showProperties();
            case "print" -> printSelected();
            case "save_disk" -> saveSelectedToDisk();
        }
        needsRedraw = true;
    }

    private void openSelected() {
        if (entries == null || selectedIndex < 0 || selectedIndex >= entries.size()) return;
        String name = entries.get(selectedIndex);
        if (name.endsWith("/")) {
            String dirName = name.substring(0, name.length() - 1);
            // Check if clicking a drive entry (e.g. "D: (label)")
            if (dirName.length() >= 2 && dirName.charAt(1) == ':'
                    && Character.isLetter(dirName.charAt(0))
                    && dirName.charAt(0) != 'C' && dirName.charAt(0) != 'c') {
                backStack.push(currentPath);
                forwardStack.clear();
                currentPath = dirName.charAt(0) + ":";
                selectedIndex = 0;
                scrollOffset = 0;
                refreshListing();
                return;
            }
            backStack.push(currentPath);
            forwardStack.clear();
            currentPath = currentPath.equals("/") ? "/" + dirName : currentPath + "/" + dirName;
            selectedIndex = 0;
            scrollOffset = 0;
            refreshListing();
        } else if (isDrivePath(currentPath)) {
            // Open file from disk — copy to temp and launch
            char letter = currentPath.charAt(0);
            DriveBlockEntity drive = os.getDrive(letter);
            if (drive != null) {
                String content = drive.readFromDisk("/" + name);
                if (content != null) {
                    String tempPath = "/Windows/Temp/" + name;
                    os.getFileSystem().writeFile(tempPath, content);
                    if (name.endsWith(".lua") || name.endsWith(".java")) {
                        os.launchProgram(new TextIDEProgram(tempPath));
                    } else {
                        os.launchProgram(new NotepadProgram(tempPath));
                    }
                } else {
                    statusMessage = "Cannot read file from disk";
                    needsRedraw = true;
                }
            }
        } else {
            String fullPath = currentPath.equals("/") ? "/" + name : currentPath + "/" + name;
            if (fullPath.endsWith(".pzl")) {
                os.launchProgram(new PuzzleProgram(fullPath));
            } else if (fullPath.endsWith(".pxl")) {
                os.launchProgram(new PaintProgram(fullPath));
            } else if (fullPath.endsWith(".lua") || fullPath.endsWith(".java")) {
                os.launchProgram(new TextIDEProgram(fullPath));
            } else {
                os.launchProgram(new NotepadProgram(fullPath));
            }
        }
    }

    private void navigateUp() {
        if (currentPath.equals("/")) return;
        backStack.push(currentPath);
        forwardStack.clear();
        if (isDrivePath(currentPath)) {
            currentPath = "/";
        } else {
            int lastSlash = currentPath.lastIndexOf('/');
            currentPath = lastSlash <= 0 ? "/" : currentPath.substring(0, lastSlash);
        }
        selectedIndex = 0;
        scrollOffset = 0;
        refreshListing();
    }

    private void navigateBack() {
        if (backStack.isEmpty()) return;
        forwardStack.push(currentPath);
        currentPath = backStack.pop();
        selectedIndex = 0;
        scrollOffset = 0;
        refreshListing();
    }

    private void navigateForward() {
        if (forwardStack.isEmpty()) return;
        backStack.push(currentPath);
        currentPath = forwardStack.pop();
        selectedIndex = 0;
        scrollOffset = 0;
        refreshListing();
    }

    private void deleteSelected() {
        if (entries == null || selectedIndex < 0 || selectedIndex >= entries.size()) return;
        String name = entries.get(selectedIndex);
        String cleanName = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        String fullPath = currentPath.equals("/") ? "/" + cleanName : currentPath + "/" + cleanName;
        if (os.getFileSystem().delete(fullPath)) {
            statusMessage = "Deleted: " + cleanName;
            refreshListing();
            selectedIndex = Math.min(selectedIndex, entries != null ? entries.size() - 1 : 0);
        } else {
            statusMessage = "Cannot delete: " + cleanName;
        }
        needsRedraw = true;
    }

    // ── Clipboard operations ──

    private String getSelectedFullPath() {
        if (entries == null || selectedIndex < 0 || selectedIndex >= entries.size()) return null;
        String name = entries.get(selectedIndex);
        String cleanName = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        return currentPath.equals("/") ? "/" + cleanName : currentPath + "/" + cleanName;
    }

    private void doCopy() {
        String path = getSelectedFullPath();
        if (path != null) {
            clipboardPath = path;
            clipboardIsCut = false;
            statusMessage = "Copied: " + path.substring(path.lastIndexOf('/') + 1);
            needsRedraw = true;
        }
    }

    private void doCut() {
        String path = getSelectedFullPath();
        if (path != null) {
            clipboardPath = path;
            clipboardIsCut = true;
            statusMessage = "Cut: " + path.substring(path.lastIndexOf('/') + 1);
            needsRedraw = true;
        }
    }

    private void doPaste() {
        if (clipboardPath == null) return;
        String srcName = clipboardPath.substring(clipboardPath.lastIndexOf('/') + 1);
        String destPath = currentPath.equals("/") ? "/" + srcName : currentPath + "/" + srcName;
        if (clipboardIsCut) {
            if (os.getFileSystem().move(clipboardPath, destPath)) {
                statusMessage = "Moved: " + srcName;
                clipboardPath = null;
            } else {
                statusMessage = "Move failed: " + srcName;
            }
        } else {
            if (os.getFileSystem().copy(clipboardPath, destPath)) {
                statusMessage = "Pasted: " + srcName;
            } else {
                statusMessage = "Paste failed: " + srcName;
            }
        }
        refreshListing();
        needsRedraw = true;
    }

    // ── Inline rename ──

    private void startRename() {
        if (entries == null || selectedIndex < 0 || selectedIndex >= entries.size()) return;
        String name = entries.get(selectedIndex);
        String cleanName = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        // For drive entries, pre-fill with just the disk label
        if (currentPath.equals("/") && cleanName.length() >= 2 && cleanName.charAt(1) == ':'
                && Character.isLetter(cleanName.charAt(0)) && cleanName.charAt(0) != 'C') {
            char letter = cleanName.charAt(0);
            DriveBlockEntity drive = os.getDrive(letter);
            if (drive != null && drive.hasDisk()) {
                String label = drive.getDiskLabel();
                cleanName = label != null ? label : "Disk";
            }
        }
        renaming = true;
        renameBuffer.setLength(0);
        renameBuffer.append(cleanName);
        renameCursor = cleanName.length();
        needsRedraw = true;
    }

    private void finishRename() {
        if (!renaming) return;
        String newName = renameBuffer.toString().trim();
        if (!newName.isEmpty() && entries != null && selectedIndex >= 0 && selectedIndex < entries.size()) {
            String oldEntry = entries.get(selectedIndex);
            String oldClean = oldEntry.endsWith("/") ? oldEntry.substring(0, oldEntry.length() - 1) : oldEntry;
            // Check if renaming a drive entry (changes disk label)
            if (currentPath.equals("/") && oldClean.length() >= 2 && oldClean.charAt(1) == ':'
                    && Character.isLetter(oldClean.charAt(0)) && oldClean.charAt(0) != 'C') {
                char letter = oldClean.charAt(0);
                DriveBlockEntity drive = os.getDrive(letter);
                if (drive != null && drive.hasDisk()) {
                    drive.setDiskLabel(newName);
                    statusMessage = "Disk renamed: " + newName;
                } else {
                    statusMessage = "Drive not available";
                }
                refreshListing();
            } else if (!newName.equals(oldClean)) {
                String oldPath = currentPath.equals("/") ? "/" + oldClean : currentPath + "/" + oldClean;
                String newPath = currentPath.equals("/") ? "/" + newName : currentPath + "/" + newName;
                if (os.getFileSystem().move(oldPath, newPath)) {
                    statusMessage = "Renamed to: " + newName;
                } else {
                    statusMessage = "Rename failed";
                }
                refreshListing();
            }
        }
        renaming = false;
        needsRedraw = true;
    }

    private void cancelRename() {
        renaming = false;
        needsRedraw = true;
    }

    private void handleRenameKey(int keyCode) {
        switch (keyCode) {
            case 257 -> finishRename();  // Enter
            case 256 -> cancelRename();  // Escape
            case 259 -> { if (renameCursor > 0) { renameBuffer.deleteCharAt(renameCursor - 1); renameCursor--; } }
            case 261 -> { if (renameCursor < renameBuffer.length()) renameBuffer.deleteCharAt(renameCursor); }
            case 263 -> { if (renameCursor > 0) renameCursor--; }
            case 262 -> { if (renameCursor < renameBuffer.length()) renameCursor++; }
            case 268 -> renameCursor = 0;
            case 269 -> renameCursor = renameBuffer.length();
        }
        needsRedraw = true;
    }

    private void handleRenameChar(char c) {
        if (c >= 32 && c != '/' && c != '\\' && renameBuffer.length() < 40) {
            renameBuffer.insert(renameCursor, c);
            renameCursor++;
            needsRedraw = true;
        }
    }

    // ── New file/folder ──

    private void createNewFolder() {
        String name = "New Folder";
        String path = currentPath.equals("/") ? "/" + name : currentPath + "/" + name;
        int n = 1;
        while (os.getFileSystem().exists(path)) {
            name = "New Folder (" + n + ")";
            path = currentPath.equals("/") ? "/" + name : currentPath + "/" + name;
            n++;
        }
        os.getFileSystem().mkdir(path);
        statusMessage = "Created: " + name;
        refreshListing();
        // Select the new folder
        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).equals(name + "/")) { selectedIndex = i; break; }
            }
        }
        needsRedraw = true;
    }

    private void createNewFile() {
        String name = "New File.txt";
        String path = currentPath.equals("/") ? "/" + name : currentPath + "/" + name;
        int n = 1;
        while (os.getFileSystem().exists(path)) {
            name = "New File (" + n + ").txt";
            path = currentPath.equals("/") ? "/" + name : currentPath + "/" + name;
            n++;
        }
        os.getFileSystem().writeFile(path, "");
        statusMessage = "Created: " + name;
        refreshListing();
        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).equals(name)) { selectedIndex = i; break; }
            }
        }
        needsRedraw = true;
    }

    // ── Properties (status message) ──

    private void showProperties() {
        String path = getSelectedFullPath();
        if (path == null) return;
        long size = os.getFileSystem().getSize(path);
        boolean isDir = os.getFileSystem().isDirectory(path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        statusMessage = name + " | " + (isDir ? "Folder" : formatSize(size)) + " | " + path;
        needsRedraw = true;
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
        Level level = os.getLevel();
        BlockPos pos = os.getBlockPos();
        if (level == null || pos == null) return null;
        for (Direction dir : Direction.values()) {
            BlockEntity be = level.getBlockEntity(pos.relative(dir));
            if (be instanceof DriveBlockEntity drive) return drive;
        }
        return null;
    }

    private void printSelected() {
        String path = getSelectedFullPath();
        if (path == null) return;
        PrinterBlockEntity printer = findPrinter();
        if (printer == null) {
            statusMessage = "No printer found (place adjacent)";
            needsRedraw = true;
            return;
        }
        String content = os.getFileSystem().readFile(path);
        if (content == null) {
            statusMessage = "Cannot read file";
            needsRedraw = true;
            return;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        printer.queuePrint(name, content);
        statusMessage = "Sent to printer: " + name;
        needsRedraw = true;
    }

    private void saveSelectedToDisk() {
        String path = getSelectedFullPath();
        if (path == null) return;
        DriveBlockEntity drive = findDrive();
        if (drive == null) {
            statusMessage = "No drive found (place adjacent)";
            needsRedraw = true;
            return;
        }
        if (!drive.hasDisk()) {
            statusMessage = "No disk in drive";
            needsRedraw = true;
            return;
        }
        String content = os.getFileSystem().readFile(path);
        if (content == null) {
            statusMessage = "Cannot read file";
            needsRedraw = true;
            return;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        drive.writeToDisk("/" + name, content);
        drive.setDiskLabel(name);
        statusMessage = "Saved to disk: " + name;
        needsRedraw = true;
    }

    private void refreshListing() {
        if (isDrivePath(currentPath)) {
            char letter = currentPath.charAt(0);
            DriveBlockEntity drive = os.getDrive(letter);
            if (drive != null && drive.hasDisk()) {
                java.util.List<String> diskFiles = drive.listDiskFiles();
                entries = new java.util.ArrayList<>();
                for (String f : diskFiles) {
                    String name = f.startsWith("/") ? f.substring(1) : f;
                    if (!name.isEmpty()) entries.add(name);
                }
            } else {
                entries = List.of();
                statusMessage = "Drive not available";
            }
        } else {
            entries = os.getFileSystem().list(currentPath);
            if (entries == null) {
                entries = List.of();
                statusMessage = "Cannot read directory";
            }
            // Inject mounted drives at root
            if (currentPath.equals("/")) {
                Map<Character, DriveBlockEntity> drives = os.getMountedDrives();
                if (!drives.isEmpty()) {
                    var withDrives = new java.util.ArrayList<String>();
                    for (var e : drives.entrySet()) {
                        String label = e.getValue().getDiskLabel();
                        if (label == null || label.isEmpty()) label = "Disk";
                        withDrives.add(e.getKey() + ": (" + label + ")/");
                    }
                    withDrives.addAll(entries);
                    entries = withDrives;
                }
            }
        }
        needsRedraw = true;
    }

    private boolean isDrivePath(String path) {
        return path.length() >= 2 && Character.isLetter(path.charAt(0))
            && path.charAt(1) == ':' && path.charAt(0) != 'C' && path.charAt(0) != 'c';
    }

    private void ensureVisible(int maxVisible) {
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        if (selectedIndex >= scrollOffset + maxVisible) scrollOffset = selectedIndex - maxVisible + 1;
    }

    @Override
    public void render(TerminalBuffer buf) {
        int maxVisible = TerminalBuffer.HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT;

        // Header — path bar
        buf.setTextColor(0);
        buf.setBackgroundColor(11); // blue
        buf.hLine(0, TerminalBuffer.WIDTH - 1, 0, ' ');
        buf.writeAt(0, 0, " Explorer");
        buf.setBackgroundColor(7); // gray
        buf.hLine(0, TerminalBuffer.WIDTH - 1, 1, ' ');
        buf.setTextColor(0);
        String pathDisplay = " \u2190 " + currentPath;
        buf.writeAt(0, 1, pathDisplay.substring(0, Math.min(pathDisplay.length(), TerminalBuffer.WIDTH)));

        // Column headers
        buf.setTextColor(8);
        buf.setBackgroundColor(15);

        // File listing
        for (int vy = 0; vy < maxVisible; vy++) {
            int idx = scrollOffset + vy;
            int screenY = HEADER_HEIGHT + vy;

            buf.setBackgroundColor(15); // black
            buf.hLine(0, TerminalBuffer.WIDTH - 1, screenY, ' ');

            if (entries != null && idx < entries.size()) {
                String entry = entries.get(idx);
                boolean isDir = entry.endsWith("/");
                boolean isSelected = (idx == selectedIndex);

                if (isSelected) {
                    buf.setBackgroundColor(11); // blue highlight
                    buf.hLine(0, TerminalBuffer.WIDTH - 1, screenY, ' ');
                }

                // Icon
                buf.setTextColor(isDir ? 4 : 0); // yellow for dirs, white for files
                String icon = isDir ? "\u25B6 " : "  ";
                buf.writeAt(1, screenY, icon);

                // Name
                buf.setTextColor(isSelected ? 0 : (isDir ? 4 : 8));
                String displayName = isDir ? entry.substring(0, entry.length() - 1) : entry;
                buf.writeAt(3, screenY, displayName.substring(0, Math.min(displayName.length(), TerminalBuffer.WIDTH - 4)));

                // File size (right-aligned) for files
                if (!isDir) {
                    String fullPath = currentPath.equals("/") ? "/" + entry : currentPath + "/" + entry;
                    long size = os.getFileSystem().getSize(fullPath);
                    String sizeStr = size + "B";
                    buf.setTextColor(7);
                    buf.writeAt(TerminalBuffer.WIDTH - sizeStr.length() - 1, screenY, sizeStr);
                }
            }
        }

        // Footer — status bar
        buf.setTextColor(0);
        buf.setBackgroundColor(7);
        buf.hLine(0, TerminalBuffer.WIDTH - 1, TerminalBuffer.HEIGHT - 1, ' ');
        int fileCount = entries != null ? entries.size() : 0;
        String footer;
        if (!statusMessage.isEmpty()) {
            footer = " " + statusMessage;
            statusMessage = "";
        } else {
            footer = String.format(" %d items  |  Enter=Open  Bksp=Up  Del=Delete  F3=Close", fileCount);
        }
        buf.writeAt(0, TerminalBuffer.HEIGHT - 1,
            footer.substring(0, Math.min(footer.length(), TerminalBuffer.WIDTH)));
    }

    // ── Pixel-mode rendering ────────────────────────────────────────

    @Override
    public void renderGraphics(PixelBuffer pb) {
        setCursorInfo(0, 0, false);
        int CW = BitmapFont.CHAR_W, CH = BitmapFont.CHAR_H;
        int W = PixelBuffer.SCREEN_W, H = PixelBuffer.SCREEN_H;
        boolean hasSelection = entries != null && selectedIndex >= 0 && selectedIndex < entries.size();

        pb.clear(0xFF1E1E2E);

        // ── Toolbar row 1 (navigation): y 0..17 ──
        pb.fillRect(0, 0, W, 18, 0xFF2A2A40);
        int btnW = 24, btnH = 16, btnY = 1;
        drawToolButton(pb, 4, btnY, btnW, btnH, SystemIcons.Icon.BACK, !backStack.isEmpty());
        drawToolButton(pb, 32, btnY, btnW, btnH, SystemIcons.Icon.FORWARD, !forwardStack.isEmpty());
        drawToolButton(pb, 60, btnY, btnW, btnH, SystemIcons.Icon.UP, !currentPath.equals("/"));

        // ── Toolbar row 2 (actions): y 18..TB_H ──
        pb.fillRect(0, 18, W, TB_H - 18, 0xFF252538);
        pb.drawHLine(0, W - 1, TB_H - 1, 0xFF444466);
        int r2y = 19;
        drawToolButton(pb, 4, r2y, btnW, btnH, SystemIcons.Icon.CUT, hasSelection);
        drawToolButton(pb, 32, r2y, btnW, btnH, SystemIcons.Icon.COPY, hasSelection);
        drawToolButton(pb, 60, r2y, btnW, btnH, SystemIcons.Icon.PASTE, clipboardPath != null);
        // Separator
        pb.drawVLine(90, r2y, r2y + btnH, 0xFF444466);
        drawToolButton(pb, 96, r2y, btnW, btnH, SystemIcons.Icon.DELETE, hasSelection);
        drawToolButton(pb, 124, r2y, btnW, btnH, SystemIcons.Icon.RENAME, hasSelection);
        // Separator
        pb.drawVLine(154, r2y, r2y + btnH, 0xFF444466);
        drawToolButton(pb, 160, r2y, btnW, btnH, SystemIcons.Icon.NEW_FOLDER, true);
        drawToolButton(pb, 188, r2y, btnW, btnH, SystemIcons.Icon.NEW_FILE, true);

        // ── Address bar ──
        int abY = TB_H;
        pb.fillRect(0, abY, W, AB_H, 0xFFFFFFFF);
        pb.drawHLine(0, W - 1, abY + AB_H - 1, 0xFFCCCCCC);
        String displayPath = isDrivePath(currentPath)
            ? currentPath + "\\" : VirtualFileSystem.displayPath(currentPath);
        pb.drawString(4, abY + 2, displayPath, 0xFF222222);

        // ── Navigation pane (left sidebar) ──
        int contentY = TB_H + AB_H;
        int contentH = H - contentY - CH;
        pb.fillRect(0, contentY, NAV_W, contentH, 0xFF252538);
        pb.drawVLine(NAV_W, contentY, contentY + contentH - 1, 0xFF444466);

        String[][] sideItems = buildSidebarItems();
        SystemIcons.Icon[] sideIcons = buildSidebarIcons(sideItems);
        for (int i = 0; i < sideItems.length; i++) {
            int iy = contentY + 4 + i * 20;
            boolean isActive = currentPath.equals(sideItems[i][1]);
            if (isActive) {
                pb.fillRect(0, iy - 2, NAV_W, 20, 0xFF3355AA);
            }
            SystemIcons.draw(pb, 4, iy, sideIcons[i]);
            pb.drawString(24, iy + 1, sideItems[i][0], isActive ? 0xFFFFFFFF : 0xFFAABBCC);
        }

        // ── File listing (main content area) ──
        int fileX = NAV_W + 1;
        int fileW = W - fileX;
        pb.fillRect(fileX, contentY, fileW, contentH, 0xFF1E1E2E);

        // Column header
        int headerY = contentY;
        pb.fillRect(fileX, headerY, fileW, CH, 0xFF2A2A40);
        pb.drawString(fileX + 24, headerY + 1, "Name", 0xFF8899AA);
        pb.drawStringRight(fileX + fileW - 8, headerY + 1, "Size", 0xFF8899AA);
        pb.drawHLine(fileX, fileX + fileW - 1, headerY + CH - 1, 0xFF444466);

        int itemY = headerY + CH;
        int maxVisible = (contentH - CH) / ROW_H;
        for (int vy = 0; vy < maxVisible; vy++) {
            int idx = scrollOffset + vy;
            if (entries == null || idx >= entries.size()) break;

            String entry = entries.get(idx);
            boolean isDir = entry.endsWith("/");
            boolean isSelected = (idx == selectedIndex);
            boolean isCutSource = clipboardIsCut && clipboardPath != null && getEntryFullPath(entry).equals(clipboardPath);
            int ry = itemY + vy * ROW_H;

            if (isSelected) {
                pb.fillRect(fileX, ry, fileW, ROW_H, 0xFF3355AA);
            }

            // Icon
            String displayName = isDir ? entry.substring(0, entry.length() - 1) : entry;
            SystemIcons.Icon icon = isDir ? SystemIcons.iconForPath(
                    currentPath.equals("/") ? "/" + displayName : currentPath + "/" + displayName)
                : SystemIcons.iconForFile(entry);
            SystemIcons.draw(pb, fileX + 4, ry + 2, icon);

            // Name (or rename input)
            if (renaming && isSelected) {
                renderRenameInput(pb, fileX + 24, ry + 2, fileW - 32);
            } else {
                int nameColor = isCutSource ? 0xFF666688 :
                    (isSelected ? 0xFFFFFFFF : (isDir ? 0xFFEEDD88 : 0xFFCCCCCC));
                String truncName = displayName.length() > 40 ? displayName.substring(0, 39) + "\u2026" : displayName;
                pb.drawString(fileX + 24, ry + 3, truncName, nameColor);
            }

            // Size
            if (!isDir) {
                String fullPath = currentPath.equals("/") ? "/" + entry : currentPath + "/" + entry;
                long size = os.getFileSystem().getSize(fullPath);
                String sizeStr = formatSize(size);
                pb.drawStringRight(fileX + fileW - 8, ry + 3, sizeStr, 0xFF8899AA);
            }
        }

        // Scroll indicator
        if (entries != null && entries.size() > maxVisible) {
            int trackH = contentH - CH;
            int thumbH = Math.max(10, trackH * maxVisible / entries.size());
            int thumbY = itemY + (trackH - thumbH) * scrollOffset / Math.max(1, entries.size() - maxVisible);
            pb.fillRect(W - 6, thumbY, 4, thumbH, 0xFF556688);
        }

        // ── Status bar ──
        int sbY = H - CH;
        pb.fillRect(0, sbY, W, CH, 0xFF2A2A40);
        int fileCount = entries != null ? entries.size() : 0;
        String status;
        if (!statusMessage.isEmpty()) {
            status = statusMessage;
            statusMessage = "";
        } else {
            status = fileCount + " items";
            if (hasSelection) {
                String selName = entries.get(selectedIndex);
                if (selName.endsWith("/")) selName = selName.substring(0, selName.length() - 1);
                status += "  |  Selected: " + selName;
            }
        }
        pb.drawString(4, sbY + 1, status, 0xFFAABBCC);
        pb.drawStringRight(W - 4, sbY + 1, "Ctrl+C/X/V  F2=Rename  Del  Bksp=Up", 0xFF667788);

        // Context menu
        contextMenu.render(pb);
    }

    private void renderRenameInput(PixelBuffer pb, int x, int y, int maxW) {
        int CW = BitmapFont.CHAR_W;
        String text = renameBuffer.toString();
        int textW = Math.max(100, text.length() * CW + 12);
        textW = Math.min(textW, maxW);
        pb.fillRect(x, y, textW, 16, 0xFF000040);
        pb.drawRect(x, y, textW, 16, 0xFF6699CC);
        pb.drawString(x + 2, y + 2, text, 0xFFFFFFFF);
        // Cursor
        int cursorX = x + 2 + renameCursor * CW;
        pb.fillRect(cursorX, y + 2, 1, 12, 0xFFFFFFFF);
    }

    private String getEntryFullPath(String entry) {
        String cleanName = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
        return currentPath.equals("/") ? "/" + cleanName : currentPath + "/" + cleanName;
    }

    private void drawToolButton(PixelBuffer pb, int x, int y, int w, int h,
                                SystemIcons.Icon icon, boolean enabled) {
        int bg = enabled ? 0xFF3A3A55 : 0xFF2A2A40;
        pb.fillRoundRect(x, y, w, h, 2, bg);
        if (enabled) pb.drawRect(x, y, w, h, 0xFF556688);
        SystemIcons.draw(pb, x + (w - 16) / 2, y + (h - 16) / 2, icon);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ── Sidebar helpers (dynamic with mounted drives) ──

    private String[][] buildSidebarItems() {
        var list = new java.util.ArrayList<String[]>();
        list.add(new String[]{"This PC", "/"});
        for (var e : os.getMountedDrives().entrySet()) {
            String lbl = e.getValue().getDiskLabel();
            if (lbl == null || lbl.isEmpty()) lbl = "Disk";
            list.add(new String[]{e.getKey() + ": " + lbl, e.getKey() + ":"});
        }
        list.add(new String[]{"Desktop", "/Users/User/Desktop"});
        list.add(new String[]{"Documents", "/Users/User/Documents"});
        list.add(new String[]{"Downloads", "/Users/User/Downloads"});
        list.add(new String[]{"Pictures", "/Users/User/Pictures"});
        return list.toArray(new String[0][]);
    }

    private SystemIcons.Icon[] buildSidebarIcons(String[][] sideItems) {
        SystemIcons.Icon[] icons = new SystemIcons.Icon[sideItems.length];
        for (int i = 0; i < sideItems.length; i++) {
            icons[i] = switch (sideItems[i][1]) {
                case "/" -> SystemIcons.Icon.COMPUTER;
                case "/Users/User/Desktop" -> SystemIcons.Icon.COMPUTER;
                case "/Users/User/Documents" -> SystemIcons.Icon.DOCUMENTS;
                case "/Users/User/Downloads" -> SystemIcons.Icon.DOWNLOADS;
                case "/Users/User/Pictures" -> SystemIcons.Icon.PICTURES;
                default -> SystemIcons.Icon.COMPUTER; // drives
            };
        }
        return icons;
    }
}

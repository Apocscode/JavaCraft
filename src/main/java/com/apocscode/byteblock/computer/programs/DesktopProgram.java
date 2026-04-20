package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.ContextMenu;
import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.SystemIcons;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.computer.BitmapFont;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows-style desktop environment for ByteBlock OS.
 * Features: desktop with icons, taskbar at bottom, start menu, window management.
 */
public class DesktopProgram extends OSProgram {

    // Window state
    public static class Window {
        String title;
        int x, y, width, height;
        boolean minimized;
        boolean maximized;
        OSProgram program;
        // Saved position for restore-from-maximize
        int savedX, savedY, savedW, savedH;
        // Cached pixel buffer for pixel-mode rendering
        PixelBuffer programBuffer;

        Window(String title, int x, int y, int w, int h, OSProgram program) {
            this.title = title;
            this.x = x; this.y = y;
            this.width = w; this.height = h;
            this.program = program;
        }
    }

    private final List<Window> windows = new ArrayList<>();
    private Window activeWindow;
    private boolean startMenuOpen;
    private boolean needsRedraw = true;

    // Mouse tracking for window dragging
    private boolean dragging;
    private int dragOffsetX, dragOffsetY;

    // Mouse tracking for window resizing
    private boolean resizing;

    // Start menu items (set in static initializer after START_LEFT is defined)
    private static final String[] START_ITEMS;
    private int startHoverIndex = -1;

    // Icon system â€” 16 preset icon characters
    static final char[] ICON_CHARS = {
        '\u25A0', '\u25B6', '#', '\u2665', '\u2605', '\u266A', '\u2302', '\u263A',
        '\u2666', '>', '@', '!', '?', '+', '\u2660', '\u2663'
    };
    static final String[] ICON_LABELS = {
        "App", "Play", "Code", "Heart", "Star", "Music", "Home", "Smiley",
        "Diamond", "Terminal", "Gear", "Alert", "Help", "Plus", "Spade", "Club"
    };

    // Dynamic desktop shortcuts from /desktop/*.lnk files
    static class Shortcut {
        String name, target, lnkFile;
        int iconIndex, colorIndex;
        int posX, posY; // desktop position (cell coords)
        String sysIcon; // optional SystemIcons.Icon override (e.g. "TERMINAL")
        Shortcut(String name, String target, int icon, int color, int px, int py, String lnkFile) {
            this.name = name; this.target = target;
            this.iconIndex = icon; this.colorIndex = color;
            this.posX = px; this.posY = py; this.lnkFile = lnkFile;
        }
    }
    private final List<Shortcut> shortcuts = new ArrayList<>();

    // Window snap-to-edge
    private int lastDragCellX, lastDragCellY;

    // Alt+Tab switcher
    private boolean altTabOpen;
    private int altTabIndex;

    // Title bar double-click detection
    private long lastTitleClickTime;
    private Window lastTitleClickWindow;

    // Icon selection and double-click
    private Shortcut selectedIcon;
    private long lastIconClickTime;
    private Shortcut lastIconClickTarget;

    // Icon dragging state
    private Shortcut draggingIcon;
    private Shortcut dragCandidate; // set on click, promoted to draggingIcon on move
    private int iconDragOffsetX, iconDragOffsetY;
    private int iconDragPxX, iconDragPxY; // pixel position during drag

    // Inline rename state
    private Shortcut renamingIcon;
    private final StringBuilder renameBuffer = new StringBuilder();
    private int renameCursor;

    // Properties overlay
    private boolean showingProperties;
    private Shortcut propertiesTarget;

    // Shortcut creation wizard overlay
    private enum WizardState { NONE, NAME_INPUT, TARGET_INPUT, ICON_PICK, COLOR_PICK }
    private WizardState wizardState = WizardState.NONE;
    private final StringBuilder wizardName = new StringBuilder();
    private final StringBuilder wizardTarget = new StringBuilder();
    private int wizardIcon = 0;
    private int wizardColor = 0;

    // Icon picker overlay (for Change Icon)
    private boolean iconPickerOpen;
    private Shortcut iconPickerTarget;
    private int iconPickerHover = -1;
    private static final SystemIcons.Icon[] PICKER_ICONS = {
        SystemIcons.Icon.COMPUTER, SystemIcons.Icon.FOLDER, SystemIcons.Icon.FOLDER_OPEN,
        SystemIcons.Icon.FILE, SystemIcons.Icon.FILE_TEXT, SystemIcons.Icon.FILE_CODE,
        SystemIcons.Icon.FILE_IMAGE, SystemIcons.Icon.RECYCLE_BIN, SystemIcons.Icon.TERMINAL,
        SystemIcons.Icon.SETTINGS, SystemIcons.Icon.PAINT, SystemIcons.Icon.PUZZLE,
        SystemIcons.Icon.CALCULATOR, SystemIcons.Icon.TASK_MANAGER, SystemIcons.Icon.EXPLORER,
        SystemIcons.Icon.NOTEPAD, SystemIcons.Icon.LUA_MOON, SystemIcons.Icon.SHUTDOWN,
        SystemIcons.Icon.RESTART, SystemIcons.Icon.DRIVE, SystemIcons.Icon.DOCUMENTS,
        SystemIcons.Icon.DOWNLOADS, SystemIcons.Icon.PICTURES, SystemIcons.Icon.NETWORK,
        SystemIcons.Icon.NEW_FILE, SystemIcons.Icon.NEW_FOLDER, SystemIcons.Icon.SEARCH,
        SystemIcons.Icon.BLUETOOTH,
    };

    // Desktop customization â€” persisted to /Windows/desktop.cfg
    private int wallpaperColor = 11;  // default blue
    private int iconTextColor = 0;    // default white
    private int taskbarColor = 7;     // default gray
    private int taskbarTextColor = 0; // default white
    private static final String DESKTOP_CFG = "/Windows/desktop.cfg";
    private static final String DESKTOP_DIR = "/Users/User/Desktop";

    // Icon grid layout (pixel sizing)
    private static final int ICON_GRID_W = 64;
    private static final int ICON_GRID_H = 48;
    private static final int ICON_MARGIN_LEFT = 8;
    private static final int ICON_MARGIN_TOP = 4;
    private static final int ICON_COLS = (PixelBuffer.SCREEN_W - ICON_MARGIN_LEFT) / ICON_GRID_W;
    private static final int ICON_ROWS = (PixelBuffer.SCREEN_H - PixelBuffer.CELL_H - ICON_MARGIN_TOP) / ICON_GRID_H;

    // Context menu
    private final ContextMenu contextMenu = new ContextMenu();
    private Shortcut contextTarget; // shortcut attached to active context menu
    private int startMenuContextIndex = -1; // start menu item right-clicked for pin

    // Start menu structure (two-panel)
    private static final String[] START_LEFT = {
        "Shell", "Lua Shell", "Puzzle", "IDE", "Notepad", "Explorer",
        "Paint", "Calculator", "Task Manager", "Bluetooth", "Buttons", "Monitor"
    };
    private static final String[] START_LEFT_TARGETS = {
        "builtin:shell", "builtin:lua", "builtin:puzzle", "builtin:edit",
        "builtin:notepad", "builtin:explorer", "builtin:paint", "builtin:calculator", "builtin:taskmanager",
        "builtin:bluetooth", "builtin:buttons", "builtin:monitor"
    };
    private static final SystemIcons.Icon[] START_LEFT_ICONS = {
        SystemIcons.Icon.TERMINAL, SystemIcons.Icon.LUA_MOON, SystemIcons.Icon.PUZZLE,
        SystemIcons.Icon.FILE_CODE, SystemIcons.Icon.NOTEPAD, SystemIcons.Icon.EXPLORER, SystemIcons.Icon.PAINT,
        SystemIcons.Icon.CALCULATOR, SystemIcons.Icon.TASK_MANAGER, SystemIcons.Icon.BLUETOOTH,
        SystemIcons.Icon.BLUETOOTH, SystemIcons.Icon.COMPUTER
    };
    private static final String[] START_RIGHT = {
        "Documents", "Pictures", "Downloads", "This PC"
    };
    private static final String[] START_RIGHT_PATHS = {
        "/Users/User/Documents", "/Users/User/Pictures", "/Users/User/Downloads", "/"
    };
    private static final SystemIcons.Icon[] START_RIGHT_ICONS = {
        SystemIcons.Icon.DOCUMENTS, SystemIcons.Icon.PICTURES,
        SystemIcons.Icon.DOWNLOADS, SystemIcons.Icon.COMPUTER
    };
    static { START_ITEMS = START_LEFT; }

    public DesktopProgram() {
        super("Desktop");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        loadDesktopConfig();
        loadShortcuts();
        needsRedraw = true;
    }

    private void loadDesktopConfig() {
        String cfg = os.getFileSystem().readFile(DESKTOP_CFG);
        if (cfg == null) return;
        for (String line : cfg.split("\n")) {
            line = line.trim();
            try {
                if (line.startsWith("wallpaper=")) wallpaperColor = Integer.parseInt(line.substring(10).trim()) & 0xF;
                else if (line.startsWith("iconText=")) iconTextColor = Integer.parseInt(line.substring(9).trim()) & 0xF;
                else if (line.startsWith("taskbar=")) taskbarColor = Integer.parseInt(line.substring(8).trim()) & 0xF;
                else if (line.startsWith("taskbarText=")) taskbarTextColor = Integer.parseInt(line.substring(12).trim()) & 0xF;
                else if (line.startsWith("textScale=")) os.setTextScale(Float.parseFloat(line.substring(10).trim()));
            } catch (NumberFormatException ignored) {}
        }
    }

    public void saveDesktopConfig() {
        String cfg = "wallpaper=" + wallpaperColor + "\niconText=" + iconTextColor
                + "\ntaskbar=" + taskbarColor + "\ntaskbarText=" + taskbarTextColor
                + "\ntextScale=" + os.getTextScale() + "\n";
        os.getFileSystem().writeFile(DESKTOP_CFG, cfg);
    }

    // Getters/setters for Settings program
    public int getWallpaperColor() { return wallpaperColor; }
    public void setWallpaperColor(int c) { wallpaperColor = c & 0xF; }
    public int getIconTextColor() { return iconTextColor; }
    public void setIconTextColor(int c) { iconTextColor = c & 0xF; }
    public int getTaskbarColor() { return taskbarColor; }
    public void setTaskbarColor(int c) { taskbarColor = c & 0xF; }
    public int getTaskbarTextColor() { return taskbarTextColor; }
    public void setTaskbarTextColor(int c) { taskbarTextColor = c & 0xF; }

    @Override
    public boolean tick() {
        // Tick all windowed programs
        List<Window> toRemove = new ArrayList<>();
        for (Window w : windows) {
            if (w.program != null && w.program.isRunning()) {
                w.program.tick();
            } else if (w.program != null) {
                toRemove.add(w);
            }
        }
        windows.removeAll(toRemove);
        if (toRemove.contains(activeWindow)) activeWindow = null;
        return running;
    }

    @Override
    public void handleEvent(OSEvent event) {
        if (wizardState != WizardState.NONE) {
            handleWizardEvent(event);
            return;
        }
        if (iconPickerOpen) {
            handleIconPickerEvent(event);
            return;
        }
        switch (event.getType()) {
            case MOUSE_CLICK -> {} // handled via MOUSE_CLICK_PX to avoid double-dispatch
            case MOUSE_CLICK_PX -> handleMouseClickPx(event.getInt(0), event.getInt(1), event.getInt(2));
            case MOUSE_DRAG -> {
                handleMouseDrag(event.getInt(0), event.getInt(1), event.getInt(2));
                // Also update context menu hover
                int dpx = event.getInt(1) * PixelBuffer.CELL_W + PixelBuffer.CELL_W / 2;
                int dpy = event.getInt(2) * PixelBuffer.CELL_H + PixelBuffer.CELL_H / 2;
                contextMenu.handleMove(dpx, dpy);
                // Forward drag to active window if not dragging/resizing the window itself
                if (!dragging && !resizing && draggingIcon == null
                        && activeWindow != null && activeWindow.program != null) {
                    int localX = event.getInt(1) - activeWindow.x;
                    int localY = event.getInt(2) - activeWindow.y - 1;
                    activeWindow.program.handleEvent(new OSEvent(OSEvent.Type.MOUSE_DRAG, event.getInt(0), localX, localY));
                    needsRedraw = true;
                }
            }
            case MOUSE_DRAG_PX -> handleMouseDragPx(event.getInt(0), event.getInt(1), event.getInt(2));
            case MOUSE_UP -> {
                if (draggingIcon != null) {
                    // Snap to nearest grid cell
                    int gridX = Math.round((float)(iconDragPxX - ICON_MARGIN_LEFT) / ICON_GRID_W);
                    int gridY = Math.round((float)(iconDragPxY - ICON_MARGIN_TOP) / ICON_GRID_H);
                    gridX = Math.max(0, Math.min(ICON_COLS - 1, gridX));
                    gridY = Math.max(0, Math.min(ICON_ROWS - 1, gridY));
                    // If cell is occupied by another icon, find nearest free cell
                    if (isGridCellOccupied(gridX, gridY, draggingIcon)) {
                        int[] free = findNearestFreeCell(gridX, gridY, draggingIcon);
                        if (free != null) { gridX = free[0]; gridY = free[1]; }
                    }
                    draggingIcon.posX = gridX;
                    draggingIcon.posY = gridY;
                    saveShortcutPosition(draggingIcon);
                    draggingIcon = null;
                    dragCandidate = null;
                }
                // Snap-to-edge when finishing a window drag
                if (dragging && activeWindow != null && !activeWindow.maximized) {
                    int ux = event.getInt(1), uy = event.getInt(2);
                    int TW = TerminalBuffer.WIDTH, TH = TerminalBuffer.HEIGHT - 1;
                    if (uy <= 0) {
                        // Snap maximize (drag to top)
                        activeWindow.savedX = activeWindow.x; activeWindow.savedY = activeWindow.y;
                        activeWindow.savedW = activeWindow.width; activeWindow.savedH = activeWindow.height;
                        activeWindow.x = 0; activeWindow.y = 0;
                        activeWindow.width = TW; activeWindow.height = TH;
                        activeWindow.maximized = true;
                    } else if (ux <= 0) {
                        // Snap left half
                        activeWindow.x = 0; activeWindow.y = 0;
                        activeWindow.width = TW / 2; activeWindow.height = TH;
                    } else if (ux >= TW - 1) {
                        // Snap right half
                        activeWindow.x = TW / 2; activeWindow.y = 0;
                        activeWindow.width = TW - TW / 2; activeWindow.height = TH;
                    }
                }
                dragging = false;
                resizing = false;
                needsRedraw = true;
                // Forward mouse-up to active window's program (e.g. end column drag)
                if (activeWindow != null && activeWindow.program != null) {
                    activeWindow.program.handleEvent(new OSEvent(OSEvent.Type.MOUSE_UP, 0));
                }
            }
            case KEY -> handleKey(event.getInt(0), event.getInt(2));
            case MOUSE_SCROLL -> {
                // Forward scroll to active window's program with local coords
                if (activeWindow != null && activeWindow.program != null) {
                    int dir = event.getInt(0);
                    int sx = event.getInt(1) - activeWindow.x;
                    int sy = event.getInt(2) - activeWindow.y - 1;
                    activeWindow.program.handleEvent(new OSEvent(OSEvent.Type.MOUSE_SCROLL, dir, sx, sy));
                    needsRedraw = true;
                }
            }
            case CHAR -> {
                if (renamingIcon != null) {
                    handleRenameChar(event.getString(0).charAt(0));
                } else if (activeWindow != null && activeWindow.program != null) {
                    activeWindow.program.handleEvent(event);
                }
                needsRedraw = true;
            }
            case PASTE -> {
                // Forward paste to active window's program
                if (activeWindow != null && activeWindow.program != null) {
                    activeWindow.program.handleEvent(event);
                    needsRedraw = true;
                }
            }
            default -> {}
        }
    }

    private void handleMouseClickPx(int button, int px, int py) {
        // Context menu check first
        if (contextMenu.isVisible()) {
            String action = contextMenu.handleClick(px, py);
            if (action != null) {
                handleContextAction(action);
            }
            needsRedraw = true;
            return;
        }
        // Convert to cell coords for standard handling
        int cx = px / PixelBuffer.CELL_W;
        int cy = py / PixelBuffer.CELL_H;
        handleMouseClick(button, cx, cy, px, py);
    }

    private void handleMouseClick(int button, int mx, int my, int px, int py) {
        needsRedraw = true;
        dragCandidate = null; // Reset drag candidate on any new click

        // Context menu intercept
        if (contextMenu.isVisible()) {
            String action = contextMenu.handleClick(px, py);
            if (action != null) handleContextAction(action);
            return;
        }

        // Right-click â†’ context menu
        if (button == 1) {
            if (startMenuOpen) {
                handleStartMenuRightClick(px, py);
            } else {
                handleRightClick(px, py);
            }
            return;
        }

        // Taskbar click (bottom row)
        if (my == TerminalBuffer.HEIGHT - 1) {
            handleTaskbarClick(mx);
            return;
        }

        // Start menu click
        if (startMenuOpen) {
            handleStartMenuClickPx(px, py);
            return;
        }

        // Window title bar / content click (iterate top to bottom in z-order)
        for (int i = windows.size() - 1; i >= 0; i--) {
            Window w = windows.get(i);
            if (w.minimized) continue;
            if (mx >= w.x && mx < w.x + w.width && my >= w.y && my < w.y + w.height) {
                // Bring to front
                windows.remove(i);
                windows.add(w);
                activeWindow = w;

                // Title bar?
                if (my == w.y) {
                    handleTitleBarClick(w, mx, my);
                } else if (my == w.y + w.height - 1 && mx >= w.x + w.width - 2) {
                    // Resize handle (bottom-right corner)
                    resizing = true;
                } else if (w.program != null) {
                    // Pass click to program (translated to window-local coords)
                    int localX = mx - w.x;
                    int localY = my - w.y - 1;
                    w.program.handleEvent(new OSEvent(OSEvent.Type.MOUSE_CLICK, button, localX, localY));
                    // Also forward pixel-precise coordinates for pixel-mode programs
                    int localPx = px - w.x * PixelBuffer.CELL_W;
                    int localPy = py - (w.y + 1) * PixelBuffer.CELL_H;
                    w.program.handleEvent(new OSEvent(OSEvent.Type.MOUSE_CLICK_PX, button, localPx, localPy));
                }
                return;
            }
        }

        // Click on desktop background / icons
        startMenuOpen = false;
        handleDesktopClick(px, py);
    }

    private void handleStartMenuRightClick(int px, int py) {
        // Compute start menu geometry (must match renderStartMenuPx / handleStartMenuClickPx)
        int smW = 240, smLeftW = 140;
        int itemH = 22, headerH = 24;
        int maxItems = Math.max(START_LEFT.length, START_RIGHT.length);
        int bodyH = maxItems * itemH;
        int footerH = 28;
        int smH = headerH + bodyH + footerH;
        int smX = 0, smY = PixelBuffer.SCREEN_H - PixelBuffer.CELL_H - smH;
        int leftY = smY + headerH;

        // Check if right-click is on a left-panel program item
        if (px >= smX && px < smX + smLeftW && py >= leftY && py < leftY + bodyH) {
            int idx = (py - leftY) / itemH;
            if (idx >= 0 && idx < START_LEFT.length) {
                startMenuContextIndex = idx;
                contextTarget = null;
                boolean alreadyOnDesktop = findShortcutByTarget(START_LEFT_TARGETS[idx]) != null;
                contextMenu.show(px, py, List.of(
                    new ContextMenu.Item("Open", "pin_open"),
                    ContextMenu.Item.sep(),
                    alreadyOnDesktop
                        ? ContextMenu.Item.disabled("Already on Desktop")
                        : new ContextMenu.Item("Pin to Desktop", "pin_desktop")
                ));
                return;
            }
        }
        // Right-click outside left panel items — just close menu
        startMenuOpen = false;
    }

    private void handleRightClick(int px, int py) {
        // Check if right-clicking on an icon
        Shortcut hit = hitTestIcon(px, py);
        if (hit != null) {
            selectedIcon = hit;
            contextTarget = hit;
            startMenuContextIndex = -1;
            contextMenu.show(px, py, List.of(
                new ContextMenu.Item("Open", "open"),
                ContextMenu.Item.sep(),
                new ContextMenu.Item("Rename", "rename"),
                new ContextMenu.Item("Change Icon", "change_icon"),
                new ContextMenu.Item("Delete", "delete"),
                ContextMenu.Item.sep(),
                new ContextMenu.Item("Properties", "properties")
            ));
        } else {
            selectedIcon = null;
            contextTarget = null;
            startMenuContextIndex = -1;
            contextMenu.show(px, py, List.of(
                new ContextMenu.Item("Sort by Name", "sort_name"),
                new ContextMenu.Item("Sort by Type", "sort_type"),
                ContextMenu.Item.sep(),
                new ContextMenu.Item("Auto Arrange Icons", "auto_arrange"),
                ContextMenu.Item.sep(),
                new ContextMenu.Item("New Folder", "new_folder"),
                new ContextMenu.Item("New Text File", "new_file"),
                ContextMenu.Item.sep(),
                new ContextMenu.Item("New Shortcut...", "wizard"),
                ContextMenu.Item.sep(),
                new ContextMenu.Item("Refresh", "refresh"),
                new ContextMenu.Item("Settings", "open_settings")
            ));
        }
    }

    private void handleContextAction(String action) {
        switch (action) {
            case "open" -> { if (contextTarget != null) launchShortcut(contextTarget); }
            case "delete" -> {
                if (contextTarget != null) {
                    os.getFileSystem().delete(DESKTOP_DIR + "/" + contextTarget.lnkFile);
                    if (selectedIcon == contextTarget) selectedIcon = null;
                    loadShortcuts();
                }
            }
            case "rename" -> { if (contextTarget != null) startIconRename(contextTarget); }
            case "change_icon" -> {
                if (contextTarget != null) {
                    iconPickerTarget = contextTarget;
                    iconPickerOpen = true;
                    iconPickerHover = -1;
                }
            }
            case "properties" -> { if (contextTarget != null) showProperties(contextTarget); }
            case "sort_name" -> autoArrangeIcons(java.util.Comparator.comparing(s -> s.name.toLowerCase()));
            case "sort_type" -> autoArrangeIcons(java.util.Comparator.comparing(s -> s.target));
            case "auto_arrange" -> autoArrangeIcons(null);
            case "new_folder" -> {
                // Create actual folder in Documents
                String baseName = "New Folder";
                String basePath = "/Users/User/Documents/" + baseName;
                int n = 1;
                while (os.getFileSystem().isDirectory(basePath)) {
                    baseName = "New Folder (" + n + ")";
                    basePath = "/Users/User/Documents/" + baseName;
                    n++;
                }
                os.getFileSystem().mkdir(basePath);
                // Find a free grid position
                int[] pos = findNextFreeCell();
                createShortcutFile(baseName, basePath, 1, 1, pos[0], pos[1]); // FOLDER icon=1, color=1
                loadShortcuts();
                // Start rename on the new shortcut
                Shortcut ns = findShortcutByTarget(basePath);
                if (ns != null) startIconRename(ns);
            }
            case "new_file" -> {
                // Create actual file in Documents
                String baseName = "New File";
                String basePath = "/Users/User/Documents/" + baseName + ".txt";
                int n = 1;
                while (os.getFileSystem().isFile(basePath)) {
                    baseName = "New File (" + n + ")";
                    basePath = "/Users/User/Documents/" + baseName + ".txt";
                    n++;
                }
                os.getFileSystem().writeFile(basePath, "");
                // Find a free grid position
                int[] pos = findNextFreeCell();
                createShortcutFile(baseName + ".txt", basePath, 4, 0, pos[0], pos[1]); // FILE_TEXT icon=4, color=0
                loadShortcuts();
                // Start rename on the new shortcut
                Shortcut ns = findShortcutByTarget(basePath);
                if (ns != null) startIconRename(ns);
            }
            case "wizard" -> startWizard();
            case "refresh" -> loadShortcuts();
            case "open_settings" -> openWindow("Settings", new SettingsProgram(), 5, 1, 45, 16);
            case "pin_desktop" -> {
                if (startMenuContextIndex >= 0 && startMenuContextIndex < START_LEFT.length) {
                    String name = START_LEFT[startMenuContextIndex];
                    String target = START_LEFT_TARGETS[startMenuContextIndex];
                    String sysIcon = START_LEFT_ICONS[startMenuContextIndex].name();
                    // Only add if not already on desktop
                    if (findShortcutByTarget(target) == null) {
                        int[] pos = findNextFreeCell();
                        createShortcutFile(name, target, 0, 0, pos[0], pos[1], sysIcon);
                        loadShortcuts();
                    }
                    startMenuContextIndex = -1;
                }
            }
            case "pin_open" -> {
                if (startMenuContextIndex >= 0 && startMenuContextIndex < START_LEFT.length) {
                    launchByTarget(START_LEFT_TARGETS[startMenuContextIndex]);
                    startMenuOpen = false;
                    startMenuContextIndex = -1;
                }
            }
        }
        needsRedraw = true;
    }

    private Shortcut hitTestIcon(int px, int py) {
        for (Shortcut s : shortcuts) {
            int ix = ICON_MARGIN_LEFT + s.posX * ICON_GRID_W;
            int iy = ICON_MARGIN_TOP + s.posY * ICON_GRID_H;
            if (px >= ix && px < ix + ICON_GRID_W && py >= iy && py < iy + ICON_GRID_H) {
                return s;
            }
        }
        return null;
    }

    private void handleTitleBarClick(Window w, int mx, int my) {
        // Buttons are each 3 cells wide, right-aligned in title bar
        int closeStart = w.x + w.width - 3;  // cells [w-3, w-2, w-1]
        int maxStart   = w.x + w.width - 6;  // cells [w-6, w-5, w-4]
        int minStart   = w.x + w.width - 9;  // cells [w-9, w-8, w-7]

        if (mx >= closeStart) {
            // Close button [X]
            if (w.program != null) w.program.shutdown();
            windows.remove(w);
            if (activeWindow == w) activeWindow = windows.isEmpty() ? null : windows.getLast();
        } else if (mx >= maxStart) {
            // Maximize / restore
            if (w.maximized) {
                w.x = w.savedX; w.y = w.savedY;
                w.width = w.savedW; w.height = w.savedH;
                w.maximized = false;
            } else {
                w.savedX = w.x; w.savedY = w.y;
                w.savedW = w.width; w.savedH = w.height;
                w.x = 0; w.y = 0;
                w.width = TerminalBuffer.WIDTH;
                w.height = TerminalBuffer.HEIGHT - 1;
                w.maximized = true;
            }
        } else if (mx >= minStart) {
            // Minimize
            w.minimized = true;
            if (activeWindow == w) {
                activeWindow = null;
                // Find next visible window
                for (int i = windows.size() - 1; i >= 0; i--) {
                    if (!windows.get(i).minimized) {
                        activeWindow = windows.get(i);
                        break;
                    }
                }
            }
        } else {
            // Double-click title bar to maximize/restore
            long now = System.currentTimeMillis();
            if (lastTitleClickWindow == w && now - lastTitleClickTime < 400) {
                if (w.maximized) {
                    w.x = w.savedX; w.y = w.savedY;
                    w.width = w.savedW; w.height = w.savedH;
                    w.maximized = false;
                } else {
                    w.savedX = w.x; w.savedY = w.y;
                    w.savedW = w.width; w.savedH = w.height;
                    w.x = 0; w.y = 0;
                    w.width = TerminalBuffer.WIDTH;
                    w.height = TerminalBuffer.HEIGHT - 1;
                    w.maximized = true;
                }
                lastTitleClickTime = 0;
            } else {
                lastTitleClickTime = now;
                lastTitleClickWindow = w;
                // Start drag
                dragging = true;
                dragOffsetX = mx - w.x;
                dragOffsetY = my - w.y;
            }
        }
    }

    private void handleMouseDrag(int button, int mx, int my) {
        if (draggingIcon != null) {
            // Icon drag handled in handleMouseDragPx (pixel coords)
            needsRedraw = true;
        } else if (resizing && activeWindow != null && !activeWindow.maximized) {
            activeWindow.width = Math.max(10, Math.min(TerminalBuffer.WIDTH - activeWindow.x, mx - activeWindow.x + 1));
            activeWindow.height = Math.max(4, Math.min(TerminalBuffer.HEIGHT - 1 - activeWindow.y, my - activeWindow.y + 1));
            needsRedraw = true;
        } else if (dragging && activeWindow != null && !activeWindow.maximized) {
            activeWindow.x = Math.max(0, Math.min(TerminalBuffer.WIDTH - activeWindow.width, mx - dragOffsetX));
            activeWindow.y = Math.max(0, Math.min(TerminalBuffer.HEIGHT - 2 - activeWindow.height, my - dragOffsetY));
            needsRedraw = true;
        }
    }

    private void handleMouseDragPx(int button, int px, int py) {
        if (dragCandidate != null && draggingIcon == null) {
            // Promote drag candidate to active icon drag
            draggingIcon = dragCandidate;
            dragCandidate = null;
            selectedIcon = draggingIcon;
        }
        if (draggingIcon != null) {
            iconDragPxX = Math.max(0, Math.min(PixelBuffer.SCREEN_W - ICON_GRID_W, px - iconDragOffsetX));
            iconDragPxY = Math.max(0, Math.min(PixelBuffer.SCREEN_H - PixelBuffer.CELL_H - ICON_GRID_H, py - iconDragOffsetY));
            needsRedraw = true;
        } else if (!dragging && !resizing && activeWindow != null && activeWindow.program != null) {
            // Forward pixel-precise drag to the active window's program
            int localPx = px - activeWindow.x * PixelBuffer.CELL_W;
            int localPy = py - (activeWindow.y + 1) * PixelBuffer.CELL_H;
            activeWindow.program.handleEvent(new OSEvent(OSEvent.Type.MOUSE_DRAG_PX, button, localPx, localPy));
            needsRedraw = true;
        }
    }

    private void handleKey(int keyCode, int mods) {
        needsRedraw = true;

        // Alt+Tab switcher
        if (keyCode == 258 && !windows.isEmpty()) { // Tab key
            if (!altTabOpen) {
                altTabOpen = true;
                altTabIndex = windows.size() - 1;
            }
            // Cycle backwards through windows
            altTabIndex--;
            if (altTabIndex < 0) altTabIndex = windows.size() - 1;
            return;
        }
        // Any non-Tab key while alt-tab is open → select
        if (altTabOpen) {
            altTabOpen = false;
            if (altTabIndex >= 0 && altTabIndex < windows.size()) {
                Window sel = windows.get(altTabIndex);
                sel.minimized = false;
                windows.remove(altTabIndex);
                windows.add(sel);
                activeWindow = sel;
            }
            return;
        }

        if (startMenuOpen) {
            if (keyCode == 256) { // Escape
                startMenuOpen = false;
            }
            return;
        }

        // Properties overlay dismiss
        if (showingProperties) {
            if (keyCode == 256 || keyCode == 257) { // Escape or Enter
                showingProperties = false;
                propertiesTarget = null;
            }
            return;
        }

        // Desktop icon keyboard shortcuts (when no window is focused)
        if (activeWindow == null) {
            // Inline rename mode
            if (renamingIcon != null) {
                handleRenameKey(keyCode);
                return;
            }
            // Icon shortcuts
            if (selectedIcon != null) {
                if (keyCode == 257) { // Enter → open
                    launchShortcut(selectedIcon);
                    selectedIcon = null;
                    return;
                }
                if (keyCode == 261) { // Delete → remove shortcut
                    os.getFileSystem().delete(DESKTOP_DIR + "/" + selectedIcon.lnkFile);
                    selectedIcon = null;
                    loadShortcuts();
                    return;
                }
                if (keyCode == 291) { // F2 → rename
                    startIconRename(selectedIcon);
                    return;
                }
            }
            if (keyCode == 256) { // Escape → deselect
                selectedIcon = null;
                return;
            }
            return; // Don't forward to (non-existent) window
        }

        // Forward to active window
        if (activeWindow != null && activeWindow.program != null) {
            activeWindow.program.handleEvent(new OSEvent(OSEvent.Type.KEY, keyCode, 0, mods));
        }
    }

    private void handleTaskbarClick(int mx) {
        if (mx < 7) {
            // [Start] button
            startMenuOpen = !startMenuOpen;
            return;
        }
        startMenuOpen = false;

        // Click on taskbar window entries
        int offset = 8;
        for (Window w : windows) {
            int labelLen = w.title.length() + 2;
            if (mx >= offset && mx < offset + labelLen) {
                if (w.minimized) {
                    w.minimized = false;
                    activeWindow = w;
                } else if (activeWindow == w) {
                    w.minimized = true;
                    activeWindow = null;
                } else {
                    activeWindow = w;
                }
                return;
            }
            offset += labelLen + 1;
        }
    }

    private void handleStartMenuClick(int mx, int my) {
        int px = mx * PixelBuffer.CELL_W + PixelBuffer.CELL_W / 2;
        int py = my * PixelBuffer.CELL_H + PixelBuffer.CELL_H / 2;
        handleStartMenuClickPx(px, py);
    }

    private void handleStartMenuClickPx(int px, int py) {
        int smW = 240, smLeftW = 140;
        int itemH = 22, headerH = 24;
        int maxItems = Math.max(START_LEFT.length, START_RIGHT.length);
        int bodyH = maxItems * itemH;
        int footerH = 28;
        int smH = headerH + bodyH + footerH;
        int smX = 0, smY = PixelBuffer.SCREEN_H - PixelBuffer.CELL_H - smH;

        // Outside menu → close
        if (px < smX || px >= smX + smW || py < smY || py >= smY + smH) {
            startMenuOpen = false;
            return;
        }

        int leftY = smY + headerH;
        int footY = smY + smH - footerH;

        // Footer clicks
        if (py >= footY) {
            if (px < smX + smW / 2) {
                launchByTarget("builtin:settings");
            } else {
                os.shutdown();
            }
            startMenuOpen = false;
            return;
        }

        // Left panel (programs)
        if (px >= smX && px < smX + smLeftW && py >= leftY && py < leftY + bodyH) {
            int idx = (py - leftY) / itemH;
            if (idx >= 0 && idx < START_LEFT_TARGETS.length) {
                launchByTarget(START_LEFT_TARGETS[idx]);
                startMenuOpen = false;
            }
            return;
        }

        // Right panel (folders)
        int rightX = smX + smLeftW + 1;
        if (px >= rightX && px < smX + smW && py >= leftY && py < leftY + bodyH) {
            int idx = (py - leftY) / itemH;
            if (idx >= 0 && idx < START_RIGHT_PATHS.length) {
                openWindow("Explorer", new ExplorerProgram(START_RIGHT_PATHS[idx]), 3, 1, 45, 16);
                startMenuOpen = false;
            }
        }
    }

    private void handleDesktopClick(int px, int py) {
        // Cancel rename if clicking elsewhere
        if (renamingIcon != null) {
            finishRename();
        }
        // Dismiss properties overlay
        if (showingProperties) {
            showingProperties = false;
            propertiesTarget = null;
            needsRedraw = true;
            return;
        }
        // Hit-test desktop shortcut icons using pixel coords
        Shortcut hit = hitTestIcon(px, py);
        if (hit != null) {
            long now = System.currentTimeMillis();
            if (lastIconClickTarget == hit && now - lastIconClickTime < 400) {
                // Double-click → launch
                launchShortcut(hit);
                lastIconClickTime = 0;
                lastIconClickTarget = null;
                selectedIcon = null;
                return;
            }
            // Single click → select + prepare drag candidate
            selectedIcon = hit;
            lastIconClickTime = now;
            lastIconClickTarget = hit;
            dragCandidate = hit;
            int ix = ICON_MARGIN_LEFT + hit.posX * ICON_GRID_W;
            int iy = ICON_MARGIN_TOP + hit.posY * ICON_GRID_H;
            iconDragOffsetX = px - ix;
            iconDragOffsetY = py - iy;
        } else {
            // Click on empty desktop → deselect
            selectedIcon = null;
            lastIconClickTime = 0;
            lastIconClickTarget = null;
        }
    }

    // --- Inline Rename ---

    private void startIconRename(Shortcut s) {
        renamingIcon = s;
        selectedIcon = s;
        renameBuffer.setLength(0);
        renameBuffer.append(s.name);
        renameCursor = s.name.length();
        needsRedraw = true;
    }

    private void finishRename() {
        if (renamingIcon == null) return;
        String newName = renameBuffer.toString().trim();
        if (!newName.isEmpty() && !newName.equals(renamingIcon.name)) {
            String oldFile = renamingIcon.lnkFile;
            renamingIcon.name = newName;
            String safeName = newName.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
            renamingIcon.lnkFile = safeName + ".lnk";
            os.getFileSystem().delete(DESKTOP_DIR + "/" + oldFile);
            saveShortcutPosition(renamingIcon);
        }
        renamingIcon = null;
        needsRedraw = true;
    }

    private void cancelRename() {
        renamingIcon = null;
        needsRedraw = true;
    }

    private void handleRenameKey(int keyCode) {
        switch (keyCode) {
            case 257 -> finishRename();  // Enter
            case 256 -> cancelRename();  // Escape
            case 259 -> { // Backspace
                if (renameCursor > 0) {
                    renameBuffer.deleteCharAt(renameCursor - 1);
                    renameCursor--;
                }
            }
            case 261 -> { // Delete
                if (renameCursor < renameBuffer.length()) {
                    renameBuffer.deleteCharAt(renameCursor);
                }
            }
            case 263 -> { if (renameCursor > 0) renameCursor--; } // Left
            case 262 -> { if (renameCursor < renameBuffer.length()) renameCursor++; } // Right
            case 268 -> renameCursor = 0; // Home
            case 269 -> renameCursor = renameBuffer.length(); // End
        }
        needsRedraw = true;
    }

    private void handleRenameChar(char c) {
        if (c >= 32 && renameBuffer.length() < 20) {
            renameBuffer.insert(renameCursor, c);
            renameCursor++;
            needsRedraw = true;
        }
    }

    // --- Grid Helpers ---

    private boolean isGridCellOccupied(int gx, int gy, Shortcut exclude) {
        for (Shortcut s : shortcuts) {
            if (s != exclude && s.posX == gx && s.posY == gy) return true;
        }
        return false;
    }

    private int[] findNearestFreeCell(int gx, int gy, Shortcut exclude) {
        // Spiral search outward from target cell
        for (int r = 1; r < Math.max(ICON_COLS, ICON_ROWS); r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.abs(dx) != r && Math.abs(dy) != r) continue;
                    int cx = gx + dx, cy = gy + dy;
                    if (cx >= 0 && cx < ICON_COLS && cy >= 0 && cy < ICON_ROWS
                            && !isGridCellOccupied(cx, cy, exclude)) {
                        return new int[]{cx, cy};
                    }
                }
            }
        }
        return null;
    }

    private int[] findNextFreeCell() {
        // Find first unoccupied cell, scanning columns top-to-bottom, left-to-right
        for (int col = 0; col < ICON_COLS; col++) {
            for (int row = 0; row < ICON_ROWS; row++) {
                if (!isGridCellOccupied(col, row, null)) {
                    return new int[]{col, row};
                }
            }
        }
        return new int[]{0, 0};
    }

    private Shortcut findShortcutByTarget(String target) {
        for (Shortcut s : shortcuts) {
            if (s.target.equals(target)) return s;
        }
        return null;
    }

    private void autoArrangeIcons(java.util.Comparator<Shortcut> comparator) {
        if (comparator != null) shortcuts.sort(comparator);
        int col = 0, row = 0;
        for (Shortcut s : shortcuts) {
            s.posX = col;
            s.posY = row;
            row++;
            if (row >= ICON_ROWS) {
                row = 0;
                col++;
            }
            saveShortcutPosition(s);
        }
        needsRedraw = true;
    }

    // --- Properties Dialog ---

    private void showProperties(Shortcut s) {
        propertiesTarget = s;
        showingProperties = true;
        needsRedraw = true;
    }

    private void launchByTarget(String target) {
        switch (target) {
            case "builtin:shell" -> openWindow("Shell", new ShellProgram(), 2, 1, 47, 16);
            case "builtin:lua" -> openWindow("Lua", new LuaShellProgram(), 2, 1, 47, 16);
            case "builtin:puzzle" -> openWindow("Puzzle", new PuzzleProgram(), 1, 1, 49, 17);
            case "builtin:edit" -> openWindow("IDE", new TextIDEProgram("/Users/User/Documents/new.txt"), 1, 1, 49, 17);
            case "builtin:notepad" -> openWindow("Notepad", new NotepadProgram(), 4, 2, 42, 15);
            case "builtin:explorer" -> openWindow("Explorer", new ExplorerProgram(), 3, 1, 45, 16);
            case "builtin:paint" -> openWindow("Paint", new PaintProgram(), 1, 1, 49, 17);
            case "builtin:settings" -> openWindow("Settings", new SettingsProgram(), 5, 1, 45, 16);
            case "builtin:calculator" -> openWindow("Calculator", new CalculatorProgram(), 10, 2, 25, 16);
            case "builtin:taskmanager" -> openWindow("Task Manager", new TaskManagerProgram(), 3, 1, 45, 16);
            case "builtin:bluetooth" -> openWindow("Bluetooth", new BluetoothProgram(), 4, 1, 45, 16);
            case "builtin:buttons" -> openWindow("Buttons", new ButtonProgram(), 2, 1, 45, 16);
            case "builtin:monitor" -> openWindow("Monitor", new MonitorProgram(), 3, 1, 45, 16);
            default -> {}
        }
    }

    private void launchStartMenuItem(int index) {
        if (index >= 0 && index < START_LEFT_TARGETS.length) {
            launchByTarget(START_LEFT_TARGETS[index]);
        }
    }

    private void launchShortcut(Shortcut s) {
        switch (s.target) {
            case "builtin:shell", "builtin:edit", "builtin:explorer", "builtin:settings",
                 "builtin:paint", "builtin:lua", "builtin:puzzle", "builtin:ide",
                 "builtin:notepad", "builtin:calculator", "builtin:taskmanager",
                 "builtin:bluetooth" -> launchByTarget(
                     s.target.equals("builtin:ide") ? "builtin:edit" : s.target);
            default -> {
                if (os.getFileSystem().isDirectory(s.target)) {
                    openWindow("Explorer", new ExplorerProgram(s.target), 3, 1, 45, 16);
                } else if (s.target.endsWith(".pxl")) {
                    openWindow("Paint", new PaintProgram(s.target), 1, 1, 49, 17);
                } else if (s.target.endsWith(".pzl")) {
                    openWindow("Puzzle", new PuzzleProgram(s.target), 1, 1, 49, 17);
                } else if (s.target.endsWith(".lua") || s.target.endsWith(".java")) {
                    openWindow(s.name, new TextIDEProgram(s.target), 1, 1, 49, 17);
                } else if (s.target.endsWith(".txt") || s.target.endsWith(".md")
                        || s.target.endsWith(".cfg") || s.target.endsWith(".log")
                        || s.target.endsWith(".ini") || s.target.endsWith(".csv")) {
                    openWindow("Notepad", new NotepadProgram(s.target), 4, 2, 42, 15);
                } else {
                    openWindow(s.name, new NotepadProgram(s.target), 4, 2, 42, 15);
                }
            }
        }
    }

    public void openWindow(String title, OSProgram program, int x, int y, int w, int h) {
        program.setOS(os);
        program.init(os);
        Window win = new Window(title, x, y, w, h, program);
        windows.add(win);
        activeWindow = win;
        needsRedraw = true;
    }

    public List<Window> getWindows() { return windows; }

    public void closeWindow(Window w) {
        w.program.shutdown();
        windows.remove(w);
        if (activeWindow == w) activeWindow = windows.isEmpty() ? null : windows.get(windows.size() - 1);
        needsRedraw = true;
    }

    @Override
    public void render(TerminalBuffer buf) {
        buf.setCursorBlink(false);
        buf.setTextColor(iconTextColor);
        buf.setBackgroundColor(wallpaperColor);
        buf.clear();

        // Desktop shortcuts
        for (Shortcut s : shortcuts) {
            if (s.posY >= TerminalBuffer.HEIGHT - 1) continue;
            char icon = (s.iconIndex >= 0 && s.iconIndex < ICON_CHARS.length) ?
                ICON_CHARS[s.iconIndex] : '\u25A0';
            buf.setTextColor(s.colorIndex);
            buf.setBackgroundColor(wallpaperColor);
            buf.writeAt(s.posX, s.posY, String.valueOf(icon) + " ");
            buf.setTextColor(iconTextColor);
            buf.writeAt(s.posX + 2, s.posY, s.name);
        }

        // Render windows (back to front)
        for (Window w : windows) {
            if (w.minimized) continue;
            renderWindow(buf, w, w == activeWindow);
        }

        // Taskbar (bottom row)
        renderTaskbar(buf);

        // Start menu (if open)
        if (startMenuOpen) {
            renderStartMenu(buf);
        }

        // Shortcut creation wizard (if active)
        if (wizardState != WizardState.NONE) {
            renderWizard(buf);
        }
    }

    // â”€â”€ Pixel-mode rendering (primary path) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Override
    public void renderGraphics(PixelBuffer pb) {
        setCursorInfo(0, 0, false);
        pb.clear(TerminalBuffer.PALETTE[wallpaperColor]);

        // Desktop icons (pixel-art from SystemIcons)
        int CW = PixelBuffer.CELL_W;
        for (Shortcut s : shortcuts) {
            // During drag, skip the dragged icon (rendered separately on top)
            if (s == draggingIcon) continue;

            int ix = ICON_MARGIN_LEFT + s.posX * ICON_GRID_W;
            int iy = ICON_MARGIN_TOP + s.posY * ICON_GRID_H;
            if (iy + ICON_GRID_H > PixelBuffer.SCREEN_H - PixelBuffer.CELL_H) continue;

            renderIcon(pb, s, ix, iy, CW);
        }

        // Render dragged icon on top at pixel position (semi-transparent look via shadow)
        if (draggingIcon != null) {
            renderIcon(pb, draggingIcon, iconDragPxX, iconDragPxY, CW);
            // Draw grid-snap preview (faint outline at snap target)
            int snapGx = Math.round((float)(iconDragPxX - ICON_MARGIN_LEFT) / ICON_GRID_W);
            int snapGy = Math.round((float)(iconDragPxY - ICON_MARGIN_TOP) / ICON_GRID_H);
            snapGx = Math.max(0, Math.min(ICON_COLS - 1, snapGx));
            snapGy = Math.max(0, Math.min(ICON_ROWS - 1, snapGy));
            int snapPx = ICON_MARGIN_LEFT + snapGx * ICON_GRID_W;
            int snapPy = ICON_MARGIN_TOP + snapGy * ICON_GRID_H;
            pb.drawRect(snapPx, snapPy, ICON_GRID_W, ICON_GRID_H, 0x60FFFFFF);
        }

        // Inline rename overlay
        if (renamingIcon != null) {
            renderRenameInput(pb, CW);
        }

        // Properties overlay
        if (showingProperties && propertiesTarget != null) {
            renderPropertiesDialog(pb);
        }

        // Windows (back to front) with drop shadows
        for (Window w : windows) {
            if (w.minimized) continue;
            renderWindowPx(pb, w, w == activeWindow);
        }

        renderTaskbarPx(pb);
        if (startMenuOpen) renderStartMenuPx(pb);
        if (wizardState != WizardState.NONE) renderWizardPx(pb);
        if (iconPickerOpen) renderIconPickerPx(pb);
        if (altTabOpen) renderAltTabPx(pb);
        contextMenu.render(pb);
    }

    private void renderIcon(PixelBuffer pb, Shortcut s, int ix, int iy, int CW) {
        // Selection highlight
        if (s == selectedIcon) {
            pb.fillRect(ix + 1, iy + 1, ICON_GRID_W - 2, ICON_GRID_H - 2, 0x403366CC);
            pb.drawRect(ix, iy, ICON_GRID_W, ICON_GRID_H, 0x803366CC);
        }

        // Draw 16x16 pixel-art icon centered in grid cell
        int iconPx = ix + (ICON_GRID_W - SystemIcons.SIZE) / 2;
        int iconPy = iy + 2;
        SystemIcons.Icon iconType = iconForShortcut(s);
        SystemIcons.draw(pb, iconPx, iconPy, iconType);

        // Draw label text centered below icon (skip if being renamed)
        if (s != renamingIcon) {
            String label = s.name.length() > 8 ? s.name.substring(0, 7) + "." : s.name;
            int labelW = label.length() * CW;
            int labelPx = ix + (ICON_GRID_W - labelW) / 2;
            int labelPy = iconPy + SystemIcons.SIZE + 4;
            if (s == selectedIcon) {
                // Selected label: highlighted background
                pb.fillRect(labelPx - 1, labelPy - 1, labelW + 2, PixelBuffer.CELL_H + 2, 0xC0264F8C);
                pb.drawString(labelPx, labelPy, label, 0xFFFFFFFF);
            } else {
                pb.drawString(labelPx + 1, labelPy + 1, label, 0xFF000000);
                pb.drawString(labelPx, labelPy, label, TerminalBuffer.PALETTE[iconTextColor]);
            }
        }
    }

    private void renderRenameInput(PixelBuffer pb, int CW) {
        int ix = ICON_MARGIN_LEFT + renamingIcon.posX * ICON_GRID_W;
        int iy = ICON_MARGIN_TOP + renamingIcon.posY * ICON_GRID_H;
        int iconPy = iy + 2;
        int labelPy = iconPy + SystemIcons.SIZE + 4;

        String text = renameBuffer.toString();
        int maxW = Math.max(ICON_GRID_W, text.length() * CW + 8);
        int labelPx = ix + (ICON_GRID_W - maxW) / 2;

        // Input background
        pb.fillRect(labelPx, labelPy - 1, maxW, PixelBuffer.CELL_H + 2, 0xFF000040);
        pb.drawRect(labelPx, labelPy - 1, maxW, PixelBuffer.CELL_H + 2, 0xFF6699CC);

        // Text
        int textX = labelPx + 2;
        pb.drawString(textX, labelPy, text, 0xFFFFFFFF);

        // Cursor
        int cursorX = textX + renameCursor * CW;
        pb.fillRect(cursorX, labelPy, 1, PixelBuffer.CELL_H, 0xFFFFFFFF);
    }

    private void renderPropertiesDialog(PixelBuffer pb) {
        int dw = 200, dh = 100;
        int dx = (PixelBuffer.SCREEN_W - dw) / 2;
        int dy = (PixelBuffer.SCREEN_H - dh) / 2;

        // Dialog background
        pb.fillRect(dx, dy, dw, dh, 0xFF2A2A3A);
        pb.drawRect(dx, dy, dw, dh, 0xFF6699CC);

        // Title bar
        pb.fillRect(dx, dy, dw, 18, 0xFF264F8C);
        pb.drawString(dx + 4, dy + 2, propertiesTarget.name + " - Properties", 0xFFFFFFFF);

        // Content
        int cy = dy + 24;
        pb.drawString(dx + 8, cy, "Name: " + propertiesTarget.name, 0xFFCCCCCC);
        cy += 14;
        pb.drawString(dx + 8, cy, "Target: " + propertiesTarget.target, 0xFFCCCCCC);
        cy += 14;
        pb.drawString(dx + 8, cy, "Position: (" + propertiesTarget.posX + ", " + propertiesTarget.posY + ")", 0xFFCCCCCC);
        cy += 14;
        pb.drawString(dx + 8, cy, "File: " + propertiesTarget.lnkFile, 0xFFCCCCCC);

        // OK button
        int btnW = 40, btnH = 16;
        int btnX = dx + (dw - btnW) / 2, btnY = dy + dh - btnH - 6;
        pb.fillRect(btnX, btnY, btnW, btnH, 0xFF3A5A8A);
        pb.drawRect(btnX, btnY, btnW, btnH, 0xFF6699CC);
        pb.drawStringCentered(btnX, btnW, btnY + 2, "OK", 0xFFFFFFFF);
    }

    // --- Icon Picker Overlay ---

    private void handleIconPickerEvent(OSEvent event) {
        needsRedraw = true;
        switch (event.getType()) {
            case KEY -> {
                if (event.getInt(0) == 256) { // Escape
                    iconPickerOpen = false;
                    iconPickerTarget = null;
                }
            }
            case MOUSE_CLICK_PX -> {
                int px = event.getInt(1), py = event.getInt(2);
                handleIconPickerClick(px, py);
            }
            default -> {}
        }
    }

    private void handleIconPickerClick(int px, int py) {
        // Picker layout: 6 columns × ceil(27/6) rows of 24×24 cells
        int cols = 6;
        int cellSize = 24;
        int rows = (PICKER_ICONS.length + cols - 1) / cols;
        int dlgW = cols * cellSize + 16;
        int dlgH = rows * cellSize + 40;
        int dlgX = (PixelBuffer.SCREEN_W - dlgW) / 2;
        int dlgY = (PixelBuffer.SCREEN_H - dlgH) / 2;
        int gridX = dlgX + 8;
        int gridY = dlgY + 22;

        // Check grid click
        if (px >= gridX && py >= gridY && px < gridX + cols * cellSize && py < gridY + rows * cellSize) {
            int col = (px - gridX) / cellSize;
            int row = (py - gridY) / cellSize;
            int idx = row * cols + col;
            if (idx >= 0 && idx < PICKER_ICONS.length && iconPickerTarget != null) {
                iconPickerTarget.sysIcon = PICKER_ICONS[idx].name();
                saveShortcutPosition(iconPickerTarget);
                iconPickerOpen = false;
                iconPickerTarget = null;
            }
            return;
        }

        // Click outside dialog dismisses
        if (px < dlgX || py < dlgY || px >= dlgX + dlgW || py >= dlgY + dlgH) {
            iconPickerOpen = false;
            iconPickerTarget = null;
        }
    }

    private void renderIconPickerPx(PixelBuffer pb) {
        int cols = 6;
        int cellSize = 24;
        int rows = (PICKER_ICONS.length + cols - 1) / cols;
        int dlgW = cols * cellSize + 16;
        int dlgH = rows * cellSize + 40;
        int dlgX = (PixelBuffer.SCREEN_W - dlgW) / 2;
        int dlgY = (PixelBuffer.SCREEN_H - dlgH) / 2;
        int gridX = dlgX + 8;
        int gridY = dlgY + 22;

        // Dialog background + border
        pb.fillRect(dlgX, dlgY, dlgW, dlgH, 0xFF2A2A3A);
        pb.drawRect(dlgX, dlgY, dlgW, dlgH, 0xFF6699CC);

        // Title bar
        pb.fillRect(dlgX, dlgY, dlgW, 18, 0xFF264F8C);
        pb.drawString(dlgX + 4, dlgY + 2, "Change Icon", 0xFFFFFFFF);

        // Grid of icons
        for (int i = 0; i < PICKER_ICONS.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = gridX + col * cellSize;
            int cy = gridY + row * cellSize;

            // Highlight current icon
            if (iconPickerTarget != null && iconPickerTarget.sysIcon != null
                    && iconPickerTarget.sysIcon.equals(PICKER_ICONS[i].name())) {
                pb.fillRect(cx, cy, cellSize, cellSize, 0xFF3A5A8A);
                pb.drawRect(cx, cy, cellSize, cellSize, 0xFF6699CC);
            }

            // Draw icon centered in cell
            int ix = cx + (cellSize - SystemIcons.SIZE) / 2;
            int iy = cy + (cellSize - SystemIcons.SIZE) / 2;
            SystemIcons.draw(pb, ix, iy, PICKER_ICONS[i]);
        }

        // Hint at bottom
        String hint = "Click to select - Esc to cancel";
        int hintX = dlgX + (dlgW - hint.length() * PixelBuffer.CELL_W) / 2;
        pb.drawString(hintX, dlgY + dlgH - 14, hint, 0xFF888888);
    }

    private SystemIcons.Icon iconForShortcut(Shortcut s) {
        // Custom icon override from .lnk sysIcon= field
        if (s.sysIcon != null && !s.sysIcon.isEmpty()) {
            try { return SystemIcons.Icon.valueOf(s.sysIcon); }
            catch (IllegalArgumentException ignored) {}
        }
        return switch (s.target) {
            case "builtin:shell" -> SystemIcons.Icon.TERMINAL;
            case "builtin:edit", "builtin:ide" -> SystemIcons.Icon.FILE_CODE;
            case "builtin:notepad" -> SystemIcons.Icon.NOTEPAD;
            case "builtin:explorer" -> SystemIcons.Icon.EXPLORER;
            case "builtin:settings" -> SystemIcons.Icon.SETTINGS;
            case "builtin:paint" -> SystemIcons.Icon.PAINT;
            case "builtin:lua" -> SystemIcons.Icon.LUA_MOON;
            case "builtin:puzzle" -> SystemIcons.Icon.PUZZLE;
            case "builtin:calculator" -> SystemIcons.Icon.CALCULATOR;
            case "builtin:taskmanager" -> SystemIcons.Icon.TASK_MANAGER;
            default -> {
                if (s.target.endsWith(".lua")) yield SystemIcons.Icon.LUA_MOON;
                if (s.target.endsWith(".pxl")) yield SystemIcons.Icon.PAINT;
                if (s.target.endsWith(".pzl")) yield SystemIcons.Icon.PUZZLE;
                if (os.getFileSystem().isDirectory(s.target)) yield SystemIcons.Icon.FOLDER;
                yield SystemIcons.Icon.FILE;
            }
        };
    }

    private void renderWindowPx(PixelBuffer pb, Window w, boolean active) {
        int CW = PixelBuffer.CELL_W, CH = PixelBuffer.CELL_H;
        int wx = w.x * CW, wy = w.y * CH;
        int ww = w.width * CW, wh = w.height * CH;

        // Drop shadow
        pb.fillRect(wx + 3, wy + 3, ww, wh, 0xFF222222);

        // Window border
        pb.drawRect(wx, wy, ww, wh, active ? 0xFF4466AA : 0xFF666666);

        // Title bar gradient
        int titleH = CH;
        int titleC1 = active ? 0xFF1A3A6E : 0xFF555555;
        int titleC2 = active ? 0xFF3366BB : 0xFF888888;
        for (int row = 0; row < titleH; row++) {
            float t = (float) row / titleH;
            int r = (int)((1 - t) * ((titleC1 >> 16) & 0xFF) + t * ((titleC2 >> 16) & 0xFF));
            int g = (int)((1 - t) * ((titleC1 >> 8) & 0xFF) + t * ((titleC2 >> 8) & 0xFF));
            int b = (int)((1 - t) * (titleC1 & 0xFF) + t * (titleC2 & 0xFF));
            int color = 0xFF000000 | (r << 16) | (g << 8) | b;
            pb.drawHLine(wx, wx + ww - 1, wy + row, color);
        }

        // Title text
        int fgWhite = 0xFFFFFFFF;
        String titleText = " " + w.title;
        if (titleText.length() > w.width - 8) titleText = titleText.substring(0, w.width - 8);
        pb.drawString(wx + 4, wy + 1, titleText, fgWhite);

        // Window control buttons (min, max, close)
        int btnW = 3 * CW;
        int closeX = wx + ww - btnW;
        int maxBtnX = closeX - btnW;
        int minBtnX = maxBtnX - btnW;
        // Close button (red)
        pb.fillRect(closeX, wy, btnW, CH, 0xFFCC3333);
        pb.drawStringCentered(closeX, btnW, wy + 1, "X", fgWhite);
        // Maximize button
        pb.fillRect(maxBtnX, wy, btnW, CH, active ? 0xFF2255AA : 0xFF666666);
        // Draw maximize/restore icon procedurally (Unicode chars outside BitmapFont range)
        int iconX = maxBtnX + btnW / 2 - 4;
        int iconY = wy + CH / 2 - 4;
        if (w.maximized) {
            // Restore icon: two overlapping rectangles
            pb.drawRect(iconX + 2, iconY, 6, 6, fgWhite);
            pb.drawRect(iconX, iconY + 2, 6, 6, fgWhite);
            pb.fillRect(iconX + 1, iconY + 3, 4, 4, active ? 0xFF2255AA : 0xFF666666);
        } else {
            // Maximize icon: single rectangle
            pb.drawRect(iconX, iconY, 8, 8, fgWhite);
            pb.drawHLine(iconX, iconX + 7, iconY + 1, fgWhite);
        }
        // Minimize button
        pb.fillRect(minBtnX, wy, btnW, CH, active ? 0xFF2255AA : 0xFF666666);
        pb.drawStringCentered(minBtnX, btnW, wy + 1, "_", fgWhite);

        // Body
        int bodyX = wx, bodyY = wy + CH;
        int bodyW = ww, bodyH = (w.height - 1) * CH;

        if (w.program != null) {
            if (w.programBuffer == null) {
                w.programBuffer = new PixelBuffer();
            }
            w.programBuffer.clear(0xFF000000);
            w.program.renderGraphics(w.programBuffer);
            pb.blit(w.programBuffer, 0, 0, bodyX, bodyY, bodyW, bodyH);
            if (active && w.program.isLastCursorBlink()) {
                int cx = w.x + w.program.getLastCursorX();
                int cy = w.y + 1 + w.program.getLastCursorY();
                if (cx >= w.x && cx < w.x + w.width && cy > w.y && cy < w.y + w.height) {
                    setCursorInfo(cx, cy, true);
                }
            }
        } else {
            pb.fillRect(bodyX, bodyY, bodyW, bodyH, 0xFF1A1A2E);
        }

        // Resize handle (three diagonal lines)
        int rhx = wx + ww - 8, rhy = wy + wh - 8;
        int rhc = active ? 0xFF6688AA : 0xFF666666;
        pb.drawLine(rhx + 6, rhy + 2, rhx + 2, rhy + 6, rhc);
        pb.drawLine(rhx + 6, rhy + 4, rhx + 4, rhy + 6, rhc);
        pb.drawLine(rhx + 6, rhy + 6, rhx + 6, rhy + 6, rhc);
    }

    private void blitTerminalToRegion(PixelBuffer dest, TerminalBuffer src,
            int dx, int dy, int maxCols, int maxRows) {
        int CW = PixelBuffer.CELL_W, CH = PixelBuffer.CELL_H;
        for (int row = 0; row < Math.min(maxRows, TerminalBuffer.HEIGHT); row++) {
            for (int col = 0; col < Math.min(maxCols, TerminalBuffer.WIDTH); col++) {
                int px = dx + col * CW, py = dy + row * CH;
                dest.fillRect(px, py, CW, CH, TerminalBuffer.PALETTE[src.getBg(col, row)]);
                char c = src.getChar(col, row);
                if (c != ' ') dest.drawChar(px, py, c, TerminalBuffer.PALETTE[src.getFg(col, row)]);
            }
        }
    }

    private void renderTaskbarPx(PixelBuffer pb) {
        int CW = PixelBuffer.CELL_W, CH = PixelBuffer.CELL_H;
        int y = (PixelBuffer.TEXT_ROWS - 1) * CH;
        int tbText = TerminalBuffer.PALETTE[taskbarTextColor];

        // Taskbar gradient background
        int barC1 = 0xFF1C2B3A;
        int barC2 = 0xFF2A3F55;
        for (int row = 0; row < CH; row++) {
            float t = (float) row / CH;
            int r = (int)((1 - t) * ((barC1 >> 16) & 0xFF) + t * ((barC2 >> 16) & 0xFF));
            int g = (int)((1 - t) * ((barC1 >> 8) & 0xFF) + t * ((barC2 >> 8) & 0xFF));
            int b = (int)((1 - t) * (barC1 & 0xFF) + t * (barC2 & 0xFF));
            pb.drawHLine(0, PixelBuffer.SCREEN_W - 1, y + row, 0xFF000000 | (r << 16) | (g << 8) | b);
        }
        // Top highlight line
        pb.drawHLine(0, PixelBuffer.SCREEN_W - 1, y, 0xFF445566);

        // Start button (Windows-style orb)
        int startW = 52;
        int startBg = startMenuOpen ? 0xFF2266CC : 0xFF226633;
        pb.fillRoundRect(2, y + 1, startW, CH - 2, 3, startBg);
        pb.drawRect(2, y + 1, startW, CH - 2, 0xFF88AACC);
        // Start icon (small 4-square Windows logo)
        int sx = 6, sy = y + 4;
        pb.fillRect(sx, sy, 3, 3, 0xFF44AAFF);
        pb.fillRect(sx + 4, sy, 3, 3, 0xFF44CC44);
        pb.fillRect(sx, sy + 4, 3, 3, 0xFFFF8844);
        pb.fillRect(sx + 4, sy + 4, 3, 3, 0xFFFFCC44);
        pb.drawString(sx + 10, y + 1, "Start", 0xFFFFFFFF);

        // Window entries in taskbar
        int offset = startW + 8;
        for (Window w : windows) {
            boolean isActive = (w == activeWindow && !w.minimized);
            int entryW = Math.min(10, w.title.length() + 2) * CW;
            int entryBg = isActive ? 0xFF3355AA : 0xFF2A3F55;
            pb.fillRoundRect(offset, y + 2, entryW, CH - 4, 2, entryBg);
            if (isActive) pb.drawRect(offset, y + 2, entryW, CH - 4, 0xFF6688BB);
            String label = truncate(w.title, 8);
            pb.drawString(offset + 4, y + 1, label, isActive ? 0xFFFFFFFF : 0xFFAABBCC);
            offset += entryW + 3;
        }

        // System tray area (right side)
        int trayX = PixelBuffer.SCREEN_W - 50;
        pb.drawVLine(trayX - 4, y + 3, y + CH - 3, 0xFF445566);
        // Clock
        long ticks = os.getTickCount();
        int seconds = (int)(ticks / 20) % 3600;
        String clock = String.format("%02d:%02d", seconds / 60, seconds % 60);
        pb.drawString(trayX, y + 1, clock, 0xFFCCDDEE);
    }

    private void renderAltTabPx(PixelBuffer pb) {
        if (windows.isEmpty()) return;
        int cellW = 64, cellH = 48, gap = 8, pad = 12;
        int count = windows.size();
        int totalW = count * cellW + (count - 1) * gap + pad * 2;
        int totalH = cellH + pad * 2 + 20; // +20 for title text
        int ox = (PixelBuffer.SCREEN_W - totalW) / 2;
        int oy = (PixelBuffer.SCREEN_H - totalH) / 2;

        // Semi-transparent backdrop
        pb.fillRect(ox - 2, oy - 2, totalW + 4, totalH + 4, 0xCC000000);
        pb.fillRect(ox, oy, totalW, totalH, 0xDD1A1A2E);
        pb.drawRect(ox, oy, totalW, totalH, 0xFF5566AA);

        for (int i = 0; i < count; i++) {
            Window w = windows.get(i);
            int cx = ox + pad + i * (cellW + gap);
            int cy = oy + pad;

            // Selection highlight
            if (i == altTabIndex) {
                pb.drawRect(cx - 2, cy - 2, cellW + 4, cellH + 4, 0xFF5599FF);
                pb.drawRect(cx - 1, cy - 1, cellW + 2, cellH + 2, 0xFF5599FF);
            }

            // Thumbnail placeholder (dark rectangle with icon)
            pb.fillRect(cx, cy, cellW, cellH, 0xFF222244);
            pb.drawRect(cx, cy, cellW, cellH, 0xFF444466);
            // Center an icon in the cell
            SystemIcons.Icon icon = SystemIcons.Icon.FILE;
            if (w.program != null) {
                String pname = w.program.getName().toLowerCase();
                if (pname.contains("shell") || pname.contains("lua")) icon = SystemIcons.Icon.TERMINAL;
                else if (pname.contains("explorer")) icon = SystemIcons.Icon.EXPLORER;
                else if (pname.contains("paint")) icon = SystemIcons.Icon.PAINT;
                else if (pname.contains("calculator")) icon = SystemIcons.Icon.CALCULATOR;
                else if (pname.contains("task")) icon = SystemIcons.Icon.TASK_MANAGER;
                else if (pname.contains("ide") || pname.contains("edit")) icon = SystemIcons.Icon.FILE_CODE;
                else if (pname.contains("notepad")) icon = SystemIcons.Icon.NOTEPAD;
                else if (pname.contains("puzzle")) icon = SystemIcons.Icon.PUZZLE;
                else if (pname.contains("settings")) icon = SystemIcons.Icon.SETTINGS;
            }
            SystemIcons.draw(pb, cx + (cellW - 16) / 2, cy + (cellH - 16) / 2, icon);

            // Title text below
            String title = w.title.length() > 8 ? w.title.substring(0, 8) : w.title;
            pb.drawStringCentered(cx, cellW, cy + cellH + 2, title,
                i == altTabIndex ? 0xFFFFFFFF : 0xFF999999);
        }
    }

    private void renderStartMenuPx(PixelBuffer pb) {
        int smW = 240, smLeftW = 140;
        int itemH = 22;
        int headerH = 24;
        int maxItems = Math.max(START_LEFT.length, START_RIGHT.length);
        int bodyH = maxItems * itemH;
        int footerH = 28;
        int smH = headerH + bodyH + footerH;
        int smX = 0, smY = PixelBuffer.SCREEN_H - PixelBuffer.CELL_H - smH;

        // Shadow
        pb.fillRect(smX + 3, smY + 3, smW, smH, 0xFF222222);

        // Background
        pb.fillRect(smX, smY, smW, smH, 0xFFF0F0F0);
        pb.drawRect(smX, smY, smW, smH, 0xFF666688);

        // Header bar (user name)
        pb.fillRect(smX, smY, smW, headerH, 0xFF2244AA);
        SystemIcons.draw(pb, smX + 4, smY + 4, SystemIcons.Icon.COMPUTER);
        pb.drawString(smX + 24, smY + 5, "User", 0xFFFFFFFF);

        // Left panel (programs)
        int leftY = smY + headerH;
        pb.fillRect(smX, leftY, smLeftW, bodyH, 0xFFFFFFFF);
        for (int i = 0; i < START_LEFT.length; i++) {
            int iy = leftY + i * itemH;
            // Hover detection based on startHoverIndex
            if (i == startHoverIndex) {
                pb.fillRect(smX, iy, smLeftW, itemH, 0xFF3399FF);
                SystemIcons.draw(pb, smX + 4, iy + 3, START_LEFT_ICONS[i]);
                pb.drawString(smX + 24, iy + 4, START_LEFT[i], 0xFFFFFFFF);
            } else {
                SystemIcons.draw(pb, smX + 4, iy + 3, START_LEFT_ICONS[i]);
                pb.drawString(smX + 24, iy + 4, START_LEFT[i], 0xFF222222);
            }
        }

        // Vertical divider
        pb.drawVLine(smX + smLeftW, leftY, leftY + bodyH - 1, 0xFFCCCCCC);

        // Right panel (folders)
        int rightX = smX + smLeftW + 1;
        int rightW = smW - smLeftW - 1;
        pb.fillRect(rightX, leftY, rightW, bodyH, 0xFFE8E8F0);
        for (int i = 0; i < START_RIGHT.length; i++) {
            int iy = leftY + i * itemH;
            SystemIcons.draw(pb, rightX + 4, iy + 3, START_RIGHT_ICONS[i]);
            pb.drawString(rightX + 24, iy + 4, START_RIGHT[i], 0xFF333344);
        }

        // Footer bar (Settings | Shutdown)
        int footY = smY + smH - footerH;
        pb.fillRect(smX, footY, smW, footerH, 0xFFDDDDEE);
        pb.drawHLine(smX, smX + smW - 1, footY, 0xFFBBBBCC);
        // Settings button
        SystemIcons.draw(pb, smX + 8, footY + 6, SystemIcons.Icon.SETTINGS);
        pb.drawString(smX + 28, footY + 7, "Settings", 0xFF444444);
        // Shutdown button
        int shutX = smX + smW / 2;
        SystemIcons.draw(pb, shutX + 8, footY + 6, SystemIcons.Icon.SHUTDOWN);
        pb.drawString(shutX + 28, footY + 7, "Shut Down", 0xFF444444);
    }

    private void renderWizardPx(PixelBuffer pb) {
        int CW = PixelBuffer.CELL_W, CH = PixelBuffer.CELL_H;
        int bx = 10, by = 3, bw = 30, bh = 12;
        int pxX = bx * CW, pxY = by * CH;
        int grayBg = TerminalBuffer.PALETTE[7];
        int blackBg = TerminalBuffer.PALETTE[15];
        int blueBg = TerminalBuffer.PALETTE[11];
        int fgWhite = TerminalBuffer.PALETTE[0];
        int fgGray = TerminalBuffer.PALETTE[8];

        pb.fillRect(pxX, pxY, bw * CW, bh * CH, grayBg);
        pb.fillRect(pxX, pxY, bw * CW, CH, blueBg);

        switch (wizardState) {
            case NAME_INPUT -> {
                pb.drawString(pxX + CW, pxY, "New Shortcut - Name", fgWhite);
                pb.drawString(pxX + 2 * CW, pxY + 2 * CH, "Enter shortcut name:", fgWhite);
                pb.fillRect(pxX + 2 * CW, pxY + 4 * CH, (bw - 4) * CW, CH, blackBg);
                pb.drawString(pxX + 2 * CW, pxY + 4 * CH, wizardName.toString(), fgWhite);
                pb.drawString(pxX + 2 * CW, pxY + 7 * CH, "Enter=Next  Esc=Cancel", fgGray);
            }
            case TARGET_INPUT -> {
                pb.drawString(pxX + CW, pxY, "New Shortcut - Target", fgWhite);
                pb.drawString(pxX + 2 * CW, pxY + 2 * CH, "Enter file path:", fgWhite);
                pb.fillRect(pxX + 2 * CW, pxY + 4 * CH, (bw - 4) * CW, CH, blackBg);
                String t = wizardTarget.length() > bw - 6 ?
                    wizardTarget.substring(wizardTarget.length() - bw + 6) : wizardTarget.toString();
                pb.drawString(pxX + 2 * CW, pxY + 4 * CH, t, fgWhite);
                pb.drawString(pxX + 2 * CW, pxY + 7 * CH, "Enter=Next  Esc=Cancel", fgGray);
            }
            case ICON_PICK -> {
                pb.drawString(pxX + CW, pxY, "New Shortcut - Icon", fgWhite);
                pb.drawString(pxX + 2 * CW, pxY + CH, "Click an icon:", fgWhite);
                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 4; col++) {
                        int idx = row * 4 + col;
                        pb.drawChar(pxX + (2 + col * 4) * CW, pxY + (2 + row) * CH,
                            ICON_CHARS[idx], fgWhite);
                    }
                }
                pb.drawString(pxX + 19 * CW, pxY + 2 * CH, ICON_LABELS[0], fgGray);
                pb.drawString(pxX + 19 * CW, pxY + 3 * CH, ICON_LABELS[4], fgGray);
                pb.drawString(pxX + 19 * CW, pxY + 4 * CH, ICON_LABELS[8], fgGray);
                pb.drawString(pxX + 19 * CW, pxY + 5 * CH, ICON_LABELS[12], fgGray);
                pb.drawString(pxX + 2 * CW, pxY + 8 * CH, "Esc=Cancel", fgGray);
            }
            case COLOR_PICK -> {
                pb.drawString(pxX + CW, pxY, "New Shortcut - Color", fgWhite);
                pb.drawString(pxX + 2 * CW, pxY + CH, "Click a color:", fgWhite);
                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 4; col++) {
                        int idx = row * 4 + col;
                        pb.fillRect(pxX + (2 + col * 4) * CW, pxY + (2 + row) * CH,
                            2 * CW, CH, TerminalBuffer.PALETTE[idx]);
                    }
                }
                pb.drawString(pxX + 2 * CW, pxY + 7 * CH,
                    "Icon: " + ICON_CHARS[wizardIcon] + "  " + wizardName, fgWhite);
                pb.drawString(pxX + 2 * CW, pxY + 8 * CH, "Esc=Cancel", fgGray);
            }
            default -> {}
        }
    }

    // â”€â”€ Legacy text-mode rendering (kept for backward compat) â”€â”€â”€â”€â”€â”€â”€

    private void renderWindow(TerminalBuffer buf, Window w, boolean active) {
        int titleBarColor = active ? 11 : 7; // blue or gray

        // Title bar
        buf.setTextColor(0); // white
        buf.setBackgroundColor(titleBarColor);
        buf.fillRect(w.x, w.y, w.x + w.width - 1, w.y, ' ');
        // Title text
        String titleText = " " + w.title;
        if (titleText.length() > w.width - 8) {
            titleText = titleText.substring(0, w.width - 8);
        }
        buf.writeAt(w.x, w.y, titleText);
        // Window controls: _ [] X
        buf.setTextColor(0);
        buf.writeAt(w.x + w.width - 5, w.y, "_");
        buf.writeAt(w.x + w.width - 3, w.y, w.maximized ? "\u25A1" : "\u25A0");
        buf.setTextColor(14); // red X
        buf.writeAt(w.x + w.width - 1, w.y, "X");

        // Window body background
        buf.setTextColor(0);
        buf.setBackgroundColor(15); // black body
        buf.fillRect(w.x, w.y + 1, w.x + w.width - 1, w.y + w.height - 1, ' ');

        // Render program content into a sub-buffer, then blit
        if (w.program != null) {
            TerminalBuffer sub = new TerminalBuffer();
            w.program.render(sub);
            blitSubBuffer(buf, sub, w.x, w.y + 1, w.width, w.height - 1);
            // Propagate cursor from active window's sub-buffer
            if (w == activeWindow && sub.isCursorBlink()) {
                int curX = w.x + sub.getCursorX();
                int curY = w.y + 1 + sub.getCursorY();
                if (curX >= w.x && curX < w.x + w.width
                        && curY > w.y && curY < w.y + w.height) {
                    buf.setCursorPos(curX, curY);
                    buf.setCursorBlink(true);
                }
            }
        }
        // Resize handle (bottom-right corner)
        buf.setTextColor(active ? 8 : 7);
        buf.setBackgroundColor(15);
        buf.writeAt(w.x + w.width - 1, w.y + w.height - 1, "/");
    }

    private void blitSubBuffer(TerminalBuffer dest, TerminalBuffer src, int dx, int dy, int w, int h) {
        for (int y = 0; y < Math.min(h, src.getHeight()); y++) {
            for (int x = 0; x < Math.min(w, src.getWidth()); x++) {
                int dstX = dx + x;
                int dstY = dy + y;
                if (dstX < 0 || dstX >= TerminalBuffer.WIDTH || dstY < 0 || dstY >= TerminalBuffer.HEIGHT) continue;
                dest.setTextColor(src.getFg(x, y));
                dest.setBackgroundColor(src.getBg(x, y));
                dest.writeAt(dstX, dstY, String.valueOf(src.getChar(x, y)));
            }
        }
    }

    private void renderTaskbar(TerminalBuffer buf) {
        buf.setTextColor(taskbarTextColor);
        buf.setBackgroundColor(taskbarColor);
        int y = TerminalBuffer.HEIGHT - 1;
        buf.hLine(0, TerminalBuffer.WIDTH - 1, y, ' ');

        // Start button
        buf.setTextColor(taskbarTextColor);
        buf.setBackgroundColor(startMenuOpen ? 5 : 13); // lime when open, green normally
        buf.writeAt(0, y, "[Start]");

        // Window entries
        buf.setBackgroundColor(taskbarColor);
        int offset = 8;
        for (Window w : windows) {
            boolean isActive = (w == activeWindow && !w.minimized);
            buf.setBackgroundColor(isActive ? 8 : taskbarColor);
            buf.setTextColor(isActive ? 0 : 8);
            String label = " " + truncate(w.title, 8) + " ";
            buf.writeAt(offset, y, label);
            offset += label.length() + 1;
        }

        // Clock (right side)
        buf.setBackgroundColor(taskbarColor);
        buf.setTextColor(taskbarTextColor);
        long ticks = os.getTickCount();
        int seconds = (int)(ticks / 20) % 3600;
        String clock = String.format("%02d:%02d", seconds / 60, seconds % 60);
        buf.writeAt(TerminalBuffer.WIDTH - clock.length() - 1, y, clock);
    }

    private void renderStartMenu(TerminalBuffer buf) {
        int menuW = 16;
        int menuH = START_ITEMS.length;
        int menuTop = TerminalBuffer.HEIGHT - 1 - menuH;

        for (int i = 0; i < menuH; i++) {
            buf.setTextColor(0);
            buf.setBackgroundColor(i == startHoverIndex ? 11 : 7);
            buf.fillRect(0, menuTop + i, menuW - 1, menuTop + i, ' ');
            buf.writeAt(1, menuTop + i, START_ITEMS[i]);
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    // --- Shortcut Management ---

    private static final java.util.Set<String> VALID_TARGETS = java.util.Set.of(
        "builtin:shell", "builtin:edit", "builtin:explorer", "builtin:settings",
        "builtin:paint", "builtin:lua", "builtin:puzzle", "builtin:ide",
        "builtin:notepad", "builtin:calculator", "builtin:taskmanager", "builtin:bluetooth"
    );

    private void loadShortcuts() {
        shortcuts.clear();
        List<String> files = os.getFileSystem().list(DESKTOP_DIR);
        if (files == null || files.isEmpty()) {
            createDefaultShortcuts();
            files = os.getFileSystem().list(DESKTOP_DIR);
            if (files == null) return;
        }
        // Clean up stale/invalid shortcut files (e.g. leftover "ide.lnk")
        cleanupStaleShortcuts(files);
        files = os.getFileSystem().list(DESKTOP_DIR);
        if (files == null) return;
        int idx = 0;
        for (String entry : files) {
            if (!entry.endsWith(".lnk")) continue;
            String content = os.getFileSystem().readFile(DESKTOP_DIR + "/" + entry);
            if (content == null) continue;
            Shortcut s = parseShortcut(content, entry, 1 + idx * 2);
            if (s != null) { shortcuts.add(s); idx++; }
        }
        fixOverlappingPositions();
    }

    private void fixOverlappingPositions() {
        if (shortcuts.size() < 3) return;
        int maxOverlap = 0;
        for (int i = 0; i < shortcuts.size(); i++) {
            int count = 0;
            for (Shortcut s : shortcuts) {
                if (shortcuts.get(i).posX == s.posX && shortcuts.get(i).posY == s.posY) count++;
            }
            maxOverlap = Math.max(maxOverlap, count);
        }
        if (maxOverlap >= 3) {
            for (int i = 0; i < shortcuts.size(); i++) {
                shortcuts.get(i).posX = 1;
                shortcuts.get(i).posY = 1 + i * 2;
                saveShortcutPosition(shortcuts.get(i));
            }
        }
    }

    private void cleanupStaleShortcuts(List<String> files) {
        for (String entry : files) {
            if (!entry.endsWith(".lnk")) continue;
            String content = os.getFileSystem().readFile(DESKTOP_DIR + "/" + entry);
            if (content == null) { os.getFileSystem().delete(DESKTOP_DIR + "/" + entry); continue; }
            String target = "";
            for (String line : content.split("\n")) {
                if (line.trim().startsWith("target=")) target = line.trim().substring(7);
            }
            // Remove shortcuts with missing/invalid builtin targets or no target
            if (target.isEmpty() || (target.startsWith("builtin:") && !VALID_TARGETS.contains(target))) {
                os.getFileSystem().delete(DESKTOP_DIR + "/" + entry);
            }
        }
    }

    private Shortcut parseShortcut(String content, String fileName, int defaultY) {
        String target = "", name = fileName.replace(".lnk", "");
        String sysIconStr = "";
        int icon = 0, color = 0, posX = 1, posY = defaultY;
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith("target=")) target = line.substring(7);
            else if (line.startsWith("icon=")) {
                try { icon = Integer.parseInt(line.substring(5).trim()); }
                catch (NumberFormatException ignored) {}
            } else if (line.startsWith("color=")) {
                try { color = Integer.parseInt(line.substring(6).trim()); }
                catch (NumberFormatException ignored) {}
            } else if (line.startsWith("name=")) name = line.substring(5);
            else if (line.startsWith("posX=")) {
                try { posX = Integer.parseInt(line.substring(5).trim()); }
                catch (NumberFormatException ignored) {}
            } else if (line.startsWith("posY=")) {
                try { posY = Integer.parseInt(line.substring(5).trim()); }
                catch (NumberFormatException ignored) {}
            } else if (line.startsWith("sysIcon=")) {
                sysIconStr = line.substring(8).trim();
            }
        }
        if (target.isEmpty()) return null;
        Shortcut s = new Shortcut(name, target,
            Math.max(0, Math.min(15, icon)), Math.max(0, Math.min(15, color)),
            posX, posY, fileName);
        if (!sysIconStr.isEmpty()) s.sysIcon = sysIconStr;
        return s;
    }

    private void createDefaultShortcuts() {
        createShortcutFile("Shell", "builtin:shell", 9, 5, 0, 0);
        createShortcutFile("Files", "builtin:explorer", 6, 1, 0, 1);
        createShortcutFile("IDE", "builtin:edit", 2, 9, 0, 2);
        createShortcutFile("Notepad", "builtin:notepad", 16, 0, 0, 3);
        createShortcutFile("Paint", "builtin:paint", 0, 2, 0, 4);
        createShortcutFile("Settings", "builtin:settings", 10, 8, 0, 5);
        createShortcutFile("Lua", "builtin:lua", 9, 4, 0, 6);
        createShortcutFile("Puzzle", "builtin:puzzle", 0, 5, 0, 7);
    }

    public void createShortcutFile(String name, String target, int icon, int color) {
        List<String> existing = os.getFileSystem().list(DESKTOP_DIR);
        int count = 0;
        if (existing != null) {
            for (String f : existing) if (f.endsWith(".lnk")) count++;
        }
        createShortcutFile(name, target, icon, color, 1, 1 + count * 2);
    }

    public void createShortcutFile(String name, String target, int icon, int color, int posX, int posY) {
        String safeName = name.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
        String content = "name=" + name + "\ntarget=" + target + "\nicon=" + icon
                + "\ncolor=" + color + "\nposX=" + posX + "\nposY=" + posY + "\n";
        os.getFileSystem().writeFile(DESKTOP_DIR + "/" + safeName + ".lnk", content);
    }

    public void createShortcutFile(String name, String target, int icon, int color, int posX, int posY, String sysIcon) {
        String safeName = name.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
        String content = "name=" + name + "\ntarget=" + target + "\nicon=" + icon
                + "\ncolor=" + color + "\nposX=" + posX + "\nposY=" + posY + "\n";
        if (sysIcon != null && !sysIcon.isEmpty()) content += "sysIcon=" + sysIcon + "\n";
        os.getFileSystem().writeFile(DESKTOP_DIR + "/" + safeName + ".lnk", content);
    }

    private void saveShortcutPosition(Shortcut s) {
        String content = "name=" + s.name + "\ntarget=" + s.target + "\nicon=" + s.iconIndex
                + "\ncolor=" + s.colorIndex + "\nposX=" + s.posX + "\nposY=" + s.posY + "\n";
        if (s.sysIcon != null && !s.sysIcon.isEmpty()) content += "sysIcon=" + s.sysIcon + "\n";
        os.getFileSystem().writeFile(DESKTOP_DIR + "/" + s.lnkFile, content);
    }

    // --- Shortcut Creation Wizard ---

    private void startWizard() {
        wizardState = WizardState.NAME_INPUT;
        wizardName.setLength(0);
        wizardTarget.setLength(0);
        wizardIcon = 0;
        wizardColor = 0;
        needsRedraw = true;
    }

    private void handleWizardEvent(OSEvent event) {
        needsRedraw = true;
        switch (event.getType()) {
            case KEY -> handleWizardKey(event.getInt(0));
            case CHAR -> handleWizardChar(event.getString(0).charAt(0));
            case MOUSE_CLICK -> handleWizardClick(event.getInt(1), event.getInt(2));
            default -> {}
        }
    }

    private void handleWizardKey(int keyCode) {
        if (keyCode == 256) { // Escape
            wizardState = WizardState.NONE;
            return;
        }
        switch (wizardState) {
            case NAME_INPUT -> {
                if (keyCode == 257 || keyCode == 335) {
                    if (!wizardName.isEmpty()) wizardState = WizardState.TARGET_INPUT;
                } else if (keyCode == 259 && !wizardName.isEmpty()) {
                    wizardName.deleteCharAt(wizardName.length() - 1);
                }
            }
            case TARGET_INPUT -> {
                if (keyCode == 257 || keyCode == 335) {
                    if (!wizardTarget.isEmpty()) wizardState = WizardState.ICON_PICK;
                } else if (keyCode == 259 && !wizardTarget.isEmpty()) {
                    wizardTarget.deleteCharAt(wizardTarget.length() - 1);
                }
            }
            default -> {}
        }
    }

    private void handleWizardChar(char c) {
        if (c < 32) return;
        switch (wizardState) {
            case NAME_INPUT -> { if (wizardName.length() < 20) wizardName.append(c); }
            case TARGET_INPUT -> { if (wizardTarget.length() < 40) wizardTarget.append(c); }
            default -> {}
        }
    }

    private void handleWizardClick(int mx, int my) {
        int bx = 10, by = 3;
        switch (wizardState) {
            case ICON_PICK -> {
                if (mx < bx + 2 || my < by + 2) return;
                int gridX = (mx - bx - 2) / 4;
                int gridY = my - by - 2;
                if (gridX >= 0 && gridX < 4 && gridY >= 0 && gridY < 4) {
                    int idx = gridY * 4 + gridX;
                    if (idx >= 0 && idx < 16) {
                        wizardIcon = idx;
                        wizardState = WizardState.COLOR_PICK;
                    }
                }
            }
            case COLOR_PICK -> {
                if (mx < bx + 2 || my < by + 2) return;
                int gridX = (mx - bx - 2) / 4;
                int gridY = my - by - 2;
                if (gridX >= 0 && gridX < 4 && gridY >= 0 && gridY < 4) {
                    int idx = gridY * 4 + gridX;
                    if (idx >= 0 && idx < 16) {
                        wizardColor = idx;
                        createShortcutFile(wizardName.toString(), wizardTarget.toString(),
                            wizardIcon, wizardColor);
                        loadShortcuts();
                        wizardState = WizardState.NONE;
                    }
                }
            }
            default -> {}
        }
    }

    private void renderWizard(TerminalBuffer buf) {
        int bx = 10, by = 3, bw = 30, bh = 12;
        // Box background
        buf.setTextColor(0);
        buf.setBackgroundColor(7);
        buf.fillRect(bx, by, bx + bw - 1, by + bh - 1, ' ');
        // Title bar
        buf.setBackgroundColor(11);
        buf.setTextColor(0);
        buf.hLine(bx, bx + bw - 1, by, ' ');

        switch (wizardState) {
            case NAME_INPUT -> {
                buf.writeAt(bx + 1, by, "New Shortcut - Name");
                buf.setBackgroundColor(7);
                buf.setTextColor(0);
                buf.writeAt(bx + 2, by + 2, "Enter shortcut name:");
                buf.setBackgroundColor(15);
                buf.setTextColor(0);
                buf.fillRect(bx + 2, by + 4, bx + bw - 3, by + 4, ' ');
                buf.writeAt(bx + 2, by + 4, wizardName.toString());
                buf.setBackgroundColor(7);
                buf.setTextColor(8);
                buf.writeAt(bx + 2, by + 7, "Enter=Next  Esc=Cancel");
            }
            case TARGET_INPUT -> {
                buf.writeAt(bx + 1, by, "New Shortcut - Target");
                buf.setBackgroundColor(7);
                buf.setTextColor(0);
                buf.writeAt(bx + 2, by + 2, "Enter file path:");
                buf.setBackgroundColor(15);
                buf.setTextColor(0);
                buf.fillRect(bx + 2, by + 4, bx + bw - 3, by + 4, ' ');
                String t = wizardTarget.length() > bw - 6 ?
                    wizardTarget.substring(wizardTarget.length() - bw + 6) :
                    wizardTarget.toString();
                buf.writeAt(bx + 2, by + 4, t);
                buf.setBackgroundColor(7);
                buf.setTextColor(8);
                buf.writeAt(bx + 2, by + 7, "Enter=Next  Esc=Cancel");
            }
            case ICON_PICK -> {
                buf.writeAt(bx + 1, by, "New Shortcut - Icon");
                buf.setBackgroundColor(7);
                buf.setTextColor(0);
                buf.writeAt(bx + 2, by + 1, "Click an icon:");
                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 4; col++) {
                        int idx = row * 4 + col;
                        buf.setTextColor(0);
                        buf.setBackgroundColor(7);
                        buf.writeAt(bx + 2 + col * 4, by + 2 + row,
                            String.valueOf(ICON_CHARS[idx]));
                    }
                }
                // Labels on the right
                buf.setTextColor(8);
                buf.setBackgroundColor(7);
                buf.writeAt(bx + 19, by + 2, ICON_LABELS[0]);
                buf.writeAt(bx + 19, by + 3, ICON_LABELS[4]);
                buf.writeAt(bx + 19, by + 4, ICON_LABELS[8]);
                buf.writeAt(bx + 19, by + 5, ICON_LABELS[12]);
                buf.writeAt(bx + 2, by + 8, "Esc=Cancel");
            }
            case COLOR_PICK -> {
                buf.writeAt(bx + 1, by, "New Shortcut - Color");
                buf.setBackgroundColor(7);
                buf.setTextColor(0);
                buf.writeAt(bx + 2, by + 1, "Click a color:");
                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 4; col++) {
                        int idx = row * 4 + col;
                        buf.setBackgroundColor(idx);
                        buf.writeAt(bx + 2 + col * 4, by + 2 + row, "  ");
                        buf.setBackgroundColor(7);
                    }
                }
                buf.setTextColor(0);
                buf.setBackgroundColor(7);
                buf.writeAt(bx + 2, by + 7, "Icon: " +
                    String.valueOf(ICON_CHARS[wizardIcon]) + "  " + wizardName);
                buf.setTextColor(8);
                buf.writeAt(bx + 2, by + 8, "Esc=Cancel");
            }
            default -> {}
        }
    }
}

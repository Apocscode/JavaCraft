package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;

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

    // Start menu items
    private static final String[] START_ITEMS = {
        "Shell", "Lua", "Puzzle", "Edit", "IDE", "Explorer", "Paint", "Settings", "New Shortcut", "Shutdown", "Reboot"
    };
    private int startHoverIndex = -1;

    // Icon system — 16 preset icon characters
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
        Shortcut(String name, String target, int icon, int color, String lnkFile) {
            this.name = name; this.target = target;
            this.iconIndex = icon; this.colorIndex = color; this.lnkFile = lnkFile;
        }
    }
    private final List<Shortcut> shortcuts = new ArrayList<>();

    // Shortcut creation wizard overlay
    private enum WizardState { NONE, NAME_INPUT, TARGET_INPUT, ICON_PICK, COLOR_PICK }
    private WizardState wizardState = WizardState.NONE;
    private final StringBuilder wizardName = new StringBuilder();
    private final StringBuilder wizardTarget = new StringBuilder();
    private int wizardIcon = 0;
    private int wizardColor = 0;

    public DesktopProgram() {
        super("Desktop");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        loadShortcuts();
        needsRedraw = true;
    }

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
        switch (event.getType()) {
            case MOUSE_CLICK -> handleMouseClick(event.getInt(0), event.getInt(1), event.getInt(2));
            case MOUSE_DRAG -> handleMouseDrag(event.getInt(0), event.getInt(1), event.getInt(2));
            case MOUSE_UP -> { dragging = false; needsRedraw = true; }
            case KEY -> handleKey(event.getInt(0));
            case CHAR -> {
                // Forward to active window's program
                if (activeWindow != null && activeWindow.program != null) {
                    activeWindow.program.handleEvent(event);
                    needsRedraw = true;
                }
            }
            default -> {}
        }
    }

    private void handleMouseClick(int button, int mx, int my) {
        needsRedraw = true;

        // Taskbar click (bottom row)
        if (my == TerminalBuffer.HEIGHT - 1) {
            handleTaskbarClick(mx);
            return;
        }

        // Start menu click
        if (startMenuOpen) {
            handleStartMenuClick(mx, my);
            return;
        }

        // Desktop icon click (top area, when no window covers it)
        if (windows.isEmpty() || activeWindow == null) {
            handleDesktopClick(mx, my);
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
                } else if (w.program != null) {
                    // Pass click to program (translated to window-local coords)
                    int localX = mx - w.x - 1;
                    int localY = my - w.y - 1;
                    w.program.handleEvent(new OSEvent(OSEvent.Type.MOUSE_CLICK, button, localX, localY));
                }
                return;
            }
        }

        // Click on desktop background
        startMenuOpen = false;
        handleDesktopClick(mx, my);
    }

    private void handleTitleBarClick(Window w, int mx, int my) {
        int closeX = w.x + w.width - 1;
        int maxX = w.x + w.width - 3;
        int minX = w.x + w.width - 5;

        if (mx == closeX) {
            // Close button [X]
            if (w.program != null) w.program.shutdown();
            windows.remove(w);
            if (activeWindow == w) activeWindow = windows.isEmpty() ? null : windows.getLast();
        } else if (mx == maxX) {
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
        } else if (mx == minX) {
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
            // Start drag
            dragging = true;
            dragOffsetX = mx - w.x;
            dragOffsetY = my - w.y;
        }
    }

    private void handleMouseDrag(int button, int mx, int my) {
        if (dragging && activeWindow != null && !activeWindow.maximized) {
            activeWindow.x = Math.max(0, Math.min(TerminalBuffer.WIDTH - activeWindow.width, mx - dragOffsetX));
            activeWindow.y = Math.max(0, Math.min(TerminalBuffer.HEIGHT - 2 - activeWindow.height, my - dragOffsetY));
            needsRedraw = true;
        }
    }

    private void handleKey(int keyCode) {
        needsRedraw = true;
        if (startMenuOpen) {
            if (keyCode == 256) { // Escape
                startMenuOpen = false;
            }
            return;
        }
        // Forward to active window
        if (activeWindow != null && activeWindow.program != null) {
            activeWindow.program.handleEvent(new OSEvent(OSEvent.Type.KEY, keyCode, 0));
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
        startMenuOpen = false;
        // Start menu is at bottom-left, items going up
        int menuH = START_ITEMS.length;
        int menuTop = TerminalBuffer.HEIGHT - 1 - menuH;
        if (mx < 16 && my >= menuTop && my < menuTop + menuH) {
            int index = my - menuTop;
            launchStartMenuItem(index);
        }
    }

    private void handleDesktopClick(int mx, int my) {
        for (int i = 0; i < shortcuts.size(); i++) {
            int iconY = 1 + i * 2;
            if (iconY >= TerminalBuffer.HEIGHT - 1) break;
            if (my == iconY && mx >= 1 && mx < 1 + shortcuts.get(i).name.length() + 3) {
                launchShortcut(shortcuts.get(i));
                return;
            }
        }
    }

    private void launchStartMenuItem(int index) {
        switch (index) {
            case 0 -> openWindow("Shell", new ShellProgram(), 2, 1, 47, 16);
            case 1 -> openWindow("Lua", new LuaShellProgram(), 2, 1, 47, 16);
            case 2 -> openWindow("Puzzle", new PuzzleProgram(), 1, 1, 49, 17);
            case 3 -> openWindow("Edit", new EditProgram("/home/new.txt"), 5, 2, 42, 15);
            case 4 -> openWindow("IDE", new TextIDEProgram(), 1, 1, 49, 17);
            case 5 -> openWindow("Explorer", new ExplorerProgram(), 3, 1, 45, 16);
            case 6 -> openWindow("Paint", new PaintProgram(), 1, 1, 49, 17);
            case 7 -> openWindow("Settings", new SettingsProgram(), 10, 3, 30, 12);
            case 8 -> startWizard();
            case 9 -> os.shutdown();
            case 10 -> os.reboot();
        }
    }

    private void launchShortcut(Shortcut s) {
        switch (s.target) {
            case "builtin:shell" -> openWindow("Shell", new ShellProgram(), 2, 1, 47, 16);
            case "builtin:edit" -> openWindow("Edit", new EditProgram("/home/new.txt"), 5, 2, 42, 15);
            case "builtin:explorer" -> openWindow("Explorer", new ExplorerProgram(), 3, 1, 45, 16);
            case "builtin:settings" -> openWindow("Settings", new SettingsProgram(), 10, 3, 30, 12);
            case "builtin:paint" -> openWindow("Paint", new PaintProgram(), 1, 1, 49, 17);
            case "builtin:lua" -> openWindow("Lua", new LuaShellProgram(), 2, 1, 47, 16);
            case "builtin:puzzle" -> openWindow("Puzzle", new PuzzleProgram(), 1, 1, 49, 17);
            case "builtin:ide" -> openWindow("IDE", new TextIDEProgram(), 1, 1, 49, 17);
            default -> {
                if (s.target.endsWith(".pxl")) {
                    openWindow("Paint", new PaintProgram(s.target), 1, 1, 49, 17);
                } else if (s.target.endsWith(".pzl")) {
                    openWindow("Puzzle", new PuzzleProgram(s.target), 1, 1, 49, 17);
                } else if (s.target.endsWith(".lua")) {
                    openWindow(s.name, new LuaShellProgram(s.target), 2, 1, 47, 16);
                } else {
                    openWindow(s.name, new EditProgram(s.target), 5, 2, 42, 15);
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

    @Override
    public void render(TerminalBuffer buf) {
        buf.setTextColor(0);
        buf.setBackgroundColor(11); // blue desktop background
        buf.clear();

        // Desktop shortcuts
        for (int i = 0; i < shortcuts.size(); i++) {
            Shortcut s = shortcuts.get(i);
            int y = 1 + i * 2;
            if (y >= TerminalBuffer.HEIGHT - 1) break;
            char icon = (s.iconIndex >= 0 && s.iconIndex < ICON_CHARS.length) ?
                ICON_CHARS[s.iconIndex] : '\u25A0';
            buf.setTextColor(s.colorIndex);
            buf.setBackgroundColor(11);
            buf.writeAt(1, y, String.valueOf(icon) + " ");
            buf.setTextColor(0);
            buf.writeAt(3, y, s.name);
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
        }
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
        buf.setTextColor(0); // white
        buf.setBackgroundColor(7); // gray taskbar
        int y = TerminalBuffer.HEIGHT - 1;
        buf.hLine(0, TerminalBuffer.WIDTH - 1, y, ' ');

        // Start button
        buf.setTextColor(0);
        buf.setBackgroundColor(startMenuOpen ? 5 : 13); // lime when open, green normally
        buf.writeAt(0, y, "[Start]");

        // Window entries
        buf.setBackgroundColor(7);
        int offset = 8;
        for (Window w : windows) {
            boolean isActive = (w == activeWindow && !w.minimized);
            buf.setBackgroundColor(isActive ? 8 : 7);
            buf.setTextColor(isActive ? 0 : 8);
            String label = " " + truncate(w.title, 8) + " ";
            buf.writeAt(offset, y, label);
            offset += label.length() + 1;
        }

        // Clock (right side)
        buf.setBackgroundColor(7);
        buf.setTextColor(0);
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

    private void loadShortcuts() {
        shortcuts.clear();
        List<String> files = os.getFileSystem().list("/desktop");
        if (files == null || files.isEmpty()) {
            createDefaultShortcuts();
            files = os.getFileSystem().list("/desktop");
            if (files == null) return;
        }
        for (String entry : files) {
            if (!entry.endsWith(".lnk")) continue;
            String content = os.getFileSystem().readFile("/desktop/" + entry);
            if (content == null) continue;
            Shortcut s = parseShortcut(content, entry);
            if (s != null) shortcuts.add(s);
        }
    }

    private Shortcut parseShortcut(String content, String fileName) {
        String target = "", name = fileName.replace(".lnk", "");
        int icon = 0, color = 0;
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
        }
        if (target.isEmpty()) return null;
        return new Shortcut(name, target,
            Math.max(0, Math.min(15, icon)), Math.max(0, Math.min(15, color)), fileName);
    }

    private void createDefaultShortcuts() {
        createShortcutFile("Shell", "builtin:shell", 9, 5);
        createShortcutFile("Files", "builtin:explorer", 6, 1);
        createShortcutFile("Edit", "builtin:edit", 2, 9);
        createShortcutFile("Paint", "builtin:paint", 0, 2);
        createShortcutFile("Settings", "builtin:settings", 10, 8);
        createShortcutFile("Lua", "builtin:lua", 9, 4);
        createShortcutFile("Puzzle", "builtin:puzzle", 0, 5);
        createShortcutFile("IDE", "builtin:ide", 2, 3);
    }

    public void createShortcutFile(String name, String target, int icon, int color) {
        String safeName = name.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
        String content = "name=" + name + "\ntarget=" + target + "\nicon=" + icon + "\ncolor=" + color + "\n";
        os.getFileSystem().writeFile("/desktop/" + safeName + ".lnk", content);
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

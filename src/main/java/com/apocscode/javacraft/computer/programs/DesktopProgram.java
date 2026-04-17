package com.apocscode.javacraft.computer.programs;

import com.apocscode.javacraft.computer.JavaOS;
import com.apocscode.javacraft.computer.OSEvent;
import com.apocscode.javacraft.computer.OSProgram;
import com.apocscode.javacraft.computer.TerminalBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows-style desktop environment for JavaCraft OS.
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
        "Shell", "Edit", "Explorer", "Settings", "Shutdown", "Reboot"
    };
    private int startHoverIndex = -1;

    // Desktop icon positions
    private static final String[] DESKTOP_ICONS = {
        "Shell", "Files", "Edit", "Settings"
    };

    public DesktopProgram() {
        super("Desktop");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
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
        // Desktop icons arranged vertically on left side, starting at row 1
        for (int i = 0; i < DESKTOP_ICONS.length; i++) {
            int iconY = 1 + i * 2;
            if (my == iconY && mx >= 1 && mx < 1 + DESKTOP_ICONS[i].length() + 3) {
                launchDesktopIcon(i);
                return;
            }
        }
    }

    private void launchStartMenuItem(int index) {
        switch (index) {
            case 0 -> openWindow("Shell", new ShellProgram(), 2, 1, 47, 16);
            case 1 -> openWindow("Edit", new EditProgram("/home/new.txt"), 5, 2, 42, 15);
            case 2 -> openWindow("Explorer", new ExplorerProgram(), 3, 1, 45, 16);
            case 3 -> openWindow("Settings", new SettingsProgram(), 10, 3, 30, 12);
            case 4 -> os.shutdown();
            case 5 -> os.reboot();
        }
    }

    private void launchDesktopIcon(int index) {
        switch (index) {
            case 0 -> openWindow("Shell", new ShellProgram(), 2, 1, 47, 16);
            case 1 -> openWindow("Explorer", new ExplorerProgram(), 3, 1, 45, 16);
            case 2 -> openWindow("Edit", new EditProgram("/home/new.txt"), 5, 2, 42, 15);
            case 3 -> openWindow("Settings", new SettingsProgram(), 10, 3, 30, 12);
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

        // Desktop icons
        buf.setTextColor(0); // white
        buf.setBackgroundColor(11);
        for (int i = 0; i < DESKTOP_ICONS.length; i++) {
            int y = 1 + i * 2;
            buf.writeAt(1, y, "\u25A0 " + DESKTOP_ICONS[i]);
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
}

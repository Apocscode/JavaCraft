package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.SystemIcons;
import com.apocscode.byteblock.computer.BitmapFont;
import com.apocscode.byteblock.computer.TerminalBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Task Manager for ByteOS. Lists running windows/processes with end-task functionality.
 */
public class TaskManagerProgram extends OSProgram {

    private int selectedIndex = 0;
    private int scrollOffset;
    private long tickCount;
    // Simulated CPU bars (visual effect)
    private final int[] cpuBars = new int[4];

    // Tab constants
    private static final int TAB_PROCESSES = 0;
    private static final int TAB_PERFORMANCE = 1;
    private int activeTab = TAB_PROCESSES;

    private static final int HEADER_H = 40;  // top area with tabs
    private static final int ROW_H = 20;
    private static final int FOOTER_H = 30;
    private static final int PAD = 4;

    public TaskManagerProgram() {
        super("Task Manager");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
    }

    @Override
    public boolean tick() {
        tickCount++;
        // Update CPU bars every 10 ticks for animation
        if (tickCount % 10 == 0) {
            for (int i = 0; i < cpuBars.length; i++) {
                cpuBars[i] = Math.max(5, Math.min(100, cpuBars[i] + (int)(Math.random() * 30) - 15));
            }
        }
        return running;
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case KEY -> {
                int key = event.getInt(0);
                List<ProcessEntry> entries = getEntries();
                if (key == 265) { // UP
                    if (selectedIndex > 0) selectedIndex--;
                } else if (key == 264) { // DOWN
                    if (selectedIndex < entries.size() - 1) selectedIndex++;
                } else if (key == 261) { // DELETE → end task
                    endSelectedTask();
                } else if (key == 258) { // TAB
                    activeTab = (activeTab + 1) % 2;
                } else if (key == 256) { // ESC
                    running = false;
                }
            }
            case MOUSE_CLICK_PX -> handleClickPx(event.getInt(1), event.getInt(2));
            case MOUSE_CLICK -> {} // Ignore — handled via MOUSE_CLICK_PX
            case MOUSE_SCROLL -> {
                int dir = event.getInt(1);
                List<ProcessEntry> entries = getEntries();
                if (dir > 0 && selectedIndex > 0) selectedIndex--;
                if (dir < 0 && selectedIndex < entries.size() - 1) selectedIndex++;
            }
            default -> {}
        }
    }

    private void handleClickPx(int px, int py) {
        int W = PixelBuffer.SCREEN_W, H = PixelBuffer.SCREEN_H;

        // Tab clicks
        if (py >= 4 && py < 28) {
            if (px >= PAD && px < PAD + 80) activeTab = TAB_PROCESSES;
            else if (px >= PAD + 84 && px < PAD + 84 + 90) activeTab = TAB_PERFORMANCE;
            return;
        }

        if (activeTab == TAB_PROCESSES) {
            // Process list area
            int listTop = HEADER_H + 20; // after column header
            if (py >= listTop && py < H - FOOTER_H) {
                int idx = scrollOffset + (py - listTop) / ROW_H;
                List<ProcessEntry> entries = getEntries();
                if (idx >= 0 && idx < entries.size()) selectedIndex = idx;
            }

            // End Task button
            int btnW = 80, btnH = 22;
            int btnX = W - PAD - btnW, btnY = H - FOOTER_H + 4;
            if (px >= btnX && px < btnX + btnW && py >= btnY && py < btnY + btnH) {
                endSelectedTask();
            }
        }
    }

    private void endSelectedTask() {
        List<ProcessEntry> entries = getEntries();
        if (selectedIndex < 0 || selectedIndex >= entries.size()) return;
        ProcessEntry entry = entries.get(selectedIndex);
        if (entry.window != null && entry.desktop != null) {
            entry.desktop.closeWindow(entry.window);
        }
        if (selectedIndex >= getEntries().size()) {
            selectedIndex = Math.max(0, getEntries().size() - 1);
        }
    }

    private record ProcessEntry(String name, String status, DesktopProgram.Window window, DesktopProgram desktop) {}

    private DesktopProgram findDesktop() {
        for (OSProgram p : os.getProcesses()) {
            if (p instanceof DesktopProgram dp) return dp;
        }
        return null;
    }

    private List<ProcessEntry> getEntries() {
        List<ProcessEntry> list = new ArrayList<>();
        DesktopProgram desktop = findDesktop();
        if (desktop != null) {
            for (DesktopProgram.Window w : desktop.getWindows()) {
                String status = w.minimized ? "Minimized" : "Running";
                list.add(new ProcessEntry(w.title, status, w, desktop));
            }
        }
        // Also show top-level processes
        for (OSProgram p : os.getProcesses()) {
            if (p instanceof DesktopProgram) {
                list.add(new ProcessEntry("ByteOS Desktop", "Running", null, null));
            }
        }
        return list;
    }

    @Override
    public void render(TerminalBuffer buf) {
        buf.setTextColor(0);
        buf.setBackgroundColor(7);
        buf.clear();
        buf.writeAt(1, 1, "Task Manager");
        List<ProcessEntry> entries = getEntries();
        for (int i = 0; i < Math.min(entries.size(), 20); i++) {
            buf.writeAt(1, 3 + i, entries.get(i).name);
        }
    }

    @Override
    public void renderGraphics(PixelBuffer pb) {
        setCursorInfo(0, 0, false);
        int W = PixelBuffer.SCREEN_W, H = PixelBuffer.SCREEN_H;

        // Background
        pb.clear(0xFF1E1E2E);

        // Tab bar
        drawTab(pb, PAD, 4, "Processes", activeTab == TAB_PROCESSES);
        drawTab(pb, PAD + 84, 4, "Performance", activeTab == TAB_PERFORMANCE);
        // Separator line under tabs
        pb.drawHLine(0, W - 1, HEADER_H - 2, 0xFF444466);

        if (activeTab == TAB_PROCESSES) renderProcesses(pb);
        else renderPerformance(pb);
    }

    private void drawTab(PixelBuffer pb, int x, int y, String label, boolean active) {
        int w = label.length() * BitmapFont.CHAR_W + 16;
        if (active) {
            pb.fillRect(x, y, w, 22, 0xFF2A2A44);
            pb.drawHLine(x, x + w - 1, y, 0xFF5588DD);
        }
        pb.drawString(x + 8, y + 4, label, active ? 0xFFCCDDFF : 0xFF888899);
    }

    private void renderProcesses(PixelBuffer pb) {
        int W = PixelBuffer.SCREEN_W, H = PixelBuffer.SCREEN_H;
        List<ProcessEntry> entries = getEntries();

        // Column headers
        int colY = HEADER_H;
        pb.fillRect(0, colY, W, 18, 0xFF252538);
        pb.drawString(PAD + 20, colY + 2, "Name", 0xFF8899BB);
        pb.drawStringRight(W - PAD - 60, colY + 2, "Status", 0xFF8899BB);
        pb.drawHLine(0, W - 1, colY + 17, 0xFF333355);

        // Process list
        int listTop = HEADER_H + 20;
        int maxVisible = (H - FOOTER_H - listTop) / ROW_H;

        // Adjust scroll
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        if (selectedIndex >= scrollOffset + maxVisible) scrollOffset = selectedIndex - maxVisible + 1;

        for (int i = 0; i < maxVisible; i++) {
            int idx = scrollOffset + i;
            if (idx >= entries.size()) break;
            ProcessEntry e = entries.get(idx);
            int ry = listTop + i * ROW_H;

            // Selection highlight
            if (idx == selectedIndex) {
                pb.fillRect(0, ry, W, ROW_H, 0xFF333366);
            } else if (i % 2 == 1) {
                pb.fillRect(0, ry, W, ROW_H, 0xFF222236);
            }

            // Icon
            SystemIcons.Icon icon = iconForProcess(e.name);
            SystemIcons.draw(pb, PAD, ry + 2, icon);

            // Name
            pb.drawString(PAD + 20, ry + 3, e.name, 0xFFDDDDEE);

            // Status
            int statusColor = e.status.equals("Running") ? 0xFF66CC66 : 0xFFAAAA66;
            pb.drawStringRight(W - PAD, ry + 3, e.status, statusColor);
        }

        // Footer
        pb.fillRect(0, H - FOOTER_H, W, FOOTER_H, 0xFF1A1A2A);
        pb.drawHLine(0, W - 1, H - FOOTER_H, 0xFF444466);

        // End Task button
        int btnW = 80, btnH = 22;
        int btnX = W - PAD - btnW, btnY = H - FOOTER_H + 4;
        pb.fillRoundRect(btnX, btnY, btnW, btnH, 3, 0xFF882222);
        pb.drawRect(btnX, btnY, btnW, btnH, 0xFFAA4444);
        pb.drawStringCentered(btnX, btnW, btnY + 4, "End Task", 0xFFFFCCCC);

        // Process count
        pb.drawString(PAD, H - FOOTER_H + 8, "Processes: " + entries.size(), 0xFF888899);
    }

    private void renderPerformance(PixelBuffer pb) {
        int W = PixelBuffer.SCREEN_W, H = PixelBuffer.SCREEN_H;

        String[] labels = {"CPU", "Memory", "Disk", "Network"};
        int[] colors = {0xFF5588DD, 0xFF55AA55, 0xFFDD8844, 0xFF9955CC};

        int barW = W - PAD * 2 - 60;
        for (int i = 0; i < 4; i++) {
            int by = HEADER_H + 10 + i * 50;

            pb.drawString(PAD, by + 4, labels[i], 0xFFCCCCDD);

            // Bar background
            int barX = PAD + 56;
            pb.fillRect(barX, by, barW, 20, 0xFF1A1A28);
            pb.drawRect(barX, by, barW, 20, 0xFF444466);

            // Filled portion
            int fillW = cpuBars[i] * barW / 100;
            if (fillW > 0) pb.fillRect(barX + 1, by + 1, fillW - 1, 18, colors[i]);

            // Percentage
            pb.drawStringRight(W - PAD, by + 4, cpuBars[i] + "%", 0xFFDDDDEE);
        }

        // Uptime
        long secs = tickCount / 20;
        String uptime = String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
        pb.drawString(PAD, HEADER_H + 220, "Up time: " + uptime, 0xFF888899);
    }

    private static SystemIcons.Icon iconForProcess(String name) {
        if (name == null) return SystemIcons.Icon.FILE;
        String lower = name.toLowerCase();
        if (lower.contains("shell") || lower.contains("lua")) return SystemIcons.Icon.TERMINAL;
        if (lower.contains("explorer") || lower.contains("files")) return SystemIcons.Icon.EXPLORER;
        if (lower.contains("paint")) return SystemIcons.Icon.PAINT;
        if (lower.contains("edit") || lower.contains("text") || lower.contains("ide")) return SystemIcons.Icon.FILE_CODE;
        if (lower.contains("notepad")) return SystemIcons.Icon.NOTEPAD;
        if (lower.contains("puzzle")) return SystemIcons.Icon.PUZZLE;
        if (lower.contains("calculator") || lower.contains("calc")) return SystemIcons.Icon.CALCULATOR;
        if (lower.contains("settings")) return SystemIcons.Icon.SETTINGS;
        if (lower.contains("desktop") || lower.contains("byteos")) return SystemIcons.Icon.COMPUTER;
        if (lower.contains("task")) return SystemIcons.Icon.TASK_MANAGER;
        return SystemIcons.Icon.FILE;
    }
}

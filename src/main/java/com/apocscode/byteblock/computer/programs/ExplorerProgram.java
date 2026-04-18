package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;

import java.util.List;

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

    public ExplorerProgram() {
        super("Explorer");
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
                handleKey(event.getInt(0));
                needsRedraw = true;
            }
            case MOUSE_CLICK -> {
                handleClick(event.getInt(1), event.getInt(2));
                needsRedraw = true;
            }
            case MOUSE_SCROLL -> {
                int dir = event.getInt(0);
                scrollOffset = Math.max(0, scrollOffset + dir);
                needsRedraw = true;
            }
            case CHAR -> needsRedraw = true;
            default -> {}
        }
    }

    private void handleKey(int keyCode) {
        int maxVisible = TerminalBuffer.HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT;
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
            case 292 -> running = false; // F3 = close
        }
    }

    private void handleClick(int mx, int my) {
        if (my >= HEADER_HEIGHT && my < TerminalBuffer.HEIGHT - FOOTER_HEIGHT) {
            int idx = scrollOffset + (my - HEADER_HEIGHT);
            if (entries != null && idx < entries.size()) {
                if (idx == selectedIndex) {
                    // Double-click effect (same item clicked again)
                    openSelected();
                } else {
                    selectedIndex = idx;
                }
            }
        }
        // Path bar click — navigate up
        if (my == 0 && mx < 8) {
            navigateUp();
        }
    }

    private void openSelected() {
        if (entries == null || selectedIndex >= entries.size()) return;
        String name = entries.get(selectedIndex);
        if (name.endsWith("/")) {
            // Directory — enter it
            String dirName = name.substring(0, name.length() - 1);
            currentPath = currentPath.equals("/") ? "/" + dirName : currentPath + "/" + dirName;
            selectedIndex = 0;
            scrollOffset = 0;
            refreshListing();
        } else {
            // File — open in appropriate program
            String fullPath = currentPath.equals("/") ? "/" + name : currentPath + "/" + name;
            if (fullPath.endsWith(".pzl")) {
                os.launchProgram(new PuzzleProgram(fullPath));
            } else if (fullPath.endsWith(".pxl")) {
                os.launchProgram(new PaintProgram(fullPath));
            } else {
                os.launchProgram(new EditProgram(fullPath));
            }
        }
    }

    private void navigateUp() {
        if (currentPath.equals("/")) return;
        int lastSlash = currentPath.lastIndexOf('/');
        currentPath = lastSlash <= 0 ? "/" : currentPath.substring(0, lastSlash);
        selectedIndex = 0;
        scrollOffset = 0;
        refreshListing();
    }

    private void deleteSelected() {
        if (entries == null || selectedIndex >= entries.size()) return;
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

    private void refreshListing() {
        entries = os.getFileSystem().list(currentPath);
        if (entries == null) {
            entries = List.of();
            statusMessage = "Cannot read directory";
        }
        needsRedraw = true;
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
}

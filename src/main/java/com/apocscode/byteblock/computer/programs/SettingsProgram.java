package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;

/**
 * Settings program for ByteBlock OS.
 * Configure: computer label, bluetooth channel, desktop colors.
 */
public class SettingsProgram extends OSProgram {

    private int selectedOption = 0;
    private boolean editing = false;
    private final StringBuilder editBuffer = new StringBuilder();

    private static final String[] OPTIONS = {
        "Computer Label",
        "Bluetooth Channel",
        "Wallpaper Color",
        "Icon Text Color",
        "Taskbar Color",
        "Taskbar Text Color",
        "Text Scale",
        "Reboot Computer",
        "Shutdown",
        "Close Settings"
    };

    private static final float[] SCALE_OPTIONS = { 1.5f, 2.0f, 2.5f, 3.0f };
    private static final String[] SCALE_LABELS = { "1.5x Small", "2x Default", "2.5x Large", "3x XL" };

    // Color names for display
    private static final String[] COLOR_NAMES = {
        "White", "Orange", "Magenta", "Lt Blue", "Yellow", "Lime", "Pink", "Gray",
        "Lt Gray", "Cyan", "Purple", "Blue", "Brown", "Green", "Red", "Black"
    };

    public SettingsProgram() {
        super("Settings");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
    }

    @Override
    public boolean tick() {
        return running;
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case KEY -> handleKey(event.getInt(0));
            case CHAR -> {
                if (editing) {
                    editBuffer.append(event.getString(0).charAt(0));
                }
            }
            case MOUSE_CLICK -> {
                int my = event.getInt(2);
                if (my >= 3 && my < 3 + OPTIONS.length) {
                    selectedOption = my - 3;
                }
            }
            default -> {}
        }
    }

    private void handleKey(int keyCode) {
        if (editing) {
            switch (keyCode) {
                case 257, 335 -> { applyEdit(); editing = false; }
                case 256 -> editing = false;
                case 259 -> {
                    if (!editBuffer.isEmpty()) editBuffer.deleteCharAt(editBuffer.length() - 1);
                }
            }
            return;
        }
        switch (keyCode) {
            case 265 -> selectedOption = Math.max(0, selectedOption - 1);
            case 264 -> selectedOption = Math.min(OPTIONS.length - 1, selectedOption + 1);
            case 257, 335 -> activateOption();
            case 256 -> running = false;
        }
    }

    private DesktopProgram getDesktop() {
        OSProgram fg = os.getForegroundProgram();
        if (fg instanceof DesktopProgram dp) return dp;
        return null;
    }

    private void activateOption() {
        DesktopProgram desktop = getDesktop();
        switch (selectedOption) {
            case 0 -> { editing = true; editBuffer.setLength(0); editBuffer.append(os.getLabel()); }
            case 1 -> { editing = true; editBuffer.setLength(0); editBuffer.append(os.getBluetoothChannel()); }
            case 2 -> { if (desktop != null) { desktop.setWallpaperColor((desktop.getWallpaperColor() + 1) & 0xF); desktop.saveDesktopConfig(); } }
            case 3 -> { if (desktop != null) { desktop.setIconTextColor((desktop.getIconTextColor() + 1) & 0xF); desktop.saveDesktopConfig(); } }
            case 4 -> { if (desktop != null) { desktop.setTaskbarColor((desktop.getTaskbarColor() + 1) & 0xF); desktop.saveDesktopConfig(); } }
            case 5 -> { if (desktop != null) { desktop.setTaskbarTextColor((desktop.getTaskbarTextColor() + 1) & 0xF); desktop.saveDesktopConfig(); } }
            case 6 -> {
                float current = os.getTextScale();
                int idx = 0;
                for (int i = 0; i < SCALE_OPTIONS.length; i++) {
                    if (Math.abs(SCALE_OPTIONS[i] - current) < 0.01f) { idx = i; break; }
                }
                os.setTextScale(SCALE_OPTIONS[(idx + 1) % SCALE_OPTIONS.length]);
                if (desktop != null) desktop.saveDesktopConfig();
            }
            case 7 -> os.reboot();
            case 8 -> os.shutdown();
            case 9 -> running = false;
        }
    }

    private void applyEdit() {
        String value = editBuffer.toString().trim();
        switch (selectedOption) {
            case 0 -> { if (!value.isEmpty()) os.setLabel(value); }
            case 1 -> {
                try {
                    int ch = Integer.parseInt(value);
                    if (ch >= 1 && ch <= 65535) os.setBluetoothChannel(ch);
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    @Override
    public void render(TerminalBuffer buf) {
        buf.setTextColor(0);
        buf.setBackgroundColor(15);
        buf.clear();

        buf.setTextColor(4);
        buf.setBackgroundColor(15);
        buf.writeAt(1, 1, "\u2699 Settings");
        buf.setTextColor(7);
        buf.hLine(1, TerminalBuffer.WIDTH - 2, 2, '\u2500');

        DesktopProgram desktop = getDesktop();

        for (int i = 0; i < OPTIONS.length; i++) {
            int y = 3 + i;
            boolean selected = (i == selectedOption);

            if (selected) {
                buf.setBackgroundColor(11);
                buf.hLine(1, TerminalBuffer.WIDTH - 2, y, ' ');
                buf.setTextColor(0);
            } else {
                buf.setBackgroundColor(15);
                buf.setTextColor(8);
            }

            buf.writeAt(2, y, (selected ? "\u25B6 " : "  ") + OPTIONS[i]);

            buf.setTextColor(selected ? 4 : 7);
            switch (i) {
                case 0 -> buf.writeAt(25, y, editing && selected ? editBuffer + "_" : os.getLabel());
                case 1 -> buf.writeAt(25, y, editing && selected ? editBuffer + "_" : String.valueOf(os.getBluetoothChannel()));
                case 2 -> {
                    if (desktop != null) {
                        int c = desktop.getWallpaperColor();
                        buf.setBackgroundColor(c);
                        buf.setTextColor(c == 0 ? 15 : 0);
                        buf.writeAt(25, y, " " + COLOR_NAMES[c] + " ");
                        buf.setBackgroundColor(selected ? 11 : 15);
                    }
                }
                case 3 -> {
                    if (desktop != null) {
                        int c = desktop.getIconTextColor();
                        buf.setBackgroundColor(c);
                        buf.setTextColor(c == 0 ? 15 : 0);
                        buf.writeAt(25, y, " " + COLOR_NAMES[c] + " ");
                        buf.setBackgroundColor(selected ? 11 : 15);
                    }
                }
                case 4 -> {
                    if (desktop != null) {
                        int c = desktop.getTaskbarColor();
                        buf.setBackgroundColor(c);
                        buf.setTextColor(c == 0 ? 15 : 0);
                        buf.writeAt(25, y, " " + COLOR_NAMES[c] + " ");
                        buf.setBackgroundColor(selected ? 11 : 15);
                    }
                }
                case 5 -> {
                    if (desktop != null) {
                        int c = desktop.getTaskbarTextColor();
                        buf.setBackgroundColor(c);
                        buf.setTextColor(c == 0 ? 15 : 0);
                        buf.writeAt(25, y, " " + COLOR_NAMES[c] + " ");
                        buf.setBackgroundColor(selected ? 11 : 15);
                    }
                }
                case 6 -> {
                    float s = os.getTextScale();
                    String label = String.format("%.1fx", s);
                    for (int si = 0; si < SCALE_OPTIONS.length; si++) {
                        if (Math.abs(SCALE_OPTIONS[si] - s) < 0.01f) { label = SCALE_LABELS[si]; break; }
                    }
                    buf.writeAt(25, y, label);
                }
            }
        }

        buf.setTextColor(7);
        buf.setBackgroundColor(15);
        int helpY = 3 + OPTIONS.length + 2;
        if (editing) {
            buf.writeAt(2, helpY, "Type value, Enter=Confirm, Esc=Cancel");
        } else {
            buf.writeAt(2, helpY, "\u2191\u2193 Navigate  Enter=Select  Esc=Close");
        }
    }
}

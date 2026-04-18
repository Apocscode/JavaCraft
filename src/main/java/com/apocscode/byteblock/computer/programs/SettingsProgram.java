package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;

/**
 * Settings program for ByteBlock OS.
 * Configure: computer label, bluetooth channel, display colors.
 */
public class SettingsProgram extends OSProgram {

    private int selectedOption = 0;
    private boolean editing = false;
    private final StringBuilder editBuffer = new StringBuilder();
    private boolean needsRedraw = true;

    private static final String[] OPTIONS = {
        "Computer Label",
        "Bluetooth Channel",
        "Reboot Computer",
        "Shutdown",
        "Close Settings"
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
            case KEY -> {
                handleKey(event.getInt(0));
                needsRedraw = true;
            }
            case CHAR -> {
                if (editing) {
                    editBuffer.append(event.getString(0).charAt(0));
                    needsRedraw = true;
                }
            }
            case MOUSE_CLICK -> {
                int my = event.getInt(2);
                if (my >= 3 && my < 3 + OPTIONS.length) {
                    selectedOption = my - 3;
                    needsRedraw = true;
                }
            }
            default -> {}
        }
    }

    private void handleKey(int keyCode) {
        if (editing) {
            switch (keyCode) {
                case 257, 335 -> { // Enter — confirm edit
                    applyEdit();
                    editing = false;
                }
                case 256 -> editing = false; // Escape — cancel
                case 259 -> { // Backspace
                    if (!editBuffer.isEmpty()) editBuffer.deleteCharAt(editBuffer.length() - 1);
                }
            }
            return;
        }

        switch (keyCode) {
            case 265 -> selectedOption = Math.max(0, selectedOption - 1); // Up
            case 264 -> selectedOption = Math.min(OPTIONS.length - 1, selectedOption + 1); // Down
            case 257, 335 -> activateOption(); // Enter
            case 256 -> running = false; // Escape
        }
    }

    private void activateOption() {
        switch (selectedOption) {
            case 0 -> { // Label
                editing = true;
                editBuffer.setLength(0);
                editBuffer.append(os.getLabel());
            }
            case 1 -> { // BT Channel
                editing = true;
                editBuffer.setLength(0);
                editBuffer.append(os.getBluetoothChannel());
            }
            case 2 -> os.reboot();
            case 3 -> os.shutdown();
            case 4 -> running = false;
        }
    }

    private void applyEdit() {
        String value = editBuffer.toString().trim();
        switch (selectedOption) {
            case 0 -> {
                if (!value.isEmpty()) os.setLabel(value);
            }
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
        if (!needsRedraw) return;
        needsRedraw = false;

        buf.setTextColor(0);
        buf.setBackgroundColor(15);
        buf.clear();

        // Title
        buf.setTextColor(4); // yellow
        buf.setBackgroundColor(15);
        buf.writeAt(1, 1, "\u2699 Settings");
        buf.setTextColor(7);
        buf.hLine(1, TerminalBuffer.WIDTH - 2, 2, '\u2500');

        // Options
        for (int i = 0; i < OPTIONS.length; i++) {
            int y = 3 + i;
            boolean selected = (i == selectedOption);

            if (selected) {
                buf.setBackgroundColor(11); // blue
                buf.hLine(1, TerminalBuffer.WIDTH - 2, y, ' ');
                buf.setTextColor(0);
            } else {
                buf.setBackgroundColor(15);
                buf.setTextColor(8);
            }

            buf.writeAt(2, y, (selected ? "\u25B6 " : "  ") + OPTIONS[i]);

            // Show current values
            buf.setTextColor(selected ? 4 : 7);
            switch (i) {
                case 0 -> buf.writeAt(25, y, editing && selected ? editBuffer + "_" : os.getLabel());
                case 1 -> buf.writeAt(25, y, editing && selected ? editBuffer + "_" : String.valueOf(os.getBluetoothChannel()));
            }
        }

        // Help text
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

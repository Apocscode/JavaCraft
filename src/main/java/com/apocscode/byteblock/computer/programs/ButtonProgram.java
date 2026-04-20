package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.RedstoneLib;
import com.apocscode.byteblock.computer.TerminalBuffer;

/**
 * Button App — desktop program for manual redstone control from the computer terminal.
 * Shows a clickable 4×4 button grid (nearest Button Panel) and relay output/input status.
 * Allows toggling buttons, setting relay outputs, and reading inputs — no coding required.
 */
public class ButtonProgram extends OSProgram {

    private JavaOS os;
    private int refreshCounter = 0;
    private static final int REFRESH_INTERVAL = 5; // every 0.25s

    // Cached state
    private int buttonStates = 0;
    private final int[] relayOutputs = new int[6];
    private final int[] relayInputs = new int[6];
    private boolean panelFound = false;
    private boolean relayFound = false;

    // Selected relay side for output editing (0-5)
    private int selectedSide = -1;

    // Layout constants
    private static final int HEADER_H = 20;
    private static final int BTN_SIZE = 28;
    private static final int BTN_GAP = 4;
    private static final int GRID_LEFT = 12;
    private static final int GRID_TOP = 28;

    // Colors
    private static final int BG        = 0xFF1A1A2A;
    private static final int HEADER_BG = 0xFF2A2A3E;
    private static final int TEXT_TITLE = 0xFF5588DD;
    private static final int TEXT_NORM  = 0xFFCCCCCC;
    private static final int TEXT_DIM   = 0xFF888899;
    private static final int ACCENT     = 0xFF44CCDD;
    private static final int GREEN      = 0xFF44DD66;
    private static final int RED_COL    = 0xFFDD4444;
    private static final int YELLOW     = 0xFFDDCC44;

    private static final int[] BUTTON_COLORS = {
        0xFFF9FFFE, 0xFFF9801D, 0xFFC74EBD, 0xFF3AB3DA,
        0xFFFED83D, 0xFF80C71F, 0xFFF38BAA, 0xFF474F52,
        0xFF9D9D97, 0xFF169C9C, 0xFF8932B8, 0xFF3C44AA,
        0xFF835432, 0xFF5E7C16, 0xFFB02E26, 0xFF1D1D21
    };
    private static final String[] COLOR_SHORT = {
        "WHT", "ORG", "MAG", "LBL", "YEL", "LIM", "PNK", "GRY",
        "LGR", "CYN", "PUR", "BLU", "BRN", "GRN", "RED", "BLK"
    };
    private static final String[] SIDE_NAMES = {"Down", "Up", "North", "South", "West", "East"};

    // Action bar buttons
    private static final int ACTION_Y_OFFSET = 160; // from grid top
    private static final int ACTION_BTN_W = 56;
    private static final int ACTION_BTN_H = 14;

    public ButtonProgram() {
        super("Buttons");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        refreshState();
    }

    @Override
    public boolean tick() {
        refreshCounter++;
        if (refreshCounter >= REFRESH_INTERVAL) {
            refreshCounter = 0;
            refreshState();
        }
        return running;
    }

    private void refreshState() {
        panelFound = RedstoneLib.findButtonPanel(os) != null;
        relayFound = RedstoneLib.findRelay(os) != null;

        if (panelFound) {
            buttonStates = RedstoneLib.getButtonStates(os);
        }
        if (relayFound) {
            for (int i = 0; i < 6; i++) {
                relayOutputs[i] = RedstoneLib.getOutput(os, i);
                relayInputs[i] = RedstoneLib.getInput(os, i);
            }
        }
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case MOUSE_CLICK_PX -> {
                int px = event.getInt(1);
                int py = event.getInt(2);
                handleClick(px, py);
            }
            default -> {}
        }
    }

    private void handleClick(int px, int py) {
        // Check button grid clicks
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int bx = GRID_LEFT + col * (BTN_SIZE + BTN_GAP);
                int by = GRID_TOP + row * (BTN_SIZE + BTN_GAP);
                if (px >= bx && px < bx + BTN_SIZE && py >= by && py < by + BTN_SIZE) {
                    int index = row * 4 + col;
                    if (panelFound) {
                        boolean current = (buttonStates & (1 << index)) != 0;
                        RedstoneLib.setButton(os, index, !current);
                        // Update local cache immediately
                        if (current) {
                            buttonStates &= ~(1 << index);
                        } else {
                            buttonStates |= (1 << index);
                        }
                    }
                    return;
                }
            }
        }

        // Check action buttons
        int actionY = GRID_TOP + ACTION_Y_OFFSET;
        // "All ON" button
        if (px >= GRID_LEFT && px < GRID_LEFT + ACTION_BTN_W &&
            py >= actionY && py < actionY + ACTION_BTN_H) {
            if (panelFound) {
                RedstoneLib.setAllButtons(os, 0xFFFF);
                buttonStates = 0xFFFF;
            }
            return;
        }
        // "All OFF" button
        int offX = GRID_LEFT + ACTION_BTN_W + 8;
        if (px >= offX && px < offX + ACTION_BTN_W &&
            py >= actionY && py < actionY + ACTION_BTN_H) {
            if (panelFound) {
                RedstoneLib.setAllButtons(os, 0);
                buttonStates = 0;
            }
            return;
        }

        // Check relay side clicks (output bars)
        int relayX = 160;
        int relayY = GRID_TOP + 16;
        for (int i = 0; i < 6; i++) {
            int ry = relayY + i * 18;
            if (px >= relayX && px < relayX + 160 && py >= ry && py < ry + 16) {
                selectedSide = i;
                return;
            }
        }

        // Check relay +/- buttons when a side is selected
        if (selectedSide >= 0 && relayFound) {
            int adjY = relayY + 6 * 18 + 4;
            // "-" button
            if (px >= relayX && px < relayX + 30 && py >= adjY && py < adjY + 14) {
                int val = Math.max(0, relayOutputs[selectedSide] - 1);
                RedstoneLib.setOutput(os, selectedSide, val);
                relayOutputs[selectedSide] = val;
                return;
            }
            // "+" button
            if (px >= relayX + 34 && px < relayX + 64 && py >= adjY && py < adjY + 14) {
                int val = Math.min(15, relayOutputs[selectedSide] + 1);
                RedstoneLib.setOutput(os, selectedSide, val);
                relayOutputs[selectedSide] = val;
                return;
            }
            // "0" button (zero)
            if (px >= relayX + 68 && px < relayX + 98 && py >= adjY && py < adjY + 14) {
                RedstoneLib.setOutput(os, selectedSide, 0);
                relayOutputs[selectedSide] = 0;
                return;
            }
            // "15" button (max)
            if (px >= relayX + 102 && px < relayX + 132 && py >= adjY && py < adjY + 14) {
                RedstoneLib.setOutput(os, selectedSide, 15);
                relayOutputs[selectedSide] = 15;
                return;
            }
        }
    }

    @Override
    public void render(TerminalBuffer buf) {
        // Not used in pixel mode
    }

    @Override
    public void renderGraphics(PixelBuffer pb) {
        setCursorInfo(0, 0, false);
        int w = pb.getWidth();
        int h = pb.getHeight();

        // Background
        pb.fillRect(0, 0, w, h, BG);

        // Header
        pb.fillRect(0, 0, w, HEADER_H, HEADER_BG);
        pb.drawString(4, 2, "\u26A1 Redstone Control", TEXT_TITLE);

        String status = panelFound ? (relayFound ? "Panel + Relay" : "Panel Only")
                : (relayFound ? "Relay Only" : "No Devices");
        int statusColor = (panelFound || relayFound) ? GREEN : RED_COL;
        pb.drawStringRight(w - 4, 2, status, statusColor);

        // === Left side: Button Grid ===
        pb.drawString(GRID_LEFT, GRID_TOP - 12, "BUTTON PANEL", panelFound ? ACCENT : TEXT_DIM);

        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int idx = row * 4 + col;
                int bx = GRID_LEFT + col * (BTN_SIZE + BTN_GAP);
                int by = GRID_TOP + row * (BTN_SIZE + BTN_GAP);

                boolean lit = (buttonStates & (1 << idx)) != 0;
                int color = BUTTON_COLORS[idx];

                if (!lit && panelFound) {
                    // Dim unlit buttons
                    int r = ((color >> 16) & 0xFF) * 77 / 256;
                    int g = ((color >> 8) & 0xFF) * 77 / 256;
                    int b = (color & 0xFF) * 77 / 256;
                    color = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                if (!panelFound) {
                    // Greyed out
                    color = 0xFF333344;
                }

                pb.fillRect(bx, by, BTN_SIZE, BTN_SIZE, color);

                // Label
                int textColor = lit ? 0xFF000000 : 0xFFCCCCCC;
                if (!panelFound) textColor = 0xFF555566;
                pb.drawString(bx + 2, by + 2, COLOR_SHORT[idx], textColor);

                // Lit indicator
                if (lit) {
                    pb.fillRect(bx, by + BTN_SIZE - 3, BTN_SIZE, 3, 0xFFFFFFFF);
                }
            }
        }

        // Action buttons below grid
        int actionY = GRID_TOP + ACTION_Y_OFFSET;
        drawActionButton(pb, GRID_LEFT, actionY, ACTION_BTN_W, ACTION_BTN_H,
                "All ON", panelFound ? GREEN : TEXT_DIM);
        drawActionButton(pb, GRID_LEFT + ACTION_BTN_W + 8, actionY, ACTION_BTN_W, ACTION_BTN_H,
                "All OFF", panelFound ? RED_COL : TEXT_DIM);

        // === Right side: Relay Status ===
        int relayX = 160;
        pb.drawString(relayX, GRID_TOP - 12, "RELAY OUTPUT", relayFound ? ACCENT : TEXT_DIM);

        int relayY = GRID_TOP + 16;
        for (int i = 0; i < 6; i++) {
            int ry = relayY + i * 18;
            boolean isSel = (i == selectedSide);

            // Background for selected
            if (isSel) {
                pb.fillRect(relayX - 2, ry - 1, 162, 17, 0xFF2A3A4A);
            }

            // Side name
            pb.drawString(relayX, ry, SIDE_NAMES[i] + ":", isSel ? ACCENT : TEXT_DIM);

            // Output bar
            int barX = relayX + 52;
            int barW = 75;
            int val = relayFound ? relayOutputs[i] : 0;
            pb.fillRect(barX, ry + 2, barW, 10, 0xFF222233);
            if (val > 0) {
                int fillW = barW * val / 15;
                int barColor = val > 10 ? RED_COL : (val > 5 ? YELLOW : GREEN);
                pb.fillRect(barX, ry + 2, fillW, 10, barColor);
            }

            // Value text
            String valStr = relayFound ? String.valueOf(val) : "-";
            pb.drawString(barX + barW + 4, ry, valStr, TEXT_NORM);

            // Input value (small, right side)
            int inVal = relayFound ? relayInputs[i] : 0;
            if (inVal > 0) {
                pb.drawString(barX + barW + 20, ry, "in:" + inVal, TEXT_DIM);
            }
        }

        // Relay +/- controls when a side is selected
        if (selectedSide >= 0) {
            int adjY = relayY + 6 * 18 + 4;
            drawActionButton(pb, relayX, adjY, 30, 14, " - ", relayFound ? YELLOW : TEXT_DIM);
            drawActionButton(pb, relayX + 34, adjY, 30, 14, " + ", relayFound ? GREEN : TEXT_DIM);
            drawActionButton(pb, relayX + 68, adjY, 30, 14, " 0 ", relayFound ? TEXT_DIM : TEXT_DIM);
            drawActionButton(pb, relayX + 102, adjY, 30, 14, " 15", relayFound ? RED_COL : TEXT_DIM);

            pb.drawString(relayX + 140, adjY + 2, SIDE_NAMES[selectedSide], ACCENT);
        }

        // Bottom status bar
        pb.fillRect(0, h - 14, w, 14, HEADER_BG);
        String bottomInfo = "Click buttons to toggle";
        if (selectedSide >= 0 && relayFound) {
            bottomInfo += " | " + SIDE_NAMES[selectedSide] + " out=" + relayOutputs[selectedSide];
        }
        pb.drawString(4, h - 12, bottomInfo, TEXT_DIM);
    }

    private void drawActionButton(PixelBuffer pb, int x, int y, int w, int h, String label, int color) {
        pb.fillRect(x, y, w, h, 0xFF2A2A3E);
        pb.drawRect(x, y, w, h, color);
        int textX = x + (w - label.length() * 8) / 2;
        pb.drawString(textX, y + 3, label, color);
    }
}

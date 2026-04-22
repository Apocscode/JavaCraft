package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity;
import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity.ButtonMode;
import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.RedstoneLib;
import com.apocscode.byteblock.computer.TerminalBuffer;

import net.minecraft.core.BlockPos;

import java.util.Arrays;

/**
 * Button App — desktop program for manual redstone control from the computer terminal.
 *
 * Left-click a button  → toggle it on/off (respects its mode — panel does the logic).
 * Right-click a button → open per-button mode config popup.
 * Left-click relay row → select it; use +/-/0/15 to adjust output.
 * All ON / All OFF     → bulk control.
 *
 * Mode popup cycles: TOGGLE · MOMENTARY · TIMER · DELAY · INVERTED.
 * Duration (ticks) can be adjusted for TIMER and DELAY modes.
 */
public class ButtonProgram extends OSProgram {

    private JavaOS os;
    private int refreshCounter = 0;
    private static final int REFRESH_INTERVAL = 5; // 0.25 s at 20 TPS

    // ── Cached live state ────────────────────────────────────────────────────

    private int buttonStates = 0;           // 16-bit mask: bit i = button i lit
    private final int[] relayOutputs = new int[6];
    private final int[] relayInputs  = new int[6];
    private boolean panelFound = false;
    private boolean relayFound = false;

    // Per-button mode / duration (refreshed from block entity)
    private final ButtonMode[] buttonModes     = new ButtonMode[16];
    private final int[]        buttonDurations = new int[16];

    // Selected relay side for output editing (-1 = none)
    private int selectedSide = -1;

    // ── Config popup state ────────────────────────────────────────────────────

    private int        configButton   = -1;              // -1 = closed
    private ButtonMode configMode     = ButtonMode.TOGGLE;
    private int        configDuration = 20;

    // ── Layout ────────────────────────────────────────────────────────────────

    private static final int HEADER_H     = 20;
    private static final int BTN_SIZE     = 28;
    private static final int BTN_GAP      = 4;
    private static final int GRID_LEFT    = 12;
    private static final int GRID_TOP     = 28;
    private static final int ACTION_Y_OFFSET = 160;
    private static final int ACTION_BTN_W = 56;
    private static final int ACTION_BTN_H = 14;

    // Popup (centred on 640×400 screen)
    private static final int POPUP_W = 220;
    private static final int POPUP_H = 140;
    private static final int POPUP_X = (640 - POPUP_W) / 2;  // 210
    private static final int POPUP_Y = (400 - POPUP_H) / 2;  // 130

    // ── Colors ────────────────────────────────────────────────────────────────

    private static final int BG         = 0xFF1A1A2A;
    private static final int HEADER_BG  = 0xFF2A2A3E;
    private static final int TEXT_TITLE = 0xFF5588DD;
    private static final int TEXT_NORM  = 0xFFCCCCCC;
    private static final int TEXT_DIM   = 0xFF888899;
    private static final int ACCENT     = 0xFF44CCDD;
    private static final int GREEN      = 0xFF44DD66;
    private static final int RED_COL    = 0xFFDD4444;
    private static final int YELLOW     = 0xFFDDCC44;
    private static final int ORANGE     = 0xFFDD8844;
    private static final int POPUP_BG   = 0xFF202030;
    private static final int POPUP_BRD  = 0xFF4455AA;

    // Wool / dye colours matching the Button Panel block renderer
    private static final int[] BUTTON_COLORS = {
        0xFFF9FFFE, 0xFFF9801D, 0xFFC74EBD, 0xFF3AB3DA,
        0xFFFED83D, 0xFF80C71F, 0xFFF38BAA, 0xFF474F52,
        0xFF9D9D97, 0xFF169C9C, 0xFF8932B8, 0xFF3C44AA,
        0xFF835432, 0xFF5E7C16, 0xFFB02E26, 0xFF1D1D21
    };
    private static final String[] COLOR_SHORT = {
        "WHT","ORG","MAG","LBL","YEL","LIM","PNK","GRY",
        "LGR","CYN","PUR","BLU","BRN","GRN","RED","BLK"
    };
    private static final String[] COLOR_NAMES = {
        "White","Orange","Magenta","Lt Blue","Yellow","Lime",
        "Pink","Gray","Lt Gray","Cyan","Purple","Blue",
        "Brown","Green","Red","Black"
    };
    private static final String[] SIDE_NAMES = {"Down","Up","North","South","West","East"};

    // Mode badge letters shown in button corner (empty = default TOGGLE, no badge needed)
    private static final String[] MODE_BADGES = { "", "M", "T", "D", "I" };

    // One-line description per mode shown in the popup
    private static final String[] MODE_DESC = {
        "Click to toggle on/off",
        "Pulses on for ~4 ticks, then auto-off",
        "Stays on for N ticks, then auto-off",
        "Toggles after N tick delay",
        "Toggle with inverted redstone output"
    };

    public ButtonProgram() {
        super("Buttons");
        Arrays.fill(buttonModes,     ButtonMode.TOGGLE);
        Arrays.fill(buttonDurations, 20);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(JavaOS os) {
        this.os = os;
        refreshState();
    }

    @Override
    public boolean tick() {
        if (++refreshCounter >= REFRESH_INTERVAL) {
            refreshCounter = 0;
            refreshState();
        }
        return running;
    }

    // ── State refresh ─────────────────────────────────────────────────────────

    private void refreshState() {
        BlockPos panelPos = RedstoneLib.findButtonPanel(os);
        panelFound = panelPos != null;
        relayFound = RedstoneLib.findRelay(os) != null;

        if (panelFound) {
            buttonStates = RedstoneLib.getButtonStates(os);
            // Read per-button modes/durations directly from the block entity
            var level = os.getLevel();
            if (level != null && level.getBlockEntity(panelPos) instanceof ButtonPanelBlockEntity panel) {
                for (int i = 0; i < 16; i++) {
                    buttonModes[i]     = panel.getMode(i);
                    buttonDurations[i] = panel.getDuration(i);
                }
            }
        }
        if (relayFound) {
            for (int i = 0; i < 6; i++) {
                relayOutputs[i] = RedstoneLib.getOutput(os, i);
                relayInputs[i]  = RedstoneLib.getInput(os, i);
            }
        }
    }

    // ── Input routing ─────────────────────────────────────────────────────────

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case MOUSE_CLICK_PX -> {
                int mouseBtn = event.getInt(0); // 0 = left, 1 = right
                int px       = event.getInt(1);
                int py       = event.getInt(2);
                if (configButton >= 0) {
                    handleConfigClick(px, py);
                } else if (mouseBtn == 1) {
                    handleRightClick(px, py);
                } else {
                    handleLeftClick(px, py);
                }
            }
            case KEY -> {
                // ESC (256) closes popup without applying
                if (event.getInt(0) == 256 && configButton >= 0) {
                    configButton = -1;
                }
            }
            default -> {}
        }
    }

    // ── Left-click handler ────────────────────────────────────────────────────

    private void handleLeftClick(int px, int py) {

        // 4×4 button grid — toggle the button
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int bx = GRID_LEFT + col * (BTN_SIZE + BTN_GAP);
                int by = GRID_TOP  + row * (BTN_SIZE + BTN_GAP);
                if (px >= bx && px < bx + BTN_SIZE && py >= by && py < by + BTN_SIZE) {
                    int idx = row * 4 + col;
                    if (panelFound) {
                        boolean cur = (buttonStates & (1 << idx)) != 0;
                        RedstoneLib.setButton(os, idx, !cur);
                        // Optimistic local update for instant visual response
                        if (cur) buttonStates &= ~(1 << idx);
                        else     buttonStates |=  (1 << idx);
                    }
                    return;
                }
            }
        }

        // "All ON" button
        int actionY = GRID_TOP + ACTION_Y_OFFSET;
        if (px >= GRID_LEFT && px < GRID_LEFT + ACTION_BTN_W
                && py >= actionY && py < actionY + ACTION_BTN_H) {
            if (panelFound) { RedstoneLib.setAllButtons(os, 0xFFFF); buttonStates = 0xFFFF; }
            return;
        }
        // "All OFF" button
        int offX = GRID_LEFT + ACTION_BTN_W + 8;
        if (px >= offX && px < offX + ACTION_BTN_W
                && py >= actionY && py < actionY + ACTION_BTN_H) {
            if (panelFound) { RedstoneLib.setAllButtons(os, 0); buttonStates = 0; }
            return;
        }

        // Relay side selector
        int relayX = 160;
        int relayY = GRID_TOP + 16;
        for (int i = 0; i < 6; i++) {
            int ry = relayY + i * 18;
            if (px >= relayX && px < relayX + 160 && py >= ry && py < ry + 16) {
                selectedSide = i;
                return;
            }
        }

        // Relay output ±/0/15 controls
        if (selectedSide >= 0 && relayFound) {
            int adjY = relayY + 6 * 18 + 4;
            if (px >= relayX      && px < relayX + 30  && py >= adjY && py < adjY + 14) {
                int v = Math.max(0, relayOutputs[selectedSide] - 1);
                RedstoneLib.setOutput(os, selectedSide, v); relayOutputs[selectedSide] = v; return;
            }
            if (px >= relayX + 34 && px < relayX + 64  && py >= adjY && py < adjY + 14) {
                int v = Math.min(15, relayOutputs[selectedSide] + 1);
                RedstoneLib.setOutput(os, selectedSide, v); relayOutputs[selectedSide] = v; return;
            }
            if (px >= relayX + 68  && px < relayX + 98  && py >= adjY && py < adjY + 14) {
                RedstoneLib.setOutput(os, selectedSide, 0);  relayOutputs[selectedSide] = 0;  return;
            }
            if (px >= relayX + 102 && px < relayX + 132 && py >= adjY && py < adjY + 14) {
                RedstoneLib.setOutput(os, selectedSide, 15); relayOutputs[selectedSide] = 15; return;
            }
        }
    }

    // ── Right-click handler — open config popup ───────────────────────────────

    private void handleRightClick(int px, int py) {
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int bx = GRID_LEFT + col * (BTN_SIZE + BTN_GAP);
                int by = GRID_TOP  + row * (BTN_SIZE + BTN_GAP);
                if (px >= bx && px < bx + BTN_SIZE && py >= by && py < by + BTN_SIZE) {
                    int idx = row * 4 + col;
                    configButton   = idx;
                    configMode     = buttonModes[idx];
                    configDuration = buttonDurations[idx];
                    return;
                }
            }
        }
    }

    // ── Config popup click handler ─────────────────────────────────────────────

    private void handleConfigClick(int px, int py) {
        int x = POPUP_X, y = POPUP_Y;

        // Click outside popup → close without applying
        if (px < x || px >= x + POPUP_W || py < y || py >= y + POPUP_H) {
            configButton = -1;
            return;
        }

        // ◄ mode arrow
        if (px >= x + 8 && px < x + 26 && py >= y + 42 && py < y + 56) {
            ButtonMode[] vals = ButtonMode.values();
            configMode = vals[(configMode.ordinal() + vals.length - 1) % vals.length];
            return;
        }
        // ► mode arrow
        if (px >= x + POPUP_W - 26 && px < x + POPUP_W - 8 && py >= y + 42 && py < y + 56) {
            configMode = configMode.next();
            return;
        }

        // Duration controls (only active for TIMER / DELAY)
        boolean durActive = configMode == ButtonMode.TIMER || configMode == ButtonMode.DELAY;
        if (durActive) {
            // [−20]
            if (px >= x + 70 && px < x + 90 && py >= y + 76 && py < y + 88) {
                configDuration = Math.max(1, configDuration - 20); return;
            }
            // [−1]
            if (px >= x + 92 && px < x + 108 && py >= y + 76 && py < y + 88) {
                configDuration = Math.max(1, configDuration - 1); return;
            }
            // [+1]
            if (px >= x + 158 && px < x + 174 && py >= y + 76 && py < y + 88) {
                configDuration = Math.min(6000, configDuration + 1); return;
            }
            // [+20]
            if (px >= x + 176 && px < x + 196 && py >= y + 76 && py < y + 88) {
                configDuration = Math.min(6000, configDuration + 20); return;
            }
        }

        // APPLY button
        int btnY = y + POPUP_H - 28;
        if (px >= x + 10 && px < x + 100 && py >= btnY && py < btnY + 18) {
            applyConfig();
            configButton = -1;
            return;
        }
        // CANCEL button
        if (px >= x + POPUP_W - 100 && px < x + POPUP_W - 10 && py >= btnY && py < btnY + 18) {
            configButton = -1;
            return;
        }
    }

    private void applyConfig() {
        if (configButton < 0) return;
        BlockPos panelPos = RedstoneLib.findButtonPanel(os);
        if (panelPos == null) return;
        var level = os.getLevel();
        if (level != null && level.getBlockEntity(panelPos) instanceof ButtonPanelBlockEntity panel) {
            panel.setMode(configButton, configMode);
            panel.setDuration(configButton, configDuration);
            // Mirror into local cache for instant visual feedback
            buttonModes[configButton]     = configMode;
            buttonDurations[configButton] = configDuration;
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(TerminalBuffer buf) { /* pixel mode only */ }

    @Override
    public void renderGraphics(PixelBuffer pb) {
        setCursorInfo(0, 0, false);
        int w = pb.getWidth(), h = pb.getHeight();

        // ── Background ─────────────────────────────────────────────────────
        pb.fillRect(0, 0, w, h, BG);

        // ── Header ─────────────────────────────────────────────────────────
        pb.fillRect(0, 0, w, HEADER_H, HEADER_BG);
        pb.drawString(4, 2, "\u26A1 Redstone Control", TEXT_TITLE);
        String status = panelFound
            ? (relayFound ? "Panel + Relay" : "Panel Only")
            : (relayFound ? "Relay Only"    : "No Devices");
        pb.drawStringRight(w - 4, 2, status, (panelFound || relayFound) ? GREEN : RED_COL);

        // ── Left: 4×4 button grid ──────────────────────────────────────────
        pb.drawString(GRID_LEFT, GRID_TOP - 12, "BUTTON PANEL", panelFound ? ACCENT : TEXT_DIM);
        if (panelFound) {
            pb.drawString(GRID_LEFT + 94, GRID_TOP - 12, "R-click: configure", TEXT_DIM);
        }

        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int idx = row * 4 + col;
                int bx  = GRID_LEFT + col * (BTN_SIZE + BTN_GAP);
                int by  = GRID_TOP  + row * (BTN_SIZE + BTN_GAP);

                boolean lit       = (buttonStates & (1 << idx)) != 0;
                int     baseColor = BUTTON_COLORS[idx];

                // Fill colour: full when lit, 30 % dim when off, grey when offline
                int fillColor;
                if (!panelFound) {
                    fillColor = 0xFF282838;
                } else if (lit) {
                    fillColor = baseColor;
                } else {
                    int r = ((baseColor >> 16) & 0xFF) * 77 / 255;
                    int g = ((baseColor >>  8) & 0xFF) * 77 / 255;
                    int b = ( baseColor        & 0xFF) * 77 / 255;
                    fillColor = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                pb.fillRect(bx, by, BTN_SIZE, BTN_SIZE, fillColor);

                // Border: bright white glow when lit; subtle dark frame when off
                if (lit && panelFound) {
                    pb.drawRect(bx, by, BTN_SIZE, BTN_SIZE, 0xFFFFFFFF);
                    // Inner top highlight
                    pb.fillRect(bx + 1, by + 1, BTN_SIZE - 2, 2, 0xFF666655);
                } else if (panelFound) {
                    pb.drawRect(bx, by, BTN_SIZE, BTN_SIZE, 0xFF111122);
                } else {
                    pb.drawRect(bx, by, BTN_SIZE, BTN_SIZE, 0xFF222233);
                }

                // Label — black on bright lit buttons, dim on off buttons
                int luminance = (((baseColor >> 16) & 0xFF) * 299
                               + ((baseColor >>  8) & 0xFF) * 587
                               + ( baseColor        & 0xFF) * 114) / 1000;
                int textColor;
                if (!panelFound)  textColor = 0xFF444455;
                else if (lit)     textColor = luminance < 80 ? 0xFFDDDDDD : 0xFF000000;
                else              textColor = TEXT_DIM;
                pb.drawString(bx + 3, by + 3, COLOR_SHORT[idx], textColor);

                // Mode badge (top-right corner) — skip for default TOGGLE
                if (panelFound) {
                    String badge = MODE_BADGES[buttonModes[idx].ordinal()];
                    if (!badge.isEmpty()) {
                        int badgeColor = modeColor(buttonModes[idx]);
                        // Dark backing so badge is readable on any button colour
                        pb.fillRect(bx + BTN_SIZE - 10, by + 1, 9, 9, 0xFF111111);
                        pb.drawString(bx + BTN_SIZE - 9, by + 2, badge, badgeColor);
                    }
                }

                // Bottom pulse strip when lit — bright accent bar
                if (lit && panelFound) {
                    pb.fillRect(bx + 1, by + BTN_SIZE - 4, BTN_SIZE - 2, 3, 0xFFFFFFFF);
                }
            }
        }

        // "All ON" / "All OFF"
        int actionY = GRID_TOP + ACTION_Y_OFFSET;
        drawActionButton(pb, GRID_LEFT,                    actionY, ACTION_BTN_W, ACTION_BTN_H, "All ON",  panelFound ? GREEN   : TEXT_DIM);
        drawActionButton(pb, GRID_LEFT + ACTION_BTN_W + 8, actionY, ACTION_BTN_W, ACTION_BTN_H, "All OFF", panelFound ? RED_COL : TEXT_DIM);

        // ── Right: Relay I/O ─────────────────────────────────────────────
        int relayX = 160;
        pb.drawString(relayX, GRID_TOP - 12, "RELAY OUTPUT", relayFound ? ACCENT : TEXT_DIM);

        int relayY = GRID_TOP + 16;
        for (int i = 0; i < 6; i++) {
            int ry    = relayY + i * 18;
            boolean s = (i == selectedSide);
            if (s) pb.fillRect(relayX - 2, ry - 1, 162, 17, 0xFF2A3A4A);

            pb.drawString(relayX, ry, SIDE_NAMES[i] + ":", s ? ACCENT : TEXT_DIM);

            int barX = relayX + 52, barW = 75;
            int val  = relayFound ? relayOutputs[i] : 0;
            pb.fillRect(barX, ry + 2, barW, 10, 0xFF222233);
            if (val > 0) {
                int barColor = val > 10 ? RED_COL : (val > 5 ? YELLOW : GREEN);
                pb.fillRect(barX, ry + 2, barW * val / 15, 10, barColor);
            }
            pb.drawString(barX + barW + 4, ry, relayFound ? String.valueOf(val) : "-", TEXT_NORM);
            int inVal = relayFound ? relayInputs[i] : 0;
            if (inVal > 0) pb.drawString(barX + barW + 20, ry, "in:" + inVal, TEXT_DIM);
        }

        if (selectedSide >= 0) {
            int adjY = relayY + 6 * 18 + 4;
            drawActionButton(pb, relayX,       adjY, 30, 14, " - ",  relayFound ? YELLOW  : TEXT_DIM);
            drawActionButton(pb, relayX + 34,  adjY, 30, 14, " + ",  relayFound ? GREEN   : TEXT_DIM);
            drawActionButton(pb, relayX + 68,  adjY, 30, 14, " 0 ",  relayFound ? TEXT_DIM: TEXT_DIM);
            drawActionButton(pb, relayX + 102, adjY, 30, 14, " 15",  relayFound ? RED_COL : TEXT_DIM);
            pb.drawString(relayX + 140, adjY + 2, SIDE_NAMES[selectedSide], ACCENT);
        }

        // ── Status bar ────────────────────────────────────────────────────
        pb.fillRect(0, h - 14, w, 14, HEADER_BG);
        String hint = "L-click: toggle  |  R-click: configure mode";
        if (selectedSide >= 0 && relayFound) {
            hint += "  |  " + SIDE_NAMES[selectedSide] + " out=" + relayOutputs[selectedSide];
        }
        pb.drawString(4, h - 12, hint, TEXT_DIM);

        // ── Config popup drawn last, always on top ────────────────────────
        if (configButton >= 0) {
            renderConfigPopup(pb);
        }
    }

    // ── Config popup renderer ─────────────────────────────────────────────────

    private void renderConfigPopup(PixelBuffer pb) {
        int x = POPUP_X, y = POPUP_Y;

        // Drop shadow
        pb.fillRect(x + 4, y + 4, POPUP_W, POPUP_H, 0xFF050508);

        // Background + border
        pb.fillRect(x, y, POPUP_W, POPUP_H, POPUP_BG);
        pb.drawRect (x, y, POPUP_W, POPUP_H, POPUP_BRD);

        // Title bar
        pb.fillRect(x + 1, y + 1, POPUP_W - 2, 18, 0xFF2A2A4A);
        // Colour swatch
        pb.fillRect(x + 6, y + 4, 10, 10, panelFound ? BUTTON_COLORS[configButton] : 0xFF555566);
        pb.drawRect (x + 6, y + 4, 10, 10, 0xFF888899);
        pb.drawString(x + 20, y + 5, "#" + (configButton + 1) + " " + COLOR_NAMES[configButton], ACCENT);
        // Divider
        pb.fillRect(x + 1, y + 19, POPUP_W - 2, 1, POPUP_BRD);

        // ── Mode row ─────────────────────────────────────────────────────
        pb.drawString(x + 8, y + 28, "Mode:", TEXT_NORM);

        // ◄ arrow
        pb.fillRect(x + 8, y + 42, 18, 14, 0xFF2A3A5A);
        pb.drawRect (x + 8, y + 42, 18, 14, POPUP_BRD);
        pb.drawString(x + 12, y + 45, "<", TEXT_NORM);

        // Mode name (centred in popup)
        String modeName = configMode.name();
        int modeNameX   = x + (POPUP_W - modeName.length() * 8) / 2;
        pb.fillRect(x + 30, y + 42, POPUP_W - 60, 14, 0xFF1A1A30);
        pb.drawString(modeNameX, y + 45, modeName, modeColor(configMode));

        // ► arrow
        pb.fillRect(x + POPUP_W - 26, y + 42, 18, 14, 0xFF2A3A5A);
        pb.drawRect (x + POPUP_W - 26, y + 42, 18, 14, POPUP_BRD);
        pb.drawString(x + POPUP_W - 22, y + 45, ">", TEXT_NORM);

        // Mode description
        pb.drawString(x + 8, y + 62, MODE_DESC[configMode.ordinal()], TEXT_DIM);

        // ── Duration row ──────────────────────────────────────────────────
        boolean durActive = configMode == ButtonMode.TIMER || configMode == ButtonMode.DELAY;
        int durColor = durActive ? TEXT_NORM : TEXT_DIM;
        pb.drawString(x + 8, y + 78, "Duration:", durColor);

        // [−20]
        pb.fillRect(x + 70, y + 76, 20, 12, durActive ? 0xFF2A3A5A : 0xFF1A1A28);
        pb.drawRect (x + 70, y + 76, 20, 12, durActive ? POPUP_BRD  : 0xFF333344);
        pb.drawString(x + 72, y + 78, "-20", durActive ? YELLOW : TEXT_DIM);

        // [−1]
        pb.fillRect(x + 92, y + 76, 16, 12, durActive ? 0xFF2A3A5A : 0xFF1A1A28);
        pb.drawRect (x + 92, y + 76, 16, 12, durActive ? POPUP_BRD  : 0xFF333344);
        pb.drawString(x + 96, y + 78, "-", durActive ? TEXT_NORM : TEXT_DIM);

        // Value + seconds hint
        String durStr = configDuration + "t";
        pb.drawString(x + 112, y + 78, durStr, durActive ? ACCENT : TEXT_DIM);
        pb.drawString(x + 112 + durStr.length() * 8 + 2, y + 78,
                      "(" + (configDuration / 20) + "s)", TEXT_DIM);

        // [+1]
        pb.fillRect(x + 158, y + 76, 16, 12, durActive ? 0xFF2A3A5A : 0xFF1A1A28);
        pb.drawRect (x + 158, y + 76, 16, 12, durActive ? POPUP_BRD  : 0xFF333344);
        pb.drawString(x + 161, y + 78, "+", durActive ? TEXT_NORM : TEXT_DIM);

        // [+20]
        pb.fillRect(x + 176, y + 76, 20, 12, durActive ? 0xFF2A3A5A : 0xFF1A1A28);
        pb.drawRect (x + 176, y + 76, 20, 12, durActive ? POPUP_BRD  : 0xFF333344);
        pb.drawString(x + 178, y + 78, "+20", durActive ? YELLOW : TEXT_DIM);

        if (!durActive) {
            pb.drawString(x + 8, y + 92, "Not used by this mode", TEXT_DIM);
        }

        // ── APPLY / CANCEL buttons ─────────────────────────────────────────
        int btnY = y + POPUP_H - 28;

        pb.fillRect(x + 10, btnY, 90, 18, 0xFF1A3A1A);
        pb.drawRect (x + 10, btnY, 90, 18, GREEN);
        pb.drawString(x + 10 + (90 - 5 * 8) / 2, btnY + 5, "APPLY", GREEN);

        pb.fillRect(x + POPUP_W - 100, btnY, 90, 18, 0xFF3A1A1A);
        pb.drawRect (x + POPUP_W - 100, btnY, 90, 18, RED_COL);
        pb.drawString(x + POPUP_W - 100 + (90 - 6 * 8) / 2, btnY + 5, "CANCEL", RED_COL);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Accent colour used for the mode badge and popup mode label. */
    private int modeColor(ButtonMode mode) {
        return switch (mode) {
            case TOGGLE    -> TEXT_DIM;
            case MOMENTARY -> ORANGE;
            case TIMER     -> YELLOW;
            case DELAY     -> ACCENT;
            case INVERTED  -> RED_COL;
        };
    }

    private void drawActionButton(PixelBuffer pb, int x, int y, int w, int h,
                                  String label, int color) {
        pb.fillRect(x, y, w, h, 0xFF2A2A3E);
        pb.drawRect(x, y, w, h, color);
        int textX = x + (w - label.length() * 8) / 2;
        pb.drawString(textX, y + 3, label, color);
    }
}

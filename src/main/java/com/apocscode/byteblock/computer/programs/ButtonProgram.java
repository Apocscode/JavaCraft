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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.apocscode.byteblock.network.BluetoothNetwork;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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

    // Per-side neighbor classification (6 sides of the relay)
    private final String[] relayNeighbors = new String[6];  // empty = air

    // Per-button mode / duration (refreshed from block entity)
    private final ButtonMode[] buttonModes     = new ButtonMode[16];
    private final int[]        buttonDurations = new int[16];

    // Per-button relay-side assignment: -1 = none, 0-5 = Down/Up/North/South/West/East
    private final int[] buttonRelayAssign = new int[16];

    // Previous button state mask for detecting changes (drives relay output)
    private int prevButtonStates = 0;

    // Selected relay side for output editing (-1 = none)
    private int selectedSide = -1;

    // ── Multi-panel state ─────────────────────────────────────────────────────
    // All discovered button panels sorted nearest-first
    private final List<BlockPos> panels = new ArrayList<>();
    // Index into panels[] that is currently being controlled
    private int selectedPanelIdx = 0;

    // Per-button flash countdown (ticks) for click feedback
    private final int[] buttonFlash = new int[16];

    // ── Rename overlay state ──────────────────────────────────────────────────
    private boolean renaming     = false;
    private String  renameBuffer = "";

    // ── Config popup state ────────────────────────────────────────────────────

    private int        configButton      = -1;              // -1 = closed
    private ButtonMode configMode        = ButtonMode.TOGGLE;
    private int        configDuration    = 20;
    private int        configRelayAssign = -1;              // relay side for this button

    // ── Layout ────────────────────────────────────────────────────────────────

    private static final int HEADER_H     = 20;
    private static final int PANEL_BAR_H  = 14;   // second header row: panel nav
    private static final int BTN_SIZE     = 28;
    private static final int BTN_GAP      = 4;
    private static final int GRID_LEFT    = 12;
    private static final int GRID_TOP     = 48;   // shifted down by PANEL_BAR_H + 6px gap
    private static final int ACTION_Y_OFFSET = 160;
    private static final int ACTION_BTN_W = 64;  // wide enough for "All OFF" (7×8px) + 4px padding
    private static final int ACTION_BTN_H = 14;

    // Popup (centred on visible window area: 640×368 for h=24 window)
    private static final int POPUP_W = 280;
    private static final int POPUP_H = 175;
    private static final int POPUP_X = (640 - POPUP_W) / 2;  // 180
    private static final int POPUP_Y = (368 - POPUP_H) / 2;  // 96

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

    // One-line description per mode shown in the popup (max 32 chars to fit POPUP_W)
    private static final String[] MODE_DESC = {
        "Click to toggle on/off",
        "Press: pulses on for ~4 ticks",
        "Press: on for N ticks, then off",
        "Press: toggle after N tick delay",
        "Toggle, inverted redstone output"
    };

    public ButtonProgram() {
        super("Buttons");
        Arrays.fill(buttonModes,     ButtonMode.TOGGLE);
        Arrays.fill(buttonDurations, 20);
        Arrays.fill(buttonRelayAssign, -1);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(JavaOS os) {
        this.os = os;
        refreshState();
    }

    @Override
    public boolean tick() {
        // Decrement button flash timers every tick (20 Hz)
        for (int i = 0; i < 16; i++) {
            if (buttonFlash[i] > 0) buttonFlash[i]--;
        }
        if (++refreshCounter >= REFRESH_INTERVAL) {
            refreshCounter = 0;
            refreshState();
            // Fire relay outputs for buttons that changed state (supports MOMENTARY/TIMER auto-off)
            int changed = buttonStates ^ prevButtonStates;
            if (changed != 0 && relayFound) {
                for (int i = 0; i < 16; i++) {
                    if ((changed & (1 << i)) != 0 && buttonRelayAssign[i] >= 0) {
                        boolean nowOn = (buttonStates & (1 << i)) != 0;
                        RedstoneLib.setOutput(os, buttonRelayAssign[i], nowOn ? 15 : 0);
                        relayOutputs[buttonRelayAssign[i]] = nowOn ? 15 : 0;
                    }
                }
            }
            prevButtonStates = buttonStates;
        }
        return running;
    }

    // ── Panel helpers ─────────────────────────────────────────────────────────

    private BlockPos getActivePanelPos() {
        return (selectedPanelIdx >= 0 && selectedPanelIdx < panels.size())
               ? panels.get(selectedPanelIdx) : null;
    }

    private ButtonPanelBlockEntity getActivePanelBE() {
        BlockPos pos = getActivePanelPos();
        if (pos == null) return null;
        Level lvl = os.getLevel();
        if (lvl == null) return null;
        Object be = lvl.getBlockEntity(pos);
        return be instanceof ButtonPanelBlockEntity p ? p : null;
    }

    private String getPanelDisplayName() {
        ButtonPanelBlockEntity be = getActivePanelBE();
        if (be == null) return "Panel";
        String lbl = be.getLabel();
        return (lbl == null || lbl.isBlank()) ? "Panel " + (selectedPanelIdx + 1) : lbl;
    }

    // ── State refresh ─────────────────────────────────────────────────────────

    private void refreshState() {
        Level lvl   = os.getLevel();
        BlockPos me = os.getBlockPos();

        // Refresh panel list every cycle — scan all registered devices,
        // filter by BUTTON_PANEL (has unlimited range so no dimension/range check needed)
        {
            List<BlockPos> found = new ArrayList<>();
            for (BluetoothNetwork.DeviceEntry d : BluetoothNetwork.getAllDevices()) {
                if (d.type() == BluetoothNetwork.DeviceType.BUTTON_PANEL) {
                    found.add(d.pos());
                }
            }
            if (me != null) {
                found.sort(Comparator.comparingDouble(p -> p.distSqr(me)));
            }
            if (!found.equals(panels)) {
                panels.clear();
                panels.addAll(found);
                if (selectedPanelIdx >= panels.size()) selectedPanelIdx = 0;
            }
        }

        BlockPos panelPos = getActivePanelPos();
        panelFound = panelPos != null;
        relayFound = RedstoneLib.findRelay(os) != null;

        if (panelFound && lvl != null) {
            if (lvl.getBlockEntity(panelPos) instanceof ButtonPanelBlockEntity panel) {
                buttonStates = panel.getButtonStates();
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
            BlockPos relayPos = RedstoneLib.findRelay(os);
            if (lvl != null && relayPos != null) {
                for (int i = 0; i < 6; i++) {
                    Direction dir = Direction.values()[i];
                    BlockState state = lvl.getBlockState(relayPos.relative(dir));
                    relayNeighbors[i] = classifyNeighbor(state);
                }
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
                if (renaming) {
                    handleRenameClick(px, py);
                } else if (configButton >= 0) {
                    handleConfigClick(px, py);
                } else if (mouseBtn == 1) {
                    handleRightClick(px, py);
                } else {
                    handleLeftClick(px, py);
                }
            }
            case CHAR -> {
                if (renaming) {
                    String ch = event.getString(0);
                    if (!ch.isEmpty() && renameBuffer.length() < 24) renameBuffer += ch;
                }
            }
            case PASTE -> {
                if (renaming) {
                    String combined = renameBuffer + event.getString(0);
                    renameBuffer = combined.length() > 24 ? combined.substring(0, 24) : combined;
                }
            }
            case KEY -> {
                int key = event.getInt(0);
                if (renaming) {
                    if (key == 256) {                         // ESC → cancel
                        renaming = false;
                    } else if (key == 257 || key == 335) {   // Enter / numpad Enter → confirm
                        applyRename();
                        renaming = false;
                    } else if (key == 259 && !renameBuffer.isEmpty()) { // Backspace
                        renameBuffer = renameBuffer.substring(0, renameBuffer.length() - 1);
                    }
                } else if (key == 256 && configButton >= 0) {
                    configButton = -1;
                }
            }
            default -> {}
        }
    }

    // ── Left-click handler ────────────────────────────────────────────────────

    private void handleLeftClick(int px, int py) {

        // Panel navigation bar (y = HEADER_H to HEADER_H + PANEL_BAR_H)
        if (py >= HEADER_H && py < HEADER_H + PANEL_BAR_H && !panels.isEmpty()) {
            int w = 640;
            // ◄ left arrow
            if (px >= 4 && px < 16 && selectedPanelIdx > 0) {
                selectedPanelIdx--;
                refreshState();
                return;
            }
            // ► right arrow
            if (px >= w - 16 && px < w - 4 && selectedPanelIdx < panels.size() - 1) {
                selectedPanelIdx++;
                refreshState();
                return;
            }
            // [Lbl] rename button
            if (px >= w - 44 && px < w - 16) {
                ButtonPanelBlockEntity be = getActivePanelBE();
                renameBuffer = (be != null) ? be.getLabel() : "";
                renaming = true;
                return;
            }
            return;
        }

        // 4×4 button grid — toggle the button
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int bx = GRID_LEFT + col * (BTN_SIZE + BTN_GAP);
                int by = GRID_TOP  + row * (BTN_SIZE + BTN_GAP);
                if (px >= bx && px < bx + BTN_SIZE && py >= by && py < by + BTN_SIZE) {
                    int idx = row * 4 + col;
                    ButtonPanelBlockEntity clickPanel = getActivePanelBE();
                    if (clickPanel != null) {
                        boolean cur = (buttonStates & (1 << idx)) != 0;
                        clickPanel.setButton(idx, !cur);
                        // Optimistic local update for instant visual response
                        if (cur) buttonStates &= ~(1 << idx);
                        else     buttonStates |=  (1 << idx);
                        buttonFlash[idx] = 5; // flash for 5 ticks ≈ 250ms
                        // Fire relay output immediately for TOGGLE/INVERTED modes
                        if (buttonRelayAssign[idx] >= 0 && relayFound) {
                            boolean nowOn = (buttonStates & (1 << idx)) != 0;
                            RedstoneLib.setOutput(os, buttonRelayAssign[idx], nowOn ? 15 : 0);
                            relayOutputs[buttonRelayAssign[idx]] = nowOn ? 15 : 0;
                        }
                    }
                    return;
                }
            }
        }

        // "All ON" button
        int actionY = GRID_TOP + ACTION_Y_OFFSET;
        if (px >= GRID_LEFT && px < GRID_LEFT + ACTION_BTN_W
                && py >= actionY && py < actionY + ACTION_BTN_H) {
            ButtonPanelBlockEntity p = getActivePanelBE();
            if (p != null) { p.setAllButtons(0xFFFF); buttonStates = 0xFFFF; }
            return;
        }
        // "All OFF" button
        int offX = GRID_LEFT + ACTION_BTN_W + 8;
        if (px >= offX && px < offX + ACTION_BTN_W
                && py >= actionY && py < actionY + ACTION_BTN_H) {            ButtonPanelBlockEntity p = getActivePanelBE();
            if (p != null) { p.setAllButtons(0); buttonStates = 0; }
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
                    configButton      = idx;
                    configMode        = buttonModes[idx];
                    configDuration    = buttonDurations[idx];
                    configRelayAssign = buttonRelayAssign[idx];
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

        // Relay assign ◄ arrow (cycles: None → Down → Up → North → South → West → East → None)
        if (px >= x + 8 && px < x + 26 && py >= y + 108 && py < y + 122) {
            configRelayAssign = (configRelayAssign <= -1) ? 5 : configRelayAssign - 1;
            return;
        }
        // Relay assign ► arrow
        if (px >= x + POPUP_W - 26 && px < x + POPUP_W - 8 && py >= y + 108 && py < y + 122) {
            configRelayAssign = (configRelayAssign >= 5) ? -1 : configRelayAssign + 1;
            return;
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
        ButtonPanelBlockEntity panel = getActivePanelBE();
        if (panel != null) {
            panel.setMode(configButton, configMode);
            panel.setDuration(configButton, configDuration);
            // Mirror into local cache for instant visual feedback
            buttonModes[configButton]     = configMode;
            buttonDurations[configButton] = configDuration;
        }
        // Apply relay assignment (program-side only, no BE storage needed)
        buttonRelayAssign[configButton] = configRelayAssign;
    }

    private void applyRename() {
        ButtonPanelBlockEntity panel = getActivePanelBE();
        if (panel != null) panel.setLabel(renameBuffer.trim());
    }

    private void handleRenameClick(int px, int py) {
        // Click outside the rename popup dismisses without saving
        int rw = 320, rh = 64;
        int rx = (640 - rw) / 2, ry = (368 - rh) / 2;
        if (px < rx || px >= rx + rw || py < ry || py >= ry + rh) {
            renaming = false;
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
        // ── Panel navigation bar ───────────────────────────────────────────────
        renderPanelBar(pb, w);
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
                boolean flashing  = buttonFlash[idx] > 0;

                // Fill colour: flash white on click, full when lit, 30% dim when off
                int fillColor;
                if (!panelFound) {
                    fillColor = 0xFF282838;
                } else if (flashing) {
                    fillColor = 0xFFEEEEEE; // bright white flash on click
                } else if (lit) {
                    fillColor = baseColor;
                } else {
                    int r = ((baseColor >> 16) & 0xFF) * 77 / 255;
                    int g = ((baseColor >>  8) & 0xFF) * 77 / 255;
                    int b = ( baseColor        & 0xFF) * 77 / 255;
                    fillColor = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                pb.fillRect(bx, by, BTN_SIZE, BTN_SIZE, fillColor);

                // Border: bright white glow when lit or flashing; subtle dark frame when off
                if ((lit || flashing) && panelFound) {
                    pb.drawRect(bx, by, BTN_SIZE, BTN_SIZE, 0xFFFFFFFF);
                    if (!flashing) pb.fillRect(bx + 1, by + 1, BTN_SIZE - 2, 2, 0xFF666655);
                } else if (panelFound) {
                    pb.drawRect(bx, by, BTN_SIZE, BTN_SIZE, 0xFF111122);
                } else {
                    pb.drawRect(bx, by, BTN_SIZE, BTN_SIZE, 0xFF222233);
                }

                // Label — black on bright lit/flash buttons, dim on off buttons
                int luminance = (((baseColor >> 16) & 0xFF) * 299
                               + ((baseColor >>  8) & 0xFF) * 587
                               + ( baseColor        & 0xFF) * 114) / 1000;
                int textColor;
                if (!panelFound)     textColor = 0xFF444455;
                else if (flashing)   textColor = 0xFF111111; // dark on white flash
                else if (lit)        textColor = luminance < 80 ? 0xFFDDDDDD : 0xFF000000;
                else                 textColor = TEXT_DIM;
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

                // Relay assign dot (bottom-left corner) — small cyan dot if assigned
                if (buttonRelayAssign[idx] >= 0) {
                    pb.fillRect(bx + 1, by + BTN_SIZE - 5, 5, 4, 0xFF00CCDD);
                }

                // Bottom pulse strip when lit (not during flash — flash is its own cue)
                if (lit && panelFound && !flashing) {
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

        // ── Right: Connected Blocks panel ─────────────────────────────────
        int nbX = 340;
        pb.drawString(nbX, GRID_TOP - 12, "CONNECTED BLOCKS", relayFound ? ACCENT : TEXT_DIM);

        int nbY = GRID_TOP + 16;
        for (int i = 0; i < 6; i++) {
            int ry = nbY + i * 18;
            String neighbor = relayNeighbors[i];
            boolean hasBlock = neighbor != null && !neighbor.isEmpty();

            // Side label
            pb.drawString(nbX, ry, SIDE_NAMES[i] + ":", hasBlock ? TEXT_NORM : TEXT_DIM);

            if (!relayFound) {
                pb.drawString(nbX + 52, ry, "-", TEXT_DIM);
            } else if (hasBlock) {
                // Colored dot to indicate something is connected
                int dotColor = neighborDotColor(neighbor);
                pb.fillRect(nbX + 52, ry + 2, 7, 7, dotColor);
                pb.drawRect(nbX + 52, ry + 2, 7, 7, 0xFF000000);
                pb.drawString(nbX + 63, ry, neighbor, TEXT_NORM);
            } else {
                pb.drawString(nbX + 52, ry, "air", TEXT_DIM);
            }
        }

        // ── Status bar (at fixed y to stay within the visible window area) ──
        int statusY = 230;
        pb.fillRect(0, statusY, w, 14, HEADER_BG);
        String hint = "L-click: toggle  |  R-click: configure mode + relay assign";
        if (selectedSide >= 0 && relayFound) {
            hint = SIDE_NAMES[selectedSide] + " output=" + relayOutputs[selectedSide]
                   + "  |  R-click button to assign relay side";
        }
        pb.drawString(4, statusY + 2, hint, TEXT_DIM);

        // ── Config popup drawn last, always on top ────────────────────────
        if (configButton >= 0) {
            renderConfigPopup(pb);
        }        // ── Rename overlay drawn on top of everything ───────────────────
        if (renaming) {
            renderRenamePopup(pb);
        }    }

    // ── Panel bar renderer ────────────────────────────────────────────────────

    private void renderPanelBar(PixelBuffer pb, int w) {
        int barY = HEADER_H;
        pb.fillRect(0, barY, w, PANEL_BAR_H, 0xFF1E1E30);
        pb.fillRect(0, barY + PANEL_BAR_H - 1, w, 1, 0xFF334466); // bottom divider

        if (panels.isEmpty()) {
            pb.drawString(4, barY + 3, "No panels in range", TEXT_DIM);
            return;
        }

        // ◄ left arrow
        boolean canLeft = selectedPanelIdx > 0;
        pb.fillRect(4, barY + 1, 12, 12, canLeft ? 0xFF2A3A5A : 0xFF1A1A28);
        pb.drawRect (4, barY + 1, 12, 12, canLeft ? POPUP_BRD : 0xFF333355);
        pb.drawString(7, barY + 3, "<", canLeft ? TEXT_NORM : TEXT_DIM);

        // ► right arrow
        boolean canRight = selectedPanelIdx < panels.size() - 1;
        pb.fillRect(w - 16, barY + 1, 12, 12, canRight ? 0xFF2A3A5A : 0xFF1A1A28);
        pb.drawRect (w - 16, barY + 1, 12, 12, canRight ? POPUP_BRD : 0xFF333355);
        pb.drawString(w - 13, barY + 3, ">", canRight ? TEXT_NORM : TEXT_DIM);

        // [Lbl] rename button (to the left of the ► arrow)
        pb.fillRect(w - 44, barY + 1, 26, 12, 0xFF2A2A44);
        pb.drawRect (w - 44, barY + 1, 26, 12, 0xFF445588);
        pb.drawString(w - 41, barY + 3, "Lbl", ACCENT);

        // Panel name + index (centred between the two arrows)
        String name    = getPanelDisplayName();
        String navText = name + " (" + (selectedPanelIdx + 1) + "/" + panels.size() + ")";
        int    navX    = (w - navText.length() * 8) / 2;
        pb.drawString(navX, barY + 3, navText, ACCENT);
    }

    // ── Rename overlay renderer ───────────────────────────────────────────────

    private void renderRenamePopup(PixelBuffer pb) {
        int rw = 320, rh = 64;
        int rx = (640 - rw) / 2, ry = (368 - rh) / 2;

        // Shadow
        pb.fillRect(rx + 4, ry + 4, rw, rh, 0xFF050508);
        // Background + border
        pb.fillRect(rx, ry, rw, rh, POPUP_BG);
        pb.drawRect (rx, ry, rw, rh, POPUP_BRD);
        // Title bar
        pb.fillRect(rx + 1, ry + 1, rw - 2, 16, 0xFF2A2A4A);
        pb.drawString(rx + 8, ry + 4, "Rename Panel  (Enter=confirm  ESC=cancel)", TEXT_DIM);
        pb.fillRect(rx + 1, ry + 17, rw - 2, 1, POPUP_BRD);
        // Text field
        int tfX = rx + 8, tfY = ry + 22, tfW = rw - 16, tfH = 16;
        pb.fillRect(tfX, tfY, tfW, tfH, 0xFF111122);
        pb.drawRect (tfX, tfY, tfW, tfH, 0xFF4455AA);
        pb.drawString(tfX + 4, tfY + 4, renameBuffer + "|", TEXT_NORM);
        // Char count hint
        pb.drawString(rx + 8, ry + 44, renameBuffer.length() + "/24 chars", TEXT_DIM);
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

        // ── Relay Assign row ──────────────────────────────────────────────
        pb.drawString(x + 8, y + 104, "Relay Side:", TEXT_NORM);

        // ◄ arrow
        pb.fillRect(x + 8, y + 108, 18, 14, 0xFF2A3A5A);
        pb.drawRect (x + 8, y + 108, 18, 14, POPUP_BRD);
        pb.drawString(x + 12, y + 111, "<", TEXT_NORM);

        // Relay assign value (centred)
        String relayName = configRelayAssign < 0 ? "None" : SIDE_NAMES[configRelayAssign];
        int relayNameX = x + (POPUP_W - relayName.length() * 8) / 2;
        pb.fillRect(x + 30, y + 108, POPUP_W - 60, 14, 0xFF1A1A30);
        pb.drawString(relayNameX, y + 111, relayName, configRelayAssign < 0 ? TEXT_DIM : ACCENT);

        // ► arrow
        pb.fillRect(x + POPUP_W - 26, y + 108, 18, 14, 0xFF2A3A5A);
        pb.drawRect (x + POPUP_W - 26, y + 108, 18, 14, POPUP_BRD);
        pb.drawString(x + POPUP_W - 22, y + 111, ">", TEXT_NORM);

        pb.drawString(x + 8, y + 126, "Press button \u2192 fires relay side", TEXT_DIM);

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

    /** Dot color for the connected-blocks panel based on block type. */
    private int neighborDotColor(String label) {
        if (label == null) return TEXT_DIM;
        return switch (label) {
            case "Lamp"         -> YELLOW;
            case "Redstone"     -> RED_COL;
            case "RS Block"     -> RED_COL;
            case "RS Torch"     -> ORANGE;
            case "Repeater"     -> ORANGE;
            case "Comparator"   -> ORANGE;
            case "Lever"        -> GREEN;
            case "Button"       -> GREEN;
            case "Pres. Plate"  -> GREEN;
            case "Observer"     -> ACCENT;
            case "Piston"       -> TEXT_NORM;
            case "Note Block"   -> 0xFFCC44CC;  // purple
            case "Bundled Cable"-> 0xFF8844DD;  // violet
            default             -> TEXT_DIM;
        };
    }

    /**
     * Classify a neighbor block into a short human-readable label.
     * Returns empty string for air, or a ≤14-char name for everything else.
     */
    private String classifyNeighbor(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) return "";
        String path = id.getPath();
        if (path.equals("air") || path.equals("cave_air") || path.equals("void_air")) return "";
        // Named redstone components
        if (path.contains("redstone_lamp"))   return "Lamp";
        if (path.contains("redstone_block"))  return "RS Block";
        if (path.contains("redstone_torch"))  return "RS Torch";
        if (path.contains("redstone_wire")
            || path.equals("redstone"))       return "Redstone";
        if (path.contains("repeater"))        return "Repeater";
        if (path.contains("comparator"))      return "Comparator";
        if (path.contains("observer"))        return "Observer";
        if (path.contains("piston"))          return "Piston";
        if (path.contains("lever"))           return "Lever";
        if (path.contains("button"))          return "Button";
        if (path.contains("pressure_plate"))  return "Pres. Plate";
        if (path.contains("note_block"))      return "Note Block";
        if (path.contains("dispenser"))       return "Dispenser";
        if (path.contains("dropper"))         return "Dropper";
        if (path.contains("hopper"))          return "Hopper";
        if (path.contains("tnt"))             return "TNT";
        // Non-vanilla bundled cable check
        if (!id.getNamespace().equals("minecraft")
            && (path.contains("cable") || path.contains("bundled"))) return "Bundled Cable";
        // Generic fallback: namespace:path → prettify
        String ns = id.getNamespace().equals("minecraft") ? "" : "[" + id.getNamespace() + "] ";
        String label = ns + path.replace("_", " ");
        if (label.length() > 14) label = label.substring(0, 13) + "\u2026"; // ellipsis
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private void drawActionButton(PixelBuffer pb, int x, int y, int w, int h,
                                  String label, int color) {
        pb.fillRect(x, y, w, h, 0xFF2A2A3E);
        pb.drawRect(x, y, w, h, color);
        int textX = x + (w - label.length() * 8) / 2;
        pb.drawString(textX, y + 3, label, color);
    }
}

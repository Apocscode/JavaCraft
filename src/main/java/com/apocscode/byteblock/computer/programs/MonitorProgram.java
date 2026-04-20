package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.block.entity.MonitorBlockEntity;
import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Monitor Manager — desktop app for discovering linked monitors,
 * viewing formation info, and sending fullscreen test patterns.
 */
public class MonitorProgram extends OSProgram {

    private JavaOS os;
    private List<MonitorInfo> monitors = new ArrayList<>();
    private int selectedIndex = -1;
    private int scrollOffset = 0;
    private int refreshCounter = 0;
    private static final int REFRESH_INTERVAL = 40; // every 2 seconds

    // Active test pattern (null = normal GUI)
    private String activePattern = null;

    // Layout
    private static final int HEADER_H = 20;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = HEADER_H + 18; // after header + column bar
    private static final int BTN_W = 80;
    private static final int BTN_H = 18;
    private static final int BTN_GAP = 4;

    // Colors
    private static final int BG         = 0xFF1E1E2E;
    private static final int HEADER_BG  = 0xFF2A2A3E;
    private static final int ROW_EVEN   = 0xFF252535;
    private static final int ROW_ODD    = 0xFF1E1E2E;
    private static final int ROW_SEL    = 0xFF3A4A5A;
    private static final int TEXT_NORM   = 0xFFCCCCCC;
    private static final int TEXT_DIM    = 0xFF888899;
    private static final int ACCENT      = 0xFF5588DD;
    private static final int GREEN       = 0xFF44DD66;
    private static final int RED         = 0xFFDD4444;
    private static final int CYAN        = 0xFF44CCDD;
    private static final int BTN_BG      = 0xFF333348;
    private static final int COL_HDR_BG  = 0xFF222233;
    private static final int COL_HDR_TXT = 0xFFAABBDD;
    private static final int WARN        = 0xFFDDCC44;

    private String statusText = "Click a pattern to apply it to the selected monitor, or all if none is selected.";

    // Button definitions for test patterns
    private static final String[][] PATTERN_BUTTONS = {
        {"Color Bars", "bars"},
        {"Grid",       "grid"},
        {"Checker",    "checker"},
        {"Gradient",   "gradient"},
        {"Solid Red",  "red"},
        {"Solid Green","green"},
        {"Solid Blue", "blue"},
        {"White",      "white"},
    };

    private record MonitorInfo(BlockPos pos, String shortId, double distance,
                               int width, int height, String mode, java.util.UUID fullId) {}

    public MonitorProgram() {
        super("Monitor");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        refreshMonitors();
    }

    @Override
    public boolean tick() {
        refreshCounter++;
        if (refreshCounter >= REFRESH_INTERVAL) {
            refreshCounter = 0;
            refreshMonitors();
        }
        return running;
    }

    private void refreshMonitors() {
        monitors.clear();
        if (os == null || os.getLevel() == null) return;

        BlockPos myPos = os.getBlockPos();
        List<BluetoothNetwork.DeviceEntry> inRange =
                BluetoothNetwork.getDevicesInRange(os.getLevel(), myPos, 64);

        for (BluetoothNetwork.DeviceEntry d : inRange) {
            if (d.type() != BluetoothNetwork.DeviceType.MONITOR) continue;
            double dist = Math.sqrt(myPos.distSqr(d.pos()));

            // Try to read formation info from the block entity
            int fw = 1, fh = 1;
            String mode = "mirror";
            if (os.getLevel().getBlockEntity(d.pos()) instanceof MonitorBlockEntity mbe) {
                fw = mbe.getMultiWidth();
                fh = mbe.getMultiHeight();
                mode = mbe.getDisplayMode();
            }

            monitors.add(new MonitorInfo(
                d.pos(),
                d.deviceId().toString().substring(0, 8),
                dist, fw, fh, mode, d.deviceId()
            ));
        }

        // Clamp selection
        if (selectedIndex >= monitors.size()) selectedIndex = monitors.size() - 1;
    }

    // ────────────────────── Event handling ──────────────────────

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case KEY -> {
                int code = event.getInt(0);
                if (code == 256) { // ESC
                    if (activePattern != null) {
                        activePattern = null; // exit test pattern
                    } else {
                        running = false;
                    }
                }
            }
            case MOUSE_CLICK_PX -> handleClickPx(event.getInt(1), event.getInt(2));
            case MOUSE_SCROLL -> {
                int dir = event.getInt(0);
                if (activePattern == null) {
                    scrollOffset = Math.max(0, scrollOffset + (dir < 0 ? -1 : 1));
                }
            }
            case MOUSE_CLICK -> {} // ignore cell-based
            default -> {}
        }
    }

    private void handleClickPx(int mx, int my) {
        // If a test pattern is active, any click exits it
        if (activePattern != null) {
            activePattern = null;
            return;
        }

        int w = PixelBuffer.SCREEN_W;

        // Check monitor list clicks
        int maxVisible = getMaxVisibleRows();
        if (my >= LIST_TOP && mx < w / 2) {
            int row = (my - LIST_TOP) / ROW_H + scrollOffset;
            if (row >= 0 && row < monitors.size()) {
                selectedIndex = row;
            }
            return;
        }

        // Check pattern buttons (right panel)
        int btnStartX = w / 2 + 10;
        int btnStartY = LIST_TOP;
        for (int i = 0; i < PATTERN_BUTTONS.length; i++) {
            int col = i % 2;
            int row = i / 2;
            int bx = btnStartX + col * (BTN_W + BTN_GAP);
            int by = btnStartY + row * (BTN_H + BTN_GAP);
            if (mx >= bx && mx < bx + BTN_W && my >= by && my < by + BTN_H) {
                applyDisplayMode("test:" + PATTERN_BUTTONS[i][1]);
                activePattern = PATTERN_BUTTONS[i][1];
                return;
            }
        }

        // Check "Mirror" / "Clear" button at bottom of right panel
        int clearBtnY = btnStartY + ((PATTERN_BUTTONS.length + 1) / 2) * (BTN_H + BTN_GAP) + 8;
        if (mx >= btnStartX && mx < btnStartX + BTN_W * 2 + BTN_GAP && my >= clearBtnY && my < clearBtnY + BTN_H) {
            applyDisplayMode("mirror");
            activePattern = null;
        }
    }

    private void applyDisplayMode(String mode) {
        if (os == null || os.getLevel() == null || os.getBlockPos() == null) return;

        int applied = 0;
        if (selectedIndex >= 0 && selectedIndex < monitors.size()) {
            MonitorInfo selected = monitors.get(selectedIndex);
            BluetoothNetwork.send(os.getLevel(), os.getBlockPos(), os.getComputerId(), selected.fullId(), 1, "display_mode:" + mode);
            applied = 1;
        } else {
            for (MonitorInfo monitor : monitors) {
                BluetoothNetwork.send(os.getLevel(), os.getBlockPos(), os.getComputerId(), monitor.fullId(), 1, "display_mode:" + mode);
                applied++;
            }
        }

        statusText = applied > 0
                ? ("Applied " + mode + " to " + applied + " monitor" + (applied == 1 ? "" : "s"))
                : "No monitors found in range.";
        refreshMonitors();
    }

    private int getMaxVisibleRows() {
        return (PixelBuffer.SCREEN_H - LIST_TOP - 4) / ROW_H;
    }

    // ────────────────────── Rendering ──────────────────────

    @Override
    public void render(TerminalBuffer buffer) {} // unused, pixel mode only

    @Override
    public void renderGraphics(PixelBuffer pb) {
        setCursorInfo(0, 0, false);

        if (activePattern != null) {
            renderTestPattern(pb);
            return;
        }

        int w = pb.getWidth();
        int h = pb.getHeight();
        int halfW = w / 2;

        // Background
        pb.fillRect(0, 0, w, h, BG);

        // ── Header ──
        pb.fillRect(0, 0, w, HEADER_H, HEADER_BG);
        pb.drawString(4, 2, "Monitor Manager", ACCENT);
        String countStr = monitors.size() + " monitor" + (monitors.size() != 1 ? "s" : "");
        pb.drawStringRight(w - 4, 2, countStr, CYAN);

        // ── Left panel: monitor list ──
        // Column header
        int colY = HEADER_H;
        pb.fillRect(0, colY, halfW, 16, COL_HDR_BG);
        pb.drawString(4, colY, "ID", COL_HDR_TXT);
        pb.drawString(80, colY, "Size", COL_HDR_TXT);
        pb.drawString(130, colY, "Dist", COL_HDR_TXT);
        pb.drawString(190, colY, "Mode", COL_HDR_TXT);

        // Rows
        int maxVisible = (h - LIST_TOP - 4) / ROW_H;
        for (int i = 0; i < maxVisible && (i + scrollOffset) < monitors.size(); i++) {
            int idx = i + scrollOffset;
            MonitorInfo mon = monitors.get(idx);
            int rowY = LIST_TOP + i * ROW_H;

            int bg = (idx == selectedIndex) ? ROW_SEL : (idx % 2 == 0 ? ROW_EVEN : ROW_ODD);
            pb.fillRect(0, rowY, halfW, ROW_H, bg);

            pb.drawString(4, rowY, mon.shortId, TEXT_NORM);
            pb.drawString(80, rowY, mon.width + "x" + mon.height, CYAN);
            pb.drawString(130, rowY, String.format("%.0fm", mon.distance), TEXT_DIM);

            int modeColor = "mirror".equals(mon.mode) ? GREEN : 0xFFDDCC44;
            pb.drawString(190, rowY, mon.mode, modeColor);
        }

        // Divider
        pb.drawVLine(halfW, HEADER_H, h, 0xFF3A3A4A);

        // ── Right panel: test patterns + info ──
        pb.fillRect(halfW, colY, halfW, 16, COL_HDR_BG);
        pb.drawString(halfW + 4, colY, "Test Patterns", COL_HDR_TXT);

        int btnStartX = halfW + 10;
        int btnStartY = LIST_TOP;

        for (int i = 0; i < PATTERN_BUTTONS.length; i++) {
            int col = i % 2;
            int row = i / 2;
            int bx = btnStartX + col * (BTN_W + BTN_GAP);
            int by = btnStartY + row * (BTN_H + BTN_GAP);

            int btnColor = getPatternPreviewColor(PATTERN_BUTTONS[i][1]);
            pb.fillRoundRect(bx, by, BTN_W, BTN_H, 3, BTN_BG);
            // Colored stripe on left edge
            pb.fillRect(bx, by + 2, 4, BTN_H - 4, btnColor);
            pb.drawString(bx + 8, by + 1, PATTERN_BUTTONS[i][0], TEXT_NORM);
        }

        // "Clear / Mirror" button
        int clearBtnY = btnStartY + ((PATTERN_BUTTONS.length + 1) / 2) * (BTN_H + BTN_GAP) + 8;
        int clearW = BTN_W * 2 + BTN_GAP;
        pb.fillRoundRect(btnStartX, clearBtnY, clearW, BTN_H, 3, 0xFF2A4A2A);
        pb.drawStringCentered(btnStartX, clearW, clearBtnY + 1, "Reset to Mirror", GREEN);

        // Selected monitor info
        if (selectedIndex >= 0 && selectedIndex < monitors.size()) {
            MonitorInfo sel = monitors.get(selectedIndex);
            int infoY = clearBtnY + BTN_H + 16;
            pb.drawString(halfW + 4, infoY, "Selected Monitor:", ACCENT);
            infoY += 16;
            pb.drawString(halfW + 8, infoY, "ID: " + sel.shortId, TEXT_DIM);
            infoY += 16;
            pb.drawString(halfW + 8, infoY, "Pos: " + sel.pos.getX() + ", " + sel.pos.getY() + ", " + sel.pos.getZ(), TEXT_DIM);
            infoY += 16;
            pb.drawString(halfW + 8, infoY, "Formation: " + sel.width + " x " + sel.height, TEXT_NORM);
            infoY += 16;
            pb.drawString(halfW + 8, infoY, "Mode: " + sel.mode, "mirror".equals(sel.mode) ? GREEN : 0xFFDDCC44);
            infoY += 16;
            pb.drawString(halfW + 8, infoY, "Distance: " + String.format("%.1f", sel.distance) + " blocks", TEXT_DIM);
        }

        // Footer hint
        pb.drawString(4, h - 30, statusText, statusText.startsWith("Applied") ? GREEN : WARN);
        pb.drawString(4, h - 14, "ESC closes preview | Reset to Mirror clears monitor test mode", TEXT_DIM);
    }

    // ────────────────────── Test pattern rendering ──────────────────────

    private void renderTestPattern(PixelBuffer pb) {
        int w = pb.getWidth();
        int h = pb.getHeight();

        switch (activePattern) {
            case "bars" -> renderColorBars(pb, w, h);
            case "grid" -> renderGrid(pb, w, h);
            case "checker" -> renderCheckerboard(pb, w, h);
            case "gradient" -> renderGradient(pb, w, h);
            case "red" -> pb.fillRect(0, 0, w, h, 0xFFFF0000);
            case "green" -> pb.fillRect(0, 0, w, h, 0xFF00FF00);
            case "blue" -> pb.fillRect(0, 0, w, h, 0xFF0000FF);
            case "white" -> pb.fillRect(0, 0, w, h, 0xFFFFFFFF);
            default -> pb.fillRect(0, 0, w, h, 0xFF000000);
        }

        // Overlay hint text
        String hint = "Test: " + activePattern.toUpperCase() + "  |  Click or ESC to exit";
        int textW = hint.length() * 8;
        pb.fillRect(w / 2 - textW / 2 - 4, h - 20, textW + 8, 18, 0xCC000000);
        pb.drawStringCentered(0, w, h - 18, hint, 0xFFFFFFFF);
    }

    private void renderColorBars(PixelBuffer pb, int w, int h) {
        int[] colors = {
            0xFFFFFFFF, 0xFFFFFF00, 0xFF00FFFF, 0xFF00FF00,
            0xFFFF00FF, 0xFFFF0000, 0xFF0000FF, 0xFF000000,
            0xFFFF8800, 0xFF8800FF, 0xFF0088FF, 0xFF88FF00
        };
        int barW = w / colors.length;
        for (int i = 0; i < colors.length; i++) {
            int x = i * barW;
            int bw = (i == colors.length - 1) ? w - x : barW;
            pb.fillRect(x, 0, bw, h, colors[i]);
        }
    }

    private void renderGrid(PixelBuffer pb, int w, int h) {
        pb.fillRect(0, 0, w, h, 0xFF000000);
        int spacing = 32;
        for (int x = 0; x < w; x += spacing) {
            pb.drawVLine(x, 0, h, 0xFF444444);
        }
        for (int y = 0; y < h; y += spacing) {
            pb.drawHLine(0, w, y, 0xFF444444);
        }
        // Center crosshair
        pb.drawHLine(0, w, h / 2, 0xFFFF0000);
        pb.drawVLine(w / 2, 0, h, 0xFFFF0000);
        // Border
        pb.drawRect(0, 0, w, h, 0xFFFFFFFF);
    }

    private void renderCheckerboard(PixelBuffer pb, int w, int h) {
        int size = 16;
        for (int y = 0; y < h; y += size) {
            for (int x = 0; x < w; x += size) {
                boolean white = ((x / size) + (y / size)) % 2 == 0;
                pb.fillRect(x, y, size, size, white ? 0xFFFFFFFF : 0xFF000000);
            }
        }
    }

    private void renderGradient(PixelBuffer pb, int w, int h) {
        // Horizontal RGB gradient — red→green top third, green→blue mid, blue→red bottom
        int thirdH = h / 3;
        for (int x = 0; x < w; x++) {
            float t = (float) x / (w - 1);
            int r1 = (int) ((1 - t) * 255);
            int g1 = (int) (t * 255);
            int color1 = 0xFF000000 | (r1 << 16) | (g1 << 8);
            for (int y = 0; y < thirdH; y++) pb.setPixel(x, y, color1);

            int g2 = (int) ((1 - t) * 255);
            int b2 = (int) (t * 255);
            int color2 = 0xFF000000 | (g2 << 8) | b2;
            for (int y = thirdH; y < thirdH * 2; y++) pb.setPixel(x, y, color2);

            int b3 = (int) ((1 - t) * 255);
            int r3 = (int) (t * 255);
            int color3 = 0xFF000000 | (r3 << 16) | b3;
            for (int y = thirdH * 2; y < h; y++) pb.setPixel(x, y, color3);
        }
    }

    private int getPatternPreviewColor(String pattern) {
        return switch (pattern) {
            case "bars" -> 0xFFFFFF00;
            case "grid" -> 0xFF888888;
            case "checker" -> 0xFFFFFFFF;
            case "gradient" -> 0xFF00AAFF;
            case "red" -> 0xFFFF0000;
            case "green" -> 0xFF00FF00;
            case "blue" -> 0xFF0000FF;
            case "white" -> 0xFFFFFFFF;
            default -> TEXT_NORM;
        };
    }
}

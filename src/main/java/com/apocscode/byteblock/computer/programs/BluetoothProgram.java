package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.SystemIcons;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bluetooth Manager — desktop app that lists all detected/connected
 * devices within range and shows device info + signal strength.
 */
public class BluetoothProgram extends OSProgram {

    private JavaOS os;
    private List<DeviceRow> deviceList = new ArrayList<>();
    private int scrollOffset = 0;
    private int selectedIndex = -1;
    private int refreshCounter = 0;
    private static final int REFRESH_INTERVAL = 20; // every 1 second

    // Layout constants  (font is 8x16 — need 16px per text row)
    private static final int HEADER_H = 36;   // two 16-px text rows + 4px padding
    private static final int COL_HDR_H = 16;  // column header band height
    private static final int ROW_H = 16;      // data row height (matches font)

    // Draggable column dividers: boundaries between Type|ID, ID|Dist, Dist|Signal
    // Defaults are set in init() based on actual window width
    private int[] colDiv = {120, 210, 280};
    private static final int GRAB_W = 5;
    private static final int MIN_COL_W = 40;
    private int draggingDiv = -1;
    private boolean colsInitialized = false;

    // Colors
    private static final int BG        = 0xFF1E1E2E;
    private static final int HEADER_BG = 0xFF2A2A3E;
    private static final int ROW_EVEN  = 0xFF252535;
    private static final int ROW_ODD   = 0xFF1E1E2E;
    private static final int ROW_SEL   = 0xFF3A4A5A;
    private static final int TEXT_HEAD  = 0xFFAABBDD;
    private static final int TEXT_NORM  = 0xFFCCCCCC;
    private static final int TEXT_DIM   = 0xFF888899;
    private static final int GREEN      = 0xFF44DD66;
    private static final int YELLOW     = 0xFFDDCC44;
    private static final int RED        = 0xFFDD4444;
    private static final int CYAN       = 0xFF44CCDD;
    private static final int ACCENT     = 0xFF5588DD;

    private record DeviceRow(String type, String shortId, double distance, int channel, String dimension, UUID fullId) {}

    public BluetoothProgram() {
        super("Bluetooth");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        refreshDevices();
    }

    @Override
    public boolean tick() {
        refreshCounter++;
        if (refreshCounter >= REFRESH_INTERVAL) {
            refreshCounter = 0;
            refreshDevices();
        }
        return running;
    }

    private void refreshDevices() {
        deviceList.clear();
        if (os.getLevel() == null || os.getBlockPos() == null) return;

        UUID myId = os.getComputerId();
        BlockPos myPos = os.getBlockPos();

        // Get all devices in standard BT range
        List<BluetoothNetwork.DeviceEntry> inRange =
                BluetoothNetwork.getDevicesInRange(os.getLevel(), myPos, BluetoothNetwork.BLOCK_RANGE);

        // Also get unlimited-range devices (robots, drones, GPS) from all dimensions
        List<BluetoothNetwork.DeviceEntry> allDevices = BluetoothNetwork.getAllDevices();

        // Merge: in-range devices + unlimited-range devices not already in the list
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (BluetoothNetwork.DeviceEntry d : inRange) {
            if (d.deviceId().equals(myId)) continue;
            seen.add(d.deviceId());
            double dist = Math.sqrt(myPos.distSqr(d.pos()));
            deviceList.add(new DeviceRow(
                d.type().getDisplayName(),
                d.deviceId().toString().substring(0, 8),
                dist,
                d.channel(),
                d.dimension(),
                d.deviceId()
            ));
        }
        for (BluetoothNetwork.DeviceEntry d : allDevices) {
            if (d.deviceId().equals(myId) || seen.contains(d.deviceId())) continue;
            if (d.type().getRange() == BluetoothNetwork.UNLIMITED_RANGE) {
                seen.add(d.deviceId());
                deviceList.add(new DeviceRow(
                    d.type().getDisplayName(),
                    d.deviceId().toString().substring(0, 8),
                    -1, // unknown distance (cross-dimensional)
                    d.channel(),
                    d.dimension(),
                    d.deviceId()
                ));
            }
        }

        // Sort: closest first, cross-dim at end
        deviceList.sort((a, b) -> {
            if (a.distance < 0 && b.distance < 0) return 0;
            if (a.distance < 0) return 1;
            if (b.distance < 0) return -1;
            return Double.compare(a.distance, b.distance);
        });

        // Clamp selection
        if (selectedIndex >= deviceList.size()) selectedIndex = deviceList.size() - 1;
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case MOUSE_CLICK_PX -> {
                int px = event.getInt(1);
                int py = event.getInt(2);
                // Check if clicking a column divider (below status header)
                draggingDiv = -1;
                for (int i = 0; i < colDiv.length; i++) {
                    if (Math.abs(px - colDiv[i]) <= GRAB_W && py >= HEADER_H) {
                        draggingDiv = i;
                        break;
                    }
                }
                // Row selection (only if not grabbing a divider)
                int listY = HEADER_H + COL_HDR_H;
                if (draggingDiv < 0 && py >= listY) {
                    int idx = (py - listY) / ROW_H + scrollOffset;
                    if (idx >= 0 && idx < deviceList.size()) {
                        selectedIndex = idx;
                    }
                }
            }
            case MOUSE_DRAG_PX -> {
                if (draggingDiv >= 0) {
                    int px = event.getInt(1);
                    int lo = (draggingDiv == 0) ? MIN_COL_W : colDiv[draggingDiv - 1] + MIN_COL_W;
                    int hi = (draggingDiv < colDiv.length - 1) ? colDiv[draggingDiv + 1] - MIN_COL_W : 640 - MIN_COL_W;
                    colDiv[draggingDiv] = Math.max(lo, Math.min(hi, px));
                }
            }
            case MOUSE_UP -> draggingDiv = -1;
            case MOUSE_SCROLL -> {
                int dir = event.getInt(0);
                scrollOffset = Math.max(0, scrollOffset + dir);
            }
            case KEY -> {
                int key = event.getInt(0);
                if (key == 265) { // UP
                    selectedIndex = Math.max(0, selectedIndex - 1);
                } else if (key == 264) { // DOWN
                    selectedIndex = Math.min(deviceList.size() - 1, selectedIndex + 1);
                }
            }
            default -> {}
        }
    }

    @Override
    public void render(TerminalBuffer buf) {
        // fallback — not used in pixel mode
    }

    @Override
    public void renderGraphics(PixelBuffer pb) {
        setCursorInfo(0, 0, false);
        // Use full buffer; the window manager blit clips to actual window body
        int w = pb.getWidth();
        int h = pb.getHeight();

        // Auto-size columns proportionally on first render
        if (!colsInitialized) {
            colDiv[0] = w * 30 / 100;   // ~30% Type
            colDiv[1] = w * 52 / 100;   // ~52% Type+ID
            colDiv[2] = w * 77 / 100;   // ~77% Type+ID+Dist
            colsInitialized = true;
        }
        // Clamp dividers to visible area
        for (int i = 0; i < colDiv.length; i++) {
            colDiv[i] = Math.min(colDiv[i], w - MIN_COL_W);
        }

        // Derive column positions from dividers
        int typeX = 4;
        int idX   = colDiv[0] + 4;
        int distX = colDiv[1] + 4;
        int sigX  = colDiv[2] + 4;
        int typeW = colDiv[0] - typeX - 2;
        int idW   = colDiv[1] - idX - 2;
        int distW = colDiv[2] - distX - 2;
        int sigW  = w - sigX - 2;

        // Background
        pb.fillRect(0, 0, w, h, BG);

        // ── Status bar (top, two text rows @ 16px each + padding) ──
        pb.fillRect(0, 0, w, HEADER_H, HEADER_BG);

        // Bluetooth icon
        drawBluetoothSymbol(pb, 4, 2);

        // Row 1: title + device count (y=2)
        pb.drawString(22, 2, "Bluetooth Manager", ACCENT);
        String countStr = deviceList.size() + " device" + (deviceList.size() != 1 ? "s" : "");
        pb.drawString(w - countStr.length() * 8 - 4, 2, countStr, CYAN);

        // Row 2: channel + ID (y=19, past first 16px row)
        String myChannel = "CH " + os.getBluetoothChannel();
        pb.drawString(22, 19, myChannel, TEXT_DIM);
        String myId = "ID: " + os.getComputerId().toString().substring(0, 8);
        pb.drawString(w - myId.length() * 8 - 4, 19, myId, TEXT_DIM);

        // ── Column header band ──
        int colY = HEADER_H;
        pb.fillRect(0, colY, w, COL_HDR_H, 0xFF222233);
        drawClipped(pb, typeX, colY, "Type", TEXT_HEAD, typeW);
        drawClipped(pb, idX,   colY, "Device ID", TEXT_HEAD, idW);
        drawClipped(pb, distX, colY, "Dist", TEXT_HEAD, distW);
        drawClipped(pb, sigX,  colY, "Signal", TEXT_HEAD, sigW);
        for (int div : colDiv) pb.drawVLine(div, colY, colY + COL_HDR_H, 0xFF3A3A4A);

        // ── Device rows ──
        int listY = colY + COL_HDR_H;
        int maxVisible = (h - listY - 16) / ROW_H;
        for (int i = 0; i < maxVisible && (i + scrollOffset) < deviceList.size(); i++) {
            int idx = i + scrollOffset;
            DeviceRow dev = deviceList.get(idx);
            int rowY = listY + i * ROW_H;

            // Row background
            int bg = (idx == selectedIndex) ? ROW_SEL : (idx % 2 == 0 ? ROW_EVEN : ROW_ODD);
            pb.fillRect(0, rowY, w, ROW_H, bg);

            // Type icon (colored dot)
            int dotColor = colorForType(dev.type);
            pb.fillRect(typeX, rowY + 5, 6, 6, dotColor);

            // Type name
            drawClipped(pb, typeX + 9, rowY, dev.type, TEXT_NORM, typeW - 9);

            // Short device ID
            drawClipped(pb, idX, rowY, dev.shortId, TEXT_DIM, idW);

            // Distance
            String distStr = dev.distance < 0 ? "cross-dim" : String.format("%.1fm", dev.distance);
            drawClipped(pb, distX, rowY, distStr, TEXT_DIM, distW);

            // Signal strength bars
            drawSignalBars(pb, sigX, rowY + 2, dev.distance);

            // Column dividers in rows
            for (int div : colDiv) pb.drawVLine(div, rowY, rowY + ROW_H, 0xFF2A2A3A);
        }

        // Divider drag handles
        if (draggingDiv >= 0) {
            int dx = colDiv[draggingDiv];
            pb.drawVLine(dx, HEADER_H, h - 16, ACCENT);
        }

        // Empty state
        if (deviceList.isEmpty()) {
            pb.drawString(w / 2 - 76, h / 2 - 4, "No devices detected", TEXT_DIM);
            pb.drawString(w / 2 - 120, h / 2 + 16, "Place blocks within range", TEXT_DIM);
        }

        // ── Bottom info bar ──
        if (selectedIndex >= 0 && selectedIndex < deviceList.size()) {
            DeviceRow sel = deviceList.get(selectedIndex);
            int barY = h - 16;
            pb.fillRect(0, barY, w, 16, HEADER_BG);
            String detail = sel.type + " | " + sel.fullId.toString().substring(0, 8) + " | CH " + sel.channel;
            drawClipped(pb, 4, barY, detail, TEXT_DIM, w - 8);
        }
    }

    /** Draw text clipped to a maximum pixel width */
    private void drawClipped(PixelBuffer pb, int x, int y, String s, int color, int maxW) {
        int maxChars = maxW / 8;
        if (maxChars <= 0) return;
        pb.drawString(x, y, s.length() > maxChars ? s.substring(0, maxChars) : s, color);
    }

    /** Draw signal strength bars (4 bars, filled based on distance) */
    private void drawSignalBars(PixelBuffer pb, int x, int y, double distance) {
        int bars;
        int color;
        if (distance < 0) {
            bars = 4; color = CYAN; // unlimited-range = full signal
        } else if (distance <= 5) {
            bars = 4; color = GREEN;
        } else if (distance <= 10) {
            bars = 3; color = GREEN;
        } else if (distance <= 13) {
            bars = 2; color = YELLOW;
        } else {
            bars = 1; color = RED;
        }
        for (int i = 0; i < 4; i++) {
            int barH = 3 + i * 2;
            int barX = x + i * 5;
            int barY = y + (10 - barH);
            int c = (i < bars) ? color : 0xFF333344;
            pb.fillRect(barX, barY, 3, barH, c);
        }
    }

    /** Draw the Bluetooth rune (≈ 14x14) */
    private void drawBluetoothSymbol(PixelBuffer pb, int x, int y) {
        int c = ACCENT;
        // Vertical center line
        pb.drawVLine(x + 6, y + 1, y + 13, c);
        // Top-right arrow
        pb.drawLine(x + 6, y + 1, x + 10, y + 5, c);
        pb.drawLine(x + 10, y + 5, x + 6, y + 7, c);
        // Bottom-right arrow
        pb.drawLine(x + 6, y + 7, x + 10, y + 9, c);
        pb.drawLine(x + 10, y + 9, x + 6, y + 13, c);
        // Left cross-lines
        pb.drawLine(x + 2, y + 4, x + 6, y + 7, c);
        pb.drawLine(x + 2, y + 10, x + 6, y + 7, c);
    }

    /** Color for device type dot */
    private int colorForType(String type) {
        return switch (type) {
            case "Computer"         -> 0xFF5588DD;
            case "GPS Satellite"    -> 0xFF44DD88;
            case "Scanner"          -> 0xFFDD8844;
            case "Tape Drive"       -> 0xFF9966CC;
            case "Printer"          -> 0xFFDDDDDD;
            case "Charging Station" -> 0xFFDDDD44;
            case "Peripheral"       -> 0xFF66AACC;
            case "Robot"            -> 0xFFDD4466;
            case "Drone"            -> 0xFF44CCDD;
            default -> TEXT_DIM;
        };
    }
}

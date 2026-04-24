package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DroneProgram — top-down minimap app for commanding drone fleets via Bluetooth.
 *
 * Renders a 128×128 heightmap-sampled minimap centered on the host computer,
 * overlays every drone registered on the OS's current Bluetooth channel, and
 * translates map clicks into "drone:waypoint:x:y:z" broadcasts. If a drone is
 * currently selected, commands target that drone only; otherwise they broadcast
 * to all drones on the channel.
 *
 * Controls:
 *   Left-click map       → send selected drone (or all) to that block
 *   Right-click map      → return all drones home
 *   Click a drone dot    → select that drone (cycles through overlapping ones)
 *   Left-click toolbar   → tool buttons (Home / Clear / Hover / Rescan)
 */
public class DroneProgram extends OSProgram {

    // Layout (640×400 pixel canvas).
    private static final int MAP_X = 8;
    private static final int MAP_Y = 24;
    private static final int MAP_SIZE = 320; // 320x320 map area
    private static final int TILE_PX = 4;    // 1 map pixel = 4 screen pixels
    private static final int MAP_TILES = MAP_SIZE / TILE_PX; // 80x80 map samples

    // Sidebar (right side).
    private static final int SIDEBAR_X = MAP_X + MAP_SIZE + 8;
    private static final int SIDEBAR_W = 640 - SIDEBAR_X - 8;

    // Toolbar y.
    private static final int TOOLBAR_Y = MAP_Y + MAP_SIZE + 8;

    // Colors (ARGB) — brightened palette.
    private static final int COL_BG = 0xFF1c2430;
    private static final int COL_PANEL = 0xFF2c3a4a;
    private static final int COL_BORDER = 0xFF5a7088;
    private static final int COL_TEXT = 0xFFf4f8fc;
    private static final int COL_DIM = 0xFFa8b8c8;
    private static final int COL_DRONE_IDLE = 0xFF30ff60;   // bright green
    private static final int COL_DRONE_MOVING = 0xFFffd040;
    private static final int COL_DRONE_LOW = 0xFFff5040;
    private static final int COL_DRONE_DEF = 0xFF60b8ff;
    private static final int COL_DRONE_SEL = 0xFFffffff;
    private static final int COL_ME = 0xFF60ffff;
    private static final int COL_BTN = 0xFF44607c;
    private static final int COL_BTN_HOT = 0xFF6088b4;

    // Terrain cache: sampled block colors, regenerated lazily.
    private int[][] mapTiles = new int[MAP_TILES][MAP_TILES];
    private BlockPos lastSampleOrigin = null;
    private int tileRefreshCursor = 0;
    private static final int TILES_PER_TICK = 64;

    // Drone tracking.
    private final List<DroneBlip> drones = new ArrayList<>();
    private UUID selectedDrone = null;
    private int hoverToolbarIdx = -1;

    private int mapBlockScale = 2; // 1 map-tile = `mapBlockScale` world blocks

    public DroneProgram() {
        super("Drones");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
    }

    @Override
    public boolean tick() {
        // Refresh terrain a few tiles per tick to amortise cost.
        sampleTerrainIncremental();
        // Rescan drone registry every tick (cheap — in-memory list).
        refreshDrones();
        return running;
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case MOUSE_CLICK_PX -> handlePixelClick(event.getInt(0), event.getInt(1), event.getInt(2));
            case MOUSE_SCROLL -> handleScroll(event.getInt(0));
            case KEY -> {
                if (event.getInt(0) == 256) running = false; // ESC
            }
            default -> {}
        }
    }

    /** Scroll up = zoom in (smaller scale), scroll down = zoom out. */
    private void handleScroll(int dir) {
        int old = mapBlockScale;
        if (dir > 0) {
            if (mapBlockScale > 1) mapBlockScale /= 2;
        } else if (dir < 0) {
            if (mapBlockScale < 16) mapBlockScale *= 2;
        }
        if (mapBlockScale != old) {
            lastSampleOrigin = null; // force terrain rescan at new scale
            tileRefreshCursor = 0;
        }
    }

    // ── Input ─────────────────────────────────────────────────────────

    private void handlePixelClick(int button, int px, int py) {
        // Toolbar buttons first.
        int btnW = 80, btnH = 18, btnGap = 6;
        String[] labels = { "Home", "Clear", "Hover", "Zoom", "Rescan" };
        for (int i = 0; i < labels.length; i++) {
            int bx = MAP_X + i * (btnW + btnGap);
            if (px >= bx && px < bx + btnW && py >= TOOLBAR_Y && py < TOOLBAR_Y + btnH) {
                handleToolbar(labels[i]);
                return;
            }
        }

        // Map area.
        if (px >= MAP_X && px < MAP_X + MAP_SIZE && py >= MAP_Y && py < MAP_Y + MAP_SIZE) {
            int tileX = (px - MAP_X) / TILE_PX;
            int tileZ = (py - MAP_Y) / TILE_PX;
            BlockPos world = tileToWorld(tileX, tileZ);

            // Select a drone if clicked on one (within 2 tiles).
            DroneBlip nearest = null;
            double bestDist = 2.5 * 2.5;
            for (DroneBlip d : drones) {
                int[] t = worldToTile(d.pos);
                double dx = t[0] - tileX, dz = t[1] - tileZ;
                double dist = dx * dx + dz * dz;
                if (dist < bestDist) { bestDist = dist; nearest = d; }
            }
            if (nearest != null) {
                selectedDrone = nearest.id;
                return;
            }

            // Left = waypoint, right = home.
            if (button == 1) {
                broadcast("drone:home");
            } else {
                broadcast("drone:waypoint:" + world.getX() + ":" + world.getY() + ":" + world.getZ());
            }
            return;
        }

        // Sidebar drone-list clicks.
        int rowY = MAP_Y;
        for (DroneBlip d : drones) {
            if (px >= SIDEBAR_X && px < SIDEBAR_X + SIDEBAR_W
                    && py >= rowY && py < rowY + 14) {
                selectedDrone = d.id;
                return;
            }
            rowY += 14;
        }
    }

    private void handleToolbar(String label) {
        switch (label) {
            case "Home" -> broadcast("drone:home");
            case "Clear" -> broadcast("drone:clear");
            case "Hover" -> broadcast("drone:hover:true");
            case "Zoom" -> mapBlockScale = (mapBlockScale >= 8) ? 1 : mapBlockScale * 2;
            case "Rescan" -> {
                lastSampleOrigin = null;
                tileRefreshCursor = 0;
            }
        }
    }

    private void broadcast(String msg) {
        Level lvl = os.getLevel();
        BlockPos pos = os.getBlockPos();
        if (lvl == null || pos == null) return;
        if (selectedDrone != null) {
            BluetoothNetwork.send(lvl, pos, selectedDrone, msg);
        } else {
            BluetoothNetwork.broadcast(lvl, pos, os.getBluetoothChannel(), msg);
        }
    }

    // ── Data ──────────────────────────────────────────────────────────

    private void refreshDrones() {
        drones.clear();
        Level lvl = os.getLevel();
        BlockPos origin = os.getBlockPos();
        if (lvl == null || origin == null) return;

        List<BluetoothNetwork.DeviceEntry> all =
                BluetoothNetwork.getDevicesOnChannel(lvl, os.getBluetoothChannel());
        // Map entity lookup by UUID — drones in the world list matching registered UUIDs.
        Map<UUID, DroneEntity> byId = new HashMap<>();
        double scanR = MAP_TILES * mapBlockScale * 1.5;
        net.minecraft.world.phys.AABB aabb =
                new net.minecraft.world.phys.AABB(origin).inflate(scanR, 64, scanR);
        for (DroneEntity d : lvl.getEntitiesOfClass(DroneEntity.class, aabb)) {
            byId.put(d.getUUID(), d);
        }
        for (BluetoothNetwork.DeviceEntry e : all) {
            if (e.type() != BluetoothNetwork.DeviceType.DRONE) continue;
            DroneEntity de = byId.get(e.deviceId());
            DroneBlip b = new DroneBlip();
            b.id = e.deviceId();
            b.pos = e.pos();
            if (de != null) {
                b.fuel = de.getFuel();
                b.defender = de.isDefender();
                b.group = de.getSwarmGroup();
                b.variant = de.getVariant().name();
                b.hasTarget = !de.getSwarmGroup().isEmpty() || de.isDefender(); // crude activity hint
            }
            drones.add(b);
        }
    }

    private void sampleTerrainIncremental() {
        Level lvl = os.getLevel();
        BlockPos origin = os.getBlockPos();
        if (lvl == null || origin == null) return;
        // If we moved far enough, wipe cache.
        if (lastSampleOrigin == null
                || Math.abs(origin.getX() - lastSampleOrigin.getX()) > MAP_TILES * mapBlockScale / 4
                || Math.abs(origin.getZ() - lastSampleOrigin.getZ()) > MAP_TILES * mapBlockScale / 4) {
            lastSampleOrigin = origin;
            tileRefreshCursor = 0;
            for (int[] row : mapTiles) java.util.Arrays.fill(row, 0);
        }
        int total = MAP_TILES * MAP_TILES;
        for (int n = 0; n < TILES_PER_TICK && tileRefreshCursor < total; n++, tileRefreshCursor++) {
            int tx = tileRefreshCursor % MAP_TILES;
            int tz = tileRefreshCursor / MAP_TILES;
            BlockPos wp = tileToWorld(tx, tz);
            // World-surface Y via heightmap (safe + cheap).
            int surfaceY;
            try {
                surfaceY = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wp.getX(), wp.getZ());
            } catch (Exception ex) {
                surfaceY = origin.getY();
            }
            BlockPos sample = new BlockPos(wp.getX(), Math.max(0, surfaceY - 1), wp.getZ());
            BlockState state = lvl.getBlockState(sample);
            mapTiles[tz][tx] = blockToMapColor(state, surfaceY - origin.getY());
        }
    }

    private BlockPos tileToWorld(int tx, int tz) {
        BlockPos origin = os.getBlockPos();
        if (origin == null) return BlockPos.ZERO;
        int half = MAP_TILES / 2;
        int wx = origin.getX() + (tx - half) * mapBlockScale;
        int wz = origin.getZ() + (tz - half) * mapBlockScale;
        return new BlockPos(wx, origin.getY(), wz);
    }

    private int[] worldToTile(BlockPos p) {
        BlockPos origin = os.getBlockPos();
        if (origin == null) return new int[]{-1, -1};
        int half = MAP_TILES / 2;
        int tx = (p.getX() - origin.getX()) / mapBlockScale + half;
        int tz = (p.getZ() - origin.getZ()) / mapBlockScale + half;
        return new int[]{tx, tz};
    }

    private int blockToMapColor(BlockState state, int altitudeOffset) {
        if (state.isAir()) return 0xFF1c1c24;
        // Pick a base color by block registry name, then modulate by altitude.
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        int base;
        if (state.getFluidState().isEmpty()) {
            base = switch (name) {
                case "grass_block", "short_grass", "tall_grass" -> 0xFF3a8c3a;
                case "sand", "sandstone" -> 0xFFc9b27a;
                case "stone", "cobblestone", "andesite", "granite", "diorite", "deepslate" -> 0xFF6a6a70;
                case "dirt", "coarse_dirt", "podzol" -> 0xFF7a5a3a;
                case "snow", "snow_block", "powder_snow" -> 0xFFeaf0f4;
                case "gravel" -> 0xFF8a8a8a;
                case "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log", "mangrove_log" -> 0xFF5a3a22;
                case "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves" -> 0xFF2a6a2a;
                case "netherrack" -> 0xFF6a1a1a;
                case "end_stone" -> 0xFFdcd6a0;
                case "lava" -> 0xFFe25c12;
                default -> 0xFF5a6068;
            };
        } else if (state == Blocks.LAVA.defaultBlockState()
                || name.contains("lava")) {
            base = 0xFFe25c12;
        } else {
            base = 0xFF2060a8; // water
        }
        // Simple shading by altitude relative to computer.
        int shade = Math.max(-60, Math.min(60, altitudeOffset * 3));
        return shadeColor(base, shade);
    }

    private int shadeColor(int argb, int shade) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.max(0, Math.min(255, ((argb >> 16) & 0xFF) + shade));
        int g = Math.max(0, Math.min(255, ((argb >> 8) & 0xFF) + shade));
        int b = Math.max(0, Math.min(255, (argb & 0xFF) + shade));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Render ────────────────────────────────────────────────────────

    @Override
    public void render(TerminalBuffer buffer) {
        // Fallback text mode for when rendered without graphics.
        buffer.writeAt(0, 0, "Drone Map — graphical mode required");
        buffer.writeAt(0, 1, "Drones registered: " + drones.size());
        int i = 2;
        for (DroneBlip d : drones) {
            if (i > 20) break;
            buffer.writeAt(0, i++, d.id.toString().substring(0, 8)
                    + " @ " + d.pos.getX() + "," + d.pos.getY() + "," + d.pos.getZ()
                    + " fuel=" + d.fuel);
        }
    }

    @Override
    public void renderGraphics(PixelBuffer pb) {
        pb.clear(COL_BG);

        // Header.
        pb.drawString(MAP_X, 6, "Drone Fleet Control", COL_TEXT);
        pb.drawStringRight(SIDEBAR_X + SIDEBAR_W, 6,
                "ch=" + os.getBluetoothChannel() + "  scale=1:" + mapBlockScale, COL_DIM);

        // Map area border.
        pb.fillRect(MAP_X - 1, MAP_Y - 1, MAP_SIZE + 2, MAP_SIZE + 2, COL_BORDER);
        pb.fillRect(MAP_X, MAP_Y, MAP_SIZE, MAP_SIZE, COL_PANEL);

        // Terrain tiles.
        for (int tz = 0; tz < MAP_TILES; tz++) {
            for (int tx = 0; tx < MAP_TILES; tx++) {
                int c = mapTiles[tz][tx];
                if (c == 0) continue;
                pb.fillRect(MAP_X + tx * TILE_PX, MAP_Y + tz * TILE_PX, TILE_PX, TILE_PX, c);
            }
        }

        // Crosshair / grid every 16 blocks.
        int step = 16 / Math.max(1, mapBlockScale);
        if (step >= 4) {
            for (int i = 0; i < MAP_TILES; i += step) {
                pb.drawHLine(MAP_X, MAP_X + MAP_SIZE - 1, MAP_Y + i * TILE_PX, 0x40ffffff);
                pb.drawVLine(MAP_X + i * TILE_PX, MAP_Y, MAP_Y + MAP_SIZE - 1, 0x40ffffff);
            }
        }

        // "You are here" marker — centre of map.
        int cx = MAP_X + MAP_SIZE / 2;
        int cy = MAP_Y + MAP_SIZE / 2;
        pb.drawRect(cx - 3, cy - 3, 7, 7, COL_ME);
        pb.setPixel(cx, cy, COL_ME);

        // Drone blips — bright green dot with black outline for readability.
        for (DroneBlip d : drones) {
            int[] t = worldToTile(d.pos);
            if (t[0] < 0 || t[0] >= MAP_TILES || t[1] < 0 || t[1] >= MAP_TILES) continue;
            int dx = MAP_X + t[0] * TILE_PX + TILE_PX / 2;
            int dy = MAP_Y + t[1] * TILE_PX + TILE_PX / 2;
            int col = droneColor(d);
            // Black outline (8px square) so the dot pops against any terrain.
            pb.fillRect(dx - 4, dy - 4, 9, 9, 0xFF000000);
            // Colored dot.
            pb.fillRect(dx - 3, dy - 3, 7, 7, col);
            // Bright inner pixel.
            pb.fillRect(dx - 1, dy - 1, 3, 3, 0xFFffffff);
            if (d.id.equals(selectedDrone)) {
                pb.drawRect(dx - 6, dy - 6, 13, 13, COL_DRONE_SEL);
                pb.drawRect(dx - 5, dy - 5, 11, 11, COL_DRONE_SEL);
            }
        }

        // Toolbar.
        int btnW = 80, btnH = 18, btnGap = 6;
        String[] labels = { "Home", "Clear", "Hover", "Zoom", "Rescan" };
        for (int i = 0; i < labels.length; i++) {
            int bx = MAP_X + i * (btnW + btnGap);
            pb.fillRect(bx, TOOLBAR_Y, btnW, btnH, COL_BTN);
            pb.drawRect(bx, TOOLBAR_Y, btnW, btnH, COL_BORDER);
            pb.drawStringCentered(bx, btnW, TOOLBAR_Y + 3, labels[i], COL_TEXT);
        }

        // Sidebar drone list.
        pb.fillRect(SIDEBAR_X - 1, MAP_Y - 1, SIDEBAR_W + 2, MAP_SIZE + 2, COL_BORDER);
        pb.fillRect(SIDEBAR_X, MAP_Y, SIDEBAR_W, MAP_SIZE, COL_PANEL);
        pb.drawString(SIDEBAR_X + 4, MAP_Y + 2, "Drones (" + drones.size() + ")", 0xFF60ffff);
        int rowY = MAP_Y + 18;
        for (DroneBlip d : drones) {
            if (rowY > MAP_Y + MAP_SIZE - 14) break;
            boolean sel = d.id.equals(selectedDrone);
            if (sel) pb.fillRect(SIDEBAR_X + 2, rowY - 2, SIDEBAR_W - 4, 14, 0xFF2c4660);
            int col = droneColor(d);
            pb.fillRect(SIDEBAR_X + 6, rowY + 2, 6, 6, col);
            String line = d.id.toString().substring(0, 6)
                    + " " + d.variant
                    + (d.group.isEmpty() ? "" : "[" + d.group + "]")
                    + " F" + d.fuel;
            pb.drawString(SIDEBAR_X + 16, rowY, line.substring(0, Math.min(line.length(), 18)),
                    sel ? 0xFFffffff : COL_TEXT);
            rowY += 14;
        }
        if (drones.isEmpty()) {
            pb.drawString(SIDEBAR_X + 4, MAP_Y + 24, "No drones on this", COL_DIM);
            pb.drawString(SIDEBAR_X + 4, MAP_Y + 38, "channel.", COL_DIM);
        }

        // Footer help.
        pb.drawString(MAP_X, TOOLBAR_Y + 24,
                "L-click: waypoint | R-click: home | click dot: select | scroll: zoom | ESC: exit", COL_TEXT);
    }

    private int droneColor(DroneBlip d) {
        if (d.fuel > 0 && d.fuel < 120) return COL_DRONE_LOW; // only critical fuel = red (~6s)
        if (d.defender) return COL_DRONE_DEF;
        if (d.hasTarget) return COL_DRONE_MOVING;
        return COL_DRONE_IDLE; // bright green — default
    }

    private static class DroneBlip {
        UUID id;
        BlockPos pos;
        int fuel;
        boolean defender;
        boolean hasTarget;
        String group = "";
        String variant = "STANDARD";
    }
}

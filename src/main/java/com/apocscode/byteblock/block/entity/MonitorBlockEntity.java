package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.MonitorBlock;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class MonitorBlockEntity extends BlockEntity {

    // Formation data
    private BlockPos originPos;
    private int multiWidth = 1;
    private int multiHeight = 1;
    private int offsetX = 0;
    private int offsetY = 0;

    // Linked computer position
    private BlockPos linkedComputerPos;

    // Bluetooth registration
    private UUID deviceId = UUID.randomUUID();

    // Display mode: "mirror" (default), "test:<pattern>", or "text" (driven by peripheral API)
    private String displayMode = "mirror";

    private int autoDetectCooldown = 0;

    // ── Text-buffer state (used when displayMode == "text") ──
    public static final int TEXT_COLS = 80;
    public static final int TEXT_ROWS = 25;
    private final char[] textChars = new char[TEXT_COLS * TEXT_ROWS];
    private final byte[] textFg    = new byte[TEXT_COLS * TEXT_ROWS]; // palette idx 0..15
    private final byte[] textBg    = new byte[TEXT_COLS * TEXT_ROWS];
    private int  termCursorX = 0, termCursorY = 0;
    private int  termFg = 0;   // white  (palette index 0)
    private int  termBg = 15;  // black  (palette index 15)
    private double textScale = 1.0;
    /** Bumped on any change to the text buffer (origin-only). Drives renderer cache invalidation. */
    private long textVersion = 1L;
    private long lastSyncedTextVersion = 0L;
    /** Set by client/server when a player right-clicks the screen in text mode. */
    private int  lastTouchX = -1, lastTouchY = -1;
    private long lastTouchVersion = 0L;
    {
        java.util.Arrays.fill(textChars, ' ');
        java.util.Arrays.fill(textFg, (byte) 0);   // white
        java.util.Arrays.fill(textBg, (byte) 15);  // black
    }

    // ── Graphics-buffer state (used when displayMode == "graphics") ──
    public static final int GFX_W = 160;
    public static final int GFX_H = 100;
    /** 4-bit packed palette indices, two pixels per byte (low nibble = even x, high = odd x). */
    private final byte[] gfxPixels = new byte[GFX_W * GFX_H / 2];
    private long gfxVersion = 1L;
    private long lastSyncedGfxVersion = 0L;

    // ── Per-monitor 16-color palette (ARGB) ──
    /** Per-monitor palette, ARGB ints, indexable 0..15. Initialized from TerminalBuffer.PALETTE. */
    private final int[] palette = new int[16];
    /** Bumped on any palette change so renderer cache invalidates. */
    private long paletteVersion = 1L;
    {
        System.arraycopy(com.apocscode.byteblock.computer.TerminalBuffer.PALETTE, 0, palette, 0, 16);
    }

    // Last-known label of the linked computer; rendered on the "no signal" tombstone.
    private String lastKnownComputerLabel = "";

    // ── User-assigned label for this monitor (formation-wide; stored on origin) ──
    /**
     * Optional human-readable name set via the shift-click GUI or {@code monitor.setLabel(name)}.
     * Used by the Monitor terminal app to display friendly names and by Lua programs filtering
     * peripherals — e.g. {@code peripheral.find("monitor", function(_, m) return m.getLabel() == "main" end)}.
     * Always read/written through {@link #getOriginEntity()}.
     */
    private String label = "";

    // ── User-assigned bezel/frame color (formation-wide; stored on origin, ARGB) ──
    /**
     * Tint applied to the slab frame and bezel quads. {@code 0xFFFFFFFF} (white) preserves the
     * original gray_concrete/black_concrete textures; any other value multiplies them, allowing
     * players to "paint" the monitor housing from the shift-click config GUI. The screen pixels
     * themselves are NEVER tinted.
     */
    private int frameColor = 0xFFFFFFFF;

    // ── Display geometry (Nuclear-Control-style thin panel + tilt/yaw) ──
    /** Slab thickness in pixels (1..4). 2 = thin panel default. */
    private int   thicknessPx = 2;
    /** Pitch of the rendered screen surface around its horizontal axis, degrees (-45..45). */
    private float tiltDegrees = 0f;
    /** Yaw of the rendered screen surface around its vertical axis, degrees (-45..45). */
    private float yawDegrees  = 0f;

    public MonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MONITOR.get(), pos, state);
        this.originPos = pos;
    }

    // ── Tick (server-side, origin only) ──────────────────────

    public void tick() {
        if (level == null || level.isClientSide()) return;
        if (!worldPosition.equals(originPos)) return;

        // Register on Bluetooth
        BluetoothNetwork.register(level, deviceId, worldPosition, 1,
                BluetoothNetwork.DeviceType.MONITOR);

        // Process BT messages (display mode commands from programs)
        BluetoothNetwork.Message msg = BluetoothNetwork.receive(deviceId);
        while (msg != null) {
            processMessage(msg);
            msg = BluetoothNetwork.receive(deviceId);
        }

        // Auto-detect linked computer
        if (linkedComputerPos == null) {
            if (autoDetectCooldown <= 0) {
                autoDetectCooldown = 40;
                findNearbyComputer();
            } else {
                autoDetectCooldown--;
            }
        }

        // Verify linked computer still exists every 2 seconds
        if (linkedComputerPos != null && level.getGameTime() % 40 == 0) {
            BlockEntity compBe = level.getBlockEntity(linkedComputerPos);
            if (!(compBe instanceof ComputerBlockEntity computer)) {
                linkedComputerPos = null;
                syncToClient();
            } else {
                String lbl = computer.getOS().getLabel();
                if (lbl == null) lbl = "";
                if (!lbl.equals(lastKnownComputerLabel)) {
                    lastKnownComputerLabel = lbl;
                    syncToClient();
                }
            }
        }
    }

    private void findNearbyComputer() {
        Direction facing = getBlockState().getValue(MonitorBlock.FACING);
        // Check all formation members' adjacent blocks
        for (int ox = 0; ox < multiWidth; ox++) {
            for (int oy = 0; oy < multiHeight; oy++) {
                BlockPos memberPos = getWorldPosForOffset(ox, oy);
                for (Direction dir : Direction.values()) {
                    BlockPos adj = memberPos.relative(dir);
                    if (level.getBlockEntity(adj) instanceof ComputerBlockEntity) {
                        linkedComputerPos = adj;
                        syncToClient();
                        return;
                    }
                }
            }
        }
    }

    private void processMessage(BluetoothNetwork.Message msg) {
        String content = msg.content();
        if (content.startsWith("display_mode:")) {
            // Format: "display_mode:mirror" or "display_mode:test:bars" etc.
            String mode = content.substring("display_mode:".length());
            setDisplayMode(mode);
        } else if (content.startsWith("config:")) {
            // Format: "config:tilt=15.0,yaw=-10.0,thickness=2" — any subset of keys allowed.
            applyConfigString(content.substring("config:".length()));
        } else if (content.startsWith("link:")) {
            // Format: "link:x,y,z" — sets the linked computer position for mirror mode
            try {
                String[] parts = content.substring(5).split(",");
                if (parts.length == 3) {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    linkedComputerPos = new BlockPos(x, y, z);
                    syncToClient();
                }
            } catch (NumberFormatException ignored) {}
        } else if (content.startsWith("label:")) {
            // Format: "label:<name>" — sets the user-assigned label (formation-wide)
            setLabel(content.substring("label:".length()));
        }
    }

    // --- Display mode ---

    public String getDisplayMode() { return displayMode; }

    public void setDisplayMode(String mode) {
        if (mode == null || mode.isEmpty()) mode = "mirror";
        this.displayMode = mode;
        syncToClient();
    }

    public UUID getDeviceId() { return deviceId; }

    public BlockPos getWorldPosForOffset(int ox, int oy) {
        if (originPos == null) return worldPosition;
        Direction facing = getBlockState().getValue(MonitorBlock.FACING);
        return switch (facing) {
            case NORTH -> originPos.offset(-ox, oy, 0);
            case SOUTH -> originPos.offset(ox, oy, 0);
            case EAST  -> originPos.offset(0, oy, -ox);
            case WEST  -> originPos.offset(0, oy, ox);
            default    -> originPos.offset(ox, oy, 0);
        };
    }

    // ── Formation management ─────────────────────────────────

    public void setFormation(BlockPos origin, int width, int height, int offX, int offY) {
        this.originPos = origin;
        this.multiWidth = width;
        this.multiHeight = height;
        this.offsetX = offX;
        this.offsetY = offY;
        setChanged();
        syncToClient();
    }

    /**
     * Re-detect formation starting from the given position.
     * Flood-fills connected same-facing monitors, validates rectangle, sets formation data.
     */
    public static void reformFormation(Level level, BlockPos startPos) {
        BlockEntity startBe = level.getBlockEntity(startPos);
        if (!(startBe instanceof MonitorBlockEntity)) return;

        BlockState startState = level.getBlockState(startPos);
        if (!(startState.getBlock() instanceof MonitorBlock)) return;
        Direction facing = startState.getValue(MonitorBlock.FACING);

        // Flood fill connected same-facing monitors
        Set<BlockPos> connected = new LinkedHashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        queue.add(startPos);
        connected.add(startPos);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (BlockPos neighbor : getPlaneNeighborPositions(current, facing)) {
                if (connected.contains(neighbor)) continue;
                if (connected.size() >= 100) break; // 10x10 max
                BlockState state = level.getBlockState(neighbor);
                if (state.getBlock() instanceof MonitorBlock
                        && state.getValue(MonitorBlock.FACING) == facing) {
                    connected.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        // Preserve linked computer from any old origin in the group
        BlockPos previousLink = null;
        for (BlockPos p : connected) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof MonitorBlockEntity m && m.linkedComputerPos != null
                    && p.equals(m.originPos)) {
                previousLink = m.linkedComputerPos;
                break;
            }
        }

        // Compute bounding box
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        for (BlockPos p : connected) {
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            int h = getHorizontalCoord(p, facing);
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
        }

        int width = maxH - minH + 1;
        int height = maxY - minY + 1;

        // Validate: must be a filled rectangle and within 10x10
        boolean validRect = (connected.size() == width * height
                && width <= 10 && height <= 10);
        if (validRect) {
            int fixedDepth = getDepthCoord(startPos, facing);
            for (int h = minH; h <= maxH && validRect; h++) {
                for (int y = minY; y <= maxY && validRect; y++) {
                    BlockPos check = makeBlockPos(h, y, fixedDepth, facing);
                    if (!connected.contains(check)) {
                        validRect = false;
                    }
                }
            }
        }

        if (validRect) {
            BlockPos origin = computeOrigin(connected, facing);
            for (BlockPos p : connected) {
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof MonitorBlockEntity monitor) {
                    int ox = computeOffsetX(p, origin, facing);
                    int oy = p.getY() - origin.getY();
                    monitor.setFormation(origin, width, height, ox, oy);
                }
            }
            // Restore linked computer on new origin
            if (previousLink != null) {
                BlockEntity originBe = level.getBlockEntity(origin);
                if (originBe instanceof MonitorBlockEntity originMonitor) {
                    originMonitor.linkedComputerPos = previousLink;
                    originMonitor.syncToClient();
                }
            }
        } else {
            // Not a valid rectangle — each block is its own 1x1 monitor
            for (BlockPos p : connected) {
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof MonitorBlockEntity monitor) {
                    monitor.setFormation(p, 1, 1, 0, 0);
                }
            }
        }
    }

    /** Returns the 4 neighbor positions in the monitor's plane (up/down + horizontal). */
    public static List<BlockPos> getPlaneNeighborPositions(BlockPos pos, Direction facing) {
        List<BlockPos> neighbors = new ArrayList<>(4);
        neighbors.add(pos.above());
        neighbors.add(pos.below());
        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            neighbors.add(pos.east());
            neighbors.add(pos.west());
        } else {
            neighbors.add(pos.north());
            neighbors.add(pos.south());
        }
        return neighbors;
    }

    private static int getHorizontalCoord(BlockPos pos, Direction facing) {
        return (facing == Direction.NORTH || facing == Direction.SOUTH) ? pos.getX() : pos.getZ();
    }

    private static int getDepthCoord(BlockPos pos, Direction facing) {
        return (facing == Direction.NORTH || facing == Direction.SOUTH) ? pos.getZ() : pos.getX();
    }

    private static BlockPos makeBlockPos(int horizontal, int y, int depth, Direction facing) {
        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            return new BlockPos(horizontal, y, depth);
        } else {
            return new BlockPos(depth, y, horizontal);
        }
    }

    /**
     * Compute the origin position (bottom-left when viewed from the front).
     * Bottom = minimum Y. Left depends on facing direction.
     */
    private static BlockPos computeOrigin(Set<BlockPos> blocks, Direction facing) {
        int minY = Integer.MAX_VALUE;
        for (BlockPos p : blocks) {
            minY = Math.min(minY, p.getY());
        }

        BlockPos best = null;
        for (BlockPos p : blocks) {
            if (p.getY() != minY) continue;
            if (best == null) {
                best = p;
                continue;
            }
            int ph = getHorizontalCoord(p, facing);
            int bh = getHorizontalCoord(best, facing);
            // NORTH/EAST: left = higher coordinate; SOUTH/WEST: left = lower coordinate
            boolean isMoreLeft = switch (facing) {
                case NORTH, EAST -> ph > bh;
                case SOUTH, WEST -> ph < bh;
                default -> false;
            };
            if (isMoreLeft) best = p;
        }
        return best;
    }

    private static int computeOffsetX(BlockPos pos, BlockPos origin, Direction facing) {
        return switch (facing) {
            case NORTH -> origin.getX() - pos.getX();
            case SOUTH -> pos.getX() - origin.getX();
            case EAST  -> origin.getZ() - pos.getZ();
            case WEST  -> pos.getZ() - origin.getZ();
            default -> 0;
        };
    }

    // ── Accessors ────────────────────────────────────────────

    public BlockPos getOriginPos() { return originPos; }
    public int getMultiWidth() { return multiWidth; }
    public int getMultiHeight() { return multiHeight; }
    public int getOffsetX() { return offsetX; }
    public int getOffsetY() { return offsetY; }
    public BlockPos getLinkedComputerPos() { return linkedComputerPos; }
    public long getTextVersion() { return textVersion; }
    public char[] getTextChars() { return textChars; }
    public byte[] getTextFg() { return textFg; }
    public byte[] getTextBg() { return textBg; }
    public double getTextScale() { return textScale; }
    public int getLastTouchX() { return lastTouchX; }
    public int getLastTouchY() { return lastTouchY; }
    public String getLastKnownComputerLabel() { return lastKnownComputerLabel; }

    /** Get the user-assigned label for this monitor formation (always read from origin). */
    public String getLabel() {
        MonitorBlockEntity o = getOriginEntity();
        return (o == null) ? "" : (o.label == null ? "" : o.label);
    }

    /**
     * Set the user-assigned label on the origin block entity (label is formation-wide).
     * Empty string clears the label. Server-side only; syncs to clients.
     */
    public void setLabel(String newLabel) {
        if (level == null || level.isClientSide()) return;
        MonitorBlockEntity o = getOriginEntity();
        if (o == null) return;
        String s = newLabel == null ? "" : newLabel.trim();
        if (s.length() > 32) s = s.substring(0, 32);
        if (s.equals(o.label)) return;
        o.label = s;
        o.setChanged();
        o.syncToClient();
    }

    /** Get the user-assigned frame/bezel ARGB color for this monitor formation. */
    public int getFrameColor() {
        MonitorBlockEntity o = getOriginEntity();
        return (o == null) ? 0xFFFFFFFF : o.frameColor;
    }

    /**
     * Set the frame/bezel tint on the origin (formation-wide). Alpha is forced to 0xFF so
     * the housing stays opaque. Server-side only; syncs all formation members.
     */
    public void setFrameColor(int argb) {
        if (level == null || level.isClientSide()) return;
        MonitorBlockEntity o = getOriginEntity();
        if (o == null) return;
        int c = 0xFF000000 | (argb & 0x00FFFFFF);
        if (c == o.frameColor) return;
        o.frameColor = c;
        // Propagate to all formation members so each block syncs the new tint to clients
        // (the renderer reads the tint from the origin, but every BE needs a fresh packet
        // to trigger client cache invalidation in MonitorRenderer).
        for (int ox = 0; ox < o.multiWidth; ox++) {
            for (int oy = 0; oy < o.multiHeight; oy++) {
                BlockPos memberPos = o.getWorldPosForOffset(ox, oy);
                BlockEntity be = level.getBlockEntity(memberPos);
                if (be instanceof MonitorBlockEntity m) {
                    m.setChanged();
                    m.syncToClient();
                }
            }
        }
    }

    public int   getThicknessPx() { return thicknessPx; }
    public float getTiltDegrees() { return tiltDegrees; }
    public float getYawDegrees()  { return yawDegrees; }

    /**
     * Apply a {@code key=value,key=value,...} config string from a Bluetooth
     * "config:" message. Mutates only the addressed fields, propagates to all
     * formation members so render/shape stay coherent, and syncs to clients.
     */
    public void applyConfigString(String body) {
        if (level == null || level.isClientSide() || body == null) return;
        Float  newTilt   = null;
        Float  newYaw    = null;
        Integer newThick = null;
        for (String kv : body.split(",")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq).trim().toLowerCase();
            String v = kv.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "tilt"      -> newTilt  = Float.parseFloat(v);
                    case "yaw"       -> newYaw   = Float.parseFloat(v);
                    case "thickness" -> newThick = Integer.parseInt(v);
                    default -> {}
                }
            } catch (NumberFormatException ignored) {}
        }
        // Walk formation and update every member
        MonitorBlockEntity origin = getOriginEntity();
        if (origin == null) origin = this;
        for (int ox = 0; ox < origin.multiWidth; ox++) {
            for (int oy = 0; oy < origin.multiHeight; oy++) {
                BlockPos memberPos = origin.getWorldPosForOffset(ox, oy);
                BlockEntity be = level.getBlockEntity(memberPos);
                if (be instanceof MonitorBlockEntity m) {
                    if (newTilt  != null) m.tiltDegrees  = clamp(newTilt,  -45f, 45f);
                    if (newYaw   != null) m.yawDegrees   = clamp(newYaw,   -45f, 45f);
                    if (newThick != null) m.thicknessPx  = (int) clamp(newThick, 1, 6);
                    m.setChanged();
                    m.syncToClient();
                }
            }
        }
        // Block shape depends on thickness — refresh collision/raytrace shape
        if (newThick != null && level != null) {
            for (int ox = 0; ox < origin.multiWidth; ox++) {
                for (int oy = 0; oy < origin.multiHeight; oy++) {
                    BlockPos memberPos = origin.getWorldPosForOffset(ox, oy);
                    BlockState st = level.getBlockState(memberPos);
                    level.sendBlockUpdated(memberPos, st, st, 3);
                }
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    public long getGfxVersion() { return gfxVersion; }
    public byte[] getGfxPixels() { return gfxPixels; }
    public long getPaletteVersion() { return paletteVersion; }
    public int  getPaletteARGB(int idx) {
        if (idx < 0 || idx >= 16) return 0xFF000000;
        return palette[idx];
    }
    /**
     * Set palette entry. Accepts a 0xRRGGBB int; alpha is forced to 0xFF. Bumps versions and syncs.
     */
    public void setPaletteColor(int idx, int rgb) {
        if (idx < 0 || idx >= 16) return;
        int argb = 0xFF000000 | (rgb & 0x00FFFFFF);
        if (palette[idx] == argb) return;
        palette[idx] = argb;
        paletteVersion++;
        textVersion++;
        gfxVersion++;
        if (level != null && !level.isClientSide()) {
            lastSyncedTextVersion = textVersion;
            lastSyncedGfxVersion = gfxVersion;
            syncToClient();
        }
    }
    public void resetPalette() {
        boolean changed = false;
        for (int i = 0; i < 16; i++) {
            int argb = com.apocscode.byteblock.computer.TerminalBuffer.PALETTE[i];
            if (palette[i] != argb) { palette[i] = argb; changed = true; }
        }
        if (!changed) return;
        paletteVersion++;
        textVersion++;
        gfxVersion++;
        if (level != null && !level.isClientSide()) {
            lastSyncedTextVersion = textVersion;
            lastSyncedGfxVersion = gfxVersion;
            syncToClient();
        }
    }

    /** Read a 4-bit palette index at (x,y). Returns 0 if OOB. */
    public int gfxGetPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= GFX_W || y >= GFX_H) return 0;
        int idx = y * GFX_W + x;
        byte b = gfxPixels[idx >> 1];
        return ((idx & 1) == 0) ? (b & 0xF) : ((b >> 4) & 0xF);
    }

    public void gfxSetPixel(int x, int y, int paletteIdx) {
        if (x < 0 || y < 0 || x >= GFX_W || y >= GFX_H) return;
        int idx = y * GFX_W + x;
        int v = paletteIdx & 0xF;
        int bi = idx >> 1;
        byte b = gfxPixels[bi];
        if ((idx & 1) == 0) gfxPixels[bi] = (byte)((b & 0xF0) | v);
        else                gfxPixels[bi] = (byte)((b & 0x0F) | (v << 4));
        gfxMarkDirty();
    }

    public void gfxFillRect(int x, int y, int w, int h, int paletteIdx) {
        if (w <= 0 || h <= 0) return;
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(GFX_W, x + w), y1 = Math.min(GFX_H, y + h);
        int v = paletteIdx & 0xF;
        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) {
                int idx = yy * GFX_W + xx;
                int bi = idx >> 1;
                byte b = gfxPixels[bi];
                if ((idx & 1) == 0) gfxPixels[bi] = (byte)((b & 0xF0) | v);
                else                gfxPixels[bi] = (byte)((b & 0x0F) | (v << 4));
            }
        }
        gfxMarkDirty();
    }

    public void gfxClear(int paletteIdx) {
        int v = paletteIdx & 0xF;
        byte fill = (byte)((v << 4) | v);
        java.util.Arrays.fill(gfxPixels, fill);
        gfxMarkDirty();
    }

    private void gfxMarkDirty() {
        gfxVersion++;
        if (level != null && !level.isClientSide()
                && gfxVersion - lastSyncedGfxVersion >= 1
                && level.getGameTime() % 4 == 0) {
            lastSyncedGfxVersion = gfxVersion;
            syncToClient();
        }
    }

    /** Force flush of any pending gfx writes. */
    public void gfxFlush() {
        if (level != null && !level.isClientSide() && gfxVersion != lastSyncedGfxVersion) {
            lastSyncedGfxVersion = gfxVersion;
            syncToClient();
        }
    }


    // ── Lua-side terminal API (called on the origin entity) ──

    private void termMarkDirty() {
        textVersion++;
        if (level != null && !level.isClientSide()) {
            // Throttle network sync to once every 4 ticks (5 Hz) to avoid spam.
            if (textVersion - lastSyncedTextVersion >= 1
                    && level.getGameTime() % 4 == 0) {
                lastSyncedTextVersion = textVersion;
                syncToClient();
            }
        }
    }

    /** Force a sync regardless of throttle (used at end of a Lua call burst). */
    public void termFlush() {
        if (level != null && !level.isClientSide() && textVersion != lastSyncedTextVersion) {
            lastSyncedTextVersion = textVersion;
            syncToClient();
        }
    }

    public void termClear() {
        java.util.Arrays.fill(textChars, ' ');
        java.util.Arrays.fill(textFg, (byte) (termFg & 0xF));
        java.util.Arrays.fill(textBg, (byte) (termBg & 0xF));
        termCursorX = 0; termCursorY = 0;
        termMarkDirty();
    }

    public void termClearLine() {
        if (termCursorY < 0 || termCursorY >= TEXT_ROWS) return;
        int off = termCursorY * TEXT_COLS;
        for (int i = 0; i < TEXT_COLS; i++) {
            textChars[off + i] = ' ';
            textFg[off + i] = (byte) (termFg & 0xF);
            textBg[off + i] = (byte) (termBg & 0xF);
        }
        termMarkDirty();
    }

    public void termSetCursorPos(int x, int y) {
        termCursorX = x; termCursorY = y;
    }

    public int termGetCursorX() { return termCursorX; }
    public int termGetCursorY() { return termCursorY; }

    public void termSetTextColor(int paletteIdx) { termFg = paletteIdx & 0xF; }
    public void termSetBackgroundColor(int paletteIdx) { termBg = paletteIdx & 0xF; }
    public int  termGetTextColor() { return termFg; }
    public int  termGetBackgroundColor() { return termBg; }

    public void termSetTextScale(double s) {
        if (s < 0.5) s = 0.5; else if (s > 5.0) s = 5.0;
        if (s != textScale) { textScale = s; termMarkDirty(); }
    }

    public void termWrite(String s) {
        if (s == null || s.isEmpty()) return;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                termCursorX = 0;
                termCursorY++;
                continue;
            }
            if (c == '\r') { termCursorX = 0; continue; }
            if (termCursorX >= 0 && termCursorX < TEXT_COLS
                    && termCursorY >= 0 && termCursorY < TEXT_ROWS) {
                int off = termCursorY * TEXT_COLS + termCursorX;
                textChars[off] = c;
                textFg[off] = (byte) (termFg & 0xF);
                textBg[off] = (byte) (termBg & 0xF);
            }
            termCursorX++;
        }
        termMarkDirty();
    }

    /** CC blit: per-char palette indices encoded as hex digits (0-9, a-f). */
    public void termBlit(String text, String fgHex, String bgHex) {
        if (text == null || text.isEmpty()) return;
        if (fgHex == null) fgHex = "";
        if (bgHex == null) bgHex = "";
        int n = text.length();
        for (int i = 0; i < n; i++) {
            if (termCursorX < 0 || termCursorX >= TEXT_COLS
                    || termCursorY < 0 || termCursorY >= TEXT_ROWS) {
                termCursorX++;
                continue;
            }
            int fgIdx = hexDigit(i < fgHex.length() ? fgHex.charAt(i) : '0', termFg);
            int bgIdx = hexDigit(i < bgHex.length() ? bgHex.charAt(i) : 'f', termBg);
            int off = termCursorY * TEXT_COLS + termCursorX;
            textChars[off] = text.charAt(i);
            textFg[off] = (byte) (fgIdx & 0xF);
            textBg[off] = (byte) (bgIdx & 0xF);
            termCursorX++;
        }
        termMarkDirty();
    }

    private static int hexDigit(char c, int fallback) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return fallback;
    }

    /** Scroll the buffer vertically by n rows (positive = up). */
    public void termScroll(int n) {
        if (n == 0 || n >= TEXT_ROWS || -n >= TEXT_ROWS) {
            // Beyond buffer: clear everything (preserve current colors)
            for (int i = 0; i < textChars.length; i++) {
                textChars[i] = ' ';
                textFg[i] = (byte) (termFg & 0xF);
                textBg[i] = (byte) (termBg & 0xF);
            }
            termMarkDirty();
            return;
        }
        if (n > 0) {
            // Move row k -> row k-n (for k >= n). Top n rows become blank.
            for (int k = n; k < TEXT_ROWS; k++) {
                System.arraycopy(textChars, k * TEXT_COLS, textChars, (k - n) * TEXT_COLS, TEXT_COLS);
                System.arraycopy(textFg,    k * TEXT_COLS, textFg,    (k - n) * TEXT_COLS, TEXT_COLS);
                System.arraycopy(textBg,    k * TEXT_COLS, textBg,    (k - n) * TEXT_COLS, TEXT_COLS);
            }
            for (int k = TEXT_ROWS - n; k < TEXT_ROWS; k++) {
                int off = k * TEXT_COLS;
                for (int i = 0; i < TEXT_COLS; i++) {
                    textChars[off + i] = ' ';
                    textFg[off + i] = (byte) (termFg & 0xF);
                    textBg[off + i] = (byte) (termBg & 0xF);
                }
            }
        } else {
            int m = -n;
            for (int k = TEXT_ROWS - 1 - m; k >= 0; k--) {
                System.arraycopy(textChars, k * TEXT_COLS, textChars, (k + m) * TEXT_COLS, TEXT_COLS);
                System.arraycopy(textFg,    k * TEXT_COLS, textFg,    (k + m) * TEXT_COLS, TEXT_COLS);
                System.arraycopy(textBg,    k * TEXT_COLS, textBg,    (k + m) * TEXT_COLS, TEXT_COLS);
            }
            for (int k = 0; k < m; k++) {
                int off = k * TEXT_COLS;
                for (int i = 0; i < TEXT_COLS; i++) {
                    textChars[off + i] = ' ';
                    textFg[off + i] = (byte) (termFg & 0xF);
                    textBg[off + i] = (byte) (termBg & 0xF);
                }
            }
        }
        termMarkDirty();
    }

    public void recordTouch(int x, int y) {
        this.lastTouchX = x;
        this.lastTouchY = y;
        this.lastTouchVersion++;
        // Don't sync touch back to client — server-only state
    }

    public MonitorBlockEntity getOriginEntity() {
        if (level == null) return null;
        if (worldPosition.equals(originPos)) return this;
        BlockEntity be = level.getBlockEntity(originPos);
        return (be instanceof MonitorBlockEntity m) ? m : null;
    }

    // ── Client sync ──────────────────────────────────────────

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void syncToClient() {
        if (level != null && !level.isClientSide()) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ── NBT persistence ──────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLong("OriginPos", originPos.asLong());
        tag.putInt("MultiWidth", multiWidth);
        tag.putInt("MultiHeight", multiHeight);
        tag.putInt("OffsetX", offsetX);
        tag.putInt("OffsetY", offsetY);
        if (linkedComputerPos != null) {
            tag.putLong("LinkedComputer", linkedComputerPos.asLong());
        }
        tag.putUUID("DeviceId", deviceId);
        tag.putString("DisplayMode", displayMode);
        // Text buffer (only meaningful when displayMode == "text")
        tag.putString("TermText", new String(textChars));
        tag.putByteArray("TermFg", textFg);
        tag.putByteArray("TermBg", textBg);
        tag.putInt("TermCx", termCursorX);
        tag.putInt("TermCy", termCursorY);
        tag.putInt("TermFgCol", termFg);
        tag.putInt("TermBgCol", termBg);
        tag.putDouble("TermScale", textScale);
        tag.putLong("TermVer", textVersion);
        tag.putByteArray("GfxPx", gfxPixels);
        tag.putLong("GfxVer", gfxVersion);
        tag.putIntArray("Palette", palette);
        tag.putLong("PalVer", paletteVersion);
        if (lastKnownComputerLabel != null && !lastKnownComputerLabel.isEmpty()) {
            tag.putString("LastLabel", lastKnownComputerLabel);
        }
        tag.putInt("ThicknessPx", thicknessPx);
        tag.putFloat("TiltDeg",    tiltDegrees);
        tag.putFloat("YawDeg",     yawDegrees);
        if (label != null && !label.isEmpty()) {
            tag.putString("Label", label);
        }
        tag.putInt("FrameColor", frameColor);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("OriginPos")) originPos = BlockPos.of(tag.getLong("OriginPos"));
        if (tag.contains("MultiWidth")) multiWidth = tag.getInt("MultiWidth");
        if (tag.contains("MultiHeight")) multiHeight = tag.getInt("MultiHeight");
        if (tag.contains("OffsetX")) offsetX = tag.getInt("OffsetX");
        if (tag.contains("OffsetY")) offsetY = tag.getInt("OffsetY");
        if (tag.contains("LinkedComputer")) {
            linkedComputerPos = BlockPos.of(tag.getLong("LinkedComputer"));
        } else {
            linkedComputerPos = null;
        }
        if (tag.hasUUID("DeviceId")) deviceId = tag.getUUID("DeviceId");
        if (tag.contains("DisplayMode")) displayMode = tag.getString("DisplayMode");
        if (tag.contains("TermText")) {
            String s = tag.getString("TermText");
            int n = Math.min(s.length(), textChars.length);
            for (int i = 0; i < n; i++) textChars[i] = s.charAt(i);
        }
        if (tag.contains("TermFg")) {
            byte[] a = tag.getByteArray("TermFg");
            System.arraycopy(a, 0, textFg, 0, Math.min(a.length, textFg.length));
        }
        if (tag.contains("TermBg")) {
            byte[] a = tag.getByteArray("TermBg");
            System.arraycopy(a, 0, textBg, 0, Math.min(a.length, textBg.length));
        }
        if (tag.contains("TermCx")) termCursorX = tag.getInt("TermCx");
        if (tag.contains("TermCy")) termCursorY = tag.getInt("TermCy");
        if (tag.contains("TermFgCol")) termFg = tag.getInt("TermFgCol");
        if (tag.contains("TermBgCol")) termBg = tag.getInt("TermBgCol");
        if (tag.contains("TermScale")) textScale = tag.getDouble("TermScale");
        if (tag.contains("TermVer")) textVersion = tag.getLong("TermVer");
        if (tag.contains("GfxPx")) {
            byte[] a = tag.getByteArray("GfxPx");
            System.arraycopy(a, 0, gfxPixels, 0, Math.min(a.length, gfxPixels.length));
        }
        if (tag.contains("GfxVer")) gfxVersion = tag.getLong("GfxVer");
        if (tag.contains("Palette")) {
            int[] a = tag.getIntArray("Palette");
            System.arraycopy(a, 0, palette, 0, Math.min(a.length, palette.length));
        }
        if (tag.contains("PalVer")) paletteVersion = tag.getLong("PalVer");
        if (tag.contains("LastLabel")) lastKnownComputerLabel = tag.getString("LastLabel");
        if (tag.contains("ThicknessPx")) thicknessPx = (int) clamp(tag.getInt("ThicknessPx"), 1, 6);
        if (tag.contains("TiltDeg"))     tiltDegrees = clamp(tag.getFloat("TiltDeg"), -45f, 45f);
        if (tag.contains("YawDeg"))      yawDegrees  = clamp(tag.getFloat("YawDeg"),  -45f, 45f);
        if (tag.contains("Label"))       label = tag.getString("Label");
        if (tag.contains("FrameColor"))  frameColor = 0xFF000000 | (tag.getInt("FrameColor") & 0x00FFFFFF);
    }
}

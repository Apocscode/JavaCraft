package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.ScannerBlock;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;
import com.apocscode.byteblock.scanner.WorldScanData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * LiDAR Scanner block entity — scans blocks, entities, and players
 * within a configurable radius. Builds a persistent world cache for
 * Lua program queries, robot pathfinding, and drone navigation.
 */
public class ScannerBlockEntity extends BlockEntity {
    private UUID deviceId = UUID.randomUUID();
    private int scanRadius = 48;
    private int entityScanInterval = 20;   // entity scan every 1 second
    private int tickCounter = 0;

    private final WorldScanData scanData = new WorldScanData();

    // Incremental block scanning
    private final Queue<SectionPos> scanQueue = new LinkedList<>();
    private boolean scanning = false;
    private int totalQueuedSections = 0;
    private static final int SECTIONS_PER_TICK = 2;

    public ScannerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SCANNER.get(), pos, state);
    }

    public void serverTick() {
        if (level == null) return;

        BluetoothNetwork.register(level, deviceId, worldPosition, 1,
                BluetoothNetwork.DeviceType.SCANNER);
        scanData.setOrigin(worldPosition);

        tickCounter++;

        // Start initial scan on first tick
        if (tickCounter == 1) startScan();

        // Entity scan every interval
        if (tickCounter % entityScanInterval == 0) {
            scanData.scanEntities(level, worldPosition, scanRadius);
        }

        // Incremental block scanning (2 sections per tick)
        if (scanning && !scanQueue.isEmpty()) {
            for (int i = 0; i < SECTIONS_PER_TICK && !scanQueue.isEmpty(); i++) {
                SectionPos section = scanQueue.poll();
                if (level.hasChunkAt(new BlockPos(
                        section.minBlockX(), section.minBlockY(), section.minBlockZ()))) {
                    scanData.scanSection(level, section);
                }
            }
            if (scanQueue.isEmpty()) {
                scanning = false;
                setChanged();
            }
        }

        // Auto-rescan every 30 seconds
        if (tickCounter % 600 == 0 && tickCounter > 1) {
            startScan();
        }

        // Bluetooth connection check
        if (tickCounter % 20 == 0) {
            boolean connected = BluetoothNetwork.isComputerInRange(level, worldPosition);
            BlockState current = level.getBlockState(worldPosition);
            if (current.getValue(ScannerBlock.CONNECTED) != connected) {
                level.setBlockAndUpdate(worldPosition,
                        current.setValue(ScannerBlock.CONNECTED, connected));
            }
        }
    }

    /**
     * Queue all relevant chunk sections for incremental scanning.
     */
    public void startScan() {
        if (level == null) return;
        scanQueue.clear();

        int sectionRadius = (scanRadius + 15) / 16;
        SectionPos center = SectionPos.of(worldPosition);
        int minSY = level.getMinBuildHeight() >> 4;
        int maxSY = (level.getMaxBuildHeight() - 1) >> 4;
        long rangeThreshold = (long)(scanRadius + 16) * (scanRadius + 16);

        for (int sx = -sectionRadius; sx <= sectionRadius; sx++) {
            for (int sz = -sectionRadius; sz <= sectionRadius; sz++) {
                for (int sy = minSY; sy <= maxSY; sy++) {
                    SectionPos section = SectionPos.of(
                            center.x() + sx, sy, center.z() + sz);
                    BlockPos sectionCenter = new BlockPos(
                            section.minBlockX() + 8,
                            section.minBlockY() + 8,
                            section.minBlockZ() + 8);
                    if (worldPosition.distSqr(sectionCenter) <= rangeThreshold) {
                        scanQueue.add(section);
                    }
                }
            }
        }
        totalQueuedSections = scanQueue.size();
        scanning = true;
    }

    /**
     * Perform an immediate scan for small radius (≤16 blocks).
     * Used by Lua scanner.scan(radius) for quick results.
     */
    public boolean performImmediateScan(int radius) {
        if (level == null) return false;
        int clamped = Math.min(radius, 16);

        int sectionRadius = (clamped + 15) / 16;
        SectionPos center = SectionPos.of(worldPosition);
        int minSY = level.getMinBuildHeight() >> 4;
        int maxSY = (level.getMaxBuildHeight() - 1) >> 4;

        for (int sx = -sectionRadius; sx <= sectionRadius; sx++) {
            for (int sz = -sectionRadius; sz <= sectionRadius; sz++) {
                for (int sy = minSY; sy <= maxSY; sy++) {
                    SectionPos section = SectionPos.of(
                            center.x() + sx, sy, center.z() + sz);
                    BlockPos check = new BlockPos(
                            section.minBlockX(), section.minBlockY(), section.minBlockZ());
                    if (level.hasChunkAt(check)) {
                        scanData.scanSection(level, section);
                    }
                }
            }
        }
        scanData.scanEntities(level, worldPosition, clamped);
        return true;
    }

    // ── Accessors ───────────────────────────────────────────────────

    public UUID getDeviceId() { return deviceId; }
    public WorldScanData getScanData() { return scanData; }
    public int getScanRadius() { return scanRadius; }
    public boolean isScanning() { return scanning; }

    public int getScanProgress() {
        if (!scanning || totalQueuedSections == 0) return 100;
        int remaining = scanQueue.size();
        return Math.max(0, Math.min(100, 100 - (remaining * 100 / totalQueuedSections)));
    }

    public void setScanRadius(int radius) {
        this.scanRadius = Math.clamp(radius, 1, 128);
        setChanged();
    }

    /** Legacy compat for ScannerBlock right-click */
    public int getLastEntityCount() { return scanData.getEntities().size(); }
    public List<String> getLastScanResults() {
        List<String> results = new ArrayList<>();
        for (WorldScanData.EntitySnapshot e : scanData.getEntities()) {
            results.add(String.format("%s:%.1f,%.1f,%.1f",
                    e.type(), e.x(), e.y(), e.z()));
        }
        return results;
    }

    // ── NBT ─────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("DeviceId", deviceId);
        tag.putInt("ScanRadius", scanRadius);
        tag.putInt("EntityScanInterval", entityScanInterval);
        tag.put("ScanData", scanData.save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("DeviceId")) deviceId = tag.getUUID("DeviceId");
        if (tag.contains("ScanRadius")) scanRadius = tag.getInt("ScanRadius");
        if (tag.contains("EntityScanInterval")) entityScanInterval = tag.getInt("EntityScanInterval");
        if (tag.contains("ScanData")) scanData.load(tag.getCompound("ScanData"));
    }
}

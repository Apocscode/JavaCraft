package com.apocscode.javacraft.block.entity;

import com.apocscode.javacraft.init.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Scanner block entity. Periodically scans a radius around itself
 * for entities and blocks. Results are queryable by connected computers.
 */
public class ScannerBlockEntity extends BlockEntity {
    private int scanRadius = 8;
    private int scanInterval = 20; // ticks (1 second)
    private int tickCounter = 0;
    private int lastEntityCount = 0;
    private final List<String> lastScanResults = new ArrayList<>();

    public ScannerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SCANNER.get(), pos, state);
    }

    public void serverTick() {
        tickCounter++;
        if (tickCounter >= scanInterval) {
            tickCounter = 0;
            performScan();
        }
    }

    private void performScan() {
        if (level == null) return;
        lastScanResults.clear();
        AABB area = new AABB(worldPosition).inflate(scanRadius);
        List<Entity> entities = level.getEntities((Entity) null, area, e -> true);
        lastEntityCount = entities.size();
        for (Entity entity : entities) {
            lastScanResults.add(String.format("%s:%.1f,%.1f,%.1f",
                    entity.getType().toShortString(),
                    entity.getX(), entity.getY(), entity.getZ()));
        }
        setChanged();
    }

    public int getLastEntityCount() { return lastEntityCount; }
    public List<String> getLastScanResults() { return List.copyOf(lastScanResults); }
    public int getScanRadius() { return scanRadius; }
    public void setScanRadius(int radius) { this.scanRadius = Math.clamp(radius, 1, 32); setChanged(); }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("ScanRadius", scanRadius);
        tag.putInt("ScanInterval", scanInterval);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("ScanRadius")) scanRadius = tag.getInt("ScanRadius");
        if (tag.contains("ScanInterval")) scanInterval = tag.getInt("ScanInterval");
    }
}

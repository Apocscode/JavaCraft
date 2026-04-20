package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.PeripheralBlock;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Universal Peripheral block entity. Detects adjacent blocks
 * and exposes their capabilities (inventory, energy, fluid, etc.)
 * to connected computers.
 */
public class PeripheralBlockEntity extends BlockEntity {
    private java.util.UUID deviceId = java.util.UUID.randomUUID();
    private String detectedType = "none";

    public PeripheralBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PERIPHERAL.get(), pos, state);
    }

    public void serverTick() {
        if (level != null && !level.isClientSide()) {
            BluetoothNetwork.register(level, deviceId, worldPosition, 1, BluetoothNetwork.DeviceType.PERIPHERAL);
            if (level.getGameTime() % 20 == 0) {
                boolean connected = BluetoothNetwork.isComputerInRange(level, worldPosition);
                BlockState current = level.getBlockState(worldPosition);
                if (current.getValue(PeripheralBlock.CONNECTED) != connected) {
                    level.setBlockAndUpdate(worldPosition, current.setValue(PeripheralBlock.CONNECTED, connected));
                }
            }
        }
    }

    public java.util.UUID getDeviceId() { return deviceId; }

    /**
     * Scans all 6 adjacent blocks and determines what type of peripheral they represent.
     */
    public String getDetectedType() {
        if (level == null) return "none";
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = worldPosition.relative(dir);
            BlockEntity adjacentBE = level.getBlockEntity(adjacent);
            if (adjacentBE != null) {
                // Detect type based on the adjacent block entity
                String name = adjacentBE.getClass().getSimpleName();
                if (name.toLowerCase().contains("chest") || name.toLowerCase().contains("barrel")) {
                    detectedType = "inventory";
                    return detectedType;
                } else if (name.toLowerCase().contains("furnace") || name.toLowerCase().contains("smoker") || name.toLowerCase().contains("blast")) {
                    detectedType = "furnace";
                    return detectedType;
                } else if (adjacentBE instanceof ComputerBlockEntity) {
                    detectedType = "computer";
                    return detectedType;
                } else {
                    detectedType = "generic:" + name;
                    return detectedType;
                }
            }
        }
        detectedType = "none";
        return detectedType;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("DeviceId", deviceId);
        tag.putString("DetectedType", detectedType);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("DeviceId")) deviceId = tag.getUUID("DeviceId");
        if (tag.contains("DetectedType")) detectedType = tag.getString("DetectedType");
    }
}

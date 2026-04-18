package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * GPS block entity. Broadcasts its coordinates on the Bluetooth network
 * at regular intervals. Place 3+ GPS blocks for triangulation.
 */
public class GpsBlockEntity extends BlockEntity {
    private UUID gpsId = UUID.randomUUID();
    private int broadcastInterval = 40; // ticks (2 seconds)
    private int tickCounter = 0;

    public GpsBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GPS.get(), pos, state);
    }

    public void serverTick() {
        tickCounter++;
        if (tickCounter >= broadcastInterval) {
            tickCounter = 0;
            // Broadcast position on Bluetooth channel 65535 (reserved for GPS)
            BluetoothNetwork.broadcast(level, worldPosition, 65535,
                    "GPS:" + gpsId.toString().substring(0, 8) + ":" +
                    worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ());
        }
    }

    public UUID getGpsId() { return gpsId; }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("GpsId", gpsId);
        tag.putInt("BroadcastInterval", broadcastInterval);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("GpsId")) gpsId = tag.getUUID("GpsId");
        if (tag.contains("BroadcastInterval")) broadcastInterval = tag.getInt("BroadcastInterval");
    }
}

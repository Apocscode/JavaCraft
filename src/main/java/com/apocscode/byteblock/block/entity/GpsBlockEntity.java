package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.GpsBlock;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * GPS satellite block entity. Broadcasts its coordinates globally on the
 * Bluetooth network with no distance or dimension restrictions.
 * A single GPS block serves the entire network.
 */
public class GpsBlockEntity extends BlockEntity {
    private UUID gpsId = UUID.randomUUID();
    private int broadcastInterval = 40; // ticks (2 seconds)
    private int tickCounter = 0;

    public GpsBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GPS.get(), pos, state);
    }

    public void serverTick() {
        // Register on the global GPS network every tick (keeps us alive in cleanup)
        String dim = level.dimension().location().toString();
        BluetoothNetwork.registerGps(gpsId, worldPosition, dim, level.getGameTime());
        BluetoothNetwork.register(level, gpsId, worldPosition, 65535, BluetoothNetwork.DeviceType.GPS);

        tickCounter++;
        if (tickCounter >= broadcastInterval) {
            tickCounter = 0;
            // Broadcast position globally — no range or dimension limits
            BluetoothNetwork.broadcastGlobal(65535,
                    "GPS:" + gpsId.toString().substring(0, 8) + ":" +
                    worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ(),
                    worldPosition);
        }

        if (level.getGameTime() % 20 == 0) {
            boolean connected = BluetoothNetwork.isComputerInRange(level, worldPosition);
            BlockState current = level.getBlockState(worldPosition);
            if (current.getValue(GpsBlock.CONNECTED) != connected) {
                level.setBlockAndUpdate(worldPosition, current.setValue(GpsBlock.CONNECTED, connected));
            }
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

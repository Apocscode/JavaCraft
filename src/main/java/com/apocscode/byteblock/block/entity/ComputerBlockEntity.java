package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.ComputerBlock;
import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Block entity for the Computer. Holds the JavaOS instance which manages
 * the terminal, filesystem, processes, and Bluetooth networking.
 */
public class ComputerBlockEntity extends BlockEntity {
    private UUID computerId = UUID.randomUUID();
    private boolean powered = true;
    private JavaOS os;

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COMPUTER.get(), pos, state);
        os = new JavaOS(computerId);
    }

    public void serverTick() {
        if (level != null && !level.isClientSide() && level.getGameTime() % 20 == 0) {
            BlockState current = level.getBlockState(worldPosition);
            if (current.getValue(ComputerBlock.CONNECTED) != powered) {
                level.setBlockAndUpdate(worldPosition, current.setValue(ComputerBlock.CONNECTED, powered));
            }
        }
        if (!powered) return;
        os.setWorldContext(level, worldPosition);
        os.tick();
        if (level != null && !level.isClientSide() && os.getFileSystem().isDirty()) {
            os.getFileSystem().clearDirty();
            setChanged();
        }
        BluetoothNetwork.register(level, computerId, worldPosition, os.getBluetoothChannel(), BluetoothNetwork.DeviceType.COMPUTER);
    }

    // --- Accessors ---

    public JavaOS getOS() { return os; }
    public UUID getComputerId() { return computerId; }
    public boolean isPowered() { return powered; }
    public void setPowered(boolean powered) { this.powered = powered; setChanged(); }

    // --- NBT Persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("ComputerId", computerId);
        tag.putBoolean("Powered", powered);
        tag.putString("Label", os.getLabel());
        tag.putInt("BluetoothChannel", os.getBluetoothChannel());
        tag.put("Filesystem", os.getFileSystem().save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("ComputerId")) computerId = tag.getUUID("ComputerId");
        if (tag.contains("Powered")) powered = tag.getBoolean("Powered");

        // Recreate OS with persisted ID
        os = new JavaOS(computerId);
        if (tag.contains("Label")) os.setLabel(tag.getString("Label"));
        if (tag.contains("BluetoothChannel")) os.setBluetoothChannel(tag.getInt("BluetoothChannel"));
        if (tag.contains("Filesystem", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            os.getFileSystem().load(tag.getCompound("Filesystem"));
        }
    }
}

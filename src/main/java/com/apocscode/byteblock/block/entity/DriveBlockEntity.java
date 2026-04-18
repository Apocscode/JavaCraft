package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.init.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drive block entity. Holds a single Disk item.
 * Computers can read/write files to the inserted disk.
 */
public class DriveBlockEntity extends BlockEntity {
    private ItemStack diskStack = ItemStack.EMPTY;

    public DriveBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRIVE.get(), pos, state);
    }

    public boolean hasDisk() {
        return !diskStack.isEmpty();
    }

    public boolean insertDisk(ItemStack stack) {
        if (!diskStack.isEmpty()) return false;
        diskStack = stack.copyWithCount(1);
        setChanged();
        return true;
    }

    public ItemStack ejectDisk() {
        if (diskStack.isEmpty()) return ItemStack.EMPTY;
        ItemStack ejected = diskStack.copy();
        diskStack = ItemStack.EMPTY;
        setChanged();
        return ejected;
    }

    public ItemStack getDiskStack() {
        return diskStack;
    }

    /**
     * Read a file from the inserted disk's NBT.
     */
    public String readFromDisk(String path) {
        if (diskStack.isEmpty()) return null;
        CompoundTag diskTag = diskStack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        CompoundTag files = diskTag.getCompound("Files");
        return files.contains(path) ? files.getString(path) : null;
    }

    /**
     * Write a file to the inserted disk's NBT.
     */
    public void writeToDisk(String path, String content) {
        if (diskStack.isEmpty()) return;
        CompoundTag diskTag = diskStack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        CompoundTag files = diskTag.getCompound("Files");
        files.putString(path, content);
        diskTag.put("Files", files);
        diskStack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(diskTag));
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!diskStack.isEmpty()) {
            tag.put("Disk", diskStack.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Disk")) {
            diskStack = ItemStack.parse(registries, tag.getCompound("Disk")).orElse(ItemStack.EMPTY);
        }
    }
}

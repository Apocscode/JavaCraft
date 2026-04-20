package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.DriveBlock;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.menu.DriveMenu;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drive block entity — reel-to-reel tape drive.
 * Holds a single Disk item in a container slot with GUI access.
 * Computers can read/write files to the inserted disk.
 */
public class DriveBlockEntity extends BlockEntity implements MenuProvider {
    private java.util.UUID deviceId = java.util.UUID.randomUUID();
    private final SimpleContainer container = new SimpleContainer(1);

    public DriveBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRIVE.get(), pos, state);
        container.addListener(c -> syncToClient());
    }

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, DriveBlockEntity be) {
        BluetoothNetwork.register(level, be.deviceId, pos, 1, BluetoothNetwork.DeviceType.DRIVE);
        if (level.getGameTime() % 20 == 0) {
            boolean connected = BluetoothNetwork.isComputerInRange(level, pos);
            if (state.getValue(DriveBlock.CONNECTED) != connected) {
                level.setBlockAndUpdate(pos, state.setValue(DriveBlock.CONNECTED, connected));
            }
        }
    }

    public java.util.UUID getDeviceId() { return deviceId; }

    public SimpleContainer getContainer() {
        return container;
    }

    public boolean hasDisk() {
        return !container.getItem(0).isEmpty();
    }

    public boolean insertDisk(ItemStack stack) {
        if (!container.getItem(0).isEmpty()) return false;
        container.setItem(0, stack.copyWithCount(1));
        syncToClient();
        return true;
    }

    public ItemStack ejectDisk() {
        ItemStack disk = container.getItem(0);
        if (disk.isEmpty()) return ItemStack.EMPTY;
        ItemStack ejected = disk.copy();
        container.setItem(0, ItemStack.EMPTY);
        syncToClient();
        return ejected;
    }

    public ItemStack getDiskStack() {
        return container.getItem(0);
    }

    /**
     * Read a file from the inserted disk's NBT.
     */
    public String readFromDisk(String path) {
        ItemStack diskStack = container.getItem(0);
        if (diskStack.isEmpty()) return null;
        CompoundTag diskTag = diskStack.getOrDefault(DataComponents.CUSTOM_DATA,
                CustomData.EMPTY).copyTag();
        CompoundTag files = diskTag.getCompound("Files");
        return files.contains(path) ? files.getString(path) : null;
    }

    /**
     * Write a file to the inserted disk's NBT.
     */
    public void writeToDisk(String path, String content) {
        ItemStack diskStack = container.getItem(0);
        if (diskStack.isEmpty()) return;
        CompoundTag diskTag = diskStack.getOrDefault(DataComponents.CUSTOM_DATA,
                CustomData.EMPTY).copyTag();
        CompoundTag files = diskTag.getCompound("Files");
        files.putString(path, content);
        diskTag.put("Files", files);
        diskStack.set(DataComponents.CUSTOM_DATA, CustomData.of(diskTag));
        syncToClient();
    }

    /**
     * Get the label of the inserted disk, or null if no disk or no label.
     */
    public String getDiskLabel() {
        ItemStack diskStack = container.getItem(0);
        if (diskStack.isEmpty()) return null;
        CompoundTag diskTag = diskStack.getOrDefault(DataComponents.CUSTOM_DATA,
                CustomData.EMPTY).copyTag();
        return diskTag.contains("Label") ? diskTag.getString("Label") : null;
    }

    /**
     * Set the label of the inserted disk.
     */
    public void setDiskLabel(String label) {
        ItemStack diskStack = container.getItem(0);
        if (diskStack.isEmpty()) return;
        CompoundTag diskTag = diskStack.getOrDefault(DataComponents.CUSTOM_DATA,
                CustomData.EMPTY).copyTag();
        diskTag.putString("Label", label);
        diskStack.set(DataComponents.CUSTOM_DATA, CustomData.of(diskTag));
        syncToClient();
    }

    /**
     * List all file paths stored on the inserted disk.
     */
    public java.util.List<String> listDiskFiles() {
        ItemStack diskStack = container.getItem(0);
        if (diskStack.isEmpty()) return java.util.List.of();
        CompoundTag diskTag = diskStack.getOrDefault(DataComponents.CUSTOM_DATA,
                CustomData.EMPTY).copyTag();
        CompoundTag files = diskTag.getCompound("Files");
        return new java.util.ArrayList<>(files.getAllKeys());
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.byteblock.drive");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return new DriveMenu(containerId, playerInv, container,
                ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("DeviceId", deviceId);
        ItemStack diskStack = container.getItem(0);
        if (!diskStack.isEmpty()) {
            tag.put("Disk", diskStack.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("DeviceId")) deviceId = tag.getUUID("DeviceId");
        if (tag.contains("Disk")) {
            container.setItem(0, ItemStack.parse(registries, tag.getCompound("Disk")).orElse(ItemStack.EMPTY));
        }
    }

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
}

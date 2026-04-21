package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * ByteChest block entity — 27-slot inventory with Bluetooth device registration.
 *
 * When the Materials Calculator on a connected Computer performs a storage scan,
 * it queries all reachable ByteChests (and optionally AE2 ME networks via ModLinkRegistry)
 * to report which required materials are available and in what quantities.
 */
public class ByteChestBlockEntity extends RandomizableContainerBlockEntity {

    private static final int SLOTS = 27;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private UUID deviceId = UUID.randomUUID();

    public ByteChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BYTE_CHEST.get(), pos, state);
    }

    // ── Server tick: keep BT registration alive ───────────────────────────────

    public void serverTick() {
        if (level == null || level.isClientSide()) return;
        BluetoothNetwork.register(level, deviceId, worldPosition, 1,
                BluetoothNetwork.DeviceType.BYTE_CHEST);
    }

    // ── UUID accessor ─────────────────────────────────────────────────────────

    public UUID getDeviceId() { return deviceId; }

    // ── RandomizableContainerBlockEntity implementation ───────────────────────

    @Override
    protected NonNullList<ItemStack> getItems() { return items; }

    @Override
    protected void setItems(NonNullList<ItemStack> pItems) { items = pItems; }

    @Override
    public int getContainerSize() { return SLOTS; }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.byteblock.byte_chest");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInv) {
        return ChestMenu.threeRows(containerId, playerInv, this);
    }

    // ── NBT persistence ───────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, items, registries);
        }
        tag.putString("deviceId", deviceId.toString());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        if (!tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, items, registries);
        }
        if (tag.contains("deviceId")) {
            try {
                deviceId = UUID.fromString(tag.getString("deviceId"));
            } catch (IllegalArgumentException ignored) {}
        }
    }
}

package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.ByteChestBlock;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
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
    /** Player-defined label. Empty string = use default name. */
    private String label = "";
    /** Paint tint as 0xRRGGBB. White (0xFFFFFF) = no visible tint. */
    private int tint = 0xFFFFFF;

    /** Client-side set of loaded ByteChests — used by GpsToolOverlay to draw labels/wireframes. */
    public static final java.util.Set<ByteChestBlockEntity> CLIENT_LOADED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public ByteChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BYTE_CHEST.get(), pos, state);
    }

    // ── Server tick: keep BT registration alive ───────────────────────────────

    public void serverTick() {
        if (level == null || level.isClientSide()) return;
        BluetoothNetwork.register(level, deviceId, worldPosition, 1,
                BluetoothNetwork.DeviceType.BYTE_CHEST);
        // Keep label registry in sync each tick (cheap; ConcurrentHashMap put).
        BluetoothNetwork.setChestLabel(deviceId, label);
        // Update BT indicator LED every second
        if (level.getGameTime() % 20 == 0) {
            boolean connected = BluetoothNetwork.isComputerInRange(level, worldPosition);
            BlockState current = level.getBlockState(worldPosition);
            if (current.getValue(ByteChestBlock.CONNECTED) != connected) {
                level.setBlockAndUpdate(worldPosition, current.setValue(ByteChestBlock.CONNECTED, connected));
            }
        }
    }

    // ── UUID / Label accessors ────────────────────────────────────────────────

    public UUID getDeviceId() { return deviceId; }

    public String getLabel() { return label == null ? "" : label; }

    public void setLabel(String newLabel) {
        this.label = newLabel == null ? "" : newLabel;
        if (this.label.length() > 32) this.label = this.label.substring(0, 32);
        BluetoothNetwork.setChestLabel(deviceId, this.label);
        setChanged();
        if (level != null && !level.isClientSide()) {
            BlockState st = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, st, st, Block.UPDATE_CLIENTS);
        }
    }

    public int getTint() { return tint; }

    public void setTint(int newTint) {
        this.tint = newTint & 0xFFFFFF;
        setChanged();
        if (level != null && !level.isClientSide()) {
            BlockState st = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, st, st, Block.UPDATE_CLIENTS);
        } else if (level != null && level.isClientSide()) {
            // Force model re-tint immediately on client.
            net.minecraft.client.Minecraft.getInstance().levelRenderer
                .blockChanged(level, worldPosition, level.getBlockState(worldPosition),
                              level.getBlockState(worldPosition), 0);
        }
    }

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
    public Component getDisplayName() {
        return label.isEmpty() ? getDefaultName() : Component.literal(label);
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
        if (!label.isEmpty()) tag.putString("Label", label);
        if (tint != 0xFFFFFF) tag.putInt("Tint", tint);
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
        label = tag.contains("Label") ? tag.getString("Label") : "";
        tint = tag.contains("Tint") ? (tag.getInt("Tint") & 0xFFFFFF) : 0xFFFFFF;
    }

    // ── Client sync (label needs to render on the chest popup HUD) ────────────

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("Label", label);
        tag.putString("deviceId", deviceId.toString());
        tag.putInt("Tint", tint);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        if (tag.contains("Label")) label = tag.getString("Label");
        if (tag.contains("Tint")) tint = tag.getInt("Tint") & 0xFFFFFF;
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
                              ClientboundBlockEntityDataPacket pkt,
                              HolderLookup.Provider registries) {
        super.onDataPacket(connection, pkt, registries);
        // Force chunk re-tint after server pushes a tint change.
        if (level != null && level.isClientSide()) {
            BlockState st = level.getBlockState(worldPosition);
            net.minecraft.client.Minecraft.getInstance().levelRenderer
                .blockChanged(level, worldPosition, st, st, 0);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ── Client-side load tracking (for GpsToolOverlay) ──────────────────────────

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) CLIENT_LOADED.add(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null && level.isClientSide()) CLIENT_LOADED.remove(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) CLIENT_LOADED.remove(this);
    }
}

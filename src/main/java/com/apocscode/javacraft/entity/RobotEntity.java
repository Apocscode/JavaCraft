package com.apocscode.javacraft.entity;

import com.apocscode.javacraft.init.ModEntities;
import com.apocscode.javacraft.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * Robot entity — a turtle-like programmable ground robot.
 * Can move, dig, place blocks, and interact with inventories.
 * Has a 16-slot internal inventory.
 */
public class RobotEntity extends PathfinderMob {
    private UUID ownerId = null;
    private UUID linkedComputerId = null;
    private int bluetoothChannel = 2;
    private Direction facing = Direction.NORTH;
    private int fuelTicks = 6000;
    private int selectedSlot = 0;
    private final SimpleContainer inventory = new SimpleContainer(16);
    private final Queue<String> commandQueue = new LinkedList<>();

    public RobotEntity(EntityType<? extends RobotEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.MOVEMENT_SPEED, 0.2)
                .add(Attributes.ATTACK_DAMAGE, 3.0);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;

        // Process next command from queue
        if (!commandQueue.isEmpty() && fuelTicks > 0) {
            String cmd = commandQueue.poll();
            executeCommand(cmd);
            fuelTicks--;
        }

        // Register on Bluetooth
        if (linkedComputerId != null) {
            BluetoothNetwork.register(level(), linkedComputerId, blockPosition(), bluetoothChannel);
        }
    }

    private void executeCommand(String cmd) {
        switch (cmd) {
            case "forward" -> moveForward();
            case "back" -> moveBack();
            case "up" -> moveUp();
            case "down" -> moveDown();
            case "turnLeft" -> facing = facing.getCounterClockWise();
            case "turnRight" -> facing = facing.getClockWise();
            case "dig" -> dig();
            case "digUp" -> digUp();
            case "digDown" -> digDown();
            case "place" -> place();
        }
    }

    // --- Movement ---

    private void moveForward() {
        BlockPos target = blockPosition().relative(facing);
        if (canMoveTo(target)) moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
    }

    private void moveBack() {
        BlockPos target = blockPosition().relative(facing.getOpposite());
        if (canMoveTo(target)) moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
    }

    private void moveUp() {
        BlockPos target = blockPosition().above();
        if (canMoveTo(target)) moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
    }

    private void moveDown() {
        BlockPos target = blockPosition().below();
        if (canMoveTo(target)) moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
    }

    private boolean canMoveTo(BlockPos pos) {
        BlockState state = level().getBlockState(pos);
        return state.isAir() || !state.isSolid();
    }

    // --- Mining ---

    private void dig() {
        BlockPos target = blockPosition().relative(facing);
        mineBlock(target);
    }

    private void digUp() {
        mineBlock(blockPosition().above());
    }

    private void digDown() {
        mineBlock(blockPosition().below());
    }

    private void mineBlock(BlockPos pos) {
        BlockState state = level().getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level(), pos) < 0) return; // Can't mine bedrock etc.

        // Drop items into robot inventory
        Block.dropResources(state, level(), pos);
        level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    // --- Placement ---

    private void place() {
        BlockPos target = blockPosition().relative(facing);
        ItemStack held = inventory.getItem(selectedSlot);
        if (held.isEmpty()) return;

        if (level().getBlockState(target).isAir()) {
            Block block = Block.byItem(held.getItem());
            if (block != Blocks.AIR) {
                level().setBlock(target, block.defaultBlockState(), 3);
                held.shrink(1);
            }
        }
    }

    // --- Interaction ---

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide()) {
            if (ownerId == null) {
                ownerId = player.getUUID();
                player.sendSystemMessage(Component.literal("[JavaCraft Robot] Linked to you."));
            } else if (ownerId.equals(player.getUUID())) {
                player.sendSystemMessage(Component.literal(
                        "[JavaCraft Robot] Fuel: " + (fuelTicks / 20) + "s | Facing: " + facing.getName()
                        + " | Inventory: " + countItems() + "/16 slots used"));
            } else {
                player.sendSystemMessage(Component.literal("[JavaCraft Robot] Not your robot."));
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    private int countItems() {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty()) count++;
        }
        return count;
    }

    // --- Programmable API ---

    public void queueCommand(String command) {
        if (commandQueue.size() < 256) commandQueue.add(command);
    }

    public void clearCommands() { commandQueue.clear(); }
    public SimpleContainer getInventory() { return inventory; }
    public int getSelectedSlot() { return selectedSlot; }
    public void setSelectedSlot(int slot) { this.selectedSlot = Math.clamp(slot, 0, 15); }
    public Direction getRobotFacing() { return facing; }
    public int getFuelTicks() { return fuelTicks; }
    public void addFuel(int ticks) { this.fuelTicks = Math.min(fuelTicks + ticks, 72000); }
    public void linkComputer(UUID computerId) { this.linkedComputerId = computerId; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("OwnerId", ownerId);
        if (linkedComputerId != null) tag.putUUID("LinkedComputer", linkedComputerId);
        tag.putInt("BluetoothChannel", bluetoothChannel);
        tag.putInt("FuelTicks", fuelTicks);
        tag.putInt("SelectedSlot", selectedSlot);
        tag.putString("Facing", facing.getName());
        // Save inventory
        CompoundTag invTag = new CompoundTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                // Use string index as key
                invTag.put(String.valueOf(i), stack.save(level().registryAccess()));
            }
        }
        tag.put("Inventory", invTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("OwnerId")) ownerId = tag.getUUID("OwnerId");
        if (tag.contains("LinkedComputer")) linkedComputerId = tag.getUUID("LinkedComputer");
        if (tag.contains("BluetoothChannel")) bluetoothChannel = tag.getInt("BluetoothChannel");
        if (tag.contains("FuelTicks")) fuelTicks = tag.getInt("FuelTicks");
        if (tag.contains("SelectedSlot")) selectedSlot = tag.getInt("SelectedSlot");
        if (tag.contains("Facing")) facing = Direction.byName(tag.getString("Facing"));
        if (facing == null) facing = Direction.NORTH;
        if (tag.contains("Inventory")) {
            CompoundTag invTag = tag.getCompound("Inventory");
            for (String key : invTag.getAllKeys()) {
                int slot = Integer.parseInt(key);
                inventory.setItem(slot, ItemStack.parse(level().registryAccess(), invTag.getCompound(key)).orElse(ItemStack.EMPTY));
            }
        }
    }
}

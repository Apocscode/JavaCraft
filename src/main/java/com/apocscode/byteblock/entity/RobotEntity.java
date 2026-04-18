package com.apocscode.byteblock.entity;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.network.BluetoothNetwork;

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
import net.neoforged.neoforge.energy.EnergyStorage;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * Robot entity — a turtle-like programmable ground robot.
 * Can move, dig, place blocks, and interact with inventories.
 * Has a 16-slot internal inventory, FE-powered, with built-in computer terminal.
 */
public class RobotEntity extends PathfinderMob {
    private static final int MAX_ENERGY = 10000;
    private static final int MAX_RECEIVE = 200;
    private static final int ENERGY_PER_ACTION = 10;

    private UUID ownerId = null;
    private UUID computerId;
    private int bluetoothChannel = 2;
    private Direction facing = Direction.NORTH;
    private int selectedSlot = 0;
    private final SimpleContainer inventory = new SimpleContainer(16);
    private final Queue<String> commandQueue = new LinkedList<>();
    private EnergyStorage energyStorage;
    private JavaOS os;

    public RobotEntity(EntityType<? extends RobotEntity> type, Level level) {
        super(type, level);
        this.computerId = UUID.randomUUID();
        this.energyStorage = new EnergyStorage(MAX_ENERGY, MAX_RECEIVE, MAX_ENERGY, 0);
        this.os = new JavaOS(computerId);
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

        // Tick the OS on both sides (client needs it for screen rendering)
        os.tick();

        if (level().isClientSide()) return;

        // Process next command from queue if we have energy
        if (!commandQueue.isEmpty() && energyStorage.getEnergyStored() >= ENERGY_PER_ACTION) {
            String cmd = commandQueue.poll();
            executeCommand(cmd);
            energyStorage.extractEnergy(ENERGY_PER_ACTION, false);
        }

        // Register on Bluetooth
        BluetoothNetwork.register(level(), computerId, blockPosition(), bluetoothChannel);
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
        if (level().isClientSide()) {
            // Open the robot's terminal screen (client-side only)
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.apocscode.byteblock.client.ComputerScreen(os));
        } else {
            if (ownerId == null) {
                ownerId = player.getUUID();
                player.sendSystemMessage(Component.literal("[ByteBlock Robot] Linked to you."));
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
    public EnergyStorage getEnergyStorage() { return energyStorage; }
    public JavaOS getOS() { return os; }
    public UUID getComputerId() { return computerId; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("OwnerId", ownerId);
        tag.putUUID("ComputerId", computerId);
        tag.putInt("BluetoothChannel", bluetoothChannel);
        tag.putInt("Energy", energyStorage.getEnergyStored());
        tag.putInt("SelectedSlot", selectedSlot);
        tag.putString("Facing", facing.getName());
        // Save OS state
        tag.putString("Label", os.getLabel());
        tag.putInt("OSBluetoothChannel", os.getBluetoothChannel());
        tag.put("Filesystem", os.getFileSystem().save());
        // Save inventory
        CompoundTag invTag = new CompoundTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                invTag.put(String.valueOf(i), stack.save(level().registryAccess()));
            }
        }
        tag.put("Inventory", invTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("OwnerId")) ownerId = tag.getUUID("OwnerId");
        if (tag.contains("ComputerId")) computerId = tag.getUUID("ComputerId");
        if (tag.contains("BluetoothChannel")) bluetoothChannel = tag.getInt("BluetoothChannel");
        if (tag.contains("SelectedSlot")) selectedSlot = tag.getInt("SelectedSlot");
        if (tag.contains("Facing")) facing = Direction.byName(tag.getString("Facing"));
        if (facing == null) facing = Direction.NORTH;
        // Restore energy — reconstruct with the saved value as initial
        int stored = tag.contains("Energy") ? tag.getInt("Energy") : 0;
        energyStorage = new EnergyStorage(MAX_ENERGY, MAX_RECEIVE, MAX_ENERGY, stored);
        // Restore OS
        os = new JavaOS(computerId);
        if (tag.contains("Label")) os.setLabel(tag.getString("Label"));
        if (tag.contains("OSBluetoothChannel")) os.setBluetoothChannel(tag.getInt("OSBluetoothChannel"));
        if (tag.contains("Filesystem")) os.getFileSystem().load(tag.getCompound("Filesystem"));
        // Restore inventory
        if (tag.contains("Inventory")) {
            CompoundTag invTag = tag.getCompound("Inventory");
            for (String key : invTag.getAllKeys()) {
                int slot = Integer.parseInt(key);
                inventory.setItem(slot, ItemStack.parse(level().registryAccess(), invTag.getCompound(key)).orElse(ItemStack.EMPTY));
            }
        }
    }
}

package com.apocscode.byteblock.entity;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.energy.EnergyStorage;

import java.util.LinkedList;
import java.util.List;
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

        // Keep the OS's world + host context fresh each tick for Lua APIs
        os.setWorldContext(level(), blockPosition());
        os.setHost(this);

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
            case "drop" -> drop(blockPosition().relative(facing));
            case "dropUp" -> drop(blockPosition().above());
            case "dropDown" -> drop(blockPosition().below());
            case "suck" -> suck(blockPosition().relative(facing));
            case "suckUp" -> suck(blockPosition().above());
            case "suckDown" -> suck(blockPosition().below());
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
        if (!state.isAir() && state.isSolid()) return false;
        // Avoid water/lava (they're non-solid but would drown/damage the robot)
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) return false;
        return true;
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

        // Collect drops and deposit into the robot's inventory; spill overflow into the world.
        if (level() instanceof ServerLevel server) {
            List<ItemStack> drops = Block.getDrops(state, server, pos, null);
            for (ItemStack drop : drops) {
                ItemStack leftover = inventory.addItem(drop);
                if (!leftover.isEmpty()) {
                    Block.popResource(level(), pos, leftover);
                }
            }
        } else {
            Block.dropResources(state, level(), pos);
        }
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

    // --- Inventory transfer (drop/suck into adjacent containers) ---

    private void drop(BlockPos target) {
        if (level() == null) return;
        ItemStack stack = inventory.getItem(selectedSlot);
        if (stack.isEmpty()) return;

        net.minecraft.world.level.block.entity.BlockEntity be = level().getBlockEntity(target);
        if (be instanceof net.minecraft.world.Container dst) {
            int before = stack.getCount();
            ItemStack leftover = insertIntoContainer(dst, stack.copy());
            int moved = before - leftover.getCount();
            if (moved > 0) {
                stack.shrink(moved);
                inventory.setItem(selectedSlot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                dst.setChanged();
            }
        } else if (level().getBlockState(target).isAir()) {
            // No container — pop the full stack into the world.
            Block.popResource(level(), target, stack.copy());
            inventory.setItem(selectedSlot, ItemStack.EMPTY);
        }
    }

    private void suck(BlockPos target) {
        if (level() == null) return;
        net.minecraft.world.level.block.entity.BlockEntity be = level().getBlockEntity(target);
        if (!(be instanceof net.minecraft.world.Container src)) return;

        for (int i = 0; i < src.getContainerSize(); i++) {
            ItemStack stack = src.getItem(i);
            if (stack.isEmpty()) continue;
            ItemStack leftover = inventory.addItem(stack.copy());
            int consumed = stack.getCount() - leftover.getCount();
            if (consumed > 0) {
                stack.shrink(consumed);
                src.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                src.setChanged();
                return; // one action per tick
            }
        }
    }

    private static ItemStack insertIntoContainer(net.minecraft.world.Container dst, ItemStack stack) {
        for (int i = 0; i < dst.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack existing = dst.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = Math.min(existing.getMaxStackSize(), dst.getMaxStackSize()) - existing.getCount();
                int move = Math.min(space, stack.getCount());
                if (move > 0) {
                    existing.grow(move);
                    stack.shrink(move);
                }
            }
        }
        for (int i = 0; i < dst.getContainerSize() && !stack.isEmpty(); i++) {
            if (dst.getItem(i).isEmpty() && dst.canPlaceItem(i, stack)) {
                int move = Math.min(Math.min(stack.getMaxStackSize(), dst.getMaxStackSize()), stack.getCount());
                ItemStack placed = stack.copy();
                placed.setCount(move);
                dst.setItem(i, placed);
                stack.shrink(move);
            }
        }
        return stack;
    }

    /**
     * Compare the block at offset with the item in the selected slot.
     * yOffset: 0=ahead, +1=above, -1=below.
     */
    public boolean compareBlock(int yOffset) {
        if (level() == null) return false;
        BlockPos pos;
        if (yOffset == 0) pos = blockPosition().relative(facing);
        else if (yOffset > 0) pos = blockPosition().above();
        else pos = blockPosition().below();

        BlockState state = level().getBlockState(pos);
        ItemStack selected = inventory.getItem(selectedSlot);
        if (selected.isEmpty()) return state.isAir();
        Block selectedBlock = Block.byItem(selected.getItem());
        return selectedBlock != Blocks.AIR && state.is(selectedBlock);
    }

    /** True if a ChargingStation is actively charging this robot. */
    public boolean isCharging() {
        if (level() == null) return false;
        net.minecraft.world.phys.AABB area =
                new net.minecraft.world.phys.AABB(blockPosition()).inflate(3.0);
        for (BlockPos p : BlockPos.betweenClosed(
                BlockPos.containing(area.minX, area.minY, area.minZ),
                BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
            net.minecraft.world.level.block.entity.BlockEntity be = level().getBlockEntity(p);
            if (be instanceof com.apocscode.byteblock.block.entity.ChargingStationBlockEntity cs
                    && cs.getEnergyStored() > 0) {
                return true;
            }
        }
        return false;
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
    public int getCommandsQueued() { return commandQueue.size(); }
    public boolean isBusy() { return !commandQueue.isEmpty(); }

    /**
     * Consume one fuel item from the given inventory slot and convert it to stored FE.
     * Returns the amount of FE added (0 if no suitable item or no capacity).
     */
    public int refuel(int slot) {
        if (slot < 0 || slot >= inventory.getContainerSize()) return 0;
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) return 0;

        int fe = fuelValueFor(stack);
        if (fe <= 0) return 0;
        int accepted = energyStorage.receiveEnergy(fe, false);
        if (accepted <= 0) return 0;
        stack.shrink(1);
        return accepted;
    }

    private static int fuelValueFor(ItemStack stack) {
        var item = stack.getItem();
        if (item == net.minecraft.world.item.Items.COAL) return 1600;
        if (item == net.minecraft.world.item.Items.CHARCOAL) return 1600;
        if (item == net.minecraft.world.item.Items.COAL_BLOCK) return 16000;
        if (item == net.minecraft.world.item.Items.BLAZE_ROD) return 2400;
        if (item == net.minecraft.world.item.Items.LAVA_BUCKET) return 20000;
        return 0;
    }

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

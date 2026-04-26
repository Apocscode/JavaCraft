package com.apocscode.byteblock.entity;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
    public static final int ENERGY_PER_ACTION = 10;

    private UUID ownerId = null;
    private UUID computerId;
    private int bluetoothChannel = 2;
    private Direction facing = Direction.NORTH;
    private int selectedSlot = 0;
    private final SimpleContainer inventory = new SimpleContainer(16);
    private ItemStack equippedTool = ItemStack.EMPTY;       // LEFT hand (legacy field name kept for NBT back-compat)
    private ItemStack equippedToolRight = ItemStack.EMPTY;  // RIGHT hand (Phase 3)
    // dedicated tool slot (pick/axe/shovel/sword/shears)
    private final Queue<String> commandQueue = new LinkedList<>();
    private EnergyStorage energyStorage;
    private JavaOS os;

    // GPS-tool nav state.
    private BlockPos navTarget = null;
    private java.util.List<BlockPos> navPath = null;
    private int navPathIdx = 0;
    private BlockPos routeSrc = null;
    private BlockPos routeDst = null;
    private boolean routeActive = false;
    private int routePhase = 0;
    private BlockPos patrolMin = null;
    private BlockPos patrolMax = null;
    private boolean patrolActive = false;
    private int patrolCornerIdx = 0;

    // R2D2-style chirp scheduler — picks a random delay 80..240 ticks (4..12s)
    // and plays a short multi-note burst when it elapses.
    private int nextChirpTick = 120;
    private int chirpBurstRemaining = 0;
    private int chirpBurstNextTick = 0;

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

        // Per-tick GPS nav stepper (runs when the command queue is idle).
        if (commandQueue.isEmpty() && energyStorage.getEnergyStored() >= ENERGY_PER_ACTION) {
            if (tickNavStep()) {
                energyStorage.extractEnergy(ENERGY_PER_ACTION, false);
            }
        }

        // R2D2-style chirps — random short note bursts every 4..12s.
        tickChirps();

        // Register on Bluetooth
        BluetoothNetwork.register(level(), computerId, blockPosition(), bluetoothChannel);
    }

    /**
     * Periodically emit a short burst of randomly-pitched note-block sounds to give
     * the robot an R2D2-like voice. Bursts contain 2..5 notes spaced 3..6 ticks apart.
     * Only runs server-side; clients hear it via {@link Level#playSound}.
     */
    private void tickChirps() {
        // Currently in a burst — keep emitting notes.
        if (chirpBurstRemaining > 0) {
            if (--chirpBurstNextTick <= 0) {
                playChirp();
                chirpBurstRemaining--;
                chirpBurstNextTick = 3 + random.nextInt(4); // 3..6 ticks between notes
            }
            return;
        }
        // Between bursts — count down to next one.
        if (--nextChirpTick > 0) return;
        chirpBurstRemaining = 2 + random.nextInt(4); // 2..5 notes
        chirpBurstNextTick = 0;
        nextChirpTick = 80 + random.nextInt(160);     // 4..12s until next burst
    }

    /** Play one random-pitch note (NOTE_BLOCK_BIT for the digital "beep"). */
    private void playChirp() {
        if (level() == null || level().isClientSide()) return;
        // Pitch range 0.6..2.0 covers the squeaky/grumbly R2 character.
        float pitch = 0.6f + random.nextFloat() * 1.4f;
        // Alternate between "BIT" (square) and "PLING" (chime) for variety.
        SoundEvent voice = random.nextBoolean()
                ? SoundEvents.NOTE_BLOCK_BIT.value()
                : SoundEvents.NOTE_BLOCK_PLING.value();
        level().playSound(null, blockPosition(), voice, SoundSource.NEUTRAL, 0.35f, pitch);
    }

    private void executeCommand(String cmd) {
        // Programmed macros from the GPS tool.
        if (cmd.startsWith("goto:") || cmd.startsWith("route:") || cmd.startsWith("patrol:") || cmd.startsWith("path:")
                || "stop".equals(cmd) || "clearNav".equals(cmd)) {
            handleNavCommand(cmd);
            return;
        }
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
            case "placeUp" -> placeUp();
            case "placeDown" -> placeDown();
            case "attack" -> attack(0);
            case "attackUp" -> attack(1);
            case "attackDown" -> attack(-1);
            case "drop" -> drop(blockPosition().relative(facing));
            case "dropUp" -> drop(blockPosition().above());
            case "dropDown" -> drop(blockPosition().below());
            case "suck" -> suck(blockPosition().relative(facing));
            case "suckUp" -> suck(blockPosition().above());
            case "suckDown" -> suck(blockPosition().below());
        }
    }

    private void handleNavCommand(String cmd) {
        String[] p = cmd.split(":");
        try {
            switch (p[0]) {
                case "goto" -> {
                    if (p.length >= 4) {
                        navTarget = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                        routeActive = false; patrolActive = false; navPath = null;
                    }
                }
                case "route" -> {
                    if (p.length >= 7) {
                        routeSrc = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                        routeDst = new BlockPos(Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
                        routeActive = true; routePhase = 0;
                        patrolActive = false; navPath = null; navTarget = null;
                    }
                }
                case "patrol" -> {
                    if (p.length >= 7) {
                        patrolMin = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                        patrolMax = new BlockPos(Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
                        patrolActive = true; patrolCornerIdx = 0;
                        routeActive = false; navPath = null; navTarget = null;
                    }
                }
                case "path" -> {
                    java.util.List<BlockPos> list = new java.util.ArrayList<>();
                    int i = 1;
                    while (i + 2 < p.length) {
                        list.add(new BlockPos(Integer.parseInt(p[i]), Integer.parseInt(p[i + 1]), Integer.parseInt(p[i + 2])));
                        i += 3;
                    }
                    if (!list.isEmpty()) {
                        navPath = list; navPathIdx = 0;
                        routeActive = false; patrolActive = false; navTarget = null;
                    }
                }
                case "stop", "clearNav" -> {
                    navTarget = null; navPath = null;
                    routeActive = false; patrolActive = false;
                }
            }
        } catch (NumberFormatException ignored) { }
    }

    /**
     * Greedy one-tile step toward the current nav target. Returns true if a step
     * (or pickup/drop) happened this tick so energy is consumed.
     */
    private boolean tickNavStep() {
        // Resolve the active goal into a single BlockPos target.
        BlockPos target = null;
        boolean endAction = false; // pickup/drop at endpoint

        if (navTarget != null) {
            target = navTarget;
        } else if (navPath != null && navPathIdx < navPath.size()) {
            target = navPath.get(navPathIdx);
        } else if (routeActive && routeSrc != null && routeDst != null) {
            target = routePhase == 0 ? routeSrc : routeDst;
        } else if (patrolActive && patrolMin != null && patrolMax != null) {
            int minX = Math.min(patrolMin.getX(), patrolMax.getX());
            int maxX = Math.max(patrolMin.getX(), patrolMax.getX());
            int minZ = Math.min(patrolMin.getZ(), patrolMax.getZ());
            int maxZ = Math.max(patrolMin.getZ(), patrolMax.getZ());
            int y = patrolMin.getY();
            target = switch (patrolCornerIdx % 4) {
                case 0 -> new BlockPos(minX, y, minZ);
                case 1 -> new BlockPos(maxX, y, minZ);
                case 2 -> new BlockPos(maxX, y, maxZ);
                default -> new BlockPos(minX, y, maxZ);
            };
        }

        if (target == null) return false;

        BlockPos here = blockPosition();
        if (here.equals(target) || here.distSqr(target) < 1.1) {
            // Arrived — advance the current goal.
            if (navTarget != null) {
                navTarget = null;
            } else if (navPath != null) {
                navPathIdx++;
                if (navPathIdx >= navPath.size()) navPath = null;
            } else if (routeActive) {
                if (routePhase == 0) {
                    suck(routeSrc); // pickup from source
                    routePhase = 1;
                } else {
                    drop(routeDst); // drop at destination
                    routePhase = 0;
                }
                endAction = true;
            } else if (patrolActive) {
                patrolCornerIdx = (patrolCornerIdx + 1) % 4;
            }
            return endAction;
        }

        // Greedy step: pick the largest axis delta, try that first, fall back to others.
        int dx = Integer.signum(target.getX() - here.getX());
        int dy = Integer.signum(target.getY() - here.getY());
        int dz = Integer.signum(target.getZ() - here.getZ());

        // Try Y first when blocked isn't a concern, else prefer horizontal.
        if (dx != 0 && tryStep(here.offset(dx, 0, 0))) return true;
        if (dz != 0 && tryStep(here.offset(0, 0, dz))) return true;
        if (dy != 0 && tryStep(here.offset(0, dy, 0))) return true;
        return false;
    }

    private boolean tryStep(BlockPos to) {
        if (!canMoveTo(to)) return false;
        // Face the movement direction for visual consistency.
        int dx = to.getX() - blockPosition().getX();
        int dz = to.getZ() - blockPosition().getZ();
        if (dx > 0) facing = Direction.EAST;
        else if (dx < 0) facing = Direction.WEST;
        else if (dz > 0) facing = Direction.SOUTH;
        else if (dz < 0) facing = Direction.NORTH;
        moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        return true;
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

        // Collect drops (with enchantments from equipped tool) and deposit into the robot's inventory.
        if (level() instanceof ServerLevel server) {
            // Pick the better mining tool from either hand by destroy-speed against this block.
            ItemStack tool = pickMiningTool(state);
            List<ItemStack> drops = Block.getDrops(state, server, pos, null, this, tool);
            for (ItemStack drop : drops) {
                ItemStack leftover = inventory.addItem(drop);
                if (!leftover.isEmpty()) {
                    Block.popResource(level(), pos, leftover);
                }
            }
            // Damage whichever tool was used (once per dig)
            if (!tool.isEmpty() && tool.isDamageableItem()) {
                tool.hurtAndBreak(1, server, null, it -> {
                    if (tool == equippedTool) equippedTool = ItemStack.EMPTY;
                    else if (tool == equippedToolRight) equippedToolRight = ItemStack.EMPTY;
                });
            }
        } else {
            Block.dropResources(state, level(), pos);
        }
        level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    // --- Placement ---

    private void place() { placeAt(blockPosition().relative(facing)); }
    private void placeUp() { placeAt(blockPosition().above()); }
    private void placeDown() { placeAt(blockPosition().below()); }

    private void placeAt(BlockPos target) {
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

    // --- Combat ---

    /** Damage the first living entity at the target offset (0=ahead, +1=above, -1=below). */
    private void attack(int yOffset) {
        if (!(level() instanceof ServerLevel server)) return;
        BlockPos target;
        if (yOffset == 0) target = blockPosition().relative(facing);
        else if (yOffset > 0) target = blockPosition().above();
        else target = blockPosition().below();
        net.minecraft.world.phys.AABB box =
                new net.minecraft.world.phys.AABB(target).inflate(0.2);
        java.util.List<net.minecraft.world.entity.LivingEntity> targets =
                server.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                        e -> e != this && e.isAlive());
        if (targets.isEmpty()) return;
        net.minecraft.world.entity.LivingEntity victim = targets.get(0);
        // Prefer a sword from either hand, else any digger, else fists.
        ItemStack weapon = pickWeapon();
        float damage = 1.0f;
        if (!weapon.isEmpty()) {
            net.minecraft.world.item.Item it = weapon.getItem();
            if (it instanceof net.minecraft.world.item.SwordItem) damage = 4.0f;
            else if (it instanceof net.minecraft.world.item.DiggerItem) damage = 2.5f;
        }
        victim.hurt(server.damageSources().mobAttack(this), damage);
        if (!weapon.isEmpty() && weapon.isDamageableItem()) {
            weapon.hurtAndBreak(1, server, null, it -> {
                if (weapon == equippedTool) equippedTool = ItemStack.EMPTY;
                else if (weapon == equippedToolRight) equippedToolRight = ItemStack.EMPTY;
            });
        }
    }

    /** Inspect the block at the given offset. Returns null if air, else the BlockState. */
    public BlockState inspectBlock(int yOffset) {
        if (level() == null) return null;
        BlockPos pos;
        if (yOffset == 0) pos = blockPosition().relative(facing);
        else if (yOffset > 0) pos = blockPosition().above();
        else pos = blockPosition().below();
        BlockState s = level().getBlockState(pos);
        return s.isAir() ? null : s;
    }

    /**
     * Move {@code count} items from {@code srcSlot} into {@code dstSlot}, merging
     * stacks where possible. Returns the actual number moved.
     */
    public int transferTo(int srcSlot, int dstSlot, int count) {
        if (srcSlot < 0 || srcSlot >= inventory.getContainerSize()) return 0;
        if (dstSlot < 0 || dstSlot >= inventory.getContainerSize()) return 0;
        if (srcSlot == dstSlot || count <= 0) return 0;
        ItemStack src = inventory.getItem(srcSlot);
        if (src.isEmpty()) return 0;
        ItemStack dst = inventory.getItem(dstSlot);
        int moved;
        if (dst.isEmpty()) {
            moved = Math.min(count, src.getCount());
            ItemStack copy = src.copy();
            copy.setCount(moved);
            inventory.setItem(dstSlot, copy);
            src.shrink(moved);
        } else if (ItemStack.isSameItemSameComponents(src, dst)) {
            int space = Math.min(dst.getMaxStackSize(), inventory.getMaxStackSize()) - dst.getCount();
            moved = Math.min(Math.min(count, src.getCount()), space);
            if (moved <= 0) return 0;
            dst.grow(moved);
            src.shrink(moved);
        } else {
            return 0;
        }
        if (src.isEmpty()) inventory.setItem(srcSlot, ItemStack.EMPTY);
        return moved;
    }

    /** Free space remaining in {@code slot} (max stack size minus current count). */
    public int getItemSpace(int slot) {
        if (slot < 0 || slot >= inventory.getContainerSize()) return 0;
        ItemStack s = inventory.getItem(slot);
        if (s.isEmpty()) return inventory.getMaxStackSize();
        return Math.min(s.getMaxStackSize(), inventory.getMaxStackSize()) - s.getCount();
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
            // Reopening after a previous SHUTDOWN (e.g. ESC out of the desktop) needs a reboot,
            // otherwise ComputerScreen.render() immediately calls onClose() because the OS is
            // still in SHUTDOWN state — making the terminal appear to "only open once".
            if (os.isShutdown()) os.reboot();
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
    public ItemStack getEquippedTool() { return equippedTool; }
    public ItemStack getEquippedToolLeft() { return equippedTool; }
    public ItemStack getEquippedToolRight() { return equippedToolRight; }

    /**
     * Pick the better mining tool for {@code state} from either hand. Empty hand if neither
     * is set. Higher destroy speed wins; ties favor the LEFT slot.
     */
    private ItemStack pickMiningTool(BlockState state) {
        float lSpeed = equippedTool.isEmpty() ? 0f : equippedTool.getDestroySpeed(state);
        float rSpeed = equippedToolRight.isEmpty() ? 0f : equippedToolRight.getDestroySpeed(state);
        if (lSpeed <= 0f && rSpeed <= 0f) return ItemStack.EMPTY;
        return rSpeed > lSpeed ? equippedToolRight : equippedTool;
    }

    /** Pick the best weapon: sword preferred over digger preferred over empty. Ties favor LEFT. */
    private ItemStack pickWeapon() {
        int lScore = weaponScore(equippedTool);
        int rScore = weaponScore(equippedToolRight);
        if (lScore == 0 && rScore == 0) return ItemStack.EMPTY;
        return rScore > lScore ? equippedToolRight : equippedTool;
    }

    private static int weaponScore(ItemStack s) {
        if (s.isEmpty()) return 0;
        if (s.getItem() instanceof net.minecraft.world.item.SwordItem) return 3;
        if (s.getItem() instanceof net.minecraft.world.item.DiggerItem) return 2;
        return 1;
    }

    /**
     * Move the item from inventory[slot] into the dedicated tool slot.
     * The currently equipped tool (if any) is swapped back into that slot.
     * Returns true on success. (Legacy single-hand entry; equips LEFT.)
     */
    public boolean equipTool(int slot) { return equipToolLeft(slot); }

    public boolean equipToolLeft(int slot) {
        if (slot < 0 || slot >= inventory.getContainerSize()) return false;
        ItemStack fromInv = inventory.getItem(slot);
        ItemStack prev = equippedTool;
        equippedTool = fromInv.isEmpty() ? ItemStack.EMPTY : fromInv.copy();
        inventory.setItem(slot, prev);
        return true;
    }

    public boolean equipToolRight(int slot) {
        if (slot < 0 || slot >= inventory.getContainerSize()) return false;
        ItemStack fromInv = inventory.getItem(slot);
        ItemStack prev = equippedToolRight;
        equippedToolRight = fromInv.isEmpty() ? ItemStack.EMPTY : fromInv.copy();
        inventory.setItem(slot, prev);
        return true;
    }

    /** Move the equipped tool back into the first empty inventory slot (or drops if full). */
    public boolean unequipTool() { return unequipToolLeft(); }

    public boolean unequipToolLeft() {
        if (equippedTool.isEmpty()) return false;
        ItemStack leftover = inventory.addItem(equippedTool);
        if (!leftover.isEmpty()) {
            Block.popResource(level(), blockPosition(), leftover);
        }
        equippedTool = ItemStack.EMPTY;
        return true;
    }

    public boolean unequipToolRight() {
        if (equippedToolRight.isEmpty()) return false;
        ItemStack leftover = inventory.addItem(equippedToolRight);
        if (!leftover.isEmpty()) {
            Block.popResource(level(), blockPosition(), leftover);
        }
        equippedToolRight = ItemStack.EMPTY;
        return true;
    }

    public EnergyStorage getEnergyStorage() { return energyStorage; }
    public JavaOS getOS() { return os; }
    public UUID getComputerId() { return computerId; }

    /**
     * Build the second-line nameplate stat string, e.g. "♥ 24/30  ⚡ 78%".
     * Health uses red §c, charge uses yellow §e (low), aqua §b (mid), green §a (high).
     */
    public Component getStatsLine() {
        int hp  = (int) Math.ceil(getHealth());
        int max = (int) getMaxHealth();
        int e   = energyStorage.getEnergyStored();
        int em  = energyStorage.getMaxEnergyStored();
        int pct = em > 0 ? (e * 100 / em) : 0;
        String pctColor = pct < 20 ? "§c" : pct < 50 ? "§e" : pct < 80 ? "§b" : "§a";
        String hpColor  = hp < max / 3 ? "§c" : hp < max * 2 / 3 ? "§e" : "§a";
        return Component.literal(hpColor + "♥ " + hp + "/" + max + "  §r" + pctColor + "⚡ " + pct + "%");
    }

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
        if (!equippedTool.isEmpty()) {
            tag.put("EquippedTool", equippedTool.save(level().registryAccess()));
        }
        if (!equippedToolRight.isEmpty()) {
            tag.put("EquippedToolRight", equippedToolRight.save(level().registryAccess()));
        }
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
        if (tag.contains("Filesystem")) {
            os.getFileSystem().load(tag.getCompound("Filesystem"));
            os.installSystemPrograms(); // re-seed new default files (idempotent)
        }
        // Restore inventory
        if (tag.contains("Inventory")) {
            CompoundTag invTag = tag.getCompound("Inventory");
            for (String key : invTag.getAllKeys()) {
                int slot = Integer.parseInt(key);
                inventory.setItem(slot, ItemStack.parse(level().registryAccess(), invTag.getCompound(key)).orElse(ItemStack.EMPTY));
            }
        }
        if (tag.contains("EquippedTool")) {
            equippedTool = ItemStack.parse(level().registryAccess(), tag.getCompound("EquippedTool"))
                    .orElse(ItemStack.EMPTY);
        }
        if (tag.contains("EquippedToolRight")) {
            equippedToolRight = ItemStack.parse(level().registryAccess(), tag.getCompound("EquippedToolRight"))
                    .orElse(ItemStack.EMPTY);
        }
    }
}

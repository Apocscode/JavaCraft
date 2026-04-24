package com.apocscode.byteblock.entity;

import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * Flying Drone entity — a programmable flying device.
 * Can be given movement commands by a connected computer via Bluetooth.
 * Hovers in place when idle. Has a 9-slot inventory for item transport.
 *
 * Bluetooth protocol — any message on the drone's channel targeted at its UUID:
 *   "drone:waypoint:x:y:z"   — append waypoint
 *   "drone:home"             — clear waypoints and return to spawn
 *   "drone:clear"            — clear waypoints
 *   "drone:hover:true|false" — toggle hover
 *   "drone:refuel:<ticks>"   — add fuel
 */
public class DroneEntity extends PathfinderMob {
    private static final int MAX_FUEL = 72000;
    private static final int HOVER_DRAIN_PERIOD = 20; // 1 fuel per second while hovering
    private static final int LOW_FUEL_THRESHOLD = 400; // ~20s — auto-return-home trigger

    private UUID ownerId = null;
    private UUID droneId;
    private UUID linkedComputerId = null;
    private int bluetoothChannel = 1;
    private final Queue<Vec3> waypoints = new LinkedList<>();
    private boolean hovering = true;
    private int fuelTicks = 6000; // 5 minutes of flight
    private int hoverDrainCounter = 0;
    private final SimpleContainer inventory = new SimpleContainer(9);
    private BlockPos homePos = null;
    private boolean defender = false;  // attack nearby hostiles if true
    private int attackCooldown = 0;
    private String swarmGroup = "";    // if non-empty, drone only obeys "drone:swarm:<group>:..." on its channel
    private DroneVariant variant = DroneVariant.STANDARD;

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.droneId = UUID.randomUUID();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FLYING_SPEED, 0.4);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;

        if (homePos == null) homePos = blockPosition();

        // Auto-return-home when fuel is low and we're idle
        if (fuelTicks > 0 && fuelTicks < LOW_FUEL_THRESHOLD && waypoints.isEmpty() && homePos != null) {
            Vec3 home = new Vec3(homePos.getX() + 0.5, homePos.getY() + 1, homePos.getZ() + 0.5);
            if (position().distanceTo(home) > 1.5) {
                addWaypoint(home);
            }
        }

        // Hover drain — once per second rather than every tick
        if (fuelTicks > 0 && hovering && waypoints.isEmpty()) {
            setDeltaMovement(Vec3.ZERO);
            if (++hoverDrainCounter >= HOVER_DRAIN_PERIOD) {
                hoverDrainCounter = 0;
                fuelTicks--;
            }
        }

        // Process waypoints
        if (!waypoints.isEmpty() && fuelTicks > 0) {
            Vec3 target = waypoints.peek();
            Vec3 current = position();
            Vec3 direction = target.subtract(current);
            double dist = direction.length();
            if (dist < 0.5) {
                waypoints.poll();
            } else {
                Vec3 move = direction.normalize().scale(0.2);
                setDeltaMovement(move);
                fuelTicks--;
            }
        }

        // Register on Bluetooth under our own UUID and drain the inbox
        BluetoothNetwork.register(level(), droneId, blockPosition(), bluetoothChannel,
                BluetoothNetwork.DeviceType.DRONE);
        BluetoothNetwork.Message msg;
        while ((msg = BluetoothNetwork.receive(droneId)) != null) {
            handleBluetoothMessage(msg.content());
        }

        // Defender behavior — attack nearest hostile mob every 2s if armed.
        // Variant determines damage + aggro radius (DEFENDER is the only one with real combat).
        if (defender && variant.attackDamage > 0) {
            if (attackCooldown > 0) attackCooldown--;
            if (attackCooldown <= 0) {
                LivingEntity target = findNearestHostile(variant.aggroRadius);
                if (target != null) {
                    Vec3 dir = target.position().subtract(position()).normalize().scale(0.3 * variant.speedMul);
                    setDeltaMovement(dir);
                    if (position().distanceTo(target.position()) < 2.0) {
                        target.hurt(damageSources().mobAttack(this), variant.attackDamage);
                        attackCooldown = 40;
                    }
                }
            }
        }
    }

    private LivingEntity findNearestHostile(double radius) {
        AABB area = getBoundingBox().inflate(radius);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Monster m : level().getEntitiesOfClass(Monster.class, area)) {
            if (!m.isAlive()) continue;
            double d = m.position().distanceToSqr(position());
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    private void handleBluetoothMessage(String raw) {
        if (raw == null || !raw.startsWith("drone:")) return;
        String[] parts = raw.split(":");
        if (parts.length < 2) return;

        // Swarm routing: "drone:swarm:<group>:<real-cmd>:..." is only obeyed
        // if the drone's swarmGroup matches. Non-swarm messages are always
        // obeyed (unless a swarm group is set and the message isn't targeted).
        String cmd;
        String[] effectiveParts;
        if ("swarm".equals(parts[1])) {
            if (parts.length < 4) return;
            if (!swarmGroup.equals(parts[2])) return;
            cmd = parts[3];
            // Strip the "drone:swarm:<group>:" prefix: remap parts[0..] to ["drone", cmd, ...tail]
            String[] tail = new String[parts.length - 4];
            System.arraycopy(parts, 4, tail, 0, tail.length);
            effectiveParts = new String[tail.length + 2];
            effectiveParts[0] = "drone";
            effectiveParts[1] = cmd;
            System.arraycopy(tail, 0, effectiveParts, 2, tail.length);
        } else {
            cmd = parts[1];
            effectiveParts = parts;
        }

        try {
            switch (cmd) {
                case "waypoint" -> {
                    if (effectiveParts.length >= 5) {
                        addWaypoint(new Vec3(
                                Double.parseDouble(effectiveParts[2]),
                                Double.parseDouble(effectiveParts[3]),
                                Double.parseDouble(effectiveParts[4])));
                    }
                }
                case "home" -> {
                    waypoints.clear();
                    if (homePos != null) {
                        addWaypoint(new Vec3(homePos.getX() + 0.5, homePos.getY() + 1, homePos.getZ() + 0.5));
                    }
                }
                case "clear" -> waypoints.clear();
                case "hover" -> {
                    if (effectiveParts.length >= 3) hovering = Boolean.parseBoolean(effectiveParts[2]);
                }
                case "refuel" -> {
                    if (effectiveParts.length >= 3) addFuel(Integer.parseInt(effectiveParts[2]));
                }
                case "pickup" -> {
                    if (effectiveParts.length >= 5) {
                        BlockPos target = new BlockPos(
                                Integer.parseInt(effectiveParts[2]),
                                Integer.parseInt(effectiveParts[3]),
                                Integer.parseInt(effectiveParts[4]));
                        int max = effectiveParts.length >= 6 ? Integer.parseInt(effectiveParts[5]) : 64;
                        pickupFromContainer(target, max);
                    }
                }
                case "drop" -> {
                    if (effectiveParts.length >= 5) {
                        BlockPos target = new BlockPos(
                                Integer.parseInt(effectiveParts[2]),
                                Integer.parseInt(effectiveParts[3]),
                                Integer.parseInt(effectiveParts[4]));
                        int max = effectiveParts.length >= 6 ? Integer.parseInt(effectiveParts[5]) : 64;
                        dropIntoContainer(target, max);
                    }
                }
                case "defender" -> {
                    if (effectiveParts.length >= 3) defender = Boolean.parseBoolean(effectiveParts[2]);
                }
                case "group" -> {
                    if (effectiveParts.length >= 3) swarmGroup = effectiveParts[2];
                    else swarmGroup = "";
                }
                case "variant" -> {
                    if (effectiveParts.length >= 3) setVariantByName(effectiveParts[2]);
                }
                case "scan" -> {
                    if (level() != null) {
                        int radius = effectiveParts.length >= 3
                                ? Math.max(1, Math.min(Integer.parseInt(effectiveParts[2]), 16))
                                : 8;
                        broadcastScanResults(radius);
                    }
                }
                default -> { /* unknown */ }
            }
        } catch (NumberFormatException ignored) {
            // Malformed command — ignore silently.
        }
    }

    /**
     * Run an entity scan from our current position and broadcast the results on
     * our Bluetooth channel as one BT message per entity, plus a final summary.
     * Controllers listen via bluetooth.receive() and filter on the "drone:scanresult:" prefix.
     * Format:  drone:scanresult:<droneUuid>:<type>:<x>:<y>:<z>:<health>:<isPlayer>:<name>
     * Followed by: drone:scandone:<droneUuid>:<count>
     */
    private void broadcastScanResults(int radius) {
        Level lvl = level();
        if (lvl == null) return;
        com.apocscode.byteblock.scanner.WorldScanData data =
                new com.apocscode.byteblock.scanner.WorldScanData();
        data.scanEntities(lvl, blockPosition(), radius);
        int count = 0;
        for (com.apocscode.byteblock.scanner.WorldScanData.EntitySnapshot e : data.getEntities()) {
            String msg = "drone:scanresult:" + droneId + ":"
                    + e.type() + ":" + e.x() + ":" + e.y() + ":" + e.z()
                    + ":" + e.health() + ":" + e.isPlayer()
                    + ":" + (e.name() == null ? "" : e.name().replace(":", " "));
            BluetoothNetwork.broadcast(lvl, blockPosition(), bluetoothChannel, msg);
            count++;
        }
        BluetoothNetwork.broadcast(lvl, blockPosition(), bluetoothChannel,
                "drone:scandone:" + droneId + ":" + count);
    }

    /** Apply a variant by name (case-insensitive). Unknown names fall back to STANDARD. */
    public void setVariantByName(String name) {
        try {
            this.variant = DroneVariant.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            this.variant = DroneVariant.STANDARD;
        }
    }

    public DroneVariant getVariant() { return variant; }
    public void setVariant(DroneVariant v) { this.variant = v == null ? DroneVariant.STANDARD : v; }

    /** Drone variants — apply stat multipliers to flight speed, fuel drain and combat. */
    public enum DroneVariant {
        STANDARD(1.0f, 1.0f, 4.0f, 5.0),   // speed, fuelDrain, atkDmg, aggroRadius
        CARGO(0.7f, 1.5f, 0.0f, 0.0),      // slow, thirsty, no combat
        DEFENDER(1.3f, 1.2f, 7.0f, 10.0),  // fast, heavy hitter, wider aggro
        SCOUT(1.6f, 0.6f, 0.0f, 0.0);      // very fast, fuel-efficient, no combat

        public final float speedMul;
        public final float fuelDrainMul;
        public final float attackDamage;
        public final double aggroRadius;
        DroneVariant(float s, float f, float a, double r) {
            this.speedMul = s; this.fuelDrainMul = f; this.attackDamage = a; this.aggroRadius = r;
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide()) {
            ItemStack held = player.getItemInHand(hand);
            int fuelValue = fuelValueFor(held);
            if (fuelValue > 0) {
                addFuel(fuelValue);
                if (!player.getAbilities().instabuild) held.shrink(1);
                player.sendSystemMessage(Component.literal(
                        "[ByteBlock Drone] +" + (fuelValue / 20) + "s fuel (now " + (fuelTicks / 20) + "s)."));
                return InteractionResult.sidedSuccess(false);
            }

            if (ownerId == null) {
                ownerId = player.getUUID();
                player.sendSystemMessage(Component.literal("[ByteBlock Drone] Linked to you."));
            } else if (ownerId.equals(player.getUUID())) {
                player.sendSystemMessage(Component.literal(
                        "[ByteBlock Drone] ID " + droneId.toString().substring(0, 8)
                                + " | Ch " + bluetoothChannel
                                + " | Fuel " + (fuelTicks / 20) + "s"
                                + " | Waypoints " + waypoints.size()));
            } else {
                player.sendSystemMessage(Component.literal("[ByteBlock Drone] Not your drone."));
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    private static int fuelValueFor(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        var item = stack.getItem();
        if (item == Items.COAL || item == Items.CHARCOAL) return 1600;
        if (item == Items.COAL_BLOCK) return 16000;
        if (item == Items.BLAZE_ROD) return 2400;
        if (item == Items.LAVA_BUCKET) return 20000;
        return 0;
    }

    // --- Programmable API ---

    public void addWaypoint(Vec3 target) {
        if (waypoints.size() < 64) waypoints.add(target);
    }

    public void clearWaypoints() { waypoints.clear(); }
    public void setHovering(boolean hover) { this.hovering = hover; }
    public boolean isHovering() { return hovering; }
    public int getFuelTicks() { return fuelTicks; }
    public int getFuel() { return fuelTicks; } // alias — matches docs naming
    public void addFuel(int ticks) { this.fuelTicks = Math.min(fuelTicks + ticks, MAX_FUEL); }
    public void linkComputer(UUID computerId) { this.linkedComputerId = computerId; }
    public UUID getDroneId() { return droneId; }
    public int getBluetoothChannel() { return bluetoothChannel; }
    public void setBluetoothChannel(int ch) { this.bluetoothChannel = Math.max(1, Math.min(65535, ch)); }
    public int getWaypointCount() { return waypoints.size(); }
    public SimpleContainer getInventory() { return inventory; }
    public BlockPos getHomePos() { return homePos; }
    public void setHomePos(BlockPos pos) { this.homePos = pos; }
    public boolean isDefender() { return defender; }
    public void setDefender(boolean d) { this.defender = d; }
    public String getSwarmGroup() { return swarmGroup; }
    public void setSwarmGroup(String g) { this.swarmGroup = g == null ? "" : g; }

    /** True if a ChargingStation is actively charging this drone (AABB overlap, within 3 blocks). */
    public boolean isCharging() {
        if (level() == null) return false;
        net.minecraft.world.phys.AABB area =
                new net.minecraft.world.phys.AABB(blockPosition()).inflate(3.0);
        for (BlockPos p : BlockPos.betweenClosed(
                BlockPos.containing(area.minX, area.minY, area.minZ),
                BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
            BlockEntity be = level().getBlockEntity(p);
            if (be instanceof com.apocscode.byteblock.block.entity.ChargingStationBlockEntity cs
                    && cs.getEnergyStored() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pull up to `max` items from the container at target into this drone's inventory.
     * Returns number of items moved.
     */
    public int pickupFromContainer(BlockPos target, int max) {
        if (level() == null || position().distanceToSqr(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 9) return 0;
        BlockEntity be = level().getBlockEntity(target);
        if (!(be instanceof Container src)) return 0;
        int moved = 0;
        for (int i = 0; i < src.getContainerSize() && moved < max; i++) {
            ItemStack stack = src.getItem(i);
            if (stack.isEmpty()) continue;
            int take = Math.min(stack.getCount(), max - moved);
            ItemStack piece = stack.copy();
            piece.setCount(take);
            ItemStack leftover = inventory.addItem(piece);
            int consumed = take - leftover.getCount();
            if (consumed > 0) {
                stack.shrink(consumed);
                src.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                src.setChanged();
                moved += consumed;
            }
        }
        return moved;
    }

    /**
     * Push up to `max` items from this drone's inventory into the container at target.
     * Returns number of items moved.
     */
    public int dropIntoContainer(BlockPos target, int max) {
        if (level() == null || position().distanceToSqr(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 9) return 0;
        BlockEntity be = level().getBlockEntity(target);
        if (!(be instanceof Container dst)) return 0;
        int moved = 0;
        for (int i = 0; i < inventory.getContainerSize() && moved < max; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            int take = Math.min(stack.getCount(), max - moved);
            ItemStack piece = stack.copy();
            piece.setCount(take);
            ItemStack leftover = insertIntoContainer(dst, piece);
            int consumed = take - leftover.getCount();
            if (consumed > 0) {
                stack.shrink(consumed);
                inventory.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                dst.setChanged();
                moved += consumed;
            }
        }
        return moved;
    }

    private static ItemStack insertIntoContainer(Container dst, ItemStack stack) {
        // Try to merge with existing stacks first.
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
        // Then empty slots.
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

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("OwnerId", ownerId);
        if (droneId != null) tag.putUUID("DroneId", droneId);
        if (linkedComputerId != null) tag.putUUID("LinkedComputer", linkedComputerId);
        tag.putInt("BluetoothChannel", bluetoothChannel);
        tag.putInt("FuelTicks", fuelTicks);
        tag.putBoolean("Hovering", hovering);
        if (homePos != null) {
            tag.putInt("HomeX", homePos.getX());
            tag.putInt("HomeY", homePos.getY());
            tag.putInt("HomeZ", homePos.getZ());
        }
        tag.putBoolean("Defender", defender);
        tag.putString("SwarmGroup", swarmGroup);
        tag.putString("Variant", variant.name());
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
        if (tag.contains("DroneId")) droneId = tag.getUUID("DroneId");
        if (tag.contains("LinkedComputer")) linkedComputerId = tag.getUUID("LinkedComputer");
        if (tag.contains("BluetoothChannel")) bluetoothChannel = tag.getInt("BluetoothChannel");
        if (tag.contains("FuelTicks")) fuelTicks = tag.getInt("FuelTicks");
        if (tag.contains("Hovering")) hovering = tag.getBoolean("Hovering");
        if (tag.contains("HomeX")) {
            homePos = new BlockPos(tag.getInt("HomeX"), tag.getInt("HomeY"), tag.getInt("HomeZ"));
        }
        if (tag.contains("Defender")) defender = tag.getBoolean("Defender");
        if (tag.contains("SwarmGroup")) swarmGroup = tag.getString("SwarmGroup");
        if (tag.contains("Variant")) setVariantByName(tag.getString("Variant"));
        if (tag.contains("Inventory")) {
            CompoundTag invTag = tag.getCompound("Inventory");
            for (String key : invTag.getAllKeys()) {
                try {
                    int slot = Integer.parseInt(key);
                    inventory.setItem(slot,
                            ItemStack.parse(level().registryAccess(), invTag.getCompound(key))
                                    .orElse(ItemStack.EMPTY));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }
}

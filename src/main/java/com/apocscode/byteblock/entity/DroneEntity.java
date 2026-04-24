package com.apocscode.byteblock.entity;

import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
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
        BluetoothNetwork.register(level(), droneId, blockPosition(), bluetoothChannel);
        BluetoothNetwork.Message msg;
        while ((msg = BluetoothNetwork.receive(droneId)) != null) {
            handleBluetoothMessage(msg.content());
        }
    }

    private void handleBluetoothMessage(String raw) {
        if (raw == null || !raw.startsWith("drone:")) return;
        String[] parts = raw.split(":");
        if (parts.length < 2) return;
        String cmd = parts[1];
        try {
            switch (cmd) {
                case "waypoint" -> {
                    if (parts.length >= 5) {
                        addWaypoint(new Vec3(
                                Double.parseDouble(parts[2]),
                                Double.parseDouble(parts[3]),
                                Double.parseDouble(parts[4])));
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
                    if (parts.length >= 3) hovering = Boolean.parseBoolean(parts[2]);
                }
                case "refuel" -> {
                    if (parts.length >= 3) addFuel(Integer.parseInt(parts[2]));
                }
                default -> { /* unknown */ }
            }
        } catch (NumberFormatException ignored) {
            // Malformed command — ignore silently.
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
    public void addFuel(int ticks) { this.fuelTicks = Math.min(fuelTicks + ticks, MAX_FUEL); }
    public void linkComputer(UUID computerId) { this.linkedComputerId = computerId; }
    public UUID getDroneId() { return droneId; }
    public int getBluetoothChannel() { return bluetoothChannel; }
    public void setBluetoothChannel(int ch) { this.bluetoothChannel = Math.max(1, Math.min(65535, ch)); }
    public int getWaypointCount() { return waypoints.size(); }
    public SimpleContainer getInventory() { return inventory; }
    public BlockPos getHomePos() { return homePos; }
    public void setHomePos(BlockPos pos) { this.homePos = pos; }

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

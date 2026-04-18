package com.apocscode.byteblock.entity;

import com.apocscode.byteblock.init.ModEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * Flying Drone entity — a programmable flying device.
 * Can be given movement commands by a connected computer.
 * Hovers in place when idle. Has inventory for item transport.
 */
public class DroneEntity extends PathfinderMob {
    private UUID ownerId = null;
    private UUID linkedComputerId = null;
    private int bluetoothChannel = 1;
    private final Queue<Vec3> waypoints = new LinkedList<>();
    private boolean hovering = true;
    private int fuelTicks = 6000; // 5 minutes of flight

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
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

        if (fuelTicks > 0 && hovering) {
            fuelTicks--;
            // Hover in place
            setDeltaMovement(Vec3.ZERO);
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

        // Register on Bluetooth network
        if (linkedComputerId != null) {
            BluetoothNetwork.register(level(), linkedComputerId, blockPosition(), bluetoothChannel);
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide()) {
            if (ownerId == null) {
                ownerId = player.getUUID();
                player.sendSystemMessage(Component.literal("[ByteBlock Drone] Linked to you."));
            } else if (ownerId.equals(player.getUUID())) {
                player.sendSystemMessage(Component.literal(
                        "[ByteBlock Drone] Fuel: " + (fuelTicks / 20) + "s | Waypoints: " + waypoints.size()));
            } else {
                player.sendSystemMessage(Component.literal("[ByteBlock Drone] Not your drone."));
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    // --- Programmable API ---

    public void addWaypoint(Vec3 target) {
        if (waypoints.size() < 64) waypoints.add(target);
    }

    public void clearWaypoints() { waypoints.clear(); }
    public void setHovering(boolean hover) { this.hovering = hover; }
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
        tag.putBoolean("Hovering", hovering);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("OwnerId")) ownerId = tag.getUUID("OwnerId");
        if (tag.contains("LinkedComputer")) linkedComputerId = tag.getUUID("LinkedComputer");
        if (tag.contains("BluetoothChannel")) bluetoothChannel = tag.getInt("BluetoothChannel");
        if (tag.contains("FuelTicks")) fuelTicks = tag.getInt("FuelTicks");
        if (tag.contains("Hovering")) hovering = tag.getBoolean("Hovering");
    }
}

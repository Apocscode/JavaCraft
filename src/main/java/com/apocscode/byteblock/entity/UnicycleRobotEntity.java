package com.apocscode.byteblock.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * Player-sized variant of the robot — single-tire balancing chassis with long
 * inventory-access arms and a chest-mounted face screen. Inherits all of the
 * RobotEntity behavior (Lua API, command queue, FE energy, Bluetooth, R2D2
 * chirps); only the visual size + a small speed/health tweak differ.
 *
 * Registered as its own EntityType so the renderer can be bound separately
 * via {@link com.apocscode.byteblock.client.UnicycleRobotRenderer}.
 */
public class UnicycleRobotEntity extends RobotEntity {

    public UnicycleRobotEntity(EntityType<? extends RobotEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 24.0)        // a touch frailer than the tank chassis
                .add(Attributes.MOVEMENT_SPEED, 0.25)    // unicycle is faster on flat ground
                .add(Attributes.ATTACK_DAMAGE, 3.0);
    }
}

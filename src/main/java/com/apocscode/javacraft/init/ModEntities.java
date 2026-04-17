package com.apocscode.javacraft.init;

import com.apocscode.javacraft.JavaCraft;
import com.apocscode.javacraft.entity.DroneEntity;
import com.apocscode.javacraft.entity.RobotEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, JavaCraft.MODID);

    // Flying Drone — programmable flying entity
    public static final DeferredHolder<EntityType<?>, EntityType<DroneEntity>> DRONE =
            ENTITIES.register("drone",
                    () -> EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC)
                            .sized(0.6f, 0.4f)
                            .clientTrackingRange(8)
                            .build(JavaCraft.MODID + ":drone"));

    // Robot — turtle-like programmable ground entity
    public static final DeferredHolder<EntityType<?>, EntityType<RobotEntity>> ROBOT =
            ENTITIES.register("robot",
                    () -> EntityType.Builder.<RobotEntity>of(RobotEntity::new, MobCategory.MISC)
                            .sized(0.9f, 0.9f)
                            .clientTrackingRange(8)
                            .build(JavaCraft.MODID + ":robot"));
}

package com.apocscode.byteblock.init;

import com.apocscode.byteblock.ByteBlock;
import com.apocscode.byteblock.block.entity.*;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ByteBlock.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ComputerBlockEntity>> COMPUTER =
            BLOCK_ENTITIES.register("computer",
                    () -> BlockEntityType.Builder.of(ComputerBlockEntity::new, ModBlocks.COMPUTER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PeripheralBlockEntity>> PERIPHERAL =
            BLOCK_ENTITIES.register("peripheral",
                    () -> BlockEntityType.Builder.of(PeripheralBlockEntity::new, ModBlocks.PERIPHERAL.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GpsBlockEntity>> GPS =
            BLOCK_ENTITIES.register("gps",
                    () -> BlockEntityType.Builder.of(GpsBlockEntity::new, ModBlocks.GPS.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScannerBlockEntity>> SCANNER =
            BLOCK_ENTITIES.register("scanner",
                    () -> BlockEntityType.Builder.of(ScannerBlockEntity::new, ModBlocks.SCANNER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DriveBlockEntity>> DRIVE =
            BLOCK_ENTITIES.register("drive",
                    () -> BlockEntityType.Builder.of(DriveBlockEntity::new, ModBlocks.DRIVE.get()).build(null));
}

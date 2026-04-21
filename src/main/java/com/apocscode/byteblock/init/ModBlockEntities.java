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

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PrinterBlockEntity>> PRINTER =
            BLOCK_ENTITIES.register("printer",
                    () -> BlockEntityType.Builder.of(PrinterBlockEntity::new, ModBlocks.PRINTER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChargingStationBlockEntity>> CHARGING_STATION =
            BLOCK_ENTITIES.register("charging_station",
                    () -> BlockEntityType.Builder.of(ChargingStationBlockEntity::new, ModBlocks.CHARGING_STATION.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MonitorBlockEntity>> MONITOR =
            BLOCK_ENTITIES.register("monitor",
                    () -> BlockEntityType.Builder.of(MonitorBlockEntity::new, ModBlocks.MONITOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RedstoneRelayBlockEntity>> REDSTONE_RELAY =
            BLOCK_ENTITIES.register("redstone_relay",
                    () -> BlockEntityType.Builder.of(RedstoneRelayBlockEntity::new, ModBlocks.REDSTONE_RELAY.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ButtonPanelBlockEntity>> BUTTON_PANEL =
            BLOCK_ENTITIES.register("button_panel",
                    () -> BlockEntityType.Builder.of(ButtonPanelBlockEntity::new, ModBlocks.BUTTON_PANEL.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.apocscode.byteblock.block.entity.ByteChestBlockEntity>> BYTE_CHEST =
            BLOCK_ENTITIES.register("byte_chest",
                    () -> BlockEntityType.Builder.of(com.apocscode.byteblock.block.entity.ByteChestBlockEntity::new, ModBlocks.BYTE_CHEST.get()).build(null));
}

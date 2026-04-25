package com.apocscode.byteblock.init;

import com.apocscode.byteblock.ByteBlock;
import com.apocscode.byteblock.block.*;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ByteBlock.MODID);

    // Computer Terminal — the main programmable computer
    public static final DeferredBlock<ComputerBlock> COMPUTER = BLOCKS.register("computer",
            () -> new ComputerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(2.0f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    // Universal Peripheral — connects to adjacent blocks and exposes their API
    public static final DeferredBlock<PeripheralBlock> PERIPHERAL = BLOCKS.register("peripheral",
            () -> new PeripheralBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(1.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    // GPS Block — provides location services to the computer network
    public static final DeferredBlock<GpsBlock> GPS = BLOCKS.register("gps",
            () -> new GpsBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLUE)
                    .strength(1.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
                    .lightLevel(state -> 7)));

    // Scanner Block — scans entities/blocks in a configurable radius
    public static final DeferredBlock<ScannerBlock> SCANNER = BLOCKS.register("scanner",
            () -> new ScannerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(1.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    // Drive — accepts disk items to save/load programs
    public static final DeferredBlock<DriveBlock> DRIVE = BLOCKS.register("drive",
            () -> new DriveBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(2.0f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    // Printer — prints text files to paper, books, and clipboards
    public static final DeferredBlock<PrinterBlock> PRINTER = BLOCKS.register("printer",
            () -> new PrinterBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(2.0f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    // Charging Station — charges Robots (FE) and Drones (fuel) standing nearby
    public static final DeferredBlock<ChargingStationBlock> CHARGING_STATION = BLOCKS.register("charging_station",
            () -> new ChargingStationBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(2.0f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 3)));

    // Monitor — multi-block display that mirrors a linked Computer's screen
    public static final DeferredBlock<MonitorBlock> MONITOR = BLOCKS.register("monitor",
            () -> new MonitorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(2.0f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 7)
                    .noOcclusion()));

    // Redstone Relay — bridge between computer programs and physical redstone
    public static final DeferredBlock<RedstoneRelayBlock> REDSTONE_RELAY = BLOCKS.register("redstone_relay",
            () -> new RedstoneRelayBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(2.0f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    // Button Panel — 16-color button grid, sends BT events to computers
    public static final DeferredBlock<ButtonPanelBlock> BUTTON_PANEL = BLOCKS.register("button_panel",
            () -> new ButtonPanelBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(1.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    // ByteChest — Bluetooth-enabled smart storage, scannable from Materials Calculator
    public static final DeferredBlock<ByteChestBlock> BYTE_CHEST = BLOCKS.register("byte_chest",
            () -> new ByteChestBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(2.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));
}

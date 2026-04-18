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
                    .lightLevel(state -> 7)));

    // Scanner Block — scans entities/blocks in a configurable radius
    public static final DeferredBlock<ScannerBlock> SCANNER = BLOCKS.register("scanner",
            () -> new ScannerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(1.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    // Drive — accepts disk items to save/load programs
    public static final DeferredBlock<DriveBlock> DRIVE = BLOCKS.register("drive",
            () -> new DriveBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(2.0f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));
}

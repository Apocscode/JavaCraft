package com.apocscode.byteblock.init;

import com.apocscode.byteblock.ByteBlock;
import com.apocscode.byteblock.item.DiskItem;
import com.apocscode.byteblock.item.GlassesItem;
import com.apocscode.byteblock.item.GpsToolItem;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ByteBlock.MODID);

    // Block items
    public static final DeferredItem<BlockItem> COMPUTER_ITEM = ITEMS.registerSimpleBlockItem("computer", ModBlocks.COMPUTER);
    public static final DeferredItem<BlockItem> PERIPHERAL_ITEM = ITEMS.registerSimpleBlockItem("peripheral", ModBlocks.PERIPHERAL);
    public static final DeferredItem<BlockItem> GPS_ITEM = ITEMS.registerSimpleBlockItem("gps", ModBlocks.GPS);
    public static final DeferredItem<BlockItem> SCANNER_ITEM = ITEMS.registerSimpleBlockItem("scanner", ModBlocks.SCANNER);
    public static final DeferredItem<BlockItem> DRIVE_ITEM = ITEMS.registerSimpleBlockItem("drive", ModBlocks.DRIVE);

    public static final DeferredItem<BlockItem> PRINTER_ITEM = ITEMS.registerSimpleBlockItem("printer", ModBlocks.PRINTER);

    // Disk — stores programs, insertable into Drive block
    public static final DeferredItem<DiskItem> DISK = ITEMS.register("disk",
            () -> new DiskItem(new Item.Properties().stacksTo(1)));

    // Glasses — Google Glass-like HUD overlay worn as helmet
    public static final DeferredItem<GlassesItem> GLASSES = ITEMS.register("glasses",
            () -> new GlassesItem(new Item.Properties().stacksTo(1).durability(500)));

    // GPS Tool — stores a block position for use with Puzzle IDE / drones
    public static final DeferredItem<GpsToolItem> GPS_TOOL = ITEMS.register("gps_tool",
            () -> new GpsToolItem(new Item.Properties().stacksTo(1)));

    // Charging station block item
    public static final DeferredItem<BlockItem> CHARGING_STATION_ITEM = ITEMS.registerSimpleBlockItem("charging_station", ModBlocks.CHARGING_STATION);

    // Monitor block item
    public static final DeferredItem<BlockItem> MONITOR_ITEM = ITEMS.registerSimpleBlockItem("monitor", ModBlocks.MONITOR);

    // Spawn eggs
    public static final DeferredItem<SpawnEggItem> ROBOT_SPAWN_EGG = ITEMS.register("robot_spawn_egg",
            () -> new SpawnEggItem(ModEntities.ROBOT.get(), 0x666666, 0xFFAA00, new Item.Properties()));

    public static final DeferredItem<SpawnEggItem> UNICYCLE_ROBOT_SPAWN_EGG = ITEMS.register("unicycle_robot_spawn_egg",
            () -> new SpawnEggItem(ModEntities.UNICYCLE_ROBOT.get(), 0xEEEEEE, 0x00CCFF, new Item.Properties()));

    public static final DeferredItem<SpawnEggItem> DRONE_SPAWN_EGG = ITEMS.register("drone_spawn_egg",
            () -> new SpawnEggItem(ModEntities.DRONE.get(), 0xDDDDDD, 0x00AAFF, new Item.Properties()));

    // Redstone blocks
    public static final DeferredItem<BlockItem> REDSTONE_RELAY_ITEM = ITEMS.registerSimpleBlockItem("redstone_relay", ModBlocks.REDSTONE_RELAY);
    public static final DeferredItem<BlockItem> BUTTON_PANEL_ITEM = ITEMS.registerSimpleBlockItem("button_panel", ModBlocks.BUTTON_PANEL);

    // ByteChest
    public static final DeferredItem<BlockItem> BYTE_CHEST_ITEM = ITEMS.registerSimpleBlockItem("byte_chest", ModBlocks.BYTE_CHEST);

}

package com.apocscode.byteblock.init;

import com.apocscode.byteblock.ByteBlock;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ByteBlock.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BYTEBLOCK_TAB =
            TABS.register("byteblock_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.byteblock"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> ModItems.COMPUTER_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        // Blocks
                        output.accept(ModItems.COMPUTER_ITEM.get());
                        output.accept(ModItems.PERIPHERAL_ITEM.get());
                        output.accept(ModItems.GPS_ITEM.get());
                        output.accept(ModItems.SCANNER_ITEM.get());
                        output.accept(ModItems.DRIVE_ITEM.get());
                        output.accept(ModItems.PRINTER_ITEM.get());
                        output.accept(ModItems.CHARGING_STATION_ITEM.get());
                        output.accept(ModItems.MONITOR_ITEM.get());
                        output.accept(ModItems.REDSTONE_RELAY_ITEM.get());
                        output.accept(ModItems.BUTTON_PANEL_ITEM.get());
                        output.accept(ModItems.BYTE_CHEST_ITEM.get());
                        // Items
                        output.accept(ModItems.DISK.get());
                        output.accept(ModItems.GLASSES.get());
                        output.accept(ModItems.GPS_TOOL.get());
                        // Entities
                        output.accept(ModItems.ROBOT_SPAWN_EGG.get());
                        output.accept(ModItems.DRONE_SPAWN_EGG.get());
                    }).build());
}

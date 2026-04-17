package com.apocscode.javacraft.init;

import com.apocscode.javacraft.JavaCraft;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, JavaCraft.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> JAVACRAFT_TAB =
            TABS.register("javacraft_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.javacraft"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> ModItems.COMPUTER_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        // Blocks
                        output.accept(ModItems.COMPUTER_ITEM.get());
                        output.accept(ModItems.PERIPHERAL_ITEM.get());
                        output.accept(ModItems.GPS_ITEM.get());
                        output.accept(ModItems.SCANNER_ITEM.get());
                        output.accept(ModItems.DRIVE_ITEM.get());
                        // Items
                        output.accept(ModItems.DISK.get());
                        output.accept(ModItems.GLASSES.get());
                        // Entities (spawn eggs)
                        output.accept(ModItems.DRONE_SPAWN_EGG.get());
                        output.accept(ModItems.ROBOT_SPAWN_EGG.get());
                    }).build());
}

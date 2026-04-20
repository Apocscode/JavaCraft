package com.apocscode.byteblock.init;

import com.apocscode.byteblock.ByteBlock;
import com.apocscode.byteblock.menu.DriveMenu;
import com.apocscode.byteblock.menu.PrinterMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, ByteBlock.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<PrinterMenu>> PRINTER =
            MENUS.register("printer", () -> IMenuTypeExtension.create(PrinterMenu::fromNetwork));

    public static final DeferredHolder<MenuType<?>, MenuType<DriveMenu>> DRIVE =
            MENUS.register("drive", () -> IMenuTypeExtension.create(DriveMenu::fromNetwork));
}

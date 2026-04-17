package com.apocscode.javacraft.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Disk item — stores programs via NBT CustomData.
 * Insert into a Drive block to read/write from computers.
 */
public class DiskItem extends Item {

    public DiskItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);

        var customData = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();

        if (customData.contains("Label")) {
            tooltipComponents.add(Component.literal("Label: " + customData.getString("Label"))
                    .withStyle(net.minecraft.ChatFormatting.AQUA));
        }

        if (customData.contains("Files")) {
            int fileCount = customData.getCompound("Files").getAllKeys().size();
            tooltipComponents.add(Component.literal(fileCount + " file(s)")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        } else {
            tooltipComponents.add(Component.literal("Empty disk")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
    }
}

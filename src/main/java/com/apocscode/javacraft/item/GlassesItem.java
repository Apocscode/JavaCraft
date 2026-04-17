package com.apocscode.javacraft.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Glasses item — Google Glass-like wearable HUD.
 * Equips to the helmet slot and provides an overlay showing
 * computer output, entity info, and Bluetooth messages.
 */
public class GlassesItem extends Item implements Equipable {

    public GlassesItem(Properties properties) {
        super(properties);
    }

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        tooltipComponents.add(Component.literal("Wearable HUD — equip on head")
                .withStyle(net.minecraft.ChatFormatting.GREEN));
        tooltipComponents.add(Component.literal("Displays computer output & Bluetooth messages")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}

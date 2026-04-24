package com.apocscode.byteblock.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * Smart Glasses — head-slot wearable that renders server-pushed HUD widgets.
 * Glasses are "paired" to a bluetooth channel (0..255) so a computer on the
 * same channel can broadcast widget layouts via the glasses.* Lua API.
 */
public class GlassesItem extends Item implements Equipable {

    public static final int DEFAULT_CHANNEL = 1;
    private static final String NBT_CHANNEL = "BtChannel";

    public GlassesItem(Properties properties) {
        super(properties);
    }

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    public static int getChannel(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(NBT_CHANNEL) ? tag.getInt(NBT_CHANNEL) : DEFAULT_CHANNEL;
    }

    public static void setChannel(ItemStack stack, int channel) {
        if (channel < 0) channel = 0;
        if (channel > 255) channel = 255;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt(NBT_CHANNEL, channel);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        tooltipComponents.add(Component.literal("Wearable HUD — equip on head")
                .withStyle(ChatFormatting.GREEN));
        tooltipComponents.add(Component.literal("BT channel: " + getChannel(stack))
                .withStyle(ChatFormatting.AQUA));
        tooltipComponents.add(Component.literal("Shift+Right-click to cycle channel")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(
            net.minecraft.world.level.Level level,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            int next = (getChannel(stack) + 1) & 0xFF;
            setChannel(stack, next);
            if (!level.isClientSide) {
                player.displayClientMessage(
                    Component.literal("Glasses channel: " + next).withStyle(ChatFormatting.AQUA), true);
            }
            return net.minecraft.world.InteractionResultHolder.success(stack);
        }
        return super.use(level, player, hand);
    }
}

package com.apocscode.byteblock.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * GPS Location Tool — right-click a block to store its position.
 * Shift+right-click to view the stored coordinates in chat.
 * Used with the Puzzle IDE's Goto XYZ and GPS Set pieces.
 */
public class GpsToolItem extends Item {

    public GpsToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;

        BlockPos pos = context.getClickedPos();
        ItemStack stack = context.getItemInHand();

        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            // Shift+click = view stored position
            var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (tag.contains("X")) {
                int x = tag.getInt("X");
                int y = tag.getInt("Y");
                int z = tag.getInt("Z");
                context.getPlayer().sendSystemMessage(
                        Component.literal("\u2302 GPS: ")
                                .withStyle(ChatFormatting.AQUA)
                                .append(Component.literal(x + ", " + y + ", " + z)
                                        .withStyle(ChatFormatting.WHITE)));
            } else {
                context.getPlayer().sendSystemMessage(
                        Component.literal("No position stored — right-click a block to set")
                                .withStyle(ChatFormatting.GRAY));
            }
        } else {
            // Normal click = store position
            var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            tag.putInt("X", pos.getX());
            tag.putInt("Y", pos.getY());
            tag.putInt("Z", pos.getZ());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

            if (context.getPlayer() != null) {
                context.getPlayer().sendSystemMessage(
                        Component.literal("\u2302 Set: ")
                                .withStyle(ChatFormatting.GREEN)
                                .append(Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
                                        .withStyle(ChatFormatting.WHITE)));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (tag.contains("X")) {
            int x = tag.getInt("X");
            int y = tag.getInt("Y");
            int z = tag.getInt("Z");
            tooltipComponents.add(Component.literal("Position: " + x + ", " + y + ", " + z)
                    .withStyle(ChatFormatting.AQUA));
        } else {
            tooltipComponents.add(Component.literal("No position set")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltipComponents.add(Component.literal("Right-click: set position")
                .withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("Shift+click: view position")
                .withStyle(ChatFormatting.GRAY));
    }
}

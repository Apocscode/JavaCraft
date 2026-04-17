package com.apocscode.javacraft.block;

import com.apocscode.javacraft.block.entity.PeripheralBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Universal Peripheral block. Place next to any block (chest, furnace, etc.)
 * to expose that block's capabilities to connected computers.
 */
public class PeripheralBlock extends Block implements EntityBlock {

    public PeripheralBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PeripheralBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PeripheralBlockEntity peripheral) {
                String type = peripheral.getDetectedType();
                player.sendSystemMessage(Component.literal("[JavaCraft] Peripheral: " + type));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}

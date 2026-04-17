package com.apocscode.javacraft.block;

import com.apocscode.javacraft.block.entity.GpsBlockEntity;
import com.apocscode.javacraft.init.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * GPS block. Broadcasts its position on the Bluetooth network.
 * Place 3+ GPS blocks to allow computers to triangulate their position.
 */
public class GpsBlock extends Block implements EntityBlock {

    public GpsBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GpsBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return type == ModBlockEntities.GPS.get()
                ? (lvl, p, st, be) -> ((GpsBlockEntity) be).serverTick()
                : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            player.sendSystemMessage(Component.literal(
                    "[JavaCraft GPS] Broadcasting at: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}

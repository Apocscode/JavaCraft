package com.apocscode.byteblock.block;

import com.apocscode.byteblock.block.entity.ByteChestBlockEntity;
import com.apocscode.byteblock.init.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
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
 * ByteChest — a Bluetooth-enabled smart storage block.
 * When linked to a Computer via BT, the Materials Calculator can scan
 * its inventory (and any adjacent AE2 ME networks) to check if required
 * crafting materials are on hand.
 */
public class ByteChestBlock extends Block implements EntityBlock {

    public ByteChestBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ByteChestBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return type == ModBlockEntities.BYTE_CHEST.get()
                ? (lvl, p, st, be) -> ((ByteChestBlockEntity) be).serverTick()
                : null;
    }

    /** Right-click opens the 27-slot chest GUI. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ByteChestBlockEntity chest) {
                player.openMenu(chest);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** Drop inventory contents when the block is broken. */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ByteChestBlockEntity chest) {
                Containers.dropContents(level, pos, chest);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}

package com.apocscode.byteblock.block;

import com.apocscode.byteblock.block.entity.ScannerBlockEntity;
import com.apocscode.byteblock.init.ModBlockEntities;

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
 * Scanner block. Scans surrounding area for entities and blocks.
 * Results are available to connected computers via the API.
 */
public class ScannerBlock extends Block implements EntityBlock {

    public ScannerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScannerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return type == ModBlockEntities.SCANNER.get()
                ? (lvl, p, st, be) -> ((ScannerBlockEntity) be).serverTick()
                : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ScannerBlockEntity scanner) {
                int count = scanner.getLastEntityCount();
                player.sendSystemMessage(Component.literal(
                        "[ByteBlock Scanner] Entities in range: " + count));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}

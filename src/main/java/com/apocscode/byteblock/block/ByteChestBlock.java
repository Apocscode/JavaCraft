package com.apocscode.byteblock.block;

import com.apocscode.byteblock.block.entity.ByteChestBlockEntity;
import com.apocscode.byteblock.init.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * ByteChest — a Bluetooth-enabled smart storage block.
 * Front face (with latch) faces the player on placement.
 * BT indicator LED turns blue when a computer is connected.
 */
public class ByteChestBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty CONNECTED = BooleanProperty.create("connected");

    public ByteChestBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(CONNECTED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CONNECTED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
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

    /** Right-click opens the 27-slot chest GUI; shift+right-click (empty hand) opens the rename UI. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        boolean shiftRename = player.isShiftKeyDown() && player.getMainHandItem().isEmpty();
        if (shiftRename) {
            if (level.isClientSide()) {
                BlockEntity be = level.getBlockEntity(pos);
                String current = (be instanceof ByteChestBlockEntity chest) ? chest.getLabel() : "";
                net.minecraft.client.Minecraft.getInstance().setScreen(
                        new com.apocscode.byteblock.client.ByteChestRenameScreen(pos, current));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
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

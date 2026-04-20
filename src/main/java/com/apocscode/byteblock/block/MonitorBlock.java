package com.apocscode.byteblock.block;

import com.apocscode.byteblock.block.entity.ComputerBlockEntity;
import com.apocscode.byteblock.block.entity.MonitorBlockEntity;
import com.apocscode.byteblock.init.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class MonitorBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private static final VoxelShape SHAPE_NORTH = Block.box(0, 0, 0, 16, 16, 4);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0, 0, 12, 16, 16, 16);
    private static final VoxelShape SHAPE_EAST  = Block.box(12, 0, 0, 16, 16, 16);
    private static final VoxelShape SHAPE_WEST  = Block.box(0, 0, 0, 4, 16, 16);

    public MonitorBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case EAST  -> SHAPE_EAST;
            case WEST  -> SHAPE_WEST;
            default    -> SHAPE_NORTH;
        };
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MonitorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != ModBlockEntities.MONITOR.get() || level.isClientSide()) return null;
        return (lvl, pos, st, be) -> ((MonitorBlockEntity) be).tick();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MonitorBlockEntity monitor) {
                MonitorBlockEntity origin = monitor.getOriginEntity();
                if (origin != null && origin.getLinkedComputerPos() != null) {
                    BlockEntity compBe = level.getBlockEntity(origin.getLinkedComputerPos());
                    if (compBe instanceof ComputerBlockEntity computer) {
                        com.apocscode.byteblock.computer.JavaOS os = computer.getOS();
                        if (os.isShutdown()) os.reboot();
                        net.minecraft.client.Minecraft.getInstance().setScreen(
                                new com.apocscode.byteblock.client.ComputerScreen(os));
                    }
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos,
                           BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide()) {
            MonitorBlockEntity.reformFormation(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (!level.isClientSide()) {
                Direction facing = state.getValue(FACING);
                var neighbors = MonitorBlockEntity.getPlaneNeighborPositions(pos, facing);
                super.onRemove(state, level, pos, newState, movedByPiston);
                for (BlockPos neighbor : neighbors) {
                    if (level.getBlockEntity(neighbor) instanceof MonitorBlockEntity) {
                        MonitorBlockEntity.reformFormation(level, neighbor);
                    }
                }
            } else {
                super.onRemove(state, level, pos, newState, movedByPiston);
            }
        } else {
            super.onRemove(state, level, pos, newState, movedByPiston);
        }
    }
}

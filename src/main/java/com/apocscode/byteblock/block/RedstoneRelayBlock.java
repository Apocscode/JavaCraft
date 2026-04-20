package com.apocscode.byteblock.block;

import com.apocscode.byteblock.block.entity.RedstoneRelayBlockEntity;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Redstone Relay — bridge between the computer and physical redstone world.
 * Outputs analog redstone (0-15) per side, reads world redstone input,
 * and supports 16-color bundled cable channels via BluetoothNetwork cache.
 * Registers on Bluetooth as REDSTONE_RELAY for program discovery.
 */
public class RedstoneRelayBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty CONNECTED = BooleanProperty.create("connected");

    public RedstoneRelayBlock(Properties properties) {
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
        return new RedstoneRelayBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return type == ModBlockEntities.REDSTONE_RELAY.get()
                ? (lvl, p, st, be) -> ((RedstoneRelayBlockEntity) be).serverTick()
                : null;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        // direction = approach direction from the querying neighbor
        // The face of our block being queried = direction.getOpposite()
        int side = direction.getOpposite().get3DDataValue();
        return BluetoothNetwork.getRedstoneOutput(pos, side);
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RedstoneRelayBlockEntity relay) {
                StringBuilder sb = new StringBuilder("[ByteBlock] Redstone Relay");
                if (relay.isConnected()) {
                    sb.append(" \u2014 Connected\n");
                    for (int i = 0; i < 6; i++) {
                        Direction d = Direction.from3DDataValue(i);
                        int out = relay.getOutput(i);
                        int in = relay.getInput(i);
                        if (out > 0 || in > 0) {
                            sb.append(d.getName()).append(": out=").append(out).append(" in=").append(in).append(" ");
                        }
                    }
                } else {
                    sb.append(" \u2014 No computer in range");
                }
                player.sendSystemMessage(Component.literal(sb.toString()));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            // Clear redstone output cache for this position
            for (int i = 0; i < 6; i++) {
                BluetoothNetwork.setRedstoneOutput(pos, i, 0);
                BluetoothNetwork.setBundledOutput(pos, i, 0);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        // Force the entity to re-read inputs on neighbor change
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof RedstoneRelayBlockEntity relay) {
            relay.markInputDirty();
        }
    }
}

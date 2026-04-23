package com.apocscode.byteblock.block;

import com.apocscode.byteblock.block.entity.ComputerBlockEntity;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The main Computer block. Right-click to open the terminal GUI.
 * Has a facing direction so the screen faces the player.
 *
 * The computer also acts as a virtual Button Panel and emits redstone +
 * bundled cable signals on all 6 sides based on its virtual panel state
 * (see ComputerBlockEntity.IButtonPanel implementation).
 */
public class ComputerBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty CONNECTED = BooleanProperty.create("connected");

    public ComputerBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(CONNECTED, false));
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
        return new ComputerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // Tick on both client and server — OS needs to run client-side for screen rendering
        return type == ModBlockEntities.COMPUTER.get()
                ? (lvl, pos, st, be) -> ((ComputerBlockEntity) be).serverTick()
                : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ComputerBlockEntity computer) {
                com.apocscode.byteblock.computer.JavaOS os = computer.getOS();
                if (os.isShutdown()) {
                    os.reboot();
                }
                net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.apocscode.byteblock.client.ComputerScreen(os));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    // --- Redstone output (driven by virtual button panel in ComputerBlockEntity) ---

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        int side = direction.getOpposite().get3DDataValue();
        return BluetoothNetwork.getRedstoneOutput(pos, side);
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            for (int i = 0; i < 6; i++) {
                BluetoothNetwork.setRedstoneOutput(pos, i, 0);
                BluetoothNetwork.setBundledOutput(pos, i, 0);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}

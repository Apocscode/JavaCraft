package com.apocscode.byteblock.block;

import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Button Panel — a thin wall block with 16 colored buttons in a 4×4 grid.
 * Each button maps to one of Minecraft's 16 dye colors.
 * Player clicks toggle buttons and send Bluetooth events to linked computers.
 * Computers can also set button states (lit/unlit) via Bluetooth messages.
 * Sneak+click opens the configuration GUI. Outputs redstone and bundled cable signals.
 */
public class ButtonPanelBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // Thin panel shapes (3 pixels deep, like a wall switch)
    private static final VoxelShape SHAPE_NORTH = Block.box(0, 0, 0, 16, 16, 3);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0, 0, 13, 16, 16, 16);
    private static final VoxelShape SHAPE_EAST  = Block.box(13, 0, 0, 16, 16, 16);
    private static final VoxelShape SHAPE_WEST  = Block.box(0, 0, 0, 3, 16, 16);

    public ButtonPanelBlock(Properties properties) {
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
        return new ButtonPanelBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return type == ModBlockEntities.BUTTON_PANEL.get()
                ? (lvl, p, st, be) -> ((ButtonPanelBlockEntity) be).serverTick()
                : null;
    }

    // --- Redstone output ---

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

    // --- Interaction ---

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        // Sneak+click: open configuration GUI
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) {
                openConfigGui(pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        // Normal click: press a button
        if (!level.isClientSide()) {
            Direction facing = state.getValue(FACING);
            Vec3 hit = hitResult.getLocation();

            // Calculate local coordinates on the front face (0.0-1.0)
            double localX, localY;
            switch (facing) {
                case SOUTH -> {
                    localX = 1.0 - (hit.x - pos.getX());
                    localY = 1.0 - (hit.y - pos.getY());
                }
                case EAST -> {
                    localX = 1.0 - (hit.z - pos.getZ());
                    localY = 1.0 - (hit.y - pos.getY());
                }
                case WEST -> {
                    localX = hit.z - pos.getZ();
                    localY = 1.0 - (hit.y - pos.getY());
                }
                default -> { // NORTH
                    localX = hit.x - pos.getX();
                    localY = 1.0 - (hit.y - pos.getY());
                }
            }

            // Map to 4×4 grid
            int col = Math.min(3, Math.max(0, (int) (localX * 4)));
            int row = Math.min(3, Math.max(0, (int) (localY * 4)));
            int buttonIndex = row * 4 + col;

            if (level.getBlockEntity(pos) instanceof ButtonPanelBlockEntity panel) {
                panel.toggleButton(buttonIndex, player);
                level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.3f, 1.2f);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
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

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
    }

    /** Opens the button panel config screen (client-side only). */
    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private static void openConfigGui(BlockPos pos) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.apocscode.byteblock.client.ButtonPanelScreen(pos));
    }
}

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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
    /** Full 6-direction facing: panel can mount on walls, floors, and ceilings. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    // Thin panel shapes (3 pixels deep) inset 1px on each face-plane edge so the slab is visibly
    // smaller than the supporting block (looks like a recessed/floating panel, not flush wallpaper).
    // Face footprint = 14×14 px centered. Mass hugs the supporting block's face on the depth axis.
    private static final VoxelShape SHAPE_NORTH = Block.box(1, 1, 13, 15, 15, 16); // back against wall SOUTH
    private static final VoxelShape SHAPE_SOUTH = Block.box(1, 1, 0, 15, 15, 3);   // back against wall NORTH
    private static final VoxelShape SHAPE_EAST  = Block.box(0, 1, 1, 3, 15, 15);   // back against wall WEST
    private static final VoxelShape SHAPE_WEST  = Block.box(13, 1, 1, 16, 15, 15); // back against wall EAST
    private static final VoxelShape SHAPE_UP    = Block.box(1, 0, 1, 15, 3, 15);   // back against floor BELOW
    private static final VoxelShape SHAPE_DOWN  = Block.box(1, 13, 1, 15, 16, 15); // back against ceiling ABOVE

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
        // Place against the clicked block's face: the panel's front points AWAY from that block
        // (i.e., toward the player). clickedFace is the face the player looked at, so the panel's
        // back hugs that face and its front is clickedFace itself.
        Direction clicked = context.getClickedFace();
        return this.defaultBlockState().setValue(FACING, clicked);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case EAST  -> SHAPE_EAST;
            case WEST  -> SHAPE_WEST;
            case UP    -> SHAPE_UP;
            case DOWN  -> SHAPE_DOWN;
            default    -> SHAPE_NORTH;
        };
    }

    /**
     * Emit world light proportional to the number of active buttons.
     * 0 active = 0 light, 1+ active scales up to a max of 14 (16 buttons → ~14 light).
     * Querying the block entity here is fine because vanilla calls this from the light engine
     * with a real BlockGetter (Level/ServerLevel/etc.) that supports BE lookup.
     */
    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ButtonPanelBlockEntity panel) {
            int count = Integer.bitCount(panel.getButtonStates() & 0xFFFF);
            if (count == 0) return 0;
            // Map 1..16 active → 7..15 light level so even one button emits noticeable light
            return Math.min(15, 6 + count);
        }
        return 0;
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
        int buttonIndex = computeButtonIndex(state, pos, hitResult);

        // If the click landed on the slab margin (not on a button face), do nothing.
        // Neither a normal click nor a shift-click should do anything here.
        if (buttonIndex < 0) {
            return InteractionResult.PASS;
        }

        // Sneak+click on a button face: open the configuration GUI for THAT button.
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) {
                openConfigGui(pos, buttonIndex);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        // Normal click: press that button
        if (!level.isClientSide()) {
            if (level.getBlockEntity(pos) instanceof ButtonPanelBlockEntity panel) {
                panel.toggleButton(buttonIndex, player);
                level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.3f, 1.2f);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * Ray-trace hit → 0..15 button index, or -1 if the click landed in the margin.
     * Kept consistent with ButtonPanelRenderer's per-facing orientation.
     */
    private static int computeButtonIndex(BlockState state, BlockPos pos, BlockHitResult hitResult) {
        Direction facing = state.getValue(FACING);
        Vec3 hit = hitResult.getLocation();
        double u, v;
        double hx = hit.x - pos.getX();
        double hy = hit.y - pos.getY();
        double hz = hit.z - pos.getZ();
        switch (facing) {
            case NORTH -> { u = hx;           v = 1.0 - hy; }
            case SOUTH -> { u = 1.0 - hx;     v = 1.0 - hy; }
            case WEST  -> { u = 1.0 - hz;     v = 1.0 - hy; }
            case EAST  -> { u = hz;           v = 1.0 - hy; }
            case UP    -> { u = hx;           v = 1.0 - hz; }
            case DOWN  -> { u = hx;           v = hz; }
            default    -> { u = hx;           v = 1.0 - hy; }
        }
        final float MARGIN = 0.125f;
        final float CELL_SIZE = (1.0f - 2 * MARGIN) / 4.0f;
        int col = (int) Math.floor((u - MARGIN) / CELL_SIZE);
        int row = (int) Math.floor((v - MARGIN) / CELL_SIZE);
        if (col < 0 || col > 3 || row < 0 || row > 3) return -1;
        return row * 4 + col;
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

    /** Opens the button panel config screen for one specific button (client-side only). */
    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private static void openConfigGui(BlockPos pos, int buttonIndex) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.apocscode.byteblock.client.ButtonPanelScreen(pos, buttonIndex));
    }
}

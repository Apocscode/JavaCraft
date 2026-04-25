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

    // Monitor slab thickness is configurable per-BE (1..6 px). Shape is computed lazily
    // and indexed by (facing, thickness). FACING = direction the screen points (front face).
    // For FACING=NORTH, slab sits at the SOUTH side of the cell (back against +Z wall).
    private static VoxelShape buildShape(Direction facing, int thicknessPx) {
        int t = Math.max(1, Math.min(16, thicknessPx));
        return switch (facing) {
            case SOUTH -> Block.box(0, 0, 0, 16, 16, t);
            case EAST  -> Block.box(0, 0, 0, t, 16, 16);
            case WEST  -> Block.box(16 - t, 0, 0, 16, 16, 16);
            default    -> Block.box(0, 0, 16 - t, 16, 16, 16);  // NORTH
        };
    }
    private static final VoxelShape[][] SHAPE_CACHE = new VoxelShape[6][7]; // 6 dirs, thickness 0..6

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
        // Mount against the clicked block's face. The monitor's front points AWAY from
        // that block (toward the player). Monitors only support horizontal (wall) placement.
        net.minecraft.core.Direction clicked = context.getClickedFace();
        if (clicked.getAxis().isHorizontal()) {
            return this.defaultBlockState().setValue(FACING, clicked);
        }
        // Clicked on top or bottom face: not supported \u2014 reject placement
        return null;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        int thickness = 2;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof MonitorBlockEntity m) thickness = m.getThicknessPx();
        thickness = Math.max(1, Math.min(6, thickness));
        int dirIdx = facing.get3DDataValue();
        VoxelShape cached = SHAPE_CACHE[dirIdx][thickness];
        if (cached == null) {
            cached = buildShape(facing, thickness);
            SHAPE_CACHE[dirIdx][thickness] = cached;
        }
        return cached;
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
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof MonitorBlockEntity monitor)) {
            return InteractionResult.PASS;
        }
        MonitorBlockEntity origin = monitor.getOriginEntity();
        if (origin == null) return InteractionResult.PASS;

        // Touch mode: if the monitor is showing its own text buffer, forward the click as a
        // monitor_touch event to the linked computer instead of opening the GUI.
        if ("text".equals(origin.getDisplayMode()) && !player.isShiftKeyDown()) {
            int[] cell = computeTouchCell(state, hitResult, origin);
            if (cell != null) {
                if (!level.isClientSide()) {
                    origin.recordTouch(cell[0], cell[1]);
                    BlockPos linked = origin.getLinkedComputerPos();
                    if (linked != null) {
                        BlockEntity compBe = level.getBlockEntity(linked);
                        if (compBe instanceof ComputerBlockEntity computer) {
                            com.apocscode.byteblock.computer.JavaOS os = computer.getOS();
                            // Determine which side of the computer the monitor is on
                            String side = computeMonitorSide(linked, origin);
                            // CC-style: monitor_touch event with side string + 1-based x,y
                            os.pushEvent(new com.apocscode.byteblock.computer.OSEvent(
                                com.apocscode.byteblock.computer.OSEvent.Type.MONITOR_TOUCH,
                                side, cell[0] + 1, cell[1] + 1));
                        }
                    }
                }
                return InteractionResult.sidedSuccess(level.isClientSide());
            }
        }

        // Mirror / fallback: shift-right-click or non-text mode opens the linked computer GUI.
        if (level.isClientSide()) {
            BlockPos linked = origin.getLinkedComputerPos();
            if (linked != null) {
                BlockEntity compBe = level.getBlockEntity(linked);
                if (compBe instanceof ComputerBlockEntity computer) {
                    com.apocscode.byteblock.computer.JavaOS os = computer.getOS();
                    if (os.isShutdown()) os.reboot();
                    net.minecraft.client.Minecraft.getInstance().setScreen(
                            new com.apocscode.byteblock.client.ComputerScreen(os));
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * Convert a click on a monitor block face into a (col, row) cell within the
     * formation-wide TEXT_COLS × TEXT_ROWS terminal grid.
     */
    private static int[] computeTouchCell(BlockState state, BlockHitResult hit,
                                          MonitorBlockEntity origin) {
        Direction facing = state.getValue(FACING);
        net.minecraft.world.phys.Vec3 loc = hit.getLocation();
        BlockPos hitPos = hit.getBlockPos();
        // u: 0..1 along the screen surface horizontally (left → right when looking at front)
        // v: 0..1 vertical (top → bottom)
        double u, v;
        switch (facing) {
            case NORTH -> { u = 1.0 - (loc.x - hitPos.getX()); v = 1.0 - (loc.y - hitPos.getY()); }
            case SOUTH -> { u =        loc.x - hitPos.getX();  v = 1.0 - (loc.y - hitPos.getY()); }
            case WEST  -> { u =        loc.z - hitPos.getZ();  v = 1.0 - (loc.y - hitPos.getY()); }
            case EAST  -> { u = 1.0 - (loc.z - hitPos.getZ()); v = 1.0 - (loc.y - hitPos.getY()); }
            default -> { return null; }
        }
        // Block-local u,v -> formation u,v using this block's offset within formation
        int multiW = origin.getMultiWidth();
        int multiH = origin.getMultiHeight();
        BlockEntity hitBe = origin.getLevel().getBlockEntity(hitPos);
        if (!(hitBe instanceof MonitorBlockEntity hitMonitor)) return null;
        double formU = (hitMonitor.getOffsetX() + u) / multiW;
        double formV = (multiH - 1 - hitMonitor.getOffsetY() + v) / multiH;
        if (formU < 0 || formU > 1 || formV < 0 || formV > 1) return null;
        // textScale shrinks the effective cell grid the same way the renderer does
        double scale = Math.max(0.5, Math.min(5.0, origin.getTextScale()));
        int effCols = Math.max(1, (int) Math.round(MonitorBlockEntity.TEXT_COLS * multiW / scale));
        int effRows = Math.max(1, (int) Math.round(MonitorBlockEntity.TEXT_ROWS * multiH / scale));
        effCols = Math.min(effCols, MonitorBlockEntity.TEXT_COLS);
        effRows = Math.min(effRows, MonitorBlockEntity.TEXT_ROWS);
        int cx = (int) Math.floor(formU * effCols);
        int cy = (int) Math.floor(formV * effRows);
        if (cx < 0) cx = 0; else if (cx >= effCols) cx = effCols - 1;
        if (cy < 0) cy = 0; else if (cy >= effRows) cy = effRows - 1;
        return new int[]{cx, cy};
    }

    /**
     * Determine which side of the computer the monitor formation is on.
     * Used as the {@code side} argument in the {@code monitor_touch} event.
     */
    private static String computeMonitorSide(BlockPos computerPos,
                                             MonitorBlockEntity originMonitor) {
        if (originMonitor.getLevel() == null) return "front";
        // Walk all 6 sides of the computer; if any adjacent block belongs to this formation,
        // that's our side.
        for (Direction d : Direction.values()) {
            BlockPos adj = computerPos.relative(d);
            BlockEntity be = originMonitor.getLevel().getBlockEntity(adj);
            if (be instanceof MonitorBlockEntity m
                    && originMonitor.getOriginPos().equals(m.getOriginPos())) {
                return com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                        .directionToSide(d);
            }
        }
        return "front";
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

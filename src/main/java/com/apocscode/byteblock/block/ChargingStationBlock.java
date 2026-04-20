package com.apocscode.byteblock.block;

import com.apocscode.byteblock.block.entity.ChargingStationBlockEntity;
import com.apocscode.byteblock.init.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Charging Station — charges Robots (FE) and Drones (fuel) that stand on it.
 * Slab-height pad with a 3-block radius detection zone.
 */
public class ChargingStationBlock extends Block implements EntityBlock {
    public static final BooleanProperty CONNECTED = BooleanProperty.create("connected");

    private static final VoxelShape SHAPE = Shapes.box(0, 0, 0, 1, 3.0 / 16.0, 1);

    public ChargingStationBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(CONNECTED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CONNECTED);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChargingStationBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return type == ModBlockEntities.CHARGING_STATION.get()
                ? (lvl, p, st, be) -> ((ChargingStationBlockEntity) be).serverTick()
                : null;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ChargingStationBlockEntity station) {
                int stored = station.getEnergyStored();
                int max = station.getMaxEnergy();
                player.sendSystemMessage(Component.literal(
                        "[Charging Station] Energy: " + stored + " / " + max + " FE"));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}

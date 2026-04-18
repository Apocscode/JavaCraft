package com.apocscode.byteblock.block;

import com.apocscode.byteblock.block.entity.DriveBlockEntity;
import com.apocscode.byteblock.init.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Drive block. Insert a Disk item to save/load programs.
 * Acts as external storage for computers.
 */
public class DriveBlock extends Block implements EntityBlock {

    public DriveBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DriveBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DriveBlockEntity drive) {
                ItemStack held = player.getMainHandItem();
                if (held.is(ModItems.DISK.get())) {
                    // Insert disk
                    if (drive.insertDisk(held.copy())) {
                        held.shrink(1);
                        player.sendSystemMessage(Component.literal("[ByteBlock Drive] Disk inserted."));
                    } else {
                        player.sendSystemMessage(Component.literal("[ByteBlock Drive] Drive already has a disk."));
                    }
                } else {
                    // Eject disk
                    ItemStack ejected = drive.ejectDisk();
                    if (!ejected.isEmpty()) {
                        if (!player.getInventory().add(ejected)) {
                            player.drop(ejected, false);
                        }
                        player.sendSystemMessage(Component.literal("[ByteBlock Drive] Disk ejected."));
                    } else {
                        player.sendSystemMessage(Component.literal("[ByteBlock Drive] No disk inserted."));
                    }
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}

package com.apocscode.byteblock.menu;

import com.apocscode.byteblock.block.entity.ChargingStationBlockEntity;
import com.apocscode.byteblock.init.ModMenuTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Menu for the Charging Station block. Has no slots — purely informational.
 * The screen reads {@link ChargingStationBlockEntity} energy + neighbor compatibility
 * directly each frame.
 */
public class ChargingStationMenu extends AbstractContainerMenu {
    private final ChargingStationBlockEntity station;
    private final BlockPos pos;

    public ChargingStationMenu(int containerId, Inventory playerInv, ChargingStationBlockEntity station) {
        super(ModMenuTypes.CHARGING_STATION.get(), containerId);
        this.station = station;
        this.pos = station.getBlockPos();
        // Informational menu — no slots.
    }

    public static ChargingStationMenu fromNetwork(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        if (!(be instanceof ChargingStationBlockEntity station)) {
            throw new IllegalStateException("ChargingStation block entity not found at " + pos);
        }
        return new ChargingStationMenu(containerId, playerInv, station);
    }

    public ChargingStationBlockEntity getStation() { return station; }
    public BlockPos getPos() { return pos; }

    @Override
    public boolean stillValid(Player player) {
        return station.getLevel() != null
                && !station.isRemoved()
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0;
    }

    @Override
    public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}

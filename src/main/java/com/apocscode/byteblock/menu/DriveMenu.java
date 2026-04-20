package com.apocscode.byteblock.menu;

import com.apocscode.byteblock.init.ModBlocks;
import com.apocscode.byteblock.init.ModMenuTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Drive container menu — 1 disk slot + player inventory.
 */
public class DriveMenu extends AbstractContainerMenu {
    private final ContainerLevelAccess access;
    private final Container container;
    private BlockPos drivePos = BlockPos.ZERO;

    public DriveMenu(int containerId, Inventory playerInv, Container container, ContainerLevelAccess access) {
        super(ModMenuTypes.DRIVE.get(), containerId);
        this.access = access;
        this.container = container;
        access.execute((level, pos) -> this.drivePos = pos);

        // Slot 0: Disk
        addSlot(new Slot(container, 0, 80, 42));

        // Player inventory (3 rows)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 104 + row * 18));
            }
        }
        // Hotbar
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 162));
        }
    }

    public static DriveMenu fromNetwork(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        DriveMenu menu = new DriveMenu(containerId, playerInv, new SimpleContainer(1), ContainerLevelAccess.NULL);
        menu.drivePos = pos;
        return menu;
    }

    public BlockPos getDrivePos() { return drivePos; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index == 0) {
            if (!moveItemStackTo(stack, 1, 37, true))
                return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, 1, false))
                return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.DRIVE.get());
    }
}

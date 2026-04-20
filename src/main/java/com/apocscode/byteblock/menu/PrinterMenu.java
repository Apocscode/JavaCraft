package com.apocscode.byteblock.menu;

import com.apocscode.byteblock.init.ModBlocks;
import com.apocscode.byteblock.init.ModMenuTypes;

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
 * Printer container menu — 2 block slots (input media + output) + player inventory.
 */
public class PrinterMenu extends AbstractContainerMenu {
    private final ContainerLevelAccess access;
    private final Container container;

    // Server-side constructor
    public PrinterMenu(int containerId, Inventory playerInv, Container container, ContainerLevelAccess access) {
        super(ModMenuTypes.PRINTER.get(), containerId);
        this.access = access;
        this.container = container;

        // Slot 0: Input media (paper, book_and_quill, clipboard)
        addSlot(new Slot(container, 0, 56, 35));
        // Slot 1: Output (printed item)
        addSlot(new OutputSlot(container, 1, 116, 35));

        // Player inventory (3 rows)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        // Hotbar
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 142));
        }
    }

    // Client-side constructor (from network)
    public static PrinterMenu fromNetwork(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        buf.readBlockPos(); // consume the position
        return new PrinterMenu(containerId, playerInv, new SimpleContainer(2), ContainerLevelAccess.NULL);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int containerSlots = 2;

        if (index < containerSlots) {
            // Move from container to player
            if (!moveItemStackTo(stack, containerSlots, containerSlots + 36, true))
                return ItemStack.EMPTY;
        } else {
            // Move from player to input slot (0) only
            if (!moveItemStackTo(stack, 0, 1, false))
                return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.PRINTER.get());
    }

    /** Output-only slot — player cannot place items here. */
    private static class OutputSlot extends Slot {
        public OutputSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}

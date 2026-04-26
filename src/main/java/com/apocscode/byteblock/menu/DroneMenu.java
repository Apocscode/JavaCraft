package com.apocscode.byteblock.menu;

import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.init.ModMenuTypes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Container menu for a {@link DroneEntity}.
 *
 * <p>Layout:
 * <ul>
 *   <li>9-slot cargo (3×3) — drone payload.</li>
 *   <li>1 battery slot (FE → fuel, 10 FE per fuel-tick).</li>
 *   <li>Standard 36-slot player inventory below.</li>
 * </ul>
 */
public class DroneMenu extends AbstractContainerMenu {
    public static final int CARGO_SLOTS = 9;
    private final DroneEntity drone;

    public DroneMenu(int containerId, Inventory playerInv, DroneEntity drone) {
        super(ModMenuTypes.DRONE.get(), containerId);
        this.drone = drone;

        // 3×3 cargo grid (slots 0..8) — top-left at (62, 18)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new Slot(drone.getInventory(), col + row * 3, 62 + col * 18, 18 + row * 18));
            }
        }
        // Battery (slot 9)
        addSlot(new EntitySlot(drone::getBatteryStack, drone::setBatteryStack,
                s -> s.isEmpty() || s.getCapability(Capabilities.EnergyStorage.ITEM) != null,
                8, 36));

        // Player inventory rows (slots 10..36)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 104 + row * 18));
            }
        }
        // Hotbar (slots 37..45)
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 162));
        }
    }

    public static DroneMenu fromNetwork(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        int entityId = buf.readInt();
        Entity ent = playerInv.player.level().getEntity(entityId);
        if (!(ent instanceof DroneEntity drone)) {
            throw new IllegalStateException("Drone entity " + entityId + " not found on client");
        }
        return new DroneMenu(containerId, playerInv, drone);
    }

    public DroneEntity getDrone() { return drone; }

    @Override
    public boolean stillValid(Player player) {
        return drone.isAlive() && player.distanceToSqr(drone) < 64.0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        final int cargoEnd     = CARGO_SLOTS;     // 9
        final int accessoryEnd = cargoEnd + 1;    // 10
        final int playerEnd    = accessoryEnd + 36;

        if (index < accessoryEnd) {
            if (!moveItemStackTo(stack, accessoryEnd, playerEnd, true)) return ItemStack.EMPTY;
        } else {
            if (stack.getCapability(Capabilities.EnergyStorage.ITEM) != null) {
                moveItemStackTo(stack, cargoEnd, accessoryEnd, false);
            }
            if (!stack.isEmpty() && !moveItemStackTo(stack, 0, cargoEnd, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }
}

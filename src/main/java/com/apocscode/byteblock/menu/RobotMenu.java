package com.apocscode.byteblock.menu;

import com.apocscode.byteblock.entity.RobotEntity;
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
 * Container menu for a {@link RobotEntity} (or any subclass like UnicycleRobotEntity).
 *
 * <p>Layout:
 * <ul>
 *   <li>16-slot main inventory (4×4 grid).</li>
 *   <li>2 tool slots (left + right hand).</li>
 *   <li>1 battery slot (accepts any item with {@code Capabilities.EnergyStorage.ITEM}).</li>
 *   <li>Standard 36-slot player inventory below.</li>
 * </ul>
 */
public class RobotMenu extends AbstractContainerMenu {
    public static final int CARGO_SLOTS = 16;
    private final RobotEntity robot;

    public RobotMenu(int containerId, Inventory playerInv, RobotEntity robot) {
        super(ModMenuTypes.ROBOT.get(), containerId);
        this.robot = robot;

        // 4×4 cargo grid (slots 0..15)  — top-left at (62, 18)
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                addSlot(new Slot(robot.getInventory(), col + row * 4, 62 + col * 18, 18 + row * 18));
            }
        }

        // Left tool  (slot 16) — column at x=8, y=18
        addSlot(new EntitySlot(robot::getEquippedToolLeft, robot::setEquippedToolLeft,
                s -> true, 8, 18));
        // Right tool (slot 17) — y=36
        addSlot(new EntitySlot(robot::getEquippedToolRight, robot::setEquippedToolRight,
                s -> true, 8, 36));
        // Battery    (slot 18) — bottom of accessory column, y=72
        addSlot(new EntitySlot(robot::getBatteryStack, robot::setBatteryStack,
                s -> s.isEmpty() || s.getCapability(Capabilities.EnergyStorage.ITEM) != null,
                8, 72));

        // Player inventory rows (slots 19..45)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 104 + row * 18));
            }
        }
        // Hotbar (slots 46..54)
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 162));
        }
    }

    public static RobotMenu fromNetwork(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        int entityId = buf.readInt();
        Entity ent = playerInv.player.level().getEntity(entityId);
        RobotEntity robot = ent instanceof RobotEntity r ? r : null;
        // If the entity hasn't replicated yet, build a stub-backed menu so the screen can open;
        // it will be re-synced once the entity arrives.
        if (robot == null) {
            // Create a transient throwaway entity is heavy — instead just throw to abort opening.
            // Vanilla will close the screen. (Should be very rare.)
            throw new IllegalStateException("Robot entity " + entityId + " not found on client");
        }
        return new RobotMenu(containerId, playerInv, robot);
    }

    public RobotEntity getRobot() { return robot; }

    @Override
    public boolean stillValid(Player player) {
        return robot.isAlive() && player.distanceToSqr(robot) < 64.0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        final int cargoEnd     = CARGO_SLOTS;        // 16
        final int accessoryEnd = cargoEnd + 3;       // 19
        final int playerEnd    = accessoryEnd + 36;  // 55

        if (index < accessoryEnd) {
            // Robot -> player
            if (!moveItemStackTo(stack, accessoryEnd, playerEnd, true)) return ItemStack.EMPTY;
        } else {
            // Player -> robot.  Try battery slot first if it's an FE item.
            if (stack.getCapability(Capabilities.EnergyStorage.ITEM) != null
                    && !moveItemStackTo(stack, accessoryEnd - 1, accessoryEnd, false)) {
                // fall through
            }
            if (!moveItemStackTo(stack, 0, cargoEnd, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }
}

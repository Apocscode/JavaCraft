package com.apocscode.byteblock.menu;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A {@link Slot} backed by a getter/setter pair on an arbitrary host (typically an entity).
 * Used so the menu can read/write the entity's per-field stacks (left tool, right tool, battery, ...)
 * without forcing the entity to maintain a parallel {@link net.minecraft.world.Container}.
 *
 * <p>Internally we still hand the parent {@code Slot} a 1-slot {@link SimpleContainer}
 * (vanilla requires a {@code Container}); we sync the host getter into that container
 * each time the slot is read or written so external code stays authoritative.
 */
public class EntitySlot extends Slot {
    private final Supplier<ItemStack> getter;
    private final Consumer<ItemStack> setter;
    private final Predicate<ItemStack> filter;

    public EntitySlot(Supplier<ItemStack> getter, Consumer<ItemStack> setter,
                      Predicate<ItemStack> filter, int x, int y) {
        super(new SimpleContainer(1), 0, x, y);
        this.getter = getter;
        this.setter = setter;
        this.filter = filter == null ? s -> true : filter;
    }

    @Override
    public ItemStack getItem() { return getter.get(); }

    @Override
    public void set(ItemStack stack) {
        setter.accept(stack);
        setChanged();
    }

    @Override
    public void setByPlayer(ItemStack newStack, ItemStack oldStack) { set(newStack); }

    @Override
    public boolean mayPlace(ItemStack stack) { return filter.test(stack); }

    @Override
    public ItemStack remove(int amount) {
        ItemStack current = getter.get();
        if (current.isEmpty()) return ItemStack.EMPTY;
        ItemStack split = current.split(amount);
        setter.accept(current.isEmpty() ? ItemStack.EMPTY : current);
        return split;
    }

    @Override
    public int getMaxStackSize() { return 64; }

    @Override
    public boolean hasItem() { return !getter.get().isEmpty(); }
}

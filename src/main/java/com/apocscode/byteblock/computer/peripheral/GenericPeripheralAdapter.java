package com.apocscode.byteblock.computer.peripheral;

import com.apocscode.byteblock.computer.JavaOS;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;

/**
 * Generic capability-based peripheral.
 *
 * <p>Wraps any block entity that exposes one or more of the standard NeoForge
 * capabilities — {@code IItemHandler}, {@code IFluidHandler}, or
 * {@code IEnergyStorage} — as a CC-style peripheral. This single adapter
 * unlocks vanilla chests/barrels/furnaces and every modded inventory, tank,
 * and energy storage in ATM10 (Mekanism, Thermal, Powah, Industrial
 * Foregoing, Botania, Create, IE, etc.) without per-mod code.
 *
 * <p>The peripheral type is the first matching capability in priority order
 * (inventory → fluid_storage → energy_storage). All matching capabilities
 * contribute methods to the wrapped table, so a hybrid block (e.g. a
 * Mekanism machine with both items and energy) exposes everything.
 *
 * <p>Registered LAST in {@link PeripheralRegistry#registerDefaults()} so
 * specific adapters always win.
 */
public class GenericPeripheralAdapter implements IPeripheralAdapter {

    @Override public String getModId() { return ""; } // built-in, no gating

    @Override
    public boolean canAdapt(BlockEntity be) {
        return getItems(be) != null || getFluids(be) != null || getEnergy(be) != null;
    }

    @Override
    public String getType(BlockEntity be) {
        if (getItems(be)  != null) return "inventory";
        if (getFluids(be) != null) return "fluid_storage";
        if (getEnergy(be) != null) return "energy_storage";
        return "unknown";
    }

    @Override
    public LuaTable buildTable(BlockEntity be) { return buildTable(be, null); }

    @Override
    public LuaTable buildTable(BlockEntity be, JavaOS callingOs) {
        LuaTable t = new LuaTable();

        IItemHandler items = getItems(be);
        if (items != null) addInventoryMethods(t, be, items, callingOs);

        IFluidHandler fluids = getFluids(be);
        if (fluids != null) addFluidMethods(t, fluids);

        IEnergyStorage energy = getEnergy(be);
        if (energy != null) addEnergyMethods(t, energy);

        // Universal getPosition()
        t.set("getPosition", new ZeroArgFunction() {
            @Override public LuaValue call() {
                BlockPos p = be.getBlockPos();
                LuaTable tbl = new LuaTable();
                tbl.set("x", LuaValue.valueOf(p.getX()));
                tbl.set("y", LuaValue.valueOf(p.getY()));
                tbl.set("z", LuaValue.valueOf(p.getZ()));
                return tbl;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Inventory (IItemHandler)
    // ══════════════════════════════════════════════════════════════════════

    static void addInventoryMethods(LuaTable t, BlockEntity be,
                                    IItemHandler h, JavaOS os) {
        t.set("size", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(h.getSlots()); }
        });

        t.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                int slots = h.getSlots();
                for (int i = 0; i < slots; i++) {
                    ItemStack stack = h.getStackInSlot(i);
                    if (!stack.isEmpty()) result.set(i + 1, itemBrief(stack));
                }
                return result;
            }
        });

        t.set("getItemDetail", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                int idx = arg.checkint() - 1;
                if (idx < 0 || idx >= h.getSlots()) return LuaValue.NIL;
                ItemStack stack = h.getStackInSlot(idx);
                if (stack.isEmpty()) return LuaValue.NIL;
                LuaTable info = itemBrief(stack);
                info.set("maxStackSize", LuaValue.valueOf(stack.getMaxStackSize()));
                LuaTable tags = new LuaTable();
                int ti = 1;
                for (var tag : stack.getTags().toList()) {
                    tags.set(ti++, LuaValue.valueOf(tag.location().toString()));
                }
                info.set("tags", tags);
                return info;
            }
        });

        t.set("getItemLimit", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                int idx = arg.checkint() - 1;
                if (idx < 0 || idx >= h.getSlots()) return LuaValue.valueOf(0);
                return LuaValue.valueOf(h.getSlotLimit(idx));
            }
        });

        // pushItems(toSide, fromSlot [, limit] [, toSlot]) → count moved
        t.set("pushItems", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String toSide = args.checkjstring(1);
                int fromSlot = args.checkint(2) - 1;
                int limit = args.narg() >= 3 && !args.arg(3).isnil() ? args.checkint(3) : Integer.MAX_VALUE;
                int toSlot = args.narg() >= 4 && !args.arg(4).isnil() ? args.checkint(4) - 1 : -1;
                if (fromSlot < 0 || fromSlot >= h.getSlots()) return LuaValue.valueOf(0);
                IItemHandler dest = resolveSideHandler(be, os, toSide);
                if (dest == null) return LuaValue.valueOf(0);
                return LuaValue.valueOf(transferItems(h, fromSlot, dest, toSlot, limit));
            }
        });

        // pullItems(fromSide, fromSlot [, limit] [, toSlot]) → count moved
        t.set("pullItems", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String fromSide = args.checkjstring(1);
                int fromSlot = args.checkint(2) - 1;
                int limit = args.narg() >= 3 && !args.arg(3).isnil() ? args.checkint(3) : Integer.MAX_VALUE;
                int toSlot = args.narg() >= 4 && !args.arg(4).isnil() ? args.checkint(4) - 1 : -1;
                IItemHandler src = resolveSideHandler(be, os, fromSide);
                if (src == null || fromSlot < 0 || fromSlot >= src.getSlots()) return LuaValue.valueOf(0);
                return LuaValue.valueOf(transferItems(src, fromSlot, h, toSlot, limit));
            }
        });
    }

    private static int transferItems(IItemHandler src, int srcSlot,
                                     IItemHandler dst, int dstSlot, int limit) {
        ItemStack peek = src.getStackInSlot(srcSlot);
        if (peek.isEmpty() || limit <= 0) return 0;
        int wanted = Math.min(limit, peek.getCount());
        ItemStack simulated = src.extractItem(srcSlot, wanted, true);
        if (simulated.isEmpty()) return 0;

        ItemStack remainder;
        if (dstSlot >= 0 && dstSlot < dst.getSlots()) {
            remainder = dst.insertItem(dstSlot, simulated, false);
        } else {
            remainder = simulated.copy();
            for (int i = 0; i < dst.getSlots() && !remainder.isEmpty(); i++) {
                remainder = dst.insertItem(i, remainder, false);
            }
        }
        int moved = simulated.getCount() - remainder.getCount();
        if (moved > 0) src.extractItem(srcSlot, moved, false);
        return moved;
    }

    private static IItemHandler resolveSideHandler(BlockEntity be, JavaOS os, String side) {
        Level level = be.getLevel();
        if (level == null) return null;
        // Side is relative to the *computer*, not this peripheral. Fall back
        // to the peripheral's own neighbors if the computer isn't known.
        BlockPos origin = (os != null && os.getBlockPos() != null) ? os.getBlockPos() : be.getBlockPos();
        Direction dir = PeripheralRegistry.sideToDirection(side);
        if (dir == null) return null;
        BlockPos target = origin.relative(dir);
        return level.getCapability(Capabilities.ItemHandler.BLOCK, target, null);
    }

    private static LuaTable itemBrief(ItemStack stack) {
        LuaTable t = new LuaTable();
        t.set("name",        LuaValue.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
        t.set("displayName", LuaValue.valueOf(stack.getHoverName().getString()));
        t.set("count",       LuaValue.valueOf(stack.getCount()));
        t.set("maxCount",    LuaValue.valueOf(stack.getMaxStackSize()));
        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Fluid (IFluidHandler)
    // ══════════════════════════════════════════════════════════════════════

    static void addFluidMethods(LuaTable t, IFluidHandler h) {
        t.set("tanks", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                int n = h.getTanks();
                for (int i = 0; i < n; i++) {
                    FluidStack fs = h.getFluidInTank(i);
                    LuaTable tank = new LuaTable();
                    tank.set("name", LuaValue.valueOf(fs.isEmpty() ? "minecraft:empty"
                            : BuiltInRegistries.FLUID.getKey(fs.getFluid()).toString()));
                    tank.set("amount",   LuaValue.valueOf(fs.getAmount()));
                    tank.set("capacity", LuaValue.valueOf(h.getTankCapacity(i)));
                    result.set(i + 1, tank);
                }
                return result;
            }
        });

        // Stub fluid push/pull — cross-block fluid transfer requires neighbour
        // resolution like items but uses IFluidHandler. Keep simple drains for now.
        t.set("getTankCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(h.getTanks()); }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // Energy (IEnergyStorage)
    // ══════════════════════════════════════════════════════════════════════

    static void addEnergyMethods(LuaTable t, IEnergyStorage e) {
        t.set("getEnergy", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(e.getEnergyStored()); }
        });
        t.set("getEnergyStored", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(e.getEnergyStored()); }
        });
        t.set("getEnergyCapacity", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(e.getMaxEnergyStored()); }
        });
        t.set("getMaxEnergyStored", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(e.getMaxEnergyStored()); }
        });
        t.set("canExtract", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(e.canExtract()); }
        });
        t.set("canReceive", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(e.canReceive()); }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // Capability lookups
    // ══════════════════════════════════════════════════════════════════════

    static IItemHandler getItems(BlockEntity be) {
        Level level = be.getLevel();
        if (level == null) return null;
        IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null);
        return h;
    }

    static IFluidHandler getFluids(BlockEntity be) {
        Level level = be.getLevel();
        if (level == null) return null;
        return level.getCapability(Capabilities.FluidHandler.BLOCK, be.getBlockPos(), null);
    }

    static IEnergyStorage getEnergy(BlockEntity be) {
        Level level = be.getLevel();
        if (level == null) return null;
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, be.getBlockPos(), null);
    }
}

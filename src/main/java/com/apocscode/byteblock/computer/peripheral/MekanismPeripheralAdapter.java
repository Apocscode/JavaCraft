package com.apocscode.byteblock.computer.peripheral;

import com.apocscode.byteblock.computer.JavaOS;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Comprehensive peripheral adapter for Mekanism machines.
 *
 * <p>Combines four data surfaces into one peripheral table:
 * <ol>
 *   <li>Standard capabilities (items, fluid, energy) via the Generic adapter helpers.</li>
 *   <li>Mekanism's chemical capability (gas/infusion/pigment/slurry — unified
 *       under {@code IChemicalHandler} in 1.21).</li>
 *   <li>Common Mekanism tile fields and methods (active state, redstone mode,
 *       machine progress) via reflection.</li>
 *   <li>Block registry name as the peripheral type — matches Mekanism's
 *       upstream CC integration convention (e.g. {@code mekanism:digital_miner}).</li>
 * </ol>
 *
 * <p>Targets every block entity whose class lives under {@code mekanism.*} or
 * {@code mekanismgenerators.*}. Only registered when the {@code mekanism} mod
 * is loaded; ByteBlock still compiles without Mekanism on the classpath.</p>
 */
public class MekanismPeripheralAdapter implements IPeripheralAdapter {

    private static volatile boolean cacheReady = false;
    private static BlockCapability<Object, net.minecraft.core.Direction> chemCap;

    // Chemical reflection
    private static Method mGetChemicalTanks;
    private static Method mGetChemicalInTank;
    private static Method mGetChemicalTankCapacity;
    private static Method mChemStackGetAmount;
    private static Method mChemStackIsEmpty;
    private static Method mChemStackGetChemical;
    private static Method mChemicalGetRegistryName;

    @Override public String getModId() { return "mekanism"; }

    @Override
    public boolean canAdapt(BlockEntity be) {
        String cls = be.getClass().getName();
        return cls.startsWith("mekanism.")
            || cls.startsWith("mekanismgenerators.")
            || cls.startsWith("mekanismadditions.")
            || cls.startsWith("mekanismtools.");
    }

    @Override
    public String getType(BlockEntity be) {
        ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return key != null ? key.toString() : "mekanism_machine";
    }

    @Override
    public LuaTable buildTable(BlockEntity be) { return buildTable(be, null); }

    @Override
    public LuaTable buildTable(BlockEntity be, JavaOS callingOs) {
        ensureCache();
        LuaTable t = new LuaTable();

        // Standard capabilities (covers ~90% of common use cases on Mekanism machines)
        IItemHandler items = GenericPeripheralAdapter.getItems(be);
        if (items != null) GenericPeripheralAdapter.addInventoryMethods(t, be, items, callingOs);

        IFluidHandler fluids = GenericPeripheralAdapter.getFluids(be);
        if (fluids != null) GenericPeripheralAdapter.addFluidMethods(t, fluids);

        IEnergyStorage energy = GenericPeripheralAdapter.getEnergy(be);
        if (energy != null) GenericPeripheralAdapter.addEnergyMethods(t, energy);

        // Mekanism-specific surfaces
        addChemicalMethods(t, be);
        addTileReflectionMethods(t, be);

        // getPosition
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
    // Chemical (Mekanism IChemicalHandler — gas/infusion/pigment/slurry unified)
    // ══════════════════════════════════════════════════════════════════════

    private static void addChemicalMethods(LuaTable t, BlockEntity be) {
        if (chemCap == null) return;
        Level level = be.getLevel();
        if (level == null) return;

        Object handler = level.getCapability(chemCap, be.getBlockPos(), null);
        if (handler == null) return;
        final Object h = handler;

        t.set("getChemicalTanks", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mGetChemicalTanks == null) return LuaValue.valueOf(0);
                try { return LuaValue.valueOf((int) mGetChemicalTanks.invoke(h)); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        t.set("getChemicals", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                if (mGetChemicalTanks == null || mGetChemicalInTank == null) return result;
                try {
                    int n = (int) mGetChemicalTanks.invoke(h);
                    for (int i = 0; i < n; i++) {
                        Object stack = mGetChemicalInTank.invoke(h, i);
                        long amount = stack != null && mChemStackGetAmount != null
                                ? ((Number) mChemStackGetAmount.invoke(stack)).longValue() : 0L;
                        long cap = mGetChemicalTankCapacity != null
                                ? ((Number) mGetChemicalTankCapacity.invoke(h, i)).longValue() : 0L;
                        boolean empty = stack == null || (mChemStackIsEmpty != null
                                && (boolean) mChemStackIsEmpty.invoke(stack));

                        LuaTable tank = new LuaTable();
                        String name = "mekanism:empty";
                        if (!empty && mChemStackGetChemical != null) {
                            try {
                                Object chem = mChemStackGetChemical.invoke(stack);
                                if (chem != null && mChemicalGetRegistryName != null) {
                                    Object rl = mChemicalGetRegistryName.invoke(chem);
                                    if (rl != null) name = rl.toString();
                                }
                            } catch (Exception ignored) {}
                        }
                        tank.set("name",     LuaValue.valueOf(name));
                        tank.set("amount",   LuaValue.valueOf(amount));
                        tank.set("capacity", LuaValue.valueOf(cap));
                        result.set(i + 1, tank);
                    }
                } catch (Exception ignored) {}
                return result;
            }
        });

        t.set("getChemicalInTank", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                if (mGetChemicalInTank == null) return LuaValue.NIL;
                int idx = arg.checkint() - 1;
                try {
                    Object stack = mGetChemicalInTank.invoke(h, idx);
                    if (stack == null) return LuaValue.NIL;
                    LuaTable tank = new LuaTable();
                    long amount = mChemStackGetAmount != null
                            ? ((Number) mChemStackGetAmount.invoke(stack)).longValue() : 0L;
                    String name = "mekanism:empty";
                    if (mChemStackGetChemical != null) {
                        Object chem = mChemStackGetChemical.invoke(stack);
                        if (chem != null && mChemicalGetRegistryName != null) {
                            Object rl = mChemicalGetRegistryName.invoke(chem);
                            if (rl != null) name = rl.toString();
                        }
                    }
                    tank.set("name",   LuaValue.valueOf(name));
                    tank.set("amount", LuaValue.valueOf(amount));
                    return tank;
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // Common TileEntityMekanism methods (reflective)
    // ══════════════════════════════════════════════════════════════════════

    private static void addTileReflectionMethods(LuaTable t, BlockEntity be) {
        // getActive() / setActive(boolean)
        Method mGetActive = findMethod(be.getClass(), "getActive");
        if (mGetActive != null) {
            t.set("getActive", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    try { return LuaValue.valueOf((boolean) mGetActive.invoke(be)); }
                    catch (Exception e) { return LuaValue.FALSE; }
                }
            });
        }

        Method mSetActive = findMethod(be.getClass(), "setActive", boolean.class);
        if (mSetActive != null) {
            t.set("setActive", new OneArgFunction() {
                @Override public LuaValue call(LuaValue arg) {
                    try { mSetActive.invoke(be, arg.checkboolean()); } catch (Exception ignored) {}
                    return LuaValue.NONE;
                }
            });
        }

        // Redstone control mode (enum -> name string)
        Method mGetRedstone = findMethod(be.getClass(), "getControlType");
        if (mGetRedstone == null) mGetRedstone = findMethod(be.getClass(), "getRedstoneMode");
        if (mGetRedstone != null) {
            final Method gr = mGetRedstone;
            t.set("getRedstoneMode", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    try {
                        Object v = gr.invoke(be);
                        return v != null ? LuaValue.valueOf(v.toString()) : LuaValue.NIL;
                    } catch (Exception e) { return LuaValue.NIL; }
                }
            });
        }

        // Machine progress (TileEntityProgressMachine)
        Method mProgress = findMethod(be.getClass(), "getProgress");
        Method mOpTicks  = findMethod(be.getClass(), "getOperatingTicks");
        Method mTicksReq = findMethod(be.getClass(), "getTicksRequired");
        if (mProgress != null) {
            final Method p = mProgress;
            t.set("getProgress", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    try {
                        Number n = (Number) p.invoke(be);
                        return n != null ? LuaValue.valueOf(n.doubleValue()) : LuaValue.valueOf(0);
                    } catch (Exception e) { return LuaValue.valueOf(0); }
                }
            });
        }
        if (mOpTicks != null) {
            final Method m = mOpTicks;
            t.set("getOperatingTicks", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    try { return LuaValue.valueOf(((Number) m.invoke(be)).intValue()); }
                    catch (Exception e) { return LuaValue.valueOf(0); }
                }
            });
        }
        if (mTicksReq != null) {
            final Method m = mTicksReq;
            t.set("getTicksRequired", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    try { return LuaValue.valueOf(((Number) m.invoke(be)).intValue()); }
                    catch (Exception e) { return LuaValue.valueOf(0); }
                }
            });
        }

        // Common stat getters (return null/0 if absent — harmless on machines that don't have them)
        addNumericGetter(t, be, "getEnergyUsage",     "getEnergyUsage");
        addNumericGetter(t, be, "getPlayersUsing",    "getPlayersUsing");
        addNumericGetter(t, be, "getCachedAmbientTemperature", "getAmbientTemperature");
    }

    private static void addNumericGetter(LuaTable t, BlockEntity be, String javaName, String luaName) {
        Method m = findMethod(be.getClass(), javaName);
        if (m == null) return;
        final Method method = m;
        t.set(luaName, new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    Object r = method.invoke(be);
                    return r instanceof Number n ? LuaValue.valueOf(n.doubleValue()) : LuaValue.valueOf(0);
                } catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reflection cache initializer
    // ══════════════════════════════════════════════════════════════════════

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static synchronized void ensureCache() {
        if (cacheReady) return;
        cacheReady = true;
        try {
            // mekanism.common.capabilities.Capabilities.CHEMICAL : MultiTypeCapability<IChemicalHandler>
            Class<?> caps = Class.forName("mekanism.common.capabilities.Capabilities");
            Field chemField = caps.getField("CHEMICAL");
            Object chemMtc = chemField.get(null);
            // MultiTypeCapability#block() → BlockCapability<H, Direction>
            Method blockM = chemMtc.getClass().getMethod("block");
            chemCap = (BlockCapability<Object, net.minecraft.core.Direction>) blockM.invoke(chemMtc);

            Class<?> ihCls = Class.forName("mekanism.api.chemical.IChemicalHandler");
            mGetChemicalTanks        = ihCls.getMethod("getChemicalTanks");
            mGetChemicalInTank       = ihCls.getMethod("getChemicalInTank", int.class);
            mGetChemicalTankCapacity = ihCls.getMethod("getChemicalTankCapacity", int.class);

            Class<?> stackCls = Class.forName("mekanism.api.chemical.ChemicalStack");
            mChemStackGetAmount   = stackCls.getMethod("getAmount");
            mChemStackIsEmpty     = stackCls.getMethod("isEmpty");
            mChemStackGetChemical = stackCls.getMethod("getChemical");

            Class<?> chemCls = Class.forName("mekanism.api.chemical.Chemical");
            mChemicalGetRegistryName = chemCls.getMethod("getRegistryName");
        } catch (Exception e) {
            // Leave handlers null — adapter still works for items/fluid/energy/tile reflection
            chemCap = null;
        }
    }
}

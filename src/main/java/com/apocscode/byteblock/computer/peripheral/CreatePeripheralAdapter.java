package com.apocscode.byteblock.computer.peripheral;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Peripheral adapter for Create mod block entities.
 *
 * <p>Wraps the following blocks as Lua peripherals:
 * <ul>
 *   <li>{@code SpeedGaugeBlockEntity}  → {@code "create_speedometer"}</li>
 *   <li>{@code StressGaugeBlockEntity} → {@code "create_stressometer"}</li>
 *   <li>{@code FluidTankBlockEntity}   → {@code "create_tank"}</li>
 *   <li>{@code MechanicalBearingBlockEntity} → {@code "create_bearing"}</li>
 *   <li>{@code DeployerBlockEntity}    → {@code "create_deployer"}</li>
 *   <li>{@code MechanicalPressBlockEntity}  → {@code "create_press"}</li>
 *   <li>{@code MillstoneBlockEntity}   → {@code "create_millstone"}</li>
 * </ul>
 *
 * All Create-specific classes are loaded reflectively; NeoForge classes
 * ({@link FluidStack}, {@link FluidTank}) are referenced directly.
 */
public class CreatePeripheralAdapter implements IPeripheralAdapter {

    // ══════════════════════════════════════════════════════════════════════
    // Reflection cache
    // ══════════════════════════════════════════════════════════════════════

    private static volatile boolean cacheReady = false;

    // ── Create block entity classes ───────────────────────────────────────
    private static Class<?> clsKineticBE;       // KineticBlockEntity
    private static Class<?> clsSpeedGaugeBE;    // SpeedGaugeBlockEntity
    private static Class<?> clsStressGaugeBE;   // StressGaugeBlockEntity
    private static Class<?> clsFluidTankBE;     // FluidTankBlockEntity
    private static Class<?> clsBearingBE;       // MechanicalBearingBlockEntity
    private static Class<?> clsDeployerBE;      // DeployerBlockEntity
    private static Class<?> clsPressBE;         // MechanicalPressBlockEntity
    private static Class<?> clsMillstoneBE;     // MillstoneBlockEntity

    // ── KineticBlockEntity fields/methods (inherited by all kinetic BEs) ──
    private static Method mGetSpeed;            // KineticBlockEntity.getSpeed() → float
    private static Method mIsOverStressed;      // KineticBlockEntity.isOverStressed() → boolean
    private static Field  fKineticSpeed;        // KineticBlockEntity.speed (protected float)
    private static Field  fKineticCapacity;     // KineticBlockEntity.capacity (protected float)
    private static Field  fKineticStress;       // KineticBlockEntity.stress (protected float)

    // ── GaugeBlockEntity fields (parent of SpeedGauge/StressGauge) ───────
    private static Field  fDialTarget;          // GaugeBlockEntity.dialTarget (public float)

    // ── StressGaugeBlockEntity methods ───────────────────────────────────
    private static Method mGetNetworkStress;    // StressGaugeBlockEntity.getNetworkStress()
    private static Method mGetNetworkCapacity;  // StressGaugeBlockEntity.getNetworkCapacity()

    // ── FluidTankBlockEntity fields ───────────────────────────────────────
    private static Field  fTankInventory;       // FluidTankBlockEntity.tankInventory (FluidTank)

    // ── MechanicalBearingBlockEntity fields ───────────────────────────────
    private static Field  fBearingRunning;      // MechanicalBearingBlockEntity.running (boolean)
    private static Field  fBearingAngle;        // MechanicalBearingBlockEntity.angle (float)

    // ── DeployerBlockEntity fields ────────────────────────────────────────
    private static Field  fDeployerHeldItem;    // DeployerBlockEntity.heldItem (ItemStack)
    private static Field  fDeployerMode;        // DeployerBlockEntity.mode (Mode enum)
    private static Field  fDeployerState;       // DeployerBlockEntity.state (State enum)
    private static Field  fDeployerTimer;       // DeployerBlockEntity.timer (int)

    // ── MechanicalPressBlockEntity fields ─────────────────────────────────
    private static Field  fPressBehaviour;      // MechanicalPressBlockEntity.pressingBehaviour
    private static Field  fPressBehaviourRunning; // PressingBehaviour.running (boolean)
    private static Field  fPressBehaviourTicks;   // PressingBehaviour.runningTicks (int)

    // ── MillstoneBlockEntity fields ───────────────────────────────────────
    private static Field  fMillInputInv;        // MillstoneBlockEntity.inputInv (ItemStackHandler)
    private static Field  fMillOutputInv;       // MillstoneBlockEntity.outputInv (ItemStackHandler)
    private static Method mInvGetSlots;         // ItemStackHandler.getSlots() → int
    private static Method mInvGetStackInSlot;   // ItemStackHandler.getStackInSlot(int) → ItemStack

    // ══════════════════════════════════════════════════════════════════════
    // IPeripheralAdapter
    // ══════════════════════════════════════════════════════════════════════

    @Override public String getModId() { return "create"; }

    @Override
    public boolean canAdapt(BlockEntity be) {
        if (!ensureCache()) return false;
        return isAnyCreateBE(be);
    }

    @Override
    public String getType(BlockEntity be) {
        if (!ensureCache()) return "unknown";
        if (clsSpeedGaugeBE  != null && clsSpeedGaugeBE.isInstance(be))  return "create_speedometer";
        if (clsStressGaugeBE != null && clsStressGaugeBE.isInstance(be)) return "create_stressometer";
        if (clsFluidTankBE   != null && clsFluidTankBE.isInstance(be))   return "create_tank";
        if (clsBearingBE     != null && clsBearingBE.isInstance(be))      return "create_bearing";
        if (clsDeployerBE    != null && clsDeployerBE.isInstance(be))     return "create_deployer";
        if (clsPressBE       != null && clsPressBE.isInstance(be))        return "create_press";
        if (clsMillstoneBE   != null && clsMillstoneBE.isInstance(be))    return "create_millstone";
        return "unknown";
    }

    @Override
    public LuaTable buildTable(BlockEntity be) {
        if (!ensureCache()) return new LuaTable();
        if (clsSpeedGaugeBE  != null && clsSpeedGaugeBE.isInstance(be))  return buildSpeedometerTable(be);
        if (clsStressGaugeBE != null && clsStressGaugeBE.isInstance(be)) return buildStressometerTable(be);
        if (clsFluidTankBE   != null && clsFluidTankBE.isInstance(be))   return buildFluidTankTable(be);
        if (clsBearingBE     != null && clsBearingBE.isInstance(be))      return buildBearingTable(be);
        if (clsDeployerBE    != null && clsDeployerBE.isInstance(be))     return buildDeployerTable(be);
        if (clsPressBE       != null && clsPressBE.isInstance(be))        return buildPressTable(be);
        if (clsMillstoneBE   != null && clsMillstoneBE.isInstance(be))    return buildMillstoneTable(be);
        return new LuaTable();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Speed Gauge — "create_speedometer"
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildSpeedometerTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        // getSpeed() → float RPM (signed: +cw, -ccw)
        t.set("getSpeed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // isRunning() → true if speed != 0
        t.set("isRunning", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).floatValue() != 0f); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // isOverstressed() → boolean
        t.set("isOverstressed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf((boolean) mIsOverStressed.invoke(be)); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // getDialTarget() → normalized dial position (0-1.125), mirrors the gauge display
        t.set("getDialTarget", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (fDialTarget == null) return LuaValue.valueOf(0);
                try { return LuaValue.valueOf((float) fDialTarget.get(be)); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getInfo() → table
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("speedometer"));
                try { info.set("speed",        LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                try { info.set("isRunning",    LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).floatValue() != 0f)); } catch (Exception ignored) {}
                try { info.set("isOverstressed", LuaValue.valueOf((boolean) mIsOverStressed.invoke(be))); } catch (Exception ignored) {}
                if (fDialTarget != null) try { info.set("dialTarget", LuaValue.valueOf((float) fDialTarget.get(be))); } catch (Exception ignored) {}
                return info;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Stress Gauge — "create_stressometer"
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildStressometerTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        // getSpeed() → kinetic speed at this gauge (RPM)
        t.set("getSpeed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getStress() → current network stress usage (SU)
        t.set("getStress", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mGetNetworkStress != null)
                    try { return LuaValue.valueOf(((Number) mGetNetworkStress.invoke(be)).doubleValue()); }
                    catch (Exception ignored) {}
                if (fKineticStress != null)
                    try { return LuaValue.valueOf((double) (float) fKineticStress.get(be)); }
                    catch (Exception ignored) {}
                return LuaValue.valueOf(0);
            }
        });

        // getCapacity() → current network stress capacity (SU)
        t.set("getCapacity", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mGetNetworkCapacity != null)
                    try { return LuaValue.valueOf(((Number) mGetNetworkCapacity.invoke(be)).doubleValue()); }
                    catch (Exception ignored) {}
                if (fKineticCapacity != null)
                    try { return LuaValue.valueOf((double) (float) fKineticCapacity.get(be)); }
                    catch (Exception ignored) {}
                return LuaValue.valueOf(0);
            }
        });

        // isOverstressed() → true if stress >= capacity
        t.set("isOverstressed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf((boolean) mIsOverStressed.invoke(be)); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // getStressPercent() → 0-100 (or higher if overstressed)
        t.set("getStressPercent", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    double stress   = mGetNetworkStress   != null ? ((Number) mGetNetworkStress.invoke(be)).doubleValue()   : (fKineticStress   != null ? (float) fKineticStress.get(be)   : 0);
                    double capacity = mGetNetworkCapacity != null ? ((Number) mGetNetworkCapacity.invoke(be)).doubleValue() : (fKineticCapacity != null ? (float) fKineticCapacity.get(be) : 0);
                    return LuaValue.valueOf(capacity > 0 ? stress / capacity * 100.0 : 0);
                } catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getInfo() → full status table
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("stressometer"));
                try { info.set("speed", LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                if (mGetNetworkStress    != null) try { info.set("stress",   LuaValue.valueOf(((Number) mGetNetworkStress.invoke(be)).doubleValue())); }   catch (Exception ignored) {}
                if (mGetNetworkCapacity  != null) try { info.set("capacity", LuaValue.valueOf(((Number) mGetNetworkCapacity.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                try { info.set("isOverstressed", LuaValue.valueOf((boolean) mIsOverStressed.invoke(be))); } catch (Exception ignored) {}
                if (fDialTarget != null) try {
                    float dial = (float) fDialTarget.get(be);
                    info.set("dialTarget", LuaValue.valueOf(dial));
                    // Stress % from dial: dialTarget is stress/capacity (clamped at 1.125 for overstress)
                    info.set("stressPercent", LuaValue.valueOf(Math.min(dial, 1f) * 100.0));
                } catch (Exception ignored) {}
                return info;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Fluid Tank — "create_tank"
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildFluidTankTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        // Internal helper: get the FluidTank from the BE
        // Returns null if tank field not accessible
        java.util.function.Supplier<FluidTank> getTank = () -> {
            if (fTankInventory == null) return null;
            try { return (FluidTank) fTankInventory.get(be); }
            catch (Exception e) { return null; }
        };

        // getFluid() → {name, amount, capacity, displayName, fillPercent} | nil
        t.set("getFluid", new ZeroArgFunction() {
            @Override public LuaValue call() {
                FluidTank tank = getTank.get();
                if (tank == null) return LuaValue.NIL;
                try {
                    FluidStack fs = tank.getFluid();
                    if (fs == null || fs.isEmpty()) return LuaValue.NIL;
                    LuaTable info = new LuaTable();
                    info.set("name",        LuaValue.valueOf(
                            BuiltInRegistries.FLUID.getKey(fs.getFluid()).toString()));
                    info.set("amount",      LuaValue.valueOf(tank.getFluidAmount()));
                    info.set("capacity",    LuaValue.valueOf(tank.getCapacity()));
                    info.set("fillPercent", LuaValue.valueOf(
                            tank.getCapacity() > 0
                                ? (double) tank.getFluidAmount() / tank.getCapacity() * 100.0
                                : 0.0));
                    return info;
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // getFluidName() → string | nil
        t.set("getFluidName", new ZeroArgFunction() {
            @Override public LuaValue call() {
                FluidTank tank = getTank.get();
                if (tank == null) return LuaValue.NIL;
                try {
                    FluidStack fs = tank.getFluid();
                    if (fs == null || fs.isEmpty()) return LuaValue.NIL;
                    return LuaValue.valueOf(BuiltInRegistries.FLUID.getKey(fs.getFluid()).toString());
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // getFluidAmount() → int (in mB)
        t.set("getFluidAmount", new ZeroArgFunction() {
            @Override public LuaValue call() {
                FluidTank tank = getTank.get();
                return tank != null ? LuaValue.valueOf(tank.getFluidAmount()) : LuaValue.valueOf(0);
            }
        });

        // getCapacity() → int (in mB)
        t.set("getCapacity", new ZeroArgFunction() {
            @Override public LuaValue call() {
                FluidTank tank = getTank.get();
                return tank != null ? LuaValue.valueOf(tank.getCapacity()) : LuaValue.valueOf(0);
            }
        });

        // getFillPercent() → double (0-100)
        t.set("getFillPercent", new ZeroArgFunction() {
            @Override public LuaValue call() {
                FluidTank tank = getTank.get();
                if (tank == null || tank.getCapacity() == 0) return LuaValue.valueOf(0);
                return LuaValue.valueOf((double) tank.getFluidAmount() / tank.getCapacity() * 100.0);
            }
        });

        // isEmpty() → boolean
        t.set("isEmpty", new ZeroArgFunction() {
            @Override public LuaValue call() {
                FluidTank tank = getTank.get();
                return LuaValue.valueOf(tank == null || tank.getFluidAmount() == 0);
            }
        });

        // isFull() → boolean
        t.set("isFull", new ZeroArgFunction() {
            @Override public LuaValue call() {
                FluidTank tank = getTank.get();
                if (tank == null) return LuaValue.FALSE;
                return LuaValue.valueOf(tank.getCapacity() > 0 && tank.getFluidAmount() >= tank.getCapacity());
            }
        });

        // getInfo() → full status table
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("tank"));
                FluidTank tank = getTank.get();
                if (tank == null) return info;
                info.set("capacity",    LuaValue.valueOf(tank.getCapacity()));
                info.set("amount",      LuaValue.valueOf(tank.getFluidAmount()));
                info.set("isEmpty",     LuaValue.valueOf(tank.getFluidAmount() == 0));
                info.set("fillPercent", LuaValue.valueOf(
                        tank.getCapacity() > 0 ? (double) tank.getFluidAmount() / tank.getCapacity() * 100.0 : 0.0));
                try {
                    FluidStack fs = tank.getFluid();
                    if (fs != null && !fs.isEmpty())
                        info.set("fluid", LuaValue.valueOf(BuiltInRegistries.FLUID.getKey(fs.getFluid()).toString()));
                } catch (Exception ignored) {}
                return info;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mechanical Bearing — "create_bearing"
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildBearingTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        // isRunning() → boolean
        t.set("isRunning", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (fBearingRunning == null) return LuaValue.FALSE;
                try { return LuaValue.valueOf((boolean) fBearingRunning.get(be)); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // getAngle() → float (current rotation angle in degrees)
        t.set("getAngle", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (fBearingAngle == null) return LuaValue.valueOf(0);
                try { return LuaValue.valueOf((double) (float) fBearingAngle.get(be)); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getSpeed() → kinetic speed in RPM
        t.set("getSpeed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // hasContraption() → true if running (contraption assembled)
        t.set("hasContraption", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (fBearingRunning == null) return LuaValue.FALSE;
                try { return LuaValue.valueOf((boolean) fBearingRunning.get(be)); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // getInfo() → full status table
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("mechanical_bearing"));
                if (fBearingRunning != null) try { info.set("running", LuaValue.valueOf((boolean) fBearingRunning.get(be))); } catch (Exception ignored) {}
                if (fBearingAngle   != null) try { info.set("angle",   LuaValue.valueOf((double) (float) fBearingAngle.get(be))); }   catch (Exception ignored) {}
                try { info.set("speed", LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                return info;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Deployer — "create_deployer"
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildDeployerTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        // getHeldItem() → {name, count, displayName} | nil
        t.set("getHeldItem", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (fDeployerHeldItem == null) return LuaValue.NIL;
                try {
                    ItemStack stack = (ItemStack) fDeployerHeldItem.get(be);
                    if (stack == null || stack.isEmpty()) return LuaValue.NIL;
                    LuaTable entry = new LuaTable();
                    entry.set("name",        LuaValue.valueOf(
                            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
                    entry.set("count",       LuaValue.valueOf(stack.getCount()));
                    entry.set("displayName", LuaValue.valueOf(stack.getHoverName().getString()));
                    return entry;
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // getMode() → "USE" | "PUNCH"
        t.set("getMode", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (fDeployerMode == null) return LuaValue.valueOf("UNKNOWN");
                try { return LuaValue.valueOf(fDeployerMode.get(be).toString()); }
                catch (Exception e) { return LuaValue.valueOf("UNKNOWN"); }
            }
        });

        // getState() → "WAITING" | "EXPANDING" | "RETRACTING" | "DUMPING"
        t.set("getState", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (fDeployerState == null) return LuaValue.valueOf("UNKNOWN");
                try { return LuaValue.valueOf(fDeployerState.get(be).toString()); }
                catch (Exception e) { return LuaValue.valueOf("UNKNOWN"); }
            }
        });

        // isActive() → true if not WAITING
        t.set("isActive", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (fDeployerState == null) return LuaValue.FALSE;
                try {
                    String state = fDeployerState.get(be).toString();
                    return LuaValue.valueOf(!state.equals("WAITING"));
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // getSpeed() → kinetic RPM
        t.set("getSpeed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getInfo() → full status table
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("deployer"));
                if (fDeployerState != null) try { info.set("state", LuaValue.valueOf(fDeployerState.get(be).toString())); } catch (Exception ignored) {}
                if (fDeployerMode  != null) try { info.set("mode",  LuaValue.valueOf(fDeployerMode.get(be).toString())); }  catch (Exception ignored) {}
                if (fDeployerHeldItem != null) try {
                    ItemStack held = (ItemStack) fDeployerHeldItem.get(be);
                    info.set("hasItem", LuaValue.valueOf(held != null && !held.isEmpty()));
                    if (held != null && !held.isEmpty())
                        info.set("heldItem", LuaValue.valueOf(BuiltInRegistries.ITEM.getKey(held.getItem()).toString()));
                } catch (Exception ignored) {}
                try { info.set("speed", LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                return info;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mechanical Press — "create_press"
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildPressTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        // isRunning() → true if pressing cycle is active
        t.set("isRunning", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (fPressBehaviour == null || fPressBehaviourRunning == null) return LuaValue.FALSE;
                try {
                    Object beh = fPressBehaviour.get(be);
                    if (beh == null) return LuaValue.FALSE;
                    return LuaValue.valueOf((boolean) fPressBehaviourRunning.get(beh));
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // getProgress() → 0-100 (cycle progress %)
        t.set("getProgress", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (fPressBehaviour == null || fPressBehaviourTicks == null) return LuaValue.valueOf(0);
                try {
                    Object beh = fPressBehaviour.get(be);
                    if (beh == null) return LuaValue.valueOf(0);
                    int ticks = (int) fPressBehaviourTicks.get(beh);
                    return LuaValue.valueOf(ticks / 2.4); // CYCLE = 240 ticks → 0-100%
                } catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getSpeed() → kinetic RPM (drives pressing speed)
        t.set("getSpeed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // getInfo() → full status table
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("mechanical_press"));
                try { info.set("speed", LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                if (fPressBehaviour != null) {
                    try {
                        Object beh = fPressBehaviour.get(be);
                        if (beh != null) {
                            if (fPressBehaviourRunning != null) info.set("running",  LuaValue.valueOf((boolean) fPressBehaviourRunning.get(beh)));
                            if (fPressBehaviourTicks   != null) info.set("progress", LuaValue.valueOf((int) fPressBehaviourTicks.get(beh) / 2.4));
                        }
                    } catch (Exception ignored) {}
                }
                return info;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Millstone — "create_millstone"
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildMillstoneTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        // getSpeed() → kinetic RPM
        t.set("getSpeed", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue()); }
                catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        // isRunning() → true if speed != 0
        t.set("isRunning", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).floatValue() != 0f); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // getInput() → [{name, count, displayName}] contents of input slot
        t.set("getInput", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return getInventoryLua(be, fMillInputInv);
            }
        });

        // getOutput() → [{name, count, displayName}] contents of output slots
        t.set("getOutput", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return getInventoryLua(be, fMillOutputInv);
            }
        });

        // getInfo() → full status table
        t.set("getInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                info.set("type", LuaValue.valueOf("millstone"));
                try { info.set("speed",     LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).doubleValue())); } catch (Exception ignored) {}
                try { info.set("isRunning", LuaValue.valueOf(((Number) mGetSpeed.invoke(be)).floatValue() != 0f)); } catch (Exception ignored) {}
                return info;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    /** Build a Lua array from an ItemStackHandler field on the given BE. */
    private static LuaValue getInventoryLua(BlockEntity be, Field invField) {
        LuaTable result = new LuaTable();
        if (invField == null || mInvGetSlots == null || mInvGetStackInSlot == null)
            return result;
        try {
            Object inv = invField.get(be);
            if (inv == null) return result;
            int slots = (int) mInvGetSlots.invoke(inv);
            int i = 1;
            for (int s = 0; s < slots; s++) {
                ItemStack stack = (ItemStack) mInvGetStackInSlot.invoke(inv, s);
                if (stack == null || stack.isEmpty()) continue;
                LuaTable entry = new LuaTable();
                entry.set("name",        LuaValue.valueOf(
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
                entry.set("count",       LuaValue.valueOf(stack.getCount()));
                entry.set("displayName", LuaValue.valueOf(stack.getHoverName().getString()));
                result.set(i++, entry);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static boolean isAnyCreateBE(BlockEntity be) {
        return (clsSpeedGaugeBE  != null && clsSpeedGaugeBE.isInstance(be))
            || (clsStressGaugeBE != null && clsStressGaugeBE.isInstance(be))
            || (clsFluidTankBE   != null && clsFluidTankBE.isInstance(be))
            || (clsBearingBE     != null && clsBearingBE.isInstance(be))
            || (clsDeployerBE    != null && clsDeployerBE.isInstance(be))
            || (clsPressBE       != null && clsPressBE.isInstance(be))
            || (clsMillstoneBE   != null && clsMillstoneBE.isInstance(be));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reflection cache initializer
    // ══════════════════════════════════════════════════════════════════════

    private static boolean ensureCache() {
        if (cacheReady) return clsKineticBE != null;
        cacheReady = true;
        try {
            // ── KineticBlockEntity (base) ──────────────────────────────────
            clsKineticBE     = Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");
            mGetSpeed        = clsKineticBE.getMethod("getSpeed");
            mIsOverStressed  = clsKineticBE.getMethod("isOverStressed");
            fKineticSpeed    = safeDeclaredField(clsKineticBE, "speed");
            fKineticCapacity = safeDeclaredField(clsKineticBE, "capacity");
            fKineticStress   = safeDeclaredField(clsKineticBE, "stress");

            // ── GaugeBlockEntity ───────────────────────────────────────────
            Class<?> clsGaugeBE = safeClass("com.simibubi.create.content.kinetics.gauge.GaugeBlockEntity");
            if (clsGaugeBE != null) {
                fDialTarget = safeField(clsGaugeBE, "dialTarget");
                if (fDialTarget == null) fDialTarget = safeDeclaredField(clsGaugeBE, "dialTarget");
            }

            // ── Speed Gauge ────────────────────────────────────────────────
            clsSpeedGaugeBE  = safeClass("com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity");

            // ── Stress Gauge ───────────────────────────────────────────────
            clsStressGaugeBE = safeClass("com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity");
            if (clsStressGaugeBE != null) {
                mGetNetworkStress    = safeMethod(clsStressGaugeBE, "getNetworkStress");
                mGetNetworkCapacity  = safeMethod(clsStressGaugeBE, "getNetworkCapacity");
            }

            // ── Fluid Tank ─────────────────────────────────────────────────
            clsFluidTankBE  = safeClass("com.simibubi.create.content.fluids.tank.FluidTankBlockEntity");
            if (clsFluidTankBE != null) {
                fTankInventory = safeDeclaredField(clsFluidTankBE, "tankInventory");
            }

            // ── Mechanical Bearing ─────────────────────────────────────────
            clsBearingBE    = safeClass("com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity");
            if (clsBearingBE != null) {
                fBearingRunning = safeDeclaredField(clsBearingBE, "running");
                fBearingAngle   = safeDeclaredField(clsBearingBE, "angle");
            }

            // ── Deployer ───────────────────────────────────────────────────
            clsDeployerBE   = safeClass("com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity");
            if (clsDeployerBE != null) {
                fDeployerHeldItem = safeDeclaredField(clsDeployerBE, "heldItem");
                fDeployerMode     = safeDeclaredField(clsDeployerBE, "mode");
                fDeployerState    = safeDeclaredField(clsDeployerBE, "state");
                fDeployerTimer    = safeDeclaredField(clsDeployerBE, "timer");
            }

            // ── Mechanical Press ───────────────────────────────────────────
            clsPressBE      = safeClass("com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity");
            if (clsPressBE != null) {
                fPressBehaviour = safeDeclaredField(clsPressBE, "pressingBehaviour");
                if (fPressBehaviour != null) {
                    Class<?> clsPressBeh = safeClass("com.simibubi.create.content.kinetics.press.PressingBehaviour");
                    if (clsPressBeh != null) {
                        fPressBehaviourRunning = safeDeclaredField(clsPressBeh, "running");
                        fPressBehaviourTicks   = safeDeclaredField(clsPressBeh, "runningTicks");
                    }
                }
            }

            // ── Millstone ──────────────────────────────────────────────────
            clsMillstoneBE  = safeClass("com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity");
            if (clsMillstoneBE != null) {
                fMillInputInv  = safeDeclaredField(clsMillstoneBE, "inputInv");
                fMillOutputInv = safeDeclaredField(clsMillstoneBE, "outputInv");
            }

            // ── ItemStackHandler (NeoForge) ────────────────────────────────
            Class<?> clsItemHandler = safeClass("net.neoforged.neoforge.items.ItemStackHandler");
            if (clsItemHandler != null) {
                mInvGetSlots      = safeMethod(clsItemHandler, "getSlots");
                mInvGetStackInSlot= safeMethod(clsItemHandler, "getStackInSlot", int.class);
            }

            return true;
        } catch (Exception e) {
            clsKineticBE = null;
            return false;
        }
    }

    // ── Reflection helpers ────────────────────────────────────────────────

    private static Class<?> safeClass(String name) {
        try { return Class.forName(name); } catch (Exception e) { return null; }
    }

    private static Method safeMethod(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) return null;
        try { return cls.getMethod(name, params); } catch (Exception e) { return null; }
    }

    private static Field safeField(Class<?> cls, String name) {
        if (cls == null) return null;
        try { return cls.getField(name); } catch (Exception e) { return null; }
    }

    private static Field safeDeclaredField(Class<?> cls, String name) {
        if (cls == null) return null;
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            // Walk up the hierarchy
            Class<?> parent = cls.getSuperclass();
            if (parent != null) return safeDeclaredField(parent, name);
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Public Java API — used by CreateDashboardProgram (no Lua)
    // ══════════════════════════════════════════════════════════════════════

    public record SpeedInfo(float speed, boolean overstressed) {}
    public record StressInfo(float stress, float capacity, boolean overstressed) {}
    public record TankInfo(String fluidName, int amountMb, int capacityMb) {}
    public record BearingInfo(boolean running, float angle, float speed) {}
    public record DeployerInfo(String heldItem, int heldCount, String mode, String state) {}
    public record PressInfo(boolean running, float progressPct) {}
    public record MillInfo(java.util.List<String> inputs, java.util.List<String> outputs, boolean running) {}

    /** Returns true if the BE is any supported Create block entity. */
    public static boolean isAvailableJava(BlockEntity be) {
        return ensureCache() && isAnyCreateBE(be);
    }

    /** Returns the Create peripheral type string for this BE, or null if not a Create BE. */
    public static String getTypeJava(BlockEntity be) {
        if (!ensureCache()) return null;
        if (clsSpeedGaugeBE  != null && clsSpeedGaugeBE.isInstance(be))  return "create_speedometer";
        if (clsStressGaugeBE != null && clsStressGaugeBE.isInstance(be)) return "create_stressometer";
        if (clsFluidTankBE   != null && clsFluidTankBE.isInstance(be))   return "create_tank";
        if (clsBearingBE     != null && clsBearingBE.isInstance(be))     return "create_bearing";
        if (clsDeployerBE    != null && clsDeployerBE.isInstance(be))    return "create_deployer";
        if (clsPressBE       != null && clsPressBE.isInstance(be))       return "create_press";
        if (clsMillstoneBE   != null && clsMillstoneBE.isInstance(be))   return "create_millstone";
        return null;
    }

    public static SpeedInfo querySpeedJava(BlockEntity be) {
        if (!ensureCache()) return null;
        try {
            float speed = ((Number) mGetSpeed.invoke(be)).floatValue();
            boolean over = mIsOverStressed != null && (boolean) mIsOverStressed.invoke(be);
            return new SpeedInfo(speed, over);
        } catch (Exception e) { return null; }
    }

    public static StressInfo queryStressJava(BlockEntity be) {
        if (!ensureCache()) return null;
        try {
            float stress = fKineticStress   != null ? (float) fKineticStress.get(be)   : 0f;
            float cap    = fKineticCapacity != null ? (float) fKineticCapacity.get(be) : 0f;
            boolean over = mIsOverStressed  != null && (boolean) mIsOverStressed.invoke(be);
            return new StressInfo(stress, cap, over);
        } catch (Exception e) { return null; }
    }

    public static TankInfo queryTankJava(BlockEntity be) {
        if (!ensureCache() || fTankInventory == null) return null;
        try {
            FluidTank tank = (FluidTank) fTankInventory.get(be);
            FluidStack fs  = tank.getFluid();
            String fluidName = fs.isEmpty() ? "(empty)"
                : BuiltInRegistries.FLUID.getKey(fs.getFluid()).toString();
            int amount   = fs.isEmpty() ? 0 : (int)(fs.getAmount() / 81);
            int capacity = (int)(tank.getCapacity() / 81);
            return new TankInfo(fluidName, amount, capacity);
        } catch (Exception e) { return null; }
    }

    public static BearingInfo queryBearingJava(BlockEntity be) {
        if (!ensureCache()) return null;
        try {
            boolean running = fBearingRunning != null && (boolean) fBearingRunning.get(be);
            float   angle   = fBearingAngle   != null ? (float)   fBearingAngle.get(be)   : 0f;
            float   speed   = mGetSpeed       != null ? ((Number) mGetSpeed.invoke(be)).floatValue() : 0f;
            return new BearingInfo(running, angle, speed);
        } catch (Exception e) { return null; }
    }

    public static DeployerInfo queryDeployerJava(BlockEntity be) {
        if (!ensureCache()) return null;
        try {
            ItemStack held = fDeployerHeldItem != null ? (ItemStack) fDeployerHeldItem.get(be) : ItemStack.EMPTY;
            String heldName  = held.isEmpty() ? "(none)" : held.getHoverName().getString();
            int    heldCount = held.getCount();
            String mode  = fDeployerMode  != null ? fDeployerMode.get(be).toString()  : "?";
            String state = fDeployerState != null ? fDeployerState.get(be).toString() : "?";
            return new DeployerInfo(heldName, heldCount, mode, state);
        } catch (Exception e) { return null; }
    }

    public static PressInfo queryPressJava(BlockEntity be) {
        if (!ensureCache() || fPressBehaviour == null) return null;
        try {
            Object beh     = fPressBehaviour.get(be);
            boolean running = fPressBehaviourRunning != null && (boolean) fPressBehaviourRunning.get(beh);
            int     ticks   = fPressBehaviourTicks   != null ? (int)     fPressBehaviourTicks.get(beh)   : 0;
            float   pct     = Math.min(100f, ticks * 100f / 40f);
            return new PressInfo(running, pct);
        } catch (Exception e) { return null; }
    }

    public static MillInfo queryMillJava(BlockEntity be) {
        if (!ensureCache()) return null;
        try {
            java.util.List<String> inputs  = new java.util.ArrayList<>();
            java.util.List<String> outputs = new java.util.ArrayList<>();
            if (fMillInputInv != null && mInvGetSlots != null && mInvGetStackInSlot != null) {
                Object inv = fMillInputInv.get(be);
                int slots  = (int) mInvGetSlots.invoke(inv);
                for (int s = 0; s < slots; s++) {
                    ItemStack st = (ItemStack) mInvGetStackInSlot.invoke(inv, s);
                    if (!st.isEmpty()) inputs.add(st.getHoverName().getString() + " x" + st.getCount());
                }
            }
            if (fMillOutputInv != null && mInvGetSlots != null && mInvGetStackInSlot != null) {
                Object inv = fMillOutputInv.get(be);
                int slots  = (int) mInvGetSlots.invoke(inv);
                for (int s = 0; s < slots; s++) {
                    ItemStack st = (ItemStack) mInvGetStackInSlot.invoke(inv, s);
                    if (!st.isEmpty()) outputs.add(st.getHoverName().getString() + " x" + st.getCount());
                }
            }
            float speed = mGetSpeed != null ? ((Number) mGetSpeed.invoke(be)).floatValue() : 0f;
            return new MillInfo(inputs, outputs, speed != 0f);
        } catch (Exception e) { return null; }
    }
}

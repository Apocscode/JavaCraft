package com.apocscode.byteblock.computer.peripheral;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Peripheral adapter for Applied Energistics 2 ME networks.
 *
 * <p>Any AE2 block entity that implements {@code IInWorldGridNodeHost} (cables,
 * interfaces, controllers, drives, etc.) adjacent to the ByteBlock computer
 * exposes the entire ME network as a {@code "me_network"} peripheral.</p>
 *
 * <p>All access is fully reflective — ByteBlock compiles and runs without AE2
 * on the classpath.</p>
 */
public class AE2PeripheralAdapter implements IPeripheralAdapter {

    // ══════════════════════════════════════════════════════════════════════
    // Reflection cache
    // ══════════════════════════════════════════════════════════════════════

    private static volatile boolean cacheReady = false;

    // ── Grid access ───────────────────────────────────────────────────────
    private static Class<?> clsGridNodeHost;   // IInWorldGridNodeHost
    private static Class<?> clsGridNode;        // IGridNode
    private static Class<?> clsGrid;            // IGrid
    private static Method   mGetGridNode;        // IInWorldGridNodeHost.getGridNode(Direction)
    private static Method   mGetGrid;            // IGridNode.getGrid()
    private static Method   mGridSize;           // IGrid.size()

    // ── Storage ───────────────────────────────────────────────────────────
    private static Class<?> clsStorageSvc;
    private static Class<?> clsMEStorage;
    private static Method   mGetStorageService;  // IGrid.getStorageService()
    private static Method   mGetInventory;        // IStorageService.getInventory()
    private static Method   mGetAvailableStacks;  // MEStorage.getAvailableStacks()

    // ── AE Keys ───────────────────────────────────────────────────────────
    private static Class<?> clsAEKey;
    private static Class<?> clsAEItemKey;
    private static Class<?> clsAEFluidKey;
    private static Method   mAEItemGetItem;       // AEItemKey.getItem()
    private static Method   mAEFluidGetFluid;     // AEFluidKey.getFluid()
    private static Method   mAEItemOf;            // AEItemKey.of(Item) — static
    private static Method   mAEItemOfStack;       // AEItemKey.of(ItemStack) — static

    // ── Crafting ──────────────────────────────────────────────────────────
    private static Class<?> clsCraftingSvc;
    private static Class<?> clsAEKeyFilter;
    private static Method   mGetCraftingService;  // IGrid.getCraftingService()
    private static Method   mIsCraftable;         // ICraftingService.isCraftable(AEKey)
    private static Method   mCanEmitFor;          // ICraftingService.canEmitFor(AEKey)
    private static Method   mIsRequesting;        // ICraftingService.isRequesting(AEKey)
    private static Method   mIsRequestingAny;     // ICraftingService.isRequestingAny()
    private static Method   mGetRequestedAmount;  // ICraftingService.getRequestedAmount(AEKey)
    private static Method   mGetCraftables;       // ICraftingService.getCraftables(AEKeyFilter)
    private static Method   mGetCpus;             // ICraftingService.getCpus()
    private static Method   mBeginCraftingCalc;   // ICraftingService.beginCraftingCalculation(...)
    private static Method   mSubmitJob;           // ICraftingService.submitJob(...)
    private static Object   allKeyFilter;          // AEKeyFilter.ALL constant

    // ── ICraftingCPU ─────────────────────────────────────────────────────
    private static Class<?> clsCraftingCPU;
    private static Method   mCpuIsBusy;           // ICraftingCPU.isBusy()
    private static Method   mCpuGetJobStatus;     // ICraftingCPU.getJobStatus() → CraftingJobStatus
    private static Method   mCpuGetAvailableStorage; // ICraftingCPU.getAvailableStorage()
    private static Method   mCpuGetCoProcessors;  // ICraftingCPU.getCoProcessors()
    private static Method   mCpuGetName;          // ICraftingCPU.getName()
    private static Method   mCpuCancelJob;        // ICraftingCPU.cancelJob()

    // ── CraftingJobStatus (record) ────────────────────────────────────────
    private static Method   mJobCrafting;          // CraftingJobStatus.crafting() → GenericStack
    private static Method   mJobTotalItems;        // CraftingJobStatus.totalItems() → long
    private static Method   mJobProgress;          // CraftingJobStatus.progress() → long
    private static Method   mJobElapsed;           // CraftingJobStatus.elapsedTimeNanos() → long

    // ── GenericStack (record) ─────────────────────────────────────────────
    private static Method   mGsWhat;              // GenericStack.what() → AEKey
    private static Method   mGsAmount;            // GenericStack.amount() → long

    // ── Energy ────────────────────────────────────────────────────────────
    private static Class<?> clsEnergySvc;
    private static Method   mGetEnergyService;    // IGrid.getEnergyService()
    private static Method   mGetStoredPower;      // IEnergyService.getStoredPower()
    private static Method   mGetMaxStoredPower;   // IEnergyService.getMaxStoredPower()
    private static Method   mGetAvgPowerUsage;    // IEnergyService.getAvgPowerUsage()
    private static Method   mGetAvgPowerInjection;// IEnergyService.getAvgPowerInjection()
    private static Method   mGetIdlePowerUsage;   // IEnergyService.getIdlePowerUsage()
    private static Method   mIsNetworkPowered;    // IEnergyService.isNetworkPowered()

    // ── ICraftingSimulationRequester / IActionSource (for requestCraft) ───
    private static Class<?> clsSimRequester;
    private static Class<?> clsActionSource;
    private static Class<?> clsCalcStrategy;
    private static Object   calcStrategyDefault;  // CalculationStrategy first value

    // ── ICraftingSubmitResult ─────────────────────────────────────────────
    private static Method   mSubmitSuccessful;    // ICraftingSubmitResult.successful()

    // ══════════════════════════════════════════════════════════════════════
    // IPeripheralAdapter
    // ══════════════════════════════════════════════════════════════════════

    @Override public String getModId() { return "ae2"; }

    @Override
    public boolean canAdapt(BlockEntity be) {
        if (!ensureCache()) return false;
        return clsGridNodeHost != null && clsGridNodeHost.isInstance(be);
    }

    @Override
    public String getType(BlockEntity be) { return "me_network"; }

    @Override
    public LuaTable buildTable(BlockEntity be) {
        LuaTable t = new LuaTable();
        if (!ensureCache()) return t;

        // ── list() → [{name, count, displayName, isCraftable}] ────────────
        t.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                try {
                    Object grid    = getGrid(be);
                    if (grid == null) return result;
                    Object svc     = mGetStorageService.invoke(grid);
                    Object storage = mGetInventory.invoke(svc);
                    Object stacks  = mGetAvailableStacks.invoke(storage);
                    if (!(stacks instanceof Map<?, ?> map)) return result;
                    Object craftSvc = safeGetService(grid, mGetCraftingService);
                    int i = 1;
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        if (!clsAEItemKey.isInstance(e.getKey())) continue;
                        Item item = (Item) mAEItemGetItem.invoke(e.getKey());
                        if (item == null) continue;
                        long count = ((Number) e.getValue()).longValue();
                        LuaTable entry = new LuaTable();
                        entry.set("name",        LuaValue.valueOf(
                                BuiltInRegistries.ITEM.getKey(item).toString()));
                        entry.set("count",       LuaValue.valueOf(count));
                        entry.set("displayName", LuaValue.valueOf(
                                new ItemStack(item).getHoverName().getString()));
                        entry.set("isCraftable", LuaValue.valueOf(
                                isCraftableKey(craftSvc, e.getKey())));
                        result.set(i++, entry);
                    }
                } catch (Exception ignored) {}
                return result;
            }
        });

        // ── listFluids() → [{name, amount, displayName}] ──────────────────
        t.set("listFluids", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                try {
                    Object grid    = getGrid(be);
                    if (grid == null) return result;
                    Object svc     = mGetStorageService.invoke(grid);
                    Object storage = mGetInventory.invoke(svc);
                    Object stacks  = mGetAvailableStacks.invoke(storage);
                    if (!(stacks instanceof Map<?, ?> map)) return result;
                    int i = 1;
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        if (!clsAEFluidKey.isInstance(e.getKey())) continue;
                        net.minecraft.world.level.material.Fluid fluid =
                            (net.minecraft.world.level.material.Fluid) mAEFluidGetFluid.invoke(e.getKey());
                        if (fluid == null) continue;
                        long amount = ((Number) e.getValue()).longValue();
                        LuaTable entry = new LuaTable();
                        entry.set("name",   LuaValue.valueOf(
                                BuiltInRegistries.FLUID.getKey(fluid).toString()));
                        entry.set("amount", LuaValue.valueOf(amount));
                        result.set(i++, entry);
                    }
                } catch (Exception ignored) {}
                return result;
            }
        });

        // ── getItem(name) → {name, count, displayName, isCraftable} | nil ─
        t.set("getItem", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nameV) {
                try {
                    String name = nameV.checkjstring();
                    Item item = lookupItem(name);
                    if (item == null) return LuaValue.NIL;
                    Object grid    = getGrid(be);
                    if (grid == null) return LuaValue.NIL;
                    Object svc     = mGetStorageService.invoke(grid);
                    Object storage = mGetInventory.invoke(svc);
                    Object stacks  = mGetAvailableStacks.invoke(storage);
                    Object craftSvc = safeGetService(grid, mGetCraftingService);
                    if (!(stacks instanceof Map<?, ?> map)) return LuaValue.NIL;
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        if (!clsAEItemKey.isInstance(e.getKey())) continue;
                        Item found = (Item) mAEItemGetItem.invoke(e.getKey());
                        if (!item.equals(found)) continue;
                        long count = ((Number) e.getValue()).longValue();
                        LuaTable entry = new LuaTable();
                        entry.set("name",        LuaValue.valueOf(name));
                        entry.set("count",       LuaValue.valueOf(count));
                        entry.set("displayName", LuaValue.valueOf(
                                new ItemStack(item).getHoverName().getString()));
                        entry.set("isCraftable", LuaValue.valueOf(
                                isCraftableKey(craftSvc, e.getKey())));
                        return entry;
                    }
                    // Not stored — check if craftable
                    Object aeKey = itemToAEKey(item);
                    if (aeKey != null && craftSvc != null && isCraftableKey(craftSvc, aeKey)) {
                        LuaTable entry = new LuaTable();
                        entry.set("name",        LuaValue.valueOf(name));
                        entry.set("count",       LuaValue.valueOf(0));
                        entry.set("displayName", LuaValue.valueOf(new ItemStack(item).getHoverName().getString()));
                        entry.set("isCraftable", LuaValue.TRUE);
                        return entry;
                    }
                } catch (Exception ignored) {}
                return LuaValue.NIL;
            }
        });

        // ── getItemCount(name) → long ──────────────────────────────────────
        t.set("getItemCount", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nameV) {
                try {
                    Item item = lookupItem(nameV.checkjstring());
                    if (item == null) return LuaValue.valueOf(0);
                    Object grid    = getGrid(be);
                    if (grid == null) return LuaValue.valueOf(0);
                    Object stacks  = mGetAvailableStacks.invoke(
                            mGetInventory.invoke(mGetStorageService.invoke(grid)));
                    if (!(stacks instanceof Map<?, ?> map)) return LuaValue.valueOf(0);
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        if (!clsAEItemKey.isInstance(e.getKey())) continue;
                        if (item.equals(mAEItemGetItem.invoke(e.getKey())))
                            return LuaValue.valueOf(((Number) e.getValue()).longValue());
                    }
                } catch (Exception ignored) {}
                return LuaValue.valueOf(0);
            }
        });

        // ── getFluidAmount(name) → long (in mB) ───────────────────────────
        t.set("getFluidAmount", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nameV) {
                try {
                    ResourceLocation rl = ResourceLocation.tryParse(nameV.checkjstring());
                    if (rl == null) return LuaValue.valueOf(0);
                    net.minecraft.world.level.material.Fluid fluid =
                            BuiltInRegistries.FLUID.get(rl);
                    if (fluid == null) return LuaValue.valueOf(0);
                    Object grid   = getGrid(be);
                    if (grid == null) return LuaValue.valueOf(0);
                    Object stacks = mGetAvailableStacks.invoke(
                            mGetInventory.invoke(mGetStorageService.invoke(grid)));
                    if (!(stacks instanceof Map<?, ?> map)) return LuaValue.valueOf(0);
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        if (!clsAEFluidKey.isInstance(e.getKey())) continue;
                        net.minecraft.world.level.material.Fluid found =
                                (net.minecraft.world.level.material.Fluid) mAEFluidGetFluid.invoke(e.getKey());
                        if (fluid.equals(found))
                            return LuaValue.valueOf(((Number) e.getValue()).longValue());
                    }
                } catch (Exception ignored) {}
                return LuaValue.valueOf(0);
            }
        });

        // ── isCraftable(name) → boolean ───────────────────────────────────
        t.set("isCraftable", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nameV) {
                try {
                    Item item = lookupItem(nameV.checkjstring());
                    if (item == null) return LuaValue.FALSE;
                    Object grid     = getGrid(be);
                    if (grid == null) return LuaValue.FALSE;
                    Object craftSvc = safeGetService(grid, mGetCraftingService);
                    if (craftSvc == null) return LuaValue.FALSE;
                    Object aeKey    = itemToAEKey(item);
                    if (aeKey == null) return LuaValue.FALSE;
                    return LuaValue.valueOf(isCraftableKey(craftSvc, aeKey));
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // ── isCurrentlyCrafting(name) → boolean ───────────────────────────
        t.set("isCurrentlyCrafting", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nameV) {
                if (mIsRequesting == null) return LuaValue.FALSE;
                try {
                    Item item = lookupItem(nameV.checkjstring());
                    if (item == null) return LuaValue.FALSE;
                    Object grid     = getGrid(be);
                    if (grid == null) return LuaValue.FALSE;
                    Object craftSvc = safeGetService(grid, mGetCraftingService);
                    if (craftSvc == null) return LuaValue.FALSE;
                    Object aeKey    = itemToAEKey(item);
                    if (aeKey == null) return LuaValue.FALSE;
                    return LuaValue.valueOf((boolean) mIsRequesting.invoke(craftSvc, aeKey));
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // ── isRequestingAnyCraft() → boolean ──────────────────────────────
        t.set("isRequestingAnyCraft", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mIsRequestingAny == null) return LuaValue.FALSE;
                try {
                    Object grid     = getGrid(be);
                    if (grid == null) return LuaValue.FALSE;
                    Object craftSvc = safeGetService(grid, mGetCraftingService);
                    if (craftSvc == null) return LuaValue.FALSE;
                    return LuaValue.valueOf((boolean) mIsRequestingAny.invoke(craftSvc));
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // ── getCraftableItems() → [{name, displayName}] ───────────────────
        t.set("getCraftableItems", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                if (mGetCraftables == null || allKeyFilter == null) return result;
                try {
                    Object grid     = getGrid(be);
                    if (grid == null) return result;
                    Object craftSvc = safeGetService(grid, mGetCraftingService);
                    if (craftSvc == null) return result;
                    Set<?> craftables = (Set<?>) mGetCraftables.invoke(craftSvc, allKeyFilter);
                    int i = 1;
                    for (Object key : craftables) {
                        if (!clsAEItemKey.isInstance(key)) continue;
                        Item item = (Item) mAEItemGetItem.invoke(key);
                        if (item == null) continue;
                        LuaTable entry = new LuaTable();
                        entry.set("name",        LuaValue.valueOf(
                                BuiltInRegistries.ITEM.getKey(item).toString()));
                        entry.set("displayName", LuaValue.valueOf(
                                new ItemStack(item).getHoverName().getString()));
                        result.set(i++, entry);
                    }
                } catch (Exception ignored) {}
                return result;
            }
        });

        // ── listCraftableFluids() → [{name, displayName}] ─────────────────
        t.set("listCraftableFluids", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                if (mGetCraftables == null || allKeyFilter == null) return result;
                try {
                    Object grid     = getGrid(be);
                    if (grid == null) return result;
                    Object craftSvc = safeGetService(grid, mGetCraftingService);
                    if (craftSvc == null) return result;
                    Set<?> craftables = (Set<?>) mGetCraftables.invoke(craftSvc, allKeyFilter);
                    int i = 1;
                    for (Object key : craftables) {
                        if (!clsAEFluidKey.isInstance(key)) continue;
                        net.minecraft.world.level.material.Fluid f =
                            (net.minecraft.world.level.material.Fluid) mAEFluidGetFluid.invoke(key);
                        if (f == null) continue;
                        LuaTable entry = new LuaTable();
                        entry.set("name", LuaValue.valueOf(
                                BuiltInRegistries.FLUID.getKey(f).toString()));
                        result.set(i++, entry);
                    }
                } catch (Exception ignored) {}
                return result;
            }
        });

        // ── getCpus() → [{name, isBusy, storage, coProcessors}] ───────────
        t.set("getCpus", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                if (mGetCpus == null) return result;
                try {
                    Object grid = getGrid(be);
                    if (grid == null) return result;
                    Object craftSvc = safeGetService(grid, mGetCraftingService);
                    if (craftSvc == null) return result;
                    Object cpus = mGetCpus.invoke(craftSvc);
                    if (!(cpus instanceof Iterable<?> it)) return result;
                    int i = 1;
                    for (Object cpu : it) {
                        LuaTable entry = new LuaTable();
                        if (mCpuGetName != null) {
                            try {
                                Object n = mCpuGetName.invoke(cpu);
                                entry.set("name", LuaValue.valueOf(n != null ? n.toString() : ""));
                            } catch (Exception ignored) {}
                        }
                        if (mCpuIsBusy != null) {
                            try { entry.set("isBusy",
                                    LuaValue.valueOf((boolean) mCpuIsBusy.invoke(cpu))); }
                            catch (Exception ignored) {}
                        }
                        if (mCpuGetAvailableStorage != null) {
                            try { entry.set("storage", LuaValue.valueOf(
                                    ((Number) mCpuGetAvailableStorage.invoke(cpu)).longValue())); }
                            catch (Exception ignored) {}
                        }
                        if (mCpuGetCoProcessors != null) {
                            try { entry.set("coProcessors", LuaValue.valueOf(
                                    ((Number) mCpuGetCoProcessors.invoke(cpu)).intValue())); }
                            catch (Exception ignored) {}
                        }
                        result.set(i++, entry);
                    }
                } catch (Exception ignored) {}
                return result;
            }
        });

        // ── requestCraft(name, amount) → string ───────────────────────────
        // Returns "ok", "not_craftable", "missing_items", or "error:<msg>"
        t.set("requestCraft", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                try {
                    String name  = args.checkjstring(1);
                    long   count = args.narg() >= 2 ? (long) args.checkdouble(2) : 1L;
                    Item item    = lookupItem(name);
                    if (item == null) return LuaValue.valueOf("not_found");
                    Object grid     = getGrid(be);
                    if (grid == null) return LuaValue.valueOf("error:no_network");
                    Object craftSvc = safeGetService(grid, mGetCraftingService);
                    if (craftSvc == null) return LuaValue.valueOf("error:no_crafting_service");
                    Object aeKey = itemToAEKey(item);
                    if (aeKey == null) return LuaValue.valueOf("error:key_creation_failed");
                    if (!isCraftableKey(craftSvc, aeKey)) return LuaValue.valueOf("not_craftable");
                    if (mBeginCraftingCalc == null || mSubmitJob == null || clsSimRequester == null)
                        return LuaValue.valueOf("error:api_unavailable");

                    // Build minimal IActionSource via proxy
                    Object actionSource = Proxy.newProxyInstance(
                        clsActionSource.getClassLoader(),
                        new Class[]{clsActionSource},
                        (proxy, method, mArgs) -> {
                            if (method.getName().equals("player"))  return Optional.empty();
                            if (method.getName().equals("machine")) return Optional.empty();
                            return null;
                        }
                    );
                    // Build ICraftingSimulationRequester via proxy
                    final Object capturedSrc = actionSource;
                    Object simReq = Proxy.newProxyInstance(
                        clsSimRequester.getClassLoader(),
                        new Class[]{clsSimRequester},
                        (proxy, method, mArgs) -> {
                            if (method.getName().equals("getActionSource")) return capturedSrc;
                            return null;
                        }
                    );

                    // Get the level from any valid BE on the current server thread
                    Level level = be.getLevel();
                    if (level == null) return LuaValue.valueOf("error:no_level");

                    // beginCraftingCalculation(Level, ICraftingSimulationRequester, AEKey, long, CalculationStrategy)
                    @SuppressWarnings("unchecked")
                    Future<Object> future = (Future<Object>) mBeginCraftingCalc.invoke(
                        craftSvc, level, simReq, aeKey, count, calcStrategyDefault);
                    Object plan = future.get(5, TimeUnit.SECONDS);
                    if (plan == null) return LuaValue.valueOf("error:calculation_failed");

                    // Check if all items are present via plan.missingItems() or similar
                    // For now we just check simulation didn't throw
                    Object submitResult = mSubmitJob.invoke(craftSvc, plan, null, null, true, actionSource);
                    if (submitResult == null) return LuaValue.valueOf("error:submit_failed");
                    if (mSubmitSuccessful != null) {
                        boolean ok = (boolean) mSubmitSuccessful.invoke(submitResult);
                        return LuaValue.valueOf(ok ? "ok" : "missing_items");
                    }
                    return LuaValue.valueOf("ok");
                } catch (Exception e) {
                    return LuaValue.valueOf("error:" + e.getClass().getSimpleName());
                }
            }
        });

        // ── getCraftingJobs() → [{cpu, busy, item, amount, progress, totalItems, elapsed, coProcessors, storage}] ──
        t.set("getCraftingJobs", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                if (mGetCpus == null) return result;
                try {
                    Object grid     = getGrid(be);
                    if (grid == null) return result;
                    Object craftSvc = safeGetService(grid, mGetCraftingService);
                    if (craftSvc == null) return result;
                    @SuppressWarnings("unchecked")
                    Collection<Object> cpus = (Collection<Object>) mGetCpus.invoke(craftSvc);
                    int i = 1;
                    for (Object cpu : cpus) {
                        LuaTable entry = new LuaTable();
                        entry.set("cpu",          LuaValue.valueOf(i));
                        boolean busy = mCpuIsBusy != null && (boolean) mCpuIsBusy.invoke(cpu);
                        entry.set("busy",         LuaValue.valueOf(busy));
                        if (mCpuGetAvailableStorage != null)
                            entry.set("storage",  LuaValue.valueOf(((Number) mCpuGetAvailableStorage.invoke(cpu)).longValue()));
                        if (mCpuGetCoProcessors != null)
                            entry.set("coProcessors", LuaValue.valueOf((int) mCpuGetCoProcessors.invoke(cpu)));
                        if (mCpuGetName != null) {
                            Object nameComp = mCpuGetName.invoke(cpu);
                            entry.set("name", nameComp != null
                                    ? LuaValue.valueOf(((net.minecraft.network.chat.Component) nameComp).getString())
                                    : LuaValue.NIL);
                        }
                        if (busy && mCpuGetJobStatus != null) {
                            Object status = mCpuGetJobStatus.invoke(cpu);
                            if (status != null) {
                                Object gs = mJobCrafting != null ? mJobCrafting.invoke(status) : null;
                                if (gs != null && mGsWhat != null) {
                                    Object key = mGsWhat.invoke(gs);
                                    if (clsAEItemKey.isInstance(key)) {
                                        Item item2 = (Item) mAEItemGetItem.invoke(key);
                                        if (item2 != null)
                                            entry.set("item", LuaValue.valueOf(
                                                    BuiltInRegistries.ITEM.getKey(item2).toString()));
                                    }
                                    if (mGsAmount != null)
                                        entry.set("amount", LuaValue.valueOf(((Number) mGsAmount.invoke(gs)).longValue()));
                                }
                                if (mJobTotalItems != null)
                                    entry.set("totalItems", LuaValue.valueOf(((Number) mJobTotalItems.invoke(status)).longValue()));
                                if (mJobProgress != null)
                                    entry.set("progress",   LuaValue.valueOf(((Number) mJobProgress.invoke(status)).longValue()));
                                if (mJobElapsed != null)
                                    entry.set("elapsedMs",  LuaValue.valueOf(
                                            ((Number) mJobElapsed.invoke(status)).longValue() / 1_000_000L));
                            }
                        }
                        result.set(i++, entry);
                    }
                } catch (Exception ignored) {}
                return result;
            }
        });

        // ── cancelCraftingJob(cpuIndex) ───────────────────────────────────
        t.set("cancelCraftingJob", new OneArgFunction() {
            @Override public LuaValue call(LuaValue idxV) {
                if (mGetCpus == null || mCpuCancelJob == null) return LuaValue.FALSE;
                try {
                    int idx = idxV.checkint();
                    Object grid     = getGrid(be);
                    if (grid == null) return LuaValue.FALSE;
                    Object craftSvc = safeGetService(grid, mGetCraftingService);
                    if (craftSvc == null) return LuaValue.FALSE;
                    @SuppressWarnings("unchecked")
                    List<Object> cpus = new ArrayList<>((Collection<Object>) mGetCpus.invoke(craftSvc));
                    if (idx < 1 || idx > cpus.size()) return LuaValue.FALSE;
                    mCpuCancelJob.invoke(cpus.get(idx - 1));
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // ── getEnergyStatus() → {stored, capacity, avgUsage, avgInjection, idleUsage, percent, powered} ──
        t.set("getEnergyStatus", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable t2 = new LuaTable();
                try {
                    Object grid = getGrid(be);
                    if (grid == null) return t2;
                    Object eng  = safeGetService(grid, mGetEnergyService);
                    if (eng == null) return t2;
                    double stored   = mGetStoredPower    != null ? ((Number) mGetStoredPower.invoke(eng)).doubleValue()    : 0;
                    double capacity = mGetMaxStoredPower != null ? ((Number) mGetMaxStoredPower.invoke(eng)).doubleValue() : 0;
                    t2.set("stored",      LuaValue.valueOf(stored));
                    t2.set("capacity",    LuaValue.valueOf(capacity));
                    if (mGetAvgPowerUsage    != null) t2.set("avgUsage",    LuaValue.valueOf(((Number) mGetAvgPowerUsage.invoke(eng)).doubleValue()));
                    if (mGetAvgPowerInjection!= null) t2.set("avgInjection",LuaValue.valueOf(((Number) mGetAvgPowerInjection.invoke(eng)).doubleValue()));
                    if (mGetIdlePowerUsage   != null) t2.set("idleUsage",   LuaValue.valueOf(((Number) mGetIdlePowerUsage.invoke(eng)).doubleValue()));
                    if (mIsNetworkPowered    != null) t2.set("powered",     LuaValue.valueOf((boolean) mIsNetworkPowered.invoke(eng)));
                    t2.set("percent", LuaValue.valueOf(capacity > 0 ? stored / capacity * 100.0 : 0.0));
                } catch (Exception ignored) {}
                return t2;
            }
        });

        // ── isNetworkPowered() → boolean ──────────────────────────────────
        t.set("isNetworkPowered", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (mIsNetworkPowered == null) return LuaValue.TRUE;
                try {
                    Object grid = getGrid(be);
                    if (grid == null) return LuaValue.FALSE;
                    Object eng  = safeGetService(grid, mGetEnergyService);
                    if (eng == null) return LuaValue.FALSE;
                    return LuaValue.valueOf((boolean) mIsNetworkPowered.invoke(eng));
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        // ── getNetworkInfo() → full summary table ─────────────────────────
        t.set("getNetworkInfo", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable info = new LuaTable();
                try {
                    Object grid = getGrid(be);
                    if (grid == null) return info;

                    // Node count
                    if (mGridSize != null)
                        info.set("nodes", LuaValue.valueOf((int) mGridSize.invoke(grid)));

                    // Storage summary
                    Object stacks = mGetAvailableStacks.invoke(
                            mGetInventory.invoke(mGetStorageService.invoke(grid)));
                    if (stacks instanceof Map<?, ?> map) {
                        long totalItems = 0; int itemTypes = 0; int fluidTypes = 0;
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            long count = ((Number) e.getValue()).longValue();
                            if (clsAEItemKey.isInstance(e.getKey())) { totalItems += count; itemTypes++; }
                            else if (clsAEFluidKey.isInstance(e.getKey())) { fluidTypes++; }
                        }
                        info.set("totalItems", LuaValue.valueOf(totalItems));
                        info.set("itemTypes",  LuaValue.valueOf(itemTypes));
                        info.set("fluidTypes", LuaValue.valueOf(fluidTypes));
                    }

                    // Crafting CPUs
                    if (mGetCpus != null) {
                        Object craftSvc = safeGetService(grid, mGetCraftingService);
                        if (craftSvc != null) {
                            Collection<?> cpus = (Collection<?>) mGetCpus.invoke(craftSvc);
                            int busy = 0;
                            for (Object cpu : cpus) {
                                if (mCpuIsBusy != null && (boolean) mCpuIsBusy.invoke(cpu)) busy++;
                            }
                            info.set("cpus",     LuaValue.valueOf(cpus.size()));
                            info.set("cpusBusy", LuaValue.valueOf(busy));
                            if (mIsRequestingAny != null)
                                info.set("anyCrafting", LuaValue.valueOf((boolean) mIsRequestingAny.invoke(craftSvc)));
                        }
                    }

                    // Energy
                    Object eng = safeGetService(grid, mGetEnergyService);
                    if (eng != null) {
                        double stored   = mGetStoredPower    != null ? ((Number) mGetStoredPower.invoke(eng)).doubleValue()    : 0;
                        double capacity = mGetMaxStoredPower != null ? ((Number) mGetMaxStoredPower.invoke(eng)).doubleValue() : 0;
                        info.set("energyStored",   LuaValue.valueOf(stored));
                        info.set("energyCapacity", LuaValue.valueOf(capacity));
                        info.set("energyPercent",  LuaValue.valueOf(capacity > 0 ? stored / capacity * 100.0 : 0.0));
                        if (mIsNetworkPowered != null)
                            info.set("powered", LuaValue.valueOf((boolean) mIsNetworkPowered.invoke(eng)));
                    }
                } catch (Exception ignored) {}
                return info;
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    /** Try all 6 directions to get the ME grid from this block entity. */
    private static Object getGrid(Object be) throws Exception {
        for (Direction dir : Direction.values()) {
            try {
                Object node = mGetGridNode.invoke(be, dir);
                if (node == null) continue;
                Object grid = mGetGrid.invoke(node);
                if (grid != null) return grid;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Get an optional service from the grid; returns null if unavailable. */
    private static Object safeGetService(Object grid, Method serviceMethod) {
        if (serviceMethod == null) return null;
        try { return serviceMethod.invoke(grid); } catch (Exception e) { return null; }
    }

    /** Returns true if the crafting service reports this key can be crafted. */
    private static boolean isCraftableKey(Object craftSvc, Object aeKey) {
        if (craftSvc == null || aeKey == null) return false;
        try {
            if (mIsCraftable != null) return (boolean) mIsCraftable.invoke(craftSvc, aeKey);
            if (mCanEmitFor  != null) return (boolean) mCanEmitFor.invoke(craftSvc, aeKey);
        } catch (Exception ignored) {}
        return false;
    }

    /** Parse an item name string to an Item. Returns null if not found. */
    private static Item lookupItem(String name) {
        ResourceLocation rl = ResourceLocation.tryParse(name);
        if (rl == null) return null;
        return BuiltInRegistries.ITEM.get(rl);
    }

    /** Create an AEItemKey from an Item using the static AEItemKey.of(Item). */
    private static Object itemToAEKey(Item item) {
        try {
            if (mAEItemOf != null) return mAEItemOf.invoke(null, item);
            if (mAEItemOfStack != null) return mAEItemOfStack.invoke(null, new ItemStack(item));
        } catch (Exception ignored) {}
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reflection cache initializer
    // ══════════════════════════════════════════════════════════════════════

    private static boolean ensureCache() {
        if (cacheReady) return clsGridNodeHost != null;
        cacheReady = true;
        try {
            // ── Grid access ────────────────────────────────────────────────
            clsGridNodeHost = Class.forName("appeng.api.networking.IInWorldGridNodeHost");
            clsGridNode     = Class.forName("appeng.api.networking.IGridNode");
            clsGrid         = Class.forName("appeng.api.networking.IGrid");
            mGetGridNode    = clsGridNodeHost.getMethod("getGridNode", Direction.class);
            mGetGrid        = clsGridNode.getMethod("getGrid");
            mGridSize       = safeMethod(clsGrid, "size");

            // ── Storage ────────────────────────────────────────────────────
            clsStorageSvc      = Class.forName("appeng.api.networking.storage.IStorageService");
            clsMEStorage       = Class.forName("appeng.api.storage.MEStorage");
            mGetStorageService = clsGrid.getMethod("getStorageService");
            mGetInventory      = clsStorageSvc.getMethod("getInventory");
            mGetAvailableStacks= clsMEStorage.getMethod("getAvailableStacks");

            // ── AE Keys ────────────────────────────────────────────────────
            clsAEKey       = Class.forName("appeng.api.stacks.AEKey");
            clsAEItemKey   = Class.forName("appeng.api.stacks.AEItemKey");
            clsAEFluidKey  = Class.forName("appeng.api.stacks.AEFluidKey");
            mAEItemGetItem = clsAEItemKey.getMethod("getItem");
            mAEFluidGetFluid = clsAEFluidKey.getMethod("getFluid");
            mAEItemOf      = safeStaticMethod(clsAEItemKey, "of", Item.class);
            mAEItemOfStack = safeStaticMethod(clsAEItemKey, "of", ItemStack.class);

            // ── Crafting ───────────────────────────────────────────────────
            clsCraftingSvc     = Class.forName("appeng.api.networking.crafting.ICraftingService");
            clsAEKeyFilter     = safeClass("appeng.api.stacks.AEKeyFilter");
            mGetCraftingService= safeMethod(clsGrid, "getCraftingService");
            mIsCraftable       = safeMethod(clsCraftingSvc, "isCraftable", clsAEKey);
            mCanEmitFor        = safeMethod(clsCraftingSvc, "canEmitFor", clsAEKey);
            mIsRequesting      = safeMethod(clsCraftingSvc, "isRequesting", clsAEKey);
            mIsRequestingAny   = safeMethod(clsCraftingSvc, "isRequestingAny");
            mGetRequestedAmount= safeMethod(clsCraftingSvc, "getRequestedAmount", clsAEKey);
            mGetCraftables     = clsAEKeyFilter != null ? safeMethod(clsCraftingSvc, "getCraftables", clsAEKeyFilter) : null;
            mGetCpus           = safeMethod(clsCraftingSvc, "getCpus");
            if (clsAEKeyFilter != null) {
                Field fAll = safeField(clsAEKeyFilter, "ALL");
                if (fAll != null) allKeyFilter = fAll.get(null);
            }

            // Crafting submission APIs
            clsSimRequester    = safeClass("appeng.api.networking.crafting.ICraftingSimulationRequester");
            clsActionSource    = safeClass("appeng.api.networking.security.IActionSource");
            clsCalcStrategy    = safeClass("appeng.api.networking.crafting.CalculationStrategy");
            if (clsCalcStrategy != null) {
                Object[] values = (Object[]) clsCalcStrategy.getMethod("values").invoke(null);
                // Prefer "REPORT_MISSING_ITEMS" for player-style requests
                for (Object v : values) {
                    if (v.toString().equals("REPORT_MISSING_ITEMS")) { calcStrategyDefault = v; break; }
                }
                if (calcStrategyDefault == null && values.length > 0) calcStrategyDefault = values[0];
                if (clsSimRequester != null) {
                    mBeginCraftingCalc = safeMethod(clsCraftingSvc, "beginCraftingCalculation",
                            Level.class, clsSimRequester, clsAEKey, long.class, clsCalcStrategy);
                }
                if (clsActionSource != null) {
                    Class<?> clsSubmitResult = safeClass("appeng.api.networking.crafting.ICraftingSubmitResult");
                    Class<?> clsCraftingCPUClass = safeClass("appeng.api.networking.crafting.ICraftingCPU");
                    if (clsSubmitResult != null && clsCraftingCPUClass != null) {
                        mSubmitJob = safeMethod(clsCraftingSvc, "submitJob",
                                safeClass("appeng.api.crafting.ICraftingPlan"),
                                safeClass("appeng.api.networking.crafting.ICraftingRequester"),
                                clsCraftingCPUClass, boolean.class, clsActionSource);
                        mSubmitSuccessful = safeMethod(clsSubmitResult, "successful");
                    }
                }
            }

            // ── ICraftingCPU ───────────────────────────────────────────────
            clsCraftingCPU           = safeClass("appeng.api.networking.crafting.ICraftingCPU");
            if (clsCraftingCPU != null) {
                mCpuIsBusy           = safeMethod(clsCraftingCPU, "isBusy");
                mCpuGetJobStatus     = safeMethod(clsCraftingCPU, "getJobStatus");
                mCpuGetAvailableStorage = safeMethod(clsCraftingCPU, "getAvailableStorage");
                mCpuGetCoProcessors  = safeMethod(clsCraftingCPU, "getCoProcessors");
                mCpuGetName          = safeMethod(clsCraftingCPU, "getName");
                mCpuCancelJob        = safeMethod(clsCraftingCPU, "cancelJob");
            }

            // ── CraftingJobStatus (record) ─────────────────────────────────
            Class<?> clsJobStatus = safeClass("appeng.api.networking.crafting.CraftingJobStatus");
            if (clsJobStatus != null) {
                mJobCrafting   = safeMethod(clsJobStatus, "crafting");
                mJobTotalItems = safeMethod(clsJobStatus, "totalItems");
                mJobProgress   = safeMethod(clsJobStatus, "progress");
                mJobElapsed    = safeMethod(clsJobStatus, "elapsedTimeNanos");
            }

            // ── GenericStack (record) ──────────────────────────────────────
            Class<?> clsGenericStack = safeClass("appeng.api.stacks.GenericStack");
            if (clsGenericStack != null) {
                mGsWhat   = safeMethod(clsGenericStack, "what");
                mGsAmount = safeMethod(clsGenericStack, "amount");
            }

            // ── Energy ────────────────────────────────────────────────────
            clsEnergySvc          = safeClass("appeng.api.networking.energy.IEnergyService");
            mGetEnergyService     = safeMethod(clsGrid, "getEnergyService");
            if (clsEnergySvc != null) {
                mGetStoredPower        = safeMethod(clsEnergySvc, "getStoredPower");
                mGetMaxStoredPower     = safeMethod(clsEnergySvc, "getMaxStoredPower");
                mGetAvgPowerUsage      = safeMethod(clsEnergySvc, "getAvgPowerUsage");
                mGetAvgPowerInjection  = safeMethod(clsEnergySvc, "getAvgPowerInjection");
                mGetIdlePowerUsage     = safeMethod(clsEnergySvc, "getIdlePowerUsage");
                mIsNetworkPowered      = safeMethod(clsEnergySvc, "isNetworkPowered");
            }

            return true;
        } catch (Exception e) {
            clsGridNodeHost = null;
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

    private static Method safeStaticMethod(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) return null;
        try {
            Method m = cls.getMethod(name, params);
            return m;
        } catch (Exception e) { return null; }
    }

    private static Field safeField(Class<?> cls, String name) {
        if (cls == null) return null;
        try { return cls.getField(name); } catch (Exception e) { return null; }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Public Java API — used by MEDashboardProgram (no Lua)
    // ══════════════════════════════════════════════════════════════════════

    /** An item stored in the ME network. */
    public record AEItemEntry(String name, String displayName, long count, boolean craftable) {}
    /** A fluid stored in the ME network. */
    public record AEFluidEntry(String name, long amountMb) {}
    /** ME network energy snapshot. */
    public record AEEnergyInfo(double stored, double capacity, double avgUsage, double avgInjection, boolean powered) {}
    /** One active crafting job. */
    public record AECraftingJob(String itemName, String cpuName, long doneItems, long totalItems, long elapsedNanos) {}

    /** Returns true if the BE is a valid AE2 node and has an accessible grid. */
    public static boolean isAvailableJava(BlockEntity be) {
        if (!ensureCache()) return false;
        if (clsGridNodeHost == null || !clsGridNodeHost.isInstance(be)) return false;
        try { return getGrid(be) != null; } catch (Exception e) { return false; }
    }

    /** Returns energy stats, or null on failure. */
    public static AEEnergyInfo queryEnergyJava(BlockEntity be) {
        if (!ensureCache()) return null;
        try {
            Object grid = getGrid(be);
            if (grid == null) return null;
            Object eng = safeGetService(grid, mGetEnergyService);
            if (eng == null) return null;
            double stored    = ((Number) mGetStoredPower.invoke(eng)).doubleValue();
            double capacity  = ((Number) mGetMaxStoredPower.invoke(eng)).doubleValue();
            double avgUsage  = mGetAvgPowerUsage    != null ? ((Number) mGetAvgPowerUsage.invoke(eng)).doubleValue()    : 0;
            double avgInject = mGetAvgPowerInjection != null ? ((Number) mGetAvgPowerInjection.invoke(eng)).doubleValue() : 0;
            boolean powered  = mIsNetworkPowered != null && (boolean) mIsNetworkPowered.invoke(eng);
            return new AEEnergyInfo(stored, capacity, avgUsage, avgInject, powered);
        } catch (Exception e) { return null; }
    }

    /** Returns all items currently stored in the ME network. */
    public static List<AEItemEntry> queryItemsJava(BlockEntity be) {
        List<AEItemEntry> list = new ArrayList<>();
        if (!ensureCache()) return list;
        try {
            Object grid    = getGrid(be);
            if (grid == null) return list;
            Object stacks  = mGetAvailableStacks.invoke(mGetInventory.invoke(mGetStorageService.invoke(grid)));
            if (!(stacks instanceof Map<?, ?> map)) return list;
            Object craftSvc = safeGetService(grid, mGetCraftingService);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!clsAEItemKey.isInstance(e.getKey())) continue;
                Item item = (Item) mAEItemGetItem.invoke(e.getKey());
                if (item == null) continue;
                long count = ((Number) e.getValue()).longValue();
                String name        = BuiltInRegistries.ITEM.getKey(item).toString();
                String displayName = new ItemStack(item).getHoverName().getString();
                boolean craftable  = isCraftableKey(craftSvc, e.getKey());
                list.add(new AEItemEntry(name, displayName, count, craftable));
            }
        } catch (Exception ignored) {}
        return list;
    }

    /** Returns all fluids currently stored in the ME network (amounts in mB). */
    public static List<AEFluidEntry> queryFluidsJava(BlockEntity be) {
        List<AEFluidEntry> list = new ArrayList<>();
        if (!ensureCache()) return list;
        try {
            Object grid   = getGrid(be);
            if (grid == null) return list;
            Object stacks = mGetAvailableStacks.invoke(mGetInventory.invoke(mGetStorageService.invoke(grid)));
            if (!(stacks instanceof Map<?, ?> map)) return list;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!clsAEFluidKey.isInstance(e.getKey())) continue;
                net.minecraft.world.level.material.Fluid fluid =
                    (net.minecraft.world.level.material.Fluid) mAEFluidGetFluid.invoke(e.getKey());
                if (fluid == null) continue;
                long droplets = ((Number) e.getValue()).longValue();
                String name = BuiltInRegistries.FLUID.getKey(fluid).toString();
                list.add(new AEFluidEntry(name, droplets / 81));  // 81 droplets per mB
            }
        } catch (Exception ignored) {}
        return list;
    }

    /** Returns active crafting jobs across all CPUs. */
    public static List<AECraftingJob> queryCraftingJobsJava(BlockEntity be) {
        List<AECraftingJob> jobs = new ArrayList<>();
        if (!ensureCache() || mGetCpus == null || mCpuIsBusy == null) return jobs;
        try {
            Object grid = getGrid(be);
            if (grid == null) return jobs;
            Object craftSvc = safeGetService(grid, mGetCraftingService);
            if (craftSvc == null) return jobs;
            Collection<?> cpus = (Collection<?>) mGetCpus.invoke(craftSvc);
            int idx = 0;
            for (Object cpu : cpus) {
                boolean busy = (boolean) mCpuIsBusy.invoke(cpu);
                if (!busy) continue;
                String cpuName = "CPU " + (++idx);
                try {
                    if (mCpuGetName != null) {
                        Object comp = mCpuGetName.invoke(cpu);
                        if (comp != null) {
                            String s = comp.toString();
                            if (!s.isEmpty()) cpuName = s;
                        }
                    }
                } catch (Exception ignored) {}
                String itemName = "Unknown";
                long doneItems = 0, totalItems = 0, elapsedNanos = 0;
                try {
                    if (mCpuGetJobStatus != null) {
                        Object status = mCpuGetJobStatus.invoke(cpu);
                        if (status != null) {
                            if (mJobTotalItems != null) totalItems  = ((Number) mJobTotalItems.invoke(status)).longValue();
                            if (mJobProgress   != null) doneItems   = ((Number) mJobProgress.invoke(status)).longValue();
                            if (mJobElapsed    != null) elapsedNanos= ((Number) mJobElapsed.invoke(status)).longValue();
                            if (mJobCrafting   != null) {
                                Object gs = mJobCrafting.invoke(status);
                                if (gs != null && mGsWhat != null) {
                                    Object key = mGsWhat.invoke(gs);
                                    if (key != null && clsAEItemKey.isInstance(key)) {
                                        Item item = (Item) mAEItemGetItem.invoke(key);
                                        if (item != null)
                                            itemName = new ItemStack(item).getHoverName().getString();
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
                jobs.add(new AECraftingJob(itemName, cpuName, doneItems, totalItems, elapsedNanos));
            }
        } catch (Exception ignored) {}
        return jobs;
    }

    /** Returns the number of grid nodes in the ME network. */
    public static int queryNodeCountJava(BlockEntity be) {
        if (!ensureCache() || mGridSize == null) return 0;
        try {
            Object grid = getGrid(be);
            if (grid == null) return 0;
            return ((Number) mGridSize.invoke(grid)).intValue();
        } catch (Exception e) { return 0; }
    }
}

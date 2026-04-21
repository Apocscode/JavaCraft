package com.apocscode.byteblock.computer.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry for external mod storage integrations.
 *
 * Mod integrations (e.g. AE2) are registered here and queried by
 * {@link StorageScanner} during a materials scan. Integrations are loaded
 * lazily using reflection so the mod compiles even when the target mod
 * is absent at runtime.
 *
 * To add support for another mod:
 *   ModLinkRegistry.register(new MyModStorageLink());
 */
public class ModLinkRegistry {

    private static final List<IStorageProvider> PROVIDERS = new ArrayList<>();
    private static boolean initialized = false;

    /** Register a mod storage provider. */
    public static void register(IStorageProvider provider) {
        PROVIDERS.add(provider);
    }

    /** @return All registered providers. */
    public static List<IStorageProvider> getProviders() {
        return PROVIDERS;
    }

    /**
     * Call once during mod setup to load built-in integrations.
     * Only registers integrations for mods that are actually loaded.
     */
    public static void registerDefaults() {
        if (initialized) return;
        initialized = true;

        if (ModList.get().isLoaded("ae2")) {
            register(new AE2StorageLink());
        }

        if (ModList.get().isLoaded("logiclink")) {
            register(new LogicLinkStorageLink());
        }
    }

    // ── Built-in: Applied Energistics 2 integration ───────────────────────────

    /**
     * Queries AE2 ME networks adjacent to the given position via reflection.
     * All AE2 API access is reflective so this compiles without AE2 on the
     * classpath.
     */
    static final class AE2StorageLink implements IStorageProvider {

        // Reflected class/method references, cached on first use.
        private static volatile boolean cacheReady = false;
        private static Class<?> clsGridNodeHost;
        private static Class<?> clsGridNode;
        private static Class<?> clsGrid;
        private static Class<?> clsStorageSvc;
        private static Class<?> clsAEItemKey;
        private static Method   mGetGridNode;
        private static Method   mGetGrid;
        private static Method   mGetStorageService;
        private static Method   mGetInventory;
        private static Method   mGetAvailableStacks;
        private static Method   mGetItem;

        @Override
        public String getModId() { return "ae2"; }

        @Override
        public Map<String, Long> getItemCounts(Level level, BlockPos nearPos) {
            Map<String, Long> result = new HashMap<>();
            if (!ensureCache()) return result;

            for (Direction dir : Direction.values()) {
                BlockPos adjPos = nearPos.relative(dir);
                BlockEntity be = level.getBlockEntity(adjPos);
                if (be == null || !clsGridNodeHost.isInstance(be)) continue;

                try {
                    // IGridNode node = be.getGridNode(dir.getOpposite())
                    Object gridNode = mGetGridNode.invoke(be, dir.getOpposite());
                    if (gridNode == null) continue;

                    // IGrid grid = node.getGrid()
                    Object grid = mGetGrid.invoke(gridNode);
                    if (grid == null) continue;

                    // IStorageService svc = grid.getStorageService()
                    Object svc = mGetStorageService.invoke(grid);
                    if (svc == null) continue;

                    // MEStorage inventory = svc.getInventory()
                    Object inventory = mGetInventory.invoke(svc);
                    if (inventory == null) continue;

                    // KeyCounter stacks = inventory.getAvailableStacks()
                    // KeyCounter extends Object2LongOpenHashMap<AEKey> which is a Map<AEKey,Long>
                    Object stacks = mGetAvailableStacks.invoke(inventory);
                    if (!(stacks instanceof Map<?, ?> stackMap)) continue;

                    for (Map.Entry<?, ?> entry : stackMap.entrySet()) {
                        if (!clsAEItemKey.isInstance(entry.getKey())) continue;
                        Item item = (Item) mGetItem.invoke(entry.getKey());
                        if (item == null) continue;
                        long amount = ((Number) entry.getValue()).longValue();
                        String id = BuiltInRegistries.ITEM.getKey(item).toString();
                        result.merge(id, amount, Long::sum);
                    }
                    // Found a grid — no need to check other faces
                    break;
                } catch (Exception ignored) {}
            }
            return result;
        }

        /** Populate reflection caches. Returns false if AE2 API is unavailable. */
        private static boolean ensureCache() {
            if (cacheReady) return clsGridNodeHost != null;
            cacheReady = true;
            try {
                clsGridNodeHost = Class.forName("appeng.api.networking.IInWorldGridNodeHost");
                clsGridNode     = Class.forName("appeng.api.networking.IGridNode");
                clsGrid         = Class.forName("appeng.api.networking.IGrid");
                clsStorageSvc   = Class.forName("appeng.api.networking.storage.IStorageService");
                clsAEItemKey    = Class.forName("appeng.api.stacks.AEItemKey");

                mGetGridNode        = clsGridNodeHost.getMethod("getGridNode", Direction.class);
                mGetGrid            = clsGridNode.getMethod("getGrid");
                mGetStorageService  = clsGrid.getMethod("getStorageService");
                mGetInventory       = clsStorageSvc.getMethod("getInventory");
                mGetAvailableStacks = Class.forName("appeng.api.storage.MEStorage")
                                          .getMethod("getAvailableStacks");
                mGetItem            = clsAEItemKey.getMethod("getItem");
                return true;
            } catch (Exception e) {
                // AE2 not present or API changed — silently disable integration
                clsGridNodeHost = null;
                return false;
            }
        }
    }

    // ── Built-in: CreateLogicLink integration ─────────────────────────────────

    /**
     * Queries Create logistics networks via a Logic Link Hub adjacent to the
     * given position. The hub's cached {@code InventorySummary} is read
     * reflectively so this compiles without LogicLink or Create on the
     * classpath.
     *
     * <p>Place a Logic Link Hub (linked to a Create logistics frequency) next
     * to a ByteChest; the Materials Calculator scan will then include all
     * items visible on that logistics network.
     */
    static final class LogicLinkStorageLink implements IStorageProvider {

        private static volatile boolean cacheReady = false;
        private static Class<?>         clsLogicLinkBE;
        private static Method           mGetNetworkSummary;
        private static Method           mGetStacks;
        private static java.lang.reflect.Field fStack;
        private static java.lang.reflect.Field fCount;

        @Override
        public String getModId() { return "logiclink"; }

        @Override
        public Map<String, Long> getItemCounts(Level level, BlockPos nearPos) {
            Map<String, Long> result = new HashMap<>();
            if (!ensureCache()) return result;

            for (Direction dir : Direction.values()) {
                BlockPos adjPos = nearPos.relative(dir);
                BlockEntity be = level.getBlockEntity(adjPos);
                if (be == null || !clsLogicLinkBE.isInstance(be)) continue;

                try {
                    // InventorySummary summary = hub.getNetworkSummary()
                    Object summary = mGetNetworkSummary.invoke(be);
                    if (summary == null) continue;

                    // List<BigItemStack> stacks = summary.getStacks()
                    Object stacksRaw = mGetStacks.invoke(summary);
                    if (!(stacksRaw instanceof java.util.List<?> stacks)) continue;

                    for (Object bis : stacks) {
                        if (bis == null) continue;
                        net.minecraft.world.item.ItemStack stack =
                                (net.minecraft.world.item.ItemStack) fStack.get(bis);
                        if (stack == null || stack.isEmpty()) continue;
                        long count = ((Number) fCount.get(bis)).longValue();
                        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        result.merge(id, count, Long::sum);
                    }
                } catch (Exception ignored) {}
            }
            return result;
        }

        /** Populate reflection caches. Returns false if LogicLink API is unavailable. */
        private static boolean ensureCache() {
            if (cacheReady) return clsLogicLinkBE != null;
            cacheReady = true;
            try {
                clsLogicLinkBE = Class.forName(
                        "com.apocscode.logiclink.block.LogicLinkBlockEntity");
                Class<?> clsInventorySummary = Class.forName(
                        "com.simibubi.create.content.logistics.packager.InventorySummary");
                Class<?> clsBigItemStack = Class.forName(
                        "com.simibubi.create.content.logistics.BigItemStack");

                mGetNetworkSummary = clsLogicLinkBE.getMethod("getNetworkSummary");
                mGetStacks         = clsInventorySummary.getMethod("getStacks");

                fStack = clsBigItemStack.getField("stack");
                fCount = clsBigItemStack.getField("count");
                fStack.setAccessible(true);
                fCount.setAccessible(true);
                return true;
            } catch (Exception e) {
                // LogicLink not present or API changed — silently disable integration
                clsLogicLinkBE = null;
                return false;
            }
        }
    }
}

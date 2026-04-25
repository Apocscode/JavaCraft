package com.apocscode.byteblock.computer.peripheral;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Peripheral adapter for the Create: Storage mod ({@code fxntstorage}).
 *
 * <p>Mirrors the API surface of CreateLogicLink's
 * {@code StorageControllerPeripheral} and {@code StorageInterfacePeripheral}
 * so Lua programs written for those peripherals run unchanged on ByteBlock.
 *
 * <p>All access is reflective — ByteBlock compiles without {@code fxntstorage}
 * on the classpath.</p>
 */
public class CreateStoragePeripheralAdapter implements IPeripheralAdapter {

    // ══════════════════════════════════════════════════════════════════════
    // Reflection cache
    // ══════════════════════════════════════════════════════════════════════

    private static volatile boolean cacheReady = false;

    // BE classes
    private static Class<?> clsControllerBE;
    private static Class<?> clsInterfaceBE;

    // StorageControllerEntity
    private static Method mCtrlGetItemHandler;        // IItemHandlerModifiable getItemHandler()
    private static Field  fCtrlStorageNetwork;        // public StorageNetwork storageNetwork

    // StorageInterfaceEntity
    private static Method mIfaceGetItemHandler;
    private static Field  fIfaceController;           // public StorageControllerEntity controller

    // StorageNetwork
    private static Field  fNetworkBoxes;              // List<StorageNetworkItem> boxes

    // StorageNetworkItem
    private static Field  fNiBlockPos;                // BlockPos blockPos
    private static Field  fNiSimpleStorageBoxEntity;  // SimpleStorageBoxEntity simpleStorageBoxEntity

    // SimpleStorageBoxEntity
    private static Method mBoxGetStoredAmount;
    private static Method mBoxGetMaxItemCapacity;
    private static Method mBoxGetCapacityUpgrades;
    private static Method mBoxHasVoidUpgrade;
    private static Method mBoxGetFilterItem;
    private static Method mBoxGetItemHandler;

    // ══════════════════════════════════════════════════════════════════════
    // IPeripheralAdapter
    // ══════════════════════════════════════════════════════════════════════

    @Override public String getModId() { return "fxntstorage"; }

    @Override
    public boolean canAdapt(BlockEntity be) {
        if (!ensureCache()) return false;
        return (clsControllerBE != null && clsControllerBE.isInstance(be))
            || (clsInterfaceBE  != null && clsInterfaceBE.isInstance(be));
    }

    @Override
    public String getType(BlockEntity be) {
        if (!ensureCache()) return "unknown";
        if (clsControllerBE != null && clsControllerBE.isInstance(be)) return "storage_controller";
        if (clsInterfaceBE  != null && clsInterfaceBE.isInstance(be))  return "storage_interface";
        return "unknown";
    }

    @Override
    public LuaTable buildTable(BlockEntity be) {
        if (!ensureCache()) return new LuaTable();
        if (clsControllerBE != null && clsControllerBE.isInstance(be)) return buildControllerTable(be);
        if (clsInterfaceBE  != null && clsInterfaceBE.isInstance(be))  return buildInterfaceTable(be);
        return new LuaTable();
    }

    // ══════════════════════════════════════════════════════════════════════
    // storage_controller
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildControllerTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        t.set("isConnected", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    Object net = fCtrlStorageNetwork.get(be);
                    if (net == null) return LuaValue.FALSE;
                    List<?> boxes = (List<?>) fNetworkBoxes.get(net);
                    return LuaValue.valueOf(boxes != null && !boxes.isEmpty());
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        t.set("getBoxCount", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    Object net = fCtrlStorageNetwork.get(be);
                    if (net == null) return LuaValue.valueOf(0);
                    List<?> boxes = (List<?>) fNetworkBoxes.get(net);
                    return LuaValue.valueOf(boxes != null ? boxes.size() : 0);
                } catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        addPositionMethod(t, be);

        t.set("size", new ZeroArgFunction() {
            @Override public LuaValue call() {
                IItemHandlerModifiable h = ctrlHandler(be);
                return LuaValue.valueOf(h != null ? h.getSlots() : 0);
            }
        });

        t.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                IItemHandlerModifiable h = ctrlHandler(be);
                LuaTable result = new LuaTable();
                if (h == null) return result;
                int slots = h.getSlots();
                for (int i = 0; i < slots; i++) {
                    ItemStack stack = h.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        result.set(i + 1, itemToTable(stack));
                    }
                }
                return result;
            }
        });

        t.set("getItemDetail", new OneArgFunction() {
            @Override public LuaValue call(LuaValue slotArg) {
                IItemHandlerModifiable h = ctrlHandler(be);
                if (h == null) return LuaValue.NIL;
                int idx = slotArg.checkint() - 1;
                if (idx < 0 || idx >= h.getSlots()) return LuaValue.NIL;
                ItemStack stack = h.getStackInSlot(idx);
                if (stack.isEmpty()) return LuaValue.NIL;
                LuaTable info = itemToTable(stack);
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

        t.set("getBoxes", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable result = new LuaTable();
                try {
                    Object net = fCtrlStorageNetwork.get(be);
                    if (net == null) return result;
                    List<?> boxes = (List<?>) fNetworkBoxes.get(net);
                    if (boxes == null) return result;
                    for (int i = 0; i < boxes.size(); i++) {
                        Object ni = boxes.get(i);
                        Object box = fNiSimpleStorageBoxEntity.get(ni);
                        BlockPos pos = (BlockPos) fNiBlockPos.get(ni);

                        LuaTable info = new LuaTable();
                        info.set("slot", LuaValue.valueOf(i + 1));

                        LuaTable posTbl = new LuaTable();
                        posTbl.set("x", LuaValue.valueOf(pos.getX()));
                        posTbl.set("y", LuaValue.valueOf(pos.getY()));
                        posTbl.set("z", LuaValue.valueOf(pos.getZ()));
                        info.set("position", posTbl);

                        if (mBoxGetStoredAmount != null)
                            info.set("storedAmount", LuaValue.valueOf(((Number) mBoxGetStoredAmount.invoke(box)).longValue()));
                        if (mBoxGetMaxItemCapacity != null)
                            info.set("maxCapacity", LuaValue.valueOf(((Number) mBoxGetMaxItemCapacity.invoke(box)).longValue()));
                        if (mBoxGetCapacityUpgrades != null)
                            info.set("capacityUpgrades", LuaValue.valueOf(((Number) mBoxGetCapacityUpgrades.invoke(box)).intValue()));
                        if (mBoxHasVoidUpgrade != null)
                            info.set("hasVoidUpgrade", LuaValue.valueOf((boolean) mBoxHasVoidUpgrade.invoke(box)));

                        if (mBoxGetFilterItem != null) {
                            ItemStack filter = (ItemStack) mBoxGetFilterItem.invoke(box);
                            if (filter != null && !filter.isEmpty()) {
                                info.set("filter", LuaValue.valueOf(itemId(filter)));
                                info.set("filterDisplayName", LuaValue.valueOf(filter.getHoverName().getString()));
                            }
                        }

                        if (mBoxGetItemHandler != null) {
                            Object boxHandler = mBoxGetItemHandler.invoke(box);
                            if (boxHandler instanceof IItemHandlerModifiable bh && bh.getSlots() > 0) {
                                ItemStack stored = bh.getStackInSlot(0);
                                if (!stored.isEmpty()) {
                                    info.set("item", LuaValue.valueOf(itemId(stored)));
                                    info.set("itemDisplayName", LuaValue.valueOf(stored.getHoverName().getString()));
                                    info.set("count", LuaValue.valueOf(stored.getCount()));
                                }
                            }
                        }

                        result.set(i + 1, info);
                    }
                } catch (Exception ignored) {}
                return result;
            }
        });

        t.set("getTotalItemCount", new ZeroArgFunction() {
            @Override public LuaValue call() {
                IItemHandlerModifiable h = ctrlHandler(be);
                if (h == null) return LuaValue.valueOf(0);
                long total = 0;
                for (int i = 0; i < h.getSlots(); i++) total += h.getStackInSlot(i).getCount();
                return LuaValue.valueOf(total);
            }
        });

        t.set("getTotalCapacity", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    Object net = fCtrlStorageNetwork.get(be);
                    if (net == null) return LuaValue.valueOf(0);
                    List<?> boxes = (List<?>) fNetworkBoxes.get(net);
                    if (boxes == null || mBoxGetMaxItemCapacity == null) return LuaValue.valueOf(0);
                    long total = 0;
                    for (Object ni : boxes) {
                        Object box = fNiSimpleStorageBoxEntity.get(ni);
                        total += ((Number) mBoxGetMaxItemCapacity.invoke(box)).longValue();
                    }
                    return LuaValue.valueOf(total);
                } catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        t.set("getItemCount", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                return LuaValue.valueOf(countItem(ctrlHandler(be), arg.checkjstring()));
            }
        });

        t.set("getItemSummary", new ZeroArgFunction() {
            @Override public LuaValue call() { return buildItemSummary(ctrlHandler(be)); }
        });

        t.set("findItems", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                return findItems(ctrlHandler(be), arg.checkjstring());
            }
        });

        // pushItems / pullItems — extract-only stub (full peripheral routing
        // requires IComputerAccess which ByteBlock does not currently expose).
        t.set("pushItems", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                IItemHandlerModifiable h = ctrlHandler(be);
                if (h == null) return LuaValue.valueOf(0);
                int fromSlot = args.checkint(2) - 1;
                if (fromSlot < 0 || fromSlot >= h.getSlots()) return LuaValue.valueOf(0);
                ItemStack src = h.getStackInSlot(fromSlot);
                if (src.isEmpty()) return LuaValue.valueOf(0);
                int limit = args.narg() >= 3 && !args.arg(3).isnil() ? args.checkint(3) : src.getCount();
                int amount = Math.min(limit, src.getCount());
                ItemStack extracted = h.extractItem(fromSlot, amount, false);
                return LuaValue.valueOf(extracted.getCount());
            }
        });
        t.set("pullItems", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                // Cross-peripheral transfer requires CC's IComputerAccess.
                // Returns 0 for safe no-op compatibility with upstream stub behavior.
                return LuaValue.valueOf(0);
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // storage_interface
    // ══════════════════════════════════════════════════════════════════════

    private LuaTable buildInterfaceTable(BlockEntity be) {
        LuaTable t = new LuaTable();

        t.set("isConnected", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(fIfaceController.get(be) != null); }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        addPositionMethod(t, be);

        t.set("getControllerPosition", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    Object ctrl = fIfaceController.get(be);
                    if (!(ctrl instanceof BlockEntity ctrlBe)) return LuaValue.NIL;
                    BlockPos p = ctrlBe.getBlockPos();
                    LuaTable tbl = new LuaTable();
                    tbl.set("x", LuaValue.valueOf(p.getX()));
                    tbl.set("y", LuaValue.valueOf(p.getY()));
                    tbl.set("z", LuaValue.valueOf(p.getZ()));
                    return tbl;
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        t.set("size", new ZeroArgFunction() {
            @Override public LuaValue call() {
                IItemHandlerModifiable h = ifaceHandler(be);
                return LuaValue.valueOf(h != null ? h.getSlots() : 0);
            }
        });

        t.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                IItemHandlerModifiable h = ifaceHandler(be);
                LuaTable result = new LuaTable();
                if (h == null) return result;
                int slots = h.getSlots();
                for (int i = 0; i < slots; i++) {
                    ItemStack stack = h.getStackInSlot(i);
                    if (!stack.isEmpty()) result.set(i + 1, itemToTable(stack));
                }
                return result;
            }
        });

        t.set("getItemDetail", new OneArgFunction() {
            @Override public LuaValue call(LuaValue slotArg) {
                IItemHandlerModifiable h = ifaceHandler(be);
                if (h == null) return LuaValue.NIL;
                int idx = slotArg.checkint() - 1;
                if (idx < 0 || idx >= h.getSlots()) return LuaValue.NIL;
                ItemStack stack = h.getStackInSlot(idx);
                if (stack.isEmpty()) return LuaValue.NIL;
                LuaTable info = itemToTable(stack);
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

        t.set("getBoxCount", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    Object ctrl = fIfaceController.get(be);
                    if (ctrl == null) return LuaValue.valueOf(0);
                    Object net = fCtrlStorageNetwork.get(ctrl);
                    if (net == null) return LuaValue.valueOf(0);
                    List<?> boxes = (List<?>) fNetworkBoxes.get(net);
                    return LuaValue.valueOf(boxes != null ? boxes.size() : 0);
                } catch (Exception e) { return LuaValue.valueOf(0); }
            }
        });

        t.set("getItemCount", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                return LuaValue.valueOf(countItem(ifaceHandler(be), arg.checkjstring()));
            }
        });

        t.set("getItemSummary", new ZeroArgFunction() {
            @Override public LuaValue call() { return buildItemSummary(ifaceHandler(be)); }
        });

        t.set("findItems", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                return findItems(ifaceHandler(be), arg.checkjstring());
            }
        });

        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private static IItemHandlerModifiable ctrlHandler(BlockEntity be) {
        if (mCtrlGetItemHandler == null) return null;
        try { return (IItemHandlerModifiable) mCtrlGetItemHandler.invoke(be); }
        catch (Exception e) { return null; }
    }

    private static IItemHandlerModifiable ifaceHandler(BlockEntity be) {
        if (mIfaceGetItemHandler == null) return null;
        try { return (IItemHandlerModifiable) mIfaceGetItemHandler.invoke(be); }
        catch (Exception e) { return null; }
    }

    private static String itemId(ItemStack stack) {
        return stack.getItem().builtInRegistryHolder().key().location().toString();
    }

    private static LuaTable itemToTable(ItemStack stack) {
        LuaTable t = new LuaTable();
        t.set("name",        LuaValue.valueOf(itemId(stack)));
        t.set("displayName", LuaValue.valueOf(stack.getHoverName().getString()));
        t.set("count",       LuaValue.valueOf(stack.getCount()));
        t.set("maxCount",    LuaValue.valueOf(stack.getMaxStackSize()));
        return t;
    }

    private static int countItem(IItemHandlerModifiable h, String id) {
        if (h == null) return 0;
        int total = 0;
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack stack = h.getStackInSlot(i);
            if (!stack.isEmpty() && itemId(stack).equals(id)) total += stack.getCount();
        }
        return total;
    }

    private static LuaTable buildItemSummary(IItemHandlerModifiable h) {
        LuaTable result = new LuaTable();
        if (h == null) return result;
        java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> displayNames = new java.util.HashMap<>();
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack stack = h.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            String id = itemId(stack);
            int[] data = counts.computeIfAbsent(id, k -> new int[]{0, 0});
            data[0] += stack.getCount();
            data[1]++;
            displayNames.putIfAbsent(id, stack.getHoverName().getString());
        }
        int idx = 1;
        for (var entry : counts.entrySet()) {
            LuaTable item = new LuaTable();
            item.set("name",        LuaValue.valueOf(entry.getKey()));
            item.set("displayName", LuaValue.valueOf(displayNames.get(entry.getKey())));
            item.set("count",       LuaValue.valueOf(entry.getValue()[0]));
            item.set("boxes",       LuaValue.valueOf(entry.getValue()[1]));
            result.set(idx++, item);
        }
        return result;
    }

    private static LuaTable findItems(IItemHandlerModifiable h, String query) {
        LuaTable result = new LuaTable();
        if (h == null || query == null || query.isEmpty()) return result;
        String lower = query.toLowerCase(Locale.ROOT);
        int idx = 1;
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack stack = h.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            String id = itemId(stack);
            String displayName = stack.getHoverName().getString();
            if (id.toLowerCase(Locale.ROOT).contains(lower)
                    || displayName.toLowerCase(Locale.ROOT).contains(lower)) {
                LuaTable info = itemToTable(stack);
                info.set("slot", LuaValue.valueOf(i + 1));
                result.set(idx++, info);
            }
        }
        return result;
    }

    private static void addPositionMethod(LuaTable t, BlockEntity be) {
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
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reflection cache initializer
    // ══════════════════════════════════════════════════════════════════════

    private static boolean ensureCache() {
        if (cacheReady) return clsControllerBE != null;
        cacheReady = true;
        try {
            clsControllerBE = Class.forName("net.fxnt.fxntstorage.controller.StorageControllerEntity");
            clsInterfaceBE  = safeClass("net.fxnt.fxntstorage.controller.StorageInterfaceEntity");

            mCtrlGetItemHandler = safeMethod(clsControllerBE, "getItemHandler");
            fCtrlStorageNetwork = safeField(clsControllerBE, "storageNetwork");

            if (clsInterfaceBE != null) {
                mIfaceGetItemHandler = safeMethod(clsInterfaceBE, "getItemHandler");
                fIfaceController     = safeField(clsInterfaceBE, "controller");
            }

            Class<?> clsNet = safeClass("net.fxnt.fxntstorage.storage_network.StorageNetwork");
            if (clsNet != null) fNetworkBoxes = safeField(clsNet, "boxes");

            Class<?> clsNi = safeClass("net.fxnt.fxntstorage.storage_network.StorageNetwork$StorageNetworkItem");
            if (clsNi != null) {
                fNiBlockPos               = safeField(clsNi, "blockPos");
                fNiSimpleStorageBoxEntity = safeField(clsNi, "simpleStorageBoxEntity");
            }

            Class<?> clsBox = safeClass("net.fxnt.fxntstorage.simple_storage_box.SimpleStorageBoxEntity");
            if (clsBox == null)
                clsBox = safeClass("net.fxnt.fxntstorage.storage_box.SimpleStorageBoxEntity");
            if (clsBox == null && fNiSimpleStorageBoxEntity != null) {
                clsBox = fNiSimpleStorageBoxEntity.getType();
            }
            if (clsBox != null) {
                mBoxGetStoredAmount     = safeMethod(clsBox, "getStoredAmount");
                mBoxGetMaxItemCapacity  = safeMethod(clsBox, "getMaxItemCapacity");
                mBoxGetCapacityUpgrades = safeMethod(clsBox, "getCapacityUpgrades");
                mBoxHasVoidUpgrade      = safeMethod(clsBox, "hasVoidUpgrade");
                mBoxGetFilterItem       = safeMethod(clsBox, "getFilterItem");
                mBoxGetItemHandler      = safeMethod(clsBox, "getItemHandler");
            }

            return true;
        } catch (Exception e) {
            clsControllerBE = null;
            return false;
        }
    }

    private static Class<?> safeClass(String name) {
        try { return Class.forName(name); } catch (Exception e) { return null; }
    }

    private static Method safeMethod(Class<?> cls, String name, Class<?>... params) {
        try { return cls.getMethod(name, params); } catch (Exception e) { return null; }
    }

    private static Field safeField(Class<?> cls, String name) {
        try {
            Field f = cls.getField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception e) { return null; }
    }
}

package com.apocscode.byteblock.computer.storage;

import com.apocscode.byteblock.block.entity.ByteChestBlockEntity;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates item counts from all storage sources visible to a Computer:
 * <ol>
 *   <li>Every {@link ByteChestBlockEntity} reachable via Bluetooth (within BT range)</li>
 *   <li>Every registered {@link IStorageProvider} (e.g. AE2 ME networks adjacent
 *       to a ByteChest)</li>
 * </ol>
 *
 * Called from {@code CraftingCalculatorProgram.tick()} when a scan is requested.
 * Always runs server-side so direct block-entity access is safe.
 */
public class StorageScanner {

    private StorageScanner() {}

    /**
     * Perform a full storage scan visible from {@code computerPos}.
     *
     * @param level       Server-side level.
     * @param computerPos Position of the Computer block running the scan.
     * @return Map of {@code "namespace:path" → total quantity} across all sources.
     */
    public static Map<String, Long> scan(Level level, BlockPos computerPos) {
        Map<String, Long> totals = new HashMap<>();

        // ── 1. ByteChests on the BT network ──────────────────────────────────
        List<BluetoothNetwork.DeviceEntry> chestEntries =
                BluetoothNetwork.getDevicesInRange(level, computerPos, BluetoothNetwork.BLOCK_RANGE)
                        .stream()
                        .filter(d -> d.type() == BluetoothNetwork.DeviceType.BYTE_CHEST)
                        .toList();

        for (BluetoothNetwork.DeviceEntry entry : chestEntries) {
            BlockEntity be = level.getBlockEntity(entry.pos());
            if (!(be instanceof ByteChestBlockEntity chest)) continue;

            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack stack = chest.getItem(i);
                if (stack.isEmpty()) continue;

                Item item = stack.getItem();
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                if (key == null) continue;

                totals.merge(key.toString(), (long) stack.getCount(), Long::sum);
            }

            // ── 2. ModLink providers adjacent to each ByteChest ──────────────
            for (IStorageProvider provider : ModLinkRegistry.getProviders()) {
                try {
                    Map<String, Long> modCounts = provider.getItemCounts(level, entry.pos());
                    for (Map.Entry<String, Long> e : modCounts.entrySet()) {
                        totals.merge(e.getKey(), e.getValue(), Long::sum);
                    }
                } catch (Exception ignored) {
                    // Provider errors should never crash the scanner
                }
            }
        }

        return totals;
    }
}

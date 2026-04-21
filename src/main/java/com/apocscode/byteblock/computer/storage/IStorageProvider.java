package com.apocscode.byteblock.computer.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Map;

/**
 * Implemented by mod-specific storage integrations (AE2, etc.).
 * Registered via {@link ModLinkRegistry}.
 *
 * When the Materials Calculator performs a storage scan, it calls every
 * registered provider and merges their item counts into the total.
 */
public interface IStorageProvider {

    /** The mod ID this provider supports (e.g. "ae2"). */
    String getModId();

    /**
     * Return a map of {@code "namespace:path" → quantity} for all items
     * accessible from the given position (e.g. adjacent to an ME controller).
     *
     * @param level      Server-side level.
     * @param nearPos    Position of the querying ByteChest (or computer).
     * @return Item ID → total count map (empty if none accessible).
     */
    Map<String, Long> getItemCounts(Level level, BlockPos nearPos);
}

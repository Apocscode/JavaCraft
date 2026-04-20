package com.apocscode.byteblock.scanner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LiDAR scan data cache — stores block, entity, and pathfinding data
 * for the scanner block. Persists to NBT with palette compression.
 */
public class WorldScanData {

    /** Non-air block cache: BlockPos.asLong() → registry name */
    private final Map<Long, String> blockCache = new ConcurrentHashMap<>();

    /** Positions with non-empty collision shapes (for pathfinding) */
    private final Set<Long> solidBlocks = ConcurrentHashMap.newKeySet();

    /** Positions containing fluids */
    private final Set<Long> fluidBlocks = ConcurrentHashMap.newKeySet();

    /** Scanned chunk sections: section key → game time when scanned */
    private final Map<Long, Long> scannedSections = new ConcurrentHashMap<>();

    /** Entity snapshots from last entity scan */
    private final List<EntitySnapshot> entitySnapshots =
            Collections.synchronizedList(new ArrayList<>());

    /** Scanner origin position */
    private BlockPos origin = BlockPos.ZERO;

    public record EntitySnapshot(
            String type, String name,
            double x, double y, double z,
            float health, float maxHealth,
            boolean isPlayer, String uuid
    ) {}

    // ── Block Scanning ──────────────────────────────────────────────

    /**
     * Scan a single 16×16×16 section. Returns non-air block count.
     */
    public int scanSection(Level level, SectionPos section) {
        int count = 0;
        int minX = section.minBlockX();
        int minY = section.minBlockY();
        int minZ = section.minBlockZ();

        for (int x = minX; x < minX + 16; x++) {
            for (int y = minY; y < minY + 16; y++) {
                for (int z = minZ; z < minZ + 16; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    long key = pos.asLong();

                    if (state.isAir()) {
                        blockCache.remove(key);
                        solidBlocks.remove(key);
                        fluidBlocks.remove(key);
                    } else {
                        String name = BuiltInRegistries.BLOCK
                                .getKey(state.getBlock()).toString();
                        blockCache.put(key, name);

                        if (!state.getCollisionShape(level, pos).isEmpty()) {
                            solidBlocks.add(key);
                        } else {
                            solidBlocks.remove(key);
                        }

                        if (!state.getFluidState().isEmpty()) {
                            fluidBlocks.add(key);
                        } else {
                            fluidBlocks.remove(key);
                        }
                        count++;
                    }
                }
            }
        }
        scannedSections.put(section.asLong(), level.getGameTime());
        return count;
    }

    /**
     * Scan all entities within radius of a center position.
     */
    public void scanEntities(Level level, BlockPos center, int radius) {
        entitySnapshots.clear();
        AABB area = new AABB(center).inflate(radius);
        List<Entity> entities = level.getEntities((Entity) null, area, e -> true);

        for (Entity entity : entities) {
            boolean isPlayer = entity instanceof Player;
            float health = 0, maxHealth = 0;
            if (entity instanceof LivingEntity living) {
                health = living.getHealth();
                maxHealth = living.getMaxHealth();
            }
            entitySnapshots.add(new EntitySnapshot(
                    entity.getType().toShortString(),
                    entity.getName().getString(),
                    entity.getX(), entity.getY(), entity.getZ(),
                    health, maxHealth,
                    isPlayer,
                    entity.getStringUUID()
            ));
        }
    }

    // ── Queries ─────────────────────────────────────────────────────

    /**
     * Get block at position. Checks live world first, falls back to cache.
     * Returns null if position has never been scanned.
     */
    public String getBlock(Level level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);

        if (level != null && level.hasChunkAt(pos)) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return "minecraft:air";
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        }

        String cached = blockCache.get(pos.asLong());
        if (cached != null) return cached;

        // If section was scanned but block not in cache → it was air
        SectionPos section = SectionPos.of(pos);
        if (scannedSections.containsKey(section.asLong())) return "minecraft:air";

        return null; // unscanned
    }

    /**
     * Check if a position has a non-empty collision shape.
     */
    public boolean isSolid(Level level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (level != null && level.hasChunkAt(pos)) {
            return !level.getBlockState(pos)
                    .getCollisionShape(level, pos).isEmpty();
        }
        return solidBlocks.contains(pos.asLong());
    }

    public boolean isPassable(Level level, int x, int y, int z) {
        return !isSolid(level, x, y, z);
    }

    public boolean isFluid(Level level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (level != null && level.hasChunkAt(pos)) {
            return !level.getBlockState(pos).getFluidState().isEmpty();
        }
        return fluidBlocks.contains(pos.asLong());
    }

    public boolean isScanned(BlockPos pos) {
        return scannedSections.containsKey(SectionPos.of(pos).asLong());
    }

    /**
     * Find nearest block of a given type (searches cache).
     */
    public BlockPos findBlock(String blockName, BlockPos center, int radius) {
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        long radiusSq = (long) radius * radius;

        for (Map.Entry<Long, String> entry : blockCache.entrySet()) {
            if (!entry.getValue().equals(blockName)) continue;
            BlockPos pos = BlockPos.of(entry.getKey());
            double dist = center.distSqr(pos);
            if (dist <= radiusSq && dist < nearestDist) {
                nearestDist = dist;
                nearest = pos;
            }
        }
        return nearest;
    }

    /**
     * Find all blocks of a given type within radius (searches cache).
     */
    public List<BlockPos> findBlocks(String blockName, BlockPos center, int radius) {
        List<BlockPos> results = new ArrayList<>();
        long radiusSq = (long) radius * radius;

        for (Map.Entry<Long, String> entry : blockCache.entrySet()) {
            if (!entry.getValue().equals(blockName)) continue;
            BlockPos pos = BlockPos.of(entry.getKey());
            if (center.distSqr(pos) <= radiusSq) {
                results.add(pos);
            }
        }
        return results;
    }

    /**
     * Get all non-air blocks in a rectangular area.
     * Small areas query live world; large areas use cache.
     */
    public Map<BlockPos, String> getBlocksInArea(Level level, BlockPos from, BlockPos to) {
        Map<BlockPos, String> result = new LinkedHashMap<>();
        int x1 = Math.min(from.getX(), to.getX());
        int y1 = Math.min(from.getY(), to.getY());
        int z1 = Math.min(from.getZ(), to.getZ());
        int x2 = Math.max(from.getX(), to.getX());
        int y2 = Math.max(from.getY(), to.getY());
        int z2 = Math.max(from.getZ(), to.getZ());
        long volume = (long)(x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);

        if (volume <= 32768) {
            // Small area: iterate positions for live accuracy
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        String name = getBlock(level, x, y, z);
                        if (name != null && !name.equals("minecraft:air")) {
                            result.put(new BlockPos(x, y, z), name);
                        }
                    }
                }
            }
        } else {
            // Large area: filter cache entries
            for (Map.Entry<Long, String> entry : blockCache.entrySet()) {
                BlockPos pos = BlockPos.of(entry.getKey());
                if (pos.getX() >= x1 && pos.getX() <= x2 &&
                    pos.getY() >= y1 && pos.getY() <= y2 &&
                    pos.getZ() >= z1 && pos.getZ() <= z2) {
                    result.put(pos, entry.getValue());
                    if (result.size() >= 100_000) break;
                }
            }
        }
        return result;
    }

    public List<EntitySnapshot> getEntities() {
        return List.copyOf(entitySnapshots);
    }

    public List<EntitySnapshot> getPlayers() {
        return entitySnapshots.stream()
                .filter(EntitySnapshot::isPlayer)
                .toList();
    }

    public int getScannedBlockCount() { return blockCache.size(); }
    public int getScannedSectionCount() { return scannedSections.size(); }

    public void setOrigin(BlockPos pos) { this.origin = pos; }
    public BlockPos getOrigin() { return origin; }

    public void clear() {
        blockCache.clear();
        solidBlocks.clear();
        fluidBlocks.clear();
        scannedSections.clear();
        entitySnapshots.clear();
    }

    // ── NBT Persistence (palette-compressed) ────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();

        // Build palette
        Map<String, Integer> paletteMap = new HashMap<>();
        List<String> palette = new ArrayList<>();
        for (String name : blockCache.values()) {
            paletteMap.computeIfAbsent(name, n -> {
                palette.add(n);
                return palette.size() - 1;
            });
        }

        // Save palette
        ListTag paletteTag = new ListTag();
        for (String name : palette) paletteTag.add(StringTag.valueOf(name));
        tag.put("Palette", paletteTag);

        // Save block positions + palette indices
        long[] positions = new long[blockCache.size()];
        int[]  indices   = new int[blockCache.size()];
        int i = 0;
        for (Map.Entry<Long, String> entry : blockCache.entrySet()) {
            positions[i] = entry.getKey();
            indices[i]   = paletteMap.getOrDefault(entry.getValue(), 0);
            i++;
        }
        tag.putLongArray("Positions", positions);
        tag.putIntArray("Indices", indices);

        // Save pathfinding sets
        tag.putLongArray("SolidBlocks",
                solidBlocks.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("FluidBlocks",
                fluidBlocks.stream().mapToLong(Long::longValue).toArray());

        // Save scanned sections
        long[] sectionKeys  = new long[scannedSections.size()];
        long[] sectionTimes = new long[scannedSections.size()];
        int j = 0;
        for (Map.Entry<Long, Long> entry : scannedSections.entrySet()) {
            sectionKeys[j]  = entry.getKey();
            sectionTimes[j] = entry.getValue();
            j++;
        }
        tag.putLongArray("SectionKeys", sectionKeys);
        tag.putLongArray("SectionTimes", sectionTimes);

        return tag;
    }

    public void load(CompoundTag tag) {
        clear();
        if (!tag.contains("Palette")) return;

        // Load palette
        ListTag paletteTag = tag.getList("Palette", Tag.TAG_STRING);
        String[] palette = new String[paletteTag.size()];
        for (int i = 0; i < paletteTag.size(); i++) {
            palette[i] = paletteTag.getString(i);
        }

        // Load blocks
        long[] positions = tag.getLongArray("Positions");
        int[]  indices   = tag.getIntArray("Indices");
        for (int i = 0; i < positions.length && i < indices.length; i++) {
            if (indices[i] >= 0 && indices[i] < palette.length) {
                blockCache.put(positions[i], palette[indices[i]]);
            }
        }

        // Load pathfinding sets
        for (long pos : tag.getLongArray("SolidBlocks")) solidBlocks.add(pos);
        for (long pos : tag.getLongArray("FluidBlocks")) fluidBlocks.add(pos);

        // Load scanned sections
        long[] sectionKeys  = tag.getLongArray("SectionKeys");
        long[] sectionTimes = tag.getLongArray("SectionTimes");
        for (int i = 0; i < sectionKeys.length && i < sectionTimes.length; i++) {
            scannedSections.put(sectionKeys[i], sectionTimes[i]);
        }
    }
}

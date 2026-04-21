package com.apocscode.byteblock.computer.peripheral;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import org.luaj.vm2.LuaTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Central registry for ByteBlock peripheral adapters.
 *
 * <p>Adapters are registered once during mod init via {@link #registerDefaults()}.
 * At runtime, {@link #findBySide} locates the block entity adjacent to the
 * computer on a given side and returns the matching adapter + BE pair.</p>
 */
public class PeripheralRegistry {

    private static final List<IPeripheralAdapter> ADAPTERS = new ArrayList<>();
    private static boolean initialized = false;

    /** Register a peripheral adapter. */
    public static void register(IPeripheralAdapter adapter) {
        ADAPTERS.add(adapter);
    }

    /**
     * Called once during mod setup to register built-in adapters.
     * Only registers adapters for mods that are currently loaded.
     */
    public static void registerDefaults() {
        if (initialized) return;
        initialized = true;

        if (ModList.get().isLoaded("logiclink")) {
            register(new LogicLinkPeripheralAdapter());
        }
    }

    /**
     * Result of a peripheral lookup — the matched adapter and block entity.
     */
    public record AdapterResult(IPeripheralAdapter adapter, BlockEntity be) {
        public String getType()      { return adapter.getType(be); }
        public LuaTable buildTable() { return adapter.buildTable(be); }
    }

    /**
     * Find a peripheral adapter for the block entity on the given side of
     * the computer. Returns null if no adapter matches.
     */
    public static AdapterResult find(Level level, BlockPos computerPos, Direction dir) {
        BlockPos adjPos = computerPos.relative(dir);
        BlockEntity be = level.getBlockEntity(adjPos);
        if (be == null) return null;
        for (IPeripheralAdapter adapter : ADAPTERS) {
            if (adapter.canAdapt(be)) return new AdapterResult(adapter, be);
        }
        return null;
    }

    /** Overload that accepts a CC/Lua-style side string. */
    public static AdapterResult findBySide(Level level, BlockPos computerPos, String side) {
        Direction dir = sideToDirection(side);
        if (dir == null) return null;
        return find(level, computerPos, dir);
    }

    /** Find the first peripheral of the given type on any of the 6 sides. */
    public static AdapterResult findByType(Level level, BlockPos computerPos, String type) {
        for (Direction dir : Direction.values()) {
            AdapterResult result = find(level, computerPos, dir);
            if (result != null && result.getType().equals(type)) return result;
        }
        return null;
    }

    /** Convert a side name string to a {@link Direction}. */
    public static Direction sideToDirection(String side) {
        return switch (side.toLowerCase()) {
            case "top",    "up"    -> Direction.UP;
            case "bottom", "down"  -> Direction.DOWN;
            case "north"           -> Direction.NORTH;
            case "south"           -> Direction.SOUTH;
            case "west"            -> Direction.WEST;
            case "east"            -> Direction.EAST;
            default                -> null;
        };
    }

    /** Convert a {@link Direction} to a side name string. */
    public static String directionToSide(Direction dir) {
        return switch (dir) {
            case UP    -> "top";
            case DOWN  -> "bottom";
            case NORTH -> "north";
            case SOUTH -> "south";
            case WEST  -> "west";
            case EAST  -> "east";
        };
    }
}

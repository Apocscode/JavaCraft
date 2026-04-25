package com.apocscode.byteblock.computer.peripheral;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.luaj.vm2.LuaTable;

/**
 * Adapter interface for wrapping adjacent block entities as Lua peripherals
 * inside ByteBlock computers.
 *
 * <p>Implementations should use reflection so ByteBlock compiles without the
 * target mod on the classpath. Register via {@link PeripheralRegistry}.</p>
 */
public interface IPeripheralAdapter {

    /** Mod ID this adapter targets (used to gate registration). */
    String getModId();

    /** Returns the peripheral type string for this block entity. */
    String getType(BlockEntity be);

    /** Returns true if this adapter can handle the given block entity. */
    boolean canAdapt(BlockEntity be);

    /**
     * Builds and returns a Lua method table for the given block entity.
     * Each key is a method name; each value is a callable LuaFunction.
     */
    LuaTable buildTable(BlockEntity be);

    /**
     * Optional overload that gives the adapter access to the calling computer's
     * {@link com.apocscode.byteblock.computer.JavaOS} instance (e.g. for filesystem
     * writes from peripheral methods like {@code monitor.savePNG}).
     * Default delegates to {@link #buildTable(BlockEntity)}.
     */
    default LuaTable buildTable(BlockEntity be, com.apocscode.byteblock.computer.JavaOS callingOs) {
        return buildTable(be);
    }
}

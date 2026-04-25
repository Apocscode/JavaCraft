package com.apocscode.byteblock.computer.peripheral;

import com.apocscode.byteblock.block.entity.MonitorBlockEntity;

import net.minecraft.world.level.block.entity.BlockEntity;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * Exposes {@link MonitorBlockEntity} as a {@code peripheral.find("monitor")} target.
 *
 * <p>API mirrors CC:Tweaked's {@code monitor} peripheral surface:
 * {@code write, clear, clearLine, getCursorPos, setCursorPos, getSize,
 * setTextColor, setBackgroundColor, getTextColor, getBackgroundColor,
 * setTextScale, getTextScale, scroll, isColor, redraw, getTouchPos}.</p>
 *
 * <p>The first call to any method puts the monitor into {@code "text"} display
 * mode, replacing the mirror feed with the monitor's own terminal buffer.</p>
 */
public class MonitorPeripheralAdapter implements IPeripheralAdapter {

    @Override public String getModId() { return "byteblock"; }

    @Override
    public boolean canAdapt(BlockEntity be) {
        return be instanceof MonitorBlockEntity;
    }

    @Override
    public String getType(BlockEntity be) { return "monitor"; }

    @Override
    public LuaTable buildTable(BlockEntity be) {
        final MonitorBlockEntity raw = (MonitorBlockEntity) be;
        final MonitorBlockEntity origin = raw.getOriginEntity() != null ? raw.getOriginEntity() : raw;

        // Switch to text mode automatically on first peripheral access
        if (!"text".equals(origin.getDisplayMode())) {
            origin.setDisplayMode("text");
        }

        LuaTable t = new LuaTable();

        t.set("write", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                origin.termWrite(v.tojstring());
                origin.termFlush();
                return LuaValue.NIL;
            }
        });

        t.set("clear", new ZeroArgFunction() {
            @Override public LuaValue call() {
                origin.termClear();
                origin.termFlush();
                return LuaValue.NIL;
            }
        });

        t.set("clearLine", new ZeroArgFunction() {
            @Override public LuaValue call() {
                origin.termClearLine();
                origin.termFlush();
                return LuaValue.NIL;
            }
        });

        t.set("getCursorPos", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                LuaValue x = LuaValue.valueOf(origin.termGetCursorX() + 1);
                LuaValue y = LuaValue.valueOf(origin.termGetCursorY() + 1);
                return LuaValue.varargsOf(new LuaValue[]{x, y});
            }
        });

        t.set("setCursorPos", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue x, LuaValue y) {
                origin.termSetCursorPos(x.checkint() - 1, y.checkint() - 1);
                return LuaValue.NIL;
            }
        });

        t.set("getSize", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                LuaValue w = LuaValue.valueOf(MonitorBlockEntity.TEXT_COLS);
                LuaValue h = LuaValue.valueOf(MonitorBlockEntity.TEXT_ROWS);
                return LuaValue.varargsOf(new LuaValue[]{w, h});
            }
        });

        t.set("setTextColor", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                origin.termSetTextColor(paletteFromLua(v));
                return LuaValue.NIL;
            }
        });
        t.set("setTextColour", t.get("setTextColor"));

        t.set("setBackgroundColor", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                origin.termSetBackgroundColor(paletteFromLua(v));
                return LuaValue.NIL;
            }
        });
        t.set("setBackgroundColour", t.get("setBackgroundColor"));

        t.set("getTextColor", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(1 << origin.termGetTextColor());
            }
        });
        t.set("getTextColour", t.get("getTextColor"));

        t.set("getBackgroundColor", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(1 << origin.termGetBackgroundColor());
            }
        });
        t.set("getBackgroundColour", t.get("getBackgroundColor"));

        t.set("setTextScale", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                origin.termSetTextScale(v.todouble());
                origin.termFlush();
                return LuaValue.NIL;
            }
        });

        t.set("getTextScale", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(origin.getTextScale()); }
        });

        t.set("scroll", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                origin.termScroll(v.checkint());
                origin.termFlush();
                return LuaValue.NIL;
            }
        });

        t.set("isColor", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.TRUE; }
        });
        t.set("isColour", t.get("isColor"));

        t.set("redraw", new ZeroArgFunction() {
            @Override public LuaValue call() {
                origin.termFlush();
                return LuaValue.NIL;
            }
        });

        // getTouchPos() -> x, y  (or nil if no touch yet)
        t.set("getTouchPos", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                if (origin.getLastTouchX() < 0) return LuaValue.NIL;
                return LuaValue.varargsOf(new LuaValue[]{
                    LuaValue.valueOf(origin.getLastTouchX() + 1),
                    LuaValue.valueOf(origin.getLastTouchY() + 1)
                });
            }
        });

        // Convenience: setMode("mirror"|"text")  — return monitor to mirror mode
        t.set("setMode", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                origin.setDisplayMode(v.tojstring());
                return LuaValue.NIL;
            }
        });

        return t;
    }

    /** Accept a CC-style bitmask color (1, 2, 4, ...) or a 0-15 palette index. */
    private static int paletteFromLua(LuaValue v) {
        int n = v.checkint();
        if (n <= 0) return 0;
        if (n < 16) return n;          // already a palette index
        // bitmask: find lowest set bit
        for (int i = 0; i < 16; i++) {
            if ((n & (1 << i)) != 0) return i;
        }
        return 0;
    }
}

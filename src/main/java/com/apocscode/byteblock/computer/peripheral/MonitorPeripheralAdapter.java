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
        return buildTable(be, null);
    }

    @Override
    public LuaTable buildTable(BlockEntity be, com.apocscode.byteblock.computer.JavaOS callingOs) {
        final MonitorBlockEntity raw = (MonitorBlockEntity) be;
        final MonitorBlockEntity origin = raw.getOriginEntity() != null ? raw.getOriginEntity() : raw;

        // Switch to text mode automatically on first peripheral access (only if currently mirroring)
        if ("mirror".equals(origin.getDisplayMode())) {
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

        // monitor.blit(text, fgHex, bgHex) — CC-style colored write at the cursor.
        t.set("blit", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                origin.termBlit(a.checkjstring(1), a.checkjstring(2), a.checkjstring(3));
                origin.termFlush();
                return LuaValue.NIL;
            }
        });

        // ── Per-monitor palette (CC:Tweaked-compatible) ──────────────────
        // setPaletteColor(color, hex)   or   setPaletteColor(color, r, g, b)
        // 'color' is the CC bitmask (1 = white, 32768 = black). Idx = log2(color) & 0xF.
        VarArgFunction setPal = new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int color = a.checkint(1);
                int idx = Integer.numberOfTrailingZeros(Math.max(1, color)) & 0xF;
                int rgb;
                if (a.narg() >= 4) {
                    int r = (int) Math.round(Math.max(0.0, Math.min(1.0, a.checkdouble(2))) * 255.0);
                    int g = (int) Math.round(Math.max(0.0, Math.min(1.0, a.checkdouble(3))) * 255.0);
                    int b = (int) Math.round(Math.max(0.0, Math.min(1.0, a.checkdouble(4))) * 255.0);
                    rgb = (r << 16) | (g << 8) | b;
                } else {
                    rgb = a.checkint(2) & 0xFFFFFF;
                }
                origin.setPaletteColor(idx, rgb);
                return LuaValue.NIL;
            }
        };
        t.set("setPaletteColor", setPal);
        t.set("setPaletteColour", setPal);

        // getPaletteColor(color) -> r, g, b (each 0..1)
        VarArgFunction getPal = new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int color = a.checkint(1);
                int idx = Integer.numberOfTrailingZeros(Math.max(1, color)) & 0xF;
                int argb = origin.getPaletteARGB(idx);
                return LuaValue.varargsOf(new LuaValue[]{
                    LuaValue.valueOf(((argb >> 16) & 0xFF) / 255.0),
                    LuaValue.valueOf(((argb >>  8) & 0xFF) / 255.0),
                    LuaValue.valueOf(( argb        & 0xFF) / 255.0)
                });
            }
        };
        t.set("getPaletteColor", getPal);
        t.set("getPaletteColour", getPal);
        t.set("nativePaletteColor", getPal);
        t.set("nativePaletteColour", getPal);

        t.set("resetPalette", new ZeroArgFunction() {
            @Override public LuaValue call() {
                origin.resetPalette();
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

        // Convenience: setMode("mirror"|"text"|"graphics")
        t.set("setMode", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                origin.setDisplayMode(v.tojstring());
                return LuaValue.NIL;
            }
        });

        // ── Graphics-mode (160×100, 16-color palette) ────────────────────
        t.set("getPixelSize", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                return LuaValue.varargsOf(new LuaValue[]{
                    LuaValue.valueOf(MonitorBlockEntity.GFX_W),
                    LuaValue.valueOf(MonitorBlockEntity.GFX_H)
                });
            }
        });
        t.set("setPixel", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                origin.gfxSetPixel(a.checkint(1) - 1, a.checkint(2) - 1, paletteFromLua(a.arg(3)));
                return LuaValue.NIL;
            }
        });
        t.set("getPixel", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int idx = origin.gfxGetPixel(a.checkint(1) - 1, a.checkint(2) - 1);
                return LuaValue.valueOf(1 << idx);
            }
        });
        t.set("fillRect", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                origin.gfxFillRect(a.checkint(1) - 1, a.checkint(2) - 1,
                                   a.checkint(3),     a.checkint(4),
                                   paletteFromLua(a.arg(5)));
                return LuaValue.NIL;
            }
        });
        t.set("clearPixels", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                origin.gfxClear(a.narg() >= 1 ? paletteFromLua(a.arg(1))
                                              : origin.termGetBackgroundColor());
                return LuaValue.NIL;
            }
        });
        t.set("redrawPixels", new ZeroArgFunction() {
            @Override public LuaValue call() { origin.gfxFlush(); return LuaValue.NIL; }
        });

        // ── Computer label exposure ──────────────────────────────────────
        t.set("getComputerLabel", new ZeroArgFunction() {
            @Override public LuaValue call() {
                String lbl = origin.getLastKnownComputerLabel();
                return (lbl == null || lbl.isEmpty()) ? LuaValue.NIL : LuaValue.valueOf(lbl);
            }
        });

        // ── savePNG(path) — render the current visible buffer to a PNG ───
        if (callingOs != null) {
            final com.apocscode.byteblock.computer.JavaOS fs = callingOs;
            t.set("savePNG", new OneArgFunction() {
                @Override public LuaValue call(LuaValue pathArg) {
                    String path = pathArg.checkjstring();
                    try {
                        java.awt.image.BufferedImage img = renderMonitorToImage(origin);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        javax.imageio.ImageIO.write(img, "png", baos);
                        fs.getFileSystem().writeBytes(path, baos.toByteArray());
                        return LuaValue.TRUE;
                    } catch (Exception e) {
                        return LuaValue.varargsOf(
                            LuaValue.FALSE, LuaValue.valueOf(e.getMessage())).arg1();
                    }
                }
            });
        }

        return t;
    }

    /** Snapshot the monitor's current visible buffer (text or graphics) as a BufferedImage. */
    private static java.awt.image.BufferedImage renderMonitorToImage(MonitorBlockEntity origin) {
        int[] palette = com.apocscode.byteblock.computer.TerminalBuffer.PALETTE;
        if ("graphics".equals(origin.getDisplayMode())) {
            int w = MonitorBlockEntity.GFX_W, h = MonitorBlockEntity.GFX_H;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = origin.gfxGetPixel(x, y);
                    int c = palette[idx & 0xF];
                    img.setRGB(x, y, 0xFF000000 | (c & 0xFFFFFF));
                }
            }
            return img;
        }
        // Text/mirror fallback: render text buffer at native font size 80*8 × 25*16 = 640×400
        int cols = MonitorBlockEntity.TEXT_COLS, rows = MonitorBlockEntity.TEXT_ROWS;
        int cellW = 8, cellH = 16;
        int w = cols * cellW, h = rows * cellH;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
            w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        com.apocscode.byteblock.computer.PixelBuffer pb =
            new com.apocscode.byteblock.computer.PixelBuffer(w, h);
        char[] chars = origin.getTextChars();
        byte[] fg = origin.getTextFg(), bg = origin.getTextBg();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int o = y * cols + x;
                pb.fillRect(x * cellW, y * cellH, cellW, cellH, palette[bg[o] & 0xF]);
                char c = chars[o];
                if (c != ' ' && c != 0) pb.drawChar(x * cellW, y * cellH, c, palette[fg[o] & 0xF]);
            }
        }
        int[] argb = pb.getPixels();
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
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

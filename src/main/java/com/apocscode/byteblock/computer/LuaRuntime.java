package com.apocscode.byteblock.computer;

import com.apocscode.byteblock.block.entity.ScannerBlockEntity;
import com.apocscode.byteblock.computer.RedstoneLib;
import com.apocscode.byteblock.network.BluetoothNetwork;
import com.apocscode.byteblock.scanner.PathfindingEngine;
import com.apocscode.byteblock.scanner.WorldScanData;

import org.luaj.vm2.*;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Lua 5.2 scripting runtime for ByteBlock computers.
 * Wraps LuaJ and exposes CC-style APIs: term, fs, os, shell, colors, peripheral.
 */
public class LuaRuntime {

    private final JavaOS os;
    private final Globals globals;
    private final ArrayBlockingQueue<String> outputQueue = new ArrayBlockingQueue<>(512);

    // Staging state for the glasses.* API (per-runtime widget list)
    private final java.util.List<GlassesHudAPI.Widget> glassesWidgets = new java.util.ArrayList<>();
    private int glassesChannel = 1;

    // --- Coroutine event-loop state (CC-style) ---
    private LuaThread programThread = null;
    private boolean programYielded = false;
    private final java.util.ArrayDeque<Varargs> programEventQueue = new java.util.ArrayDeque<>();

    // --- Multishell task registry ---
    static final class MsTask {
        final int id;
        String title;
        LuaThread thread;
        boolean yielded;
        boolean alive = true;
        final java.util.ArrayDeque<Varargs> queue = new java.util.ArrayDeque<>();
        MsTask(int id, String title, LuaThread thread) {
            this.id = id; this.title = title; this.thread = thread;
        }
    }
    private final java.util.List<MsTask> msTasks = new java.util.ArrayList<>();
    private int msNextId = 1;
    private int msFocusId = 0;


    // Sandbox instruction limit per resume (~500K ops)
    private static final int INSTRUCTION_LIMIT = 500_000;

    public LuaRuntime(JavaOS os) {
        this.os = os;
        this.globals = createSandboxedGlobals();
        installAPIs();
    }

    private Globals createSandboxedGlobals() {
        Globals g = new Globals();
        g.load(new JseBaseLib());         // print, tostring, tonumber, etc.
        g.load(new PackageLib());         // require/module
        g.load(new Bit32Lib());           // bit32
        g.load(new TableLib());           // table.*
        g.load(new org.luaj.vm2.lib.CoroutineLib()); // coroutine.create/resume/yield/wrap/status
        g.load(new StringLib());          // string.*
        g.load(new JseMathLib());        // math.*
        LoadState.install(g);
        LuaC.install(g);

        // Redirect print to our output queue
        g.set("print", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= args.narg(); i++) {
                    if (i > 1) sb.append('\t');
                    sb.append(args.arg(i).tojstring());
                }
                pushOutput(sb.toString());
                return NONE;
            }
        });

        // Redirect write (no newline)
        g.set("write", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                pushOutput(arg.tojstring());
                return NONE;
            }
        });

        // Remove dangerous functions
        g.set("dofile", LuaValue.NIL);
        g.set("loadfile", LuaValue.NIL);
        g.set("io", LuaValue.NIL);
        g.set("debug", LuaValue.NIL);
        g.set("os", LuaValue.NIL); // We replace with our own

        // type() function
        g.set("type", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                return LuaValue.valueOf(arg.typename());
            }
        });

        // tostring
        g.set("tostring", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                return LuaValue.valueOf(arg.tojstring());
            }
        });

        // tonumber
        g.set("tonumber", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                if (arg.isnumber()) return arg.tonumber();
                try {
                    return LuaValue.valueOf(Double.parseDouble(arg.tojstring()));
                } catch (NumberFormatException e) {
                    return LuaValue.NIL;
                }
            }
        });

        // sleep (CC-style global)
        g.set("sleep", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                double secs = arg.optdouble(1.0);
                int id = os.startTimer(secs);
                while (true) {
                    Varargs evt = globals.running.state.lua_yield(LuaValue.NONE);
                    LuaValue name = evt.arg1();
                    if (name.isstring() && "timer".equals(name.tojstring())
                            && evt.arg(2).toint() == id) {
                        return NONE;
                    }
                    if (name.isstring() && "terminate".equals(name.tojstring())) {
                        throw new LuaError("Terminated");
                    }
                }
            }
        });

        // error
        g.set("error", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                throw new LuaError(args.optjstring(1, "error"));
            }
        });

        // pcall
        g.set("pcall", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaValue func = args.arg1();
                try {
                    Varargs result = func.invoke(args.subargs(2));
                    return LuaValue.varargsOf(LuaValue.TRUE, result);
                } catch (LuaError e) {
                    return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(e.getMessage()));
                }
            }
        });

        // pairs / ipairs
        g.set("pairs", new PairsFunction());
        g.set("ipairs", new IPairsFunction());

        // select
        g.set("select", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaValue idx = args.arg1();
                if (idx.isstring() && idx.tojstring().equals("#")) {
                    return LuaValue.valueOf(args.narg() - 1);
                }
                int n = idx.checkint();
                if (n < 0) n = args.narg() + n;
                if (n < 1) n = 1;
                return args.subargs(n + 1);
            }
        });

        return g;
    }

    // --- CC-style API Tables ---

    private void installAPIs() {
        installTermAPI();
        installFsAPI();
        installOsAPI();
        installColorsAPI();
        installShellAPI();
        installTextUtilsAPI();
        installGpsAPI();
        installBluetoothAPI();
        installRednetAPI();
        installRedstoneAPI();
        installRelayAPI();
        installButtonsAPI();
        installRobotAPI();
        installDroneAPI();
        installScannerAPI();
        installPeripheralAPI();
        installGlassesAPI();
        installCCExtensions();
    }

    private void installTermAPI() {
        LuaTable term = new LuaTable();
        TerminalBuffer tb = os.getTerminal();

        term.set("write", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                pushOutput(arg.tojstring());
                return NONE;
            }
        });

        term.set("setCursorPos", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue x, LuaValue y) {
                tb.setCursorPos(x.checkint() - 1, y.checkint() - 1);
                return NONE;
            }
        });

        term.set("getCursorPos", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.varargsOf(
                    LuaValue.valueOf(tb.getCursorX() + 1),
                    LuaValue.valueOf(tb.getCursorY() + 1));
            }
        });

        term.set("clear", new ZeroArgFunction() {
            @Override public LuaValue call() { tb.clear(); return NONE; }
        });

        term.set("clearLine", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                tb.hLine(0, TerminalBuffer.WIDTH - 1, tb.getCursorY(), ' ');
                return NONE;
            }
        });

        term.set("setTextColor", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                tb.setTextColor(ccColorToIndex(arg.checkint()));
                return NONE;
            }
        });
        term.set("setTextColour", term.get("setTextColor"));

        term.set("setBackgroundColor", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                tb.setBackgroundColor(ccColorToIndex(arg.checkint()));
                return NONE;
            }
        });
        term.set("setBackgroundColour", term.get("setBackgroundColor"));

        term.set("getTextColor", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(indexToCcColor(tb.getCurrentFg())); }
        });
        term.set("getTextColour", term.get("getTextColor"));

        term.set("getBackgroundColor", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(indexToCcColor(tb.getCurrentBg())); }
        });
        term.set("getBackgroundColour", term.get("getBackgroundColor"));

        term.set("getSize", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.varargsOf(
                    LuaValue.valueOf(TerminalBuffer.WIDTH),
                    LuaValue.valueOf(TerminalBuffer.HEIGHT));
            }
        });

        term.set("scroll", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                int n = arg.checkint();
                if (n > 0) for (int i = 0; i < n; i++) tb.scroll();
                else if (n < 0) for (int i = 0; i < -n; i++) tb.scrollDown();
                return NONE;
            }
        });

        term.set("setCursorBlink", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                tb.setCursorBlink(arg.toboolean());
                return NONE;
            }
        });

        term.set("isColor", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.TRUE; }
        });
        term.set("isColour", term.get("isColor"));

        // term.blit(text, fgHex, bgHex) — write text with per-char fg/bg colors.
        term.set("blit", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                tb.blit(a.checkjstring(1), a.checkjstring(2), a.checkjstring(3));
                return NONE;
            }
        });

        globals.set("term", term);
    }

    private void installFsAPI() {
        LuaTable fs = new LuaTable();
        VirtualFileSystem vfs = os.getFileSystem();

        fs.set("list", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue path) {
                List<String> entries = vfs.list(path.checkjstring());
                if (entries == null) return LuaValue.NIL;
                LuaTable t = new LuaTable();
                for (int i = 0; i < entries.size(); i++) {
                    String entry = entries.get(i);
                    // Strip trailing / for CC compatibility
                    if (entry.endsWith("/")) entry = entry.substring(0, entry.length() - 1);
                    t.set(i + 1, LuaValue.valueOf(entry));
                }
                return t;
            }
        });

        fs.set("exists", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { return LuaValue.valueOf(vfs.exists(p.checkjstring())); }
        });

        fs.set("isDir", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { return LuaValue.valueOf(vfs.isDirectory(p.checkjstring())); }
        });

        fs.set("makeDir", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { vfs.mkdir(p.checkjstring()); return NONE; }
        });

        fs.set("delete", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { vfs.delete(p.checkjstring()); return NONE; }
        });

        fs.set("copy", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue s, LuaValue d) {
                return LuaValue.valueOf(vfs.copy(s.checkjstring(), d.checkjstring()));
            }
        });

        fs.set("move", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue s, LuaValue d) {
                return LuaValue.valueOf(vfs.move(s.checkjstring(), d.checkjstring()));
            }
        });

        fs.set("getSize", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { return LuaValue.valueOf(vfs.getSize(p.checkjstring())); }
        });

        fs.set("getName", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue p) {
                String path = p.checkjstring();
                int idx = path.lastIndexOf('/');
                return LuaValue.valueOf(idx >= 0 ? path.substring(idx + 1) : path);
            }
        });

        fs.set("getDir", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue p) {
                String path = p.checkjstring();
                int idx = path.lastIndexOf('/');
                return LuaValue.valueOf(idx > 0 ? path.substring(0, idx) : "/");
            }
        });

        // fs.open — CC-style file handle
        fs.set("open", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue pathVal, LuaValue modeVal) {
                String path = pathVal.checkjstring();
                String mode = modeVal.checkjstring();
                if (mode.equals("r") || mode.equals("rb")) {
                    String content = vfs.readFile(path);
                    if (content == null) return LuaValue.NIL;
                    LuaTable handle = new LuaTable();
                    final int[] pos = {0};
                    handle.set("readAll", new ZeroArgFunction() {
                        @Override public LuaValue call() { pos[0] = content.length(); return LuaValue.valueOf(content); }
                    });
                    handle.set("readLine", new ZeroArgFunction() {
                        @Override
                        public LuaValue call() {
                            if (pos[0] >= content.length()) return LuaValue.NIL;
                            int nl = content.indexOf('\n', pos[0]);
                            if (nl < 0) nl = content.length();
                            String line = content.substring(pos[0], nl);
                            pos[0] = nl + 1;
                            return LuaValue.valueOf(line);
                        }
                    });
                    handle.set("close", new ZeroArgFunction() {
                        @Override public LuaValue call() { return NONE; }
                    });
                    return handle;
                } else if (mode.equals("w") || mode.equals("wb") || mode.equals("a") || mode.equals("ab")) {
                    final StringBuilder buf = new StringBuilder();
                    if (mode.startsWith("a")) {
                        String existing = vfs.readFile(path);
                        if (existing != null) buf.append(existing);
                    }
                    LuaTable handle = new LuaTable();
                    handle.set("write", new OneArgFunction() {
                        @Override public LuaValue call(LuaValue v) { buf.append(v.tojstring()); return NONE; }
                    });
                    handle.set("writeLine", new OneArgFunction() {
                        @Override public LuaValue call(LuaValue v) { buf.append(v.tojstring()).append('\n'); return NONE; }
                    });
                    handle.set("close", new ZeroArgFunction() {
                        @Override public LuaValue call() { vfs.writeFile(path, buf.toString()); return NONE; }
                    });
                    handle.set("flush", new ZeroArgFunction() {
                        @Override public LuaValue call() { vfs.writeFile(path, buf.toString()); return NONE; }
                    });
                    return handle;
                }
                return LuaValue.NIL;
            }
        });

        globals.set("fs", fs);
    }

    private void installOsAPI() {
        LuaTable osTable = new LuaTable();

        osTable.set("clock", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(os.getTickCount() / 20.0); }
        });

        osTable.set("time", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(os.getTickCount() / 20.0); }
        });

        osTable.set("startTimer", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                return LuaValue.valueOf(os.startTimer(arg.checkdouble()));
            }
        });

        osTable.set("getComputerID", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(os.getComputerId().hashCode() & 0x7FFFFFFF); }
        });

        osTable.set("getComputerLabel", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(os.getLabel()); }
        });

        osTable.set("setComputerLabel", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) { os.setLabel(v.checkjstring()); return NONE; }
        });

        osTable.set("shutdown", new ZeroArgFunction() {
            @Override public LuaValue call() { os.shutdown(); return NONE; }
        });

        osTable.set("reboot", new ZeroArgFunction() {
            @Override public LuaValue call() { os.reboot(); return NONE; }
        });

        osTable.set("sleep", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                // Yielding sleep: start a timer and pullEvent until it matches.
                double secs = arg.optdouble(1.0);
                int id = os.startTimer(secs);
                while (true) {
                    Varargs evt = globals.running.state.lua_yield(LuaValue.NONE);
                    LuaValue name = evt.arg1();
                    if (name.isstring() && "timer".equals(name.tojstring())
                            && evt.arg(2).toint() == id) {
                        return NONE;
                    }
                    if (name.isstring() && "terminate".equals(name.tojstring())) {
                        throw new LuaError("Terminated");
                    }
                }
            }
        });

        // os.pullEvent(filter?) — yields until a matching event arrives.
        // "terminate" always passes through (CC semantics).
        osTable.set("pullEvent", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) {
                String filter = a.isnoneornil(1) ? null : a.checkjstring(1);
                while (true) {
                    Varargs evt = globals.running.state.lua_yield(LuaValue.NONE);
                    String n = evt.arg1().isstring() ? evt.arg1().tojstring() : "";
                    if ("terminate".equals(n)) {
                        throw new LuaError("Terminated");
                    }
                    if (filter == null || filter.equals(n)) {
                        return evt;
                    }
                }
            }
        });

        // os.pullEventRaw(filter?) — like pullEvent but doesn't throw on "terminate".
        osTable.set("pullEventRaw", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) {
                String filter = a.isnoneornil(1) ? null : a.checkjstring(1);
                while (true) {
                    Varargs evt = globals.running.state.lua_yield(LuaValue.NONE);
                    String n = evt.arg1().isstring() ? evt.arg1().tojstring() : "";
                    if (filter == null || filter.equals(n)) {
                        return evt;
                    }
                }
            }
        });

        // os.queueEvent(name, ...) — push an event onto the program queue.
        osTable.set("queueEvent", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) {
                int n = a.narg();
                if (n < 1) return NONE;
                LuaValue[] vals = new LuaValue[n];
                for (int i = 0; i < n; i++) vals[i] = a.arg(i + 1);
                programEventQueue.offer(LuaValue.varargsOf(vals));
                return NONE;
            }
        });

        osTable.set("version", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf("ByteBlock 1.0 (Lua 5.2)"); }
        });

        // os.computerLabel() — CC alias for getComputerLabel().
        osTable.set("computerLabel", new ZeroArgFunction() {
            @Override public LuaValue call() {
                String l = os.getLabel();
                return l == null ? LuaValue.NIL : LuaValue.valueOf(l);
            }
        });

        // os.loadAPI(path) — load a Lua file in its own env and expose the
        // resulting global table under the file's basename. CC API loader.
        osTable.set("loadAPI", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                String path = v.checkjstring();
                String src = os.getFileSystem().readFile(path);
                if (src == null) return LuaValue.FALSE;
                // Derive API name from the file's basename, stripping extension.
                int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                String base = slash >= 0 ? path.substring(slash + 1) : path;
                int dot = base.lastIndexOf('.');
                String apiName = dot > 0 ? base.substring(0, dot) : base;
                // Run in an env that inherits globals; new globals defined go to
                // a private table, which is exposed under apiName.
                LuaTable apiEnv = new LuaTable();
                LuaTable mt = new LuaTable();
                mt.set("__index", globals);
                apiEnv.setmetatable(mt);
                try {
                    LuaValue chunk = globals.load(src, "@" + path, apiEnv);
                    chunk.call();
                } catch (LuaError e) { return LuaValue.FALSE; }
                // Strip the inherited metatable so the API table only contains
                // its own definitions, and publish it on globals.
                apiEnv.setmetatable(null);
                globals.set(apiName, apiEnv);
                return LuaValue.TRUE;
            }
        });

        // os.unloadAPI(name) — remove a previously loaded API from globals.
        osTable.set("unloadAPI", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                String name = v.checkjstring();
                if ("os".equals(name)) return NONE;  // CC protects os
                globals.set(name, LuaValue.NIL);
                return NONE;
            }
        });

        globals.set("os", osTable);
    }

    private void installColorsAPI() {
        LuaTable colors = new LuaTable();
        colors.set("white",     LuaValue.valueOf(1));
        colors.set("orange",    LuaValue.valueOf(2));
        colors.set("magenta",   LuaValue.valueOf(4));
        colors.set("lightBlue", LuaValue.valueOf(8));
        colors.set("yellow",    LuaValue.valueOf(16));
        colors.set("lime",      LuaValue.valueOf(32));
        colors.set("pink",      LuaValue.valueOf(64));
        colors.set("gray",      LuaValue.valueOf(128));
        colors.set("lightGray", LuaValue.valueOf(256));
        colors.set("cyan",      LuaValue.valueOf(512));
        colors.set("purple",    LuaValue.valueOf(1024));
        colors.set("blue",      LuaValue.valueOf(2048));
        colors.set("brown",     LuaValue.valueOf(4096));
        colors.set("green",     LuaValue.valueOf(8192));
        colors.set("red",       LuaValue.valueOf(16384));
        colors.set("black",     LuaValue.valueOf(32768));

        // colors.combine(c1, c2, ...) — bitwise OR of color bitmasks.
        colors.set("combine", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int out = 0;
                for (int i = 1; i <= a.narg(); i++) out |= a.checkint(i);
                return LuaValue.valueOf(out);
            }
        });
        // colors.subtract(set, c1, c2, ...) — clears the listed bits from set.
        colors.set("subtract", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int set = a.checkint(1);
                for (int i = 2; i <= a.narg(); i++) set &= ~a.checkint(i);
                return LuaValue.valueOf(set);
            }
        });
        // colors.test(set, color) — true iff every bit in `color` is set in `set`.
        colors.set("test", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue set, LuaValue color) {
                int s = set.checkint(); int c = color.checkint();
                return LuaValue.valueOf((s & c) == c);
            }
        });
        // colors.packRGB(r, g, b)  — 0..1 floats → 24-bit int.
        VarArgFunction packRGB = new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int r = (int) Math.round(Math.max(0.0, Math.min(1.0, a.checkdouble(1))) * 255.0);
                int g = (int) Math.round(Math.max(0.0, Math.min(1.0, a.checkdouble(2))) * 255.0);
                int b = (int) Math.round(Math.max(0.0, Math.min(1.0, a.checkdouble(3))) * 255.0);
                return LuaValue.valueOf((r << 16) | (g << 8) | b);
            }
        };
        colors.set("packRGB", packRGB);
        colors.set("rgb8", packRGB);
        // colors.unpackRGB(rgb) — 24-bit int → 3 floats (0..1).
        colors.set("unpackRGB", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                int rgb = v.checkint();
                return LuaValue.varargsOf(new LuaValue[] {
                    LuaValue.valueOf(((rgb >> 16) & 0xFF) / 255.0),
                    LuaValue.valueOf(((rgb >>  8) & 0xFF) / 255.0),
                    LuaValue.valueOf(( rgb        & 0xFF) / 255.0)
                }).arg1();
            }
        });
        // colors.toBlit(color) — palette bitmask → single hex char ('0'..'f').
        colors.set("toBlit", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                int idx = Integer.numberOfTrailingZeros(Math.max(1, v.checkint())) & 0xF;
                return LuaValue.valueOf(Integer.toHexString(idx));
            }
        });
        // colors.fromBlit(ch) — single hex char → palette bitmask.
        colors.set("fromBlit", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                String s = v.checkjstring();
                if (s.isEmpty()) return LuaValue.NIL;
                int idx = Character.digit(s.charAt(0), 16);
                if (idx < 0) return LuaValue.NIL;
                return LuaValue.valueOf(1 << idx);
            }
        });

        globals.set("colors", colors);
        globals.set("colours", colors);
    }

    /**
     * Load + invoke a chunk with Lua varargs. Returns the chunk's return value
     * varargs (so multi-return shell scripts work), or null on error.
     */
    public Varargs executeWithArgs(String code, String chunkName, Varargs args) {
        try {
            InputStream is = new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8));
            LuaValue chunk = globals.load(is, chunkName, "bt", globals);
            return chunk.invoke(args == null ? LuaValue.NONE : args);
        } catch (LuaError e) {
            pushOutput("Lua Error: " + e.getMessage());
            return null;
        } catch (Exception e) {
            pushOutput("Error: " + e.getMessage());
            return null;
        }
    }

    // --- Shell state (mutable, shared across shell.* calls) ---
    private final java.util.LinkedHashMap<String, String> shellAliases = new java.util.LinkedHashMap<>();
    private final java.util.ArrayDeque<String> shellProgramStack = new java.util.ArrayDeque<>();
    private String shellPath = "/Program Files:/rom/programs";

    private void installShellAPI() {
        LuaTable shell = new LuaTable();
        final String[] currentDir = {"/"};

        // Default aliases (CC-style).
        shellAliases.put("ls", "list");
        shellAliases.put("dir", "list");
        shellAliases.put("cp", "copy");
        shellAliases.put("mv", "move");
        shellAliases.put("rm", "delete");
        shellAliases.put("preview", "edit");

        shell.set("dir", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(currentDir[0]); }
        });
        shell.set("setDir", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue v) {
                String d = v.checkjstring();
                if (os.getFileSystem().isDirectory(d)) currentDir[0] = d;
                return NONE;
            }
        });
        shell.set("resolve", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue v) {
                String p = v.checkjstring();
                if (p.startsWith("/")) return LuaValue.valueOf(p);
                return LuaValue.valueOf(currentDir[0].equals("/") ? "/" + p : currentDir[0] + "/" + p);
            }
        });

        // shell.path() / shell.setPath(path)
        shell.set("path", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(shellPath); }
        });
        shell.set("setPath", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                shellPath = v.checkjstring(); return NONE;
            }
        });

        // shell.aliases() -> table { alias = command }
        shell.set("aliases", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable t = new LuaTable();
                for (var e : shellAliases.entrySet()) {
                    t.set(e.getKey(), LuaValue.valueOf(e.getValue()));
                }
                return t;
            }
        });
        shell.set("setAlias", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue k, LuaValue v) {
                shellAliases.put(k.checkjstring(), v.checkjstring()); return NONE;
            }
        });
        shell.set("clearAlias", new OneArgFunction() {
            @Override public LuaValue call(LuaValue k) {
                shellAliases.remove(k.checkjstring()); return NONE;
            }
        });

        // shell.resolveProgram(name) -> path or nil
        shell.set("resolveProgram", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                String name = v.checkjstring();
                String alias = shellAliases.get(name);
                if (alias != null) name = alias;
                String found = resolveProgramOnPath(name);
                return found != null ? LuaValue.valueOf(found) : LuaValue.NIL;
            }
        });

        // shell.programs([includeHidden]) -> list of program names available on path
        shell.set("programs", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                boolean inclHidden = a.optboolean(1, false);
                java.util.TreeSet<String> names = new java.util.TreeSet<>();
                VirtualFileSystem vfs = os.getFileSystem();
                if (vfs != null) {
                    for (String dir : shellPath.split(":")) {
                        if (dir.isEmpty() || !vfs.exists(dir) || !vfs.isDirectory(dir)) continue;
                        for (String n : vfs.list(dir)) {
                            if (!inclHidden && n.startsWith(".")) continue;
                            String full = dir.endsWith("/") ? dir + n : dir + "/" + n;
                            if (vfs.isDirectory(full)) continue;
                            // Strip .lua extension if present
                            String prog = n.endsWith(".lua") ? n.substring(0, n.length() - 4) : n;
                            names.add(prog);
                        }
                    }
                }
                LuaTable out = new LuaTable();
                int i = 1;
                for (String n : names) out.set(i++, LuaValue.valueOf(n));
                return out;
            }
        });

        // shell.complete(line) — completes program name (first word) or file path (later words).
        shell.set("complete", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                String line = v.checkjstring();
                LuaTable out = new LuaTable();
                int sp = line.lastIndexOf(' ');
                if (sp < 0) {
                    // First word: program completion
                    String partial = line;
                    java.util.TreeSet<String> matches = new java.util.TreeSet<>();
                    for (String alias : shellAliases.keySet()) {
                        if (alias.startsWith(partial)) matches.add(alias.substring(partial.length()));
                    }
                    VirtualFileSystem vfs = os.getFileSystem();
                    if (vfs != null) {
                        for (String dir : shellPath.split(":")) {
                            if (dir.isEmpty() || !vfs.exists(dir) || !vfs.isDirectory(dir)) continue;
                            for (String n : vfs.list(dir)) {
                                String prog = n.endsWith(".lua") ? n.substring(0, n.length() - 4) : n;
                                if (prog.startsWith(partial)) matches.add(prog.substring(partial.length()));
                            }
                        }
                    }
                    int i = 1;
                    for (String m : matches) out.set(i++, LuaValue.valueOf(m + " "));
                } else {
                    // Subsequent word: file completion via fs.complete
                    String partial = line.substring(sp + 1);
                    VirtualFileSystem vfs = os.getFileSystem();
                    if (vfs == null) return out;
                    int slash = partial.lastIndexOf('/');
                    String dir = slash < 0 ? currentDir[0] : partial.substring(0, slash + 1);
                    String name = slash < 0 ? partial : partial.substring(slash + 1);
                    String resolveDir = dir.startsWith("/") ? dir
                            : (currentDir[0].equals("/") ? "/" + dir : currentDir[0] + "/" + dir);
                    if (resolveDir.isEmpty()) resolveDir = "/";
                    if (!vfs.exists(resolveDir) || !vfs.isDirectory(resolveDir)) return out;
                    int i = 1;
                    for (String n : vfs.list(resolveDir)) {
                        if (!n.startsWith(name)) continue;
                        String full = resolveDir.endsWith("/") ? resolveDir + n : resolveDir + "/" + n;
                        boolean isDir = vfs.isDirectory(full);
                        out.set(i++, LuaValue.valueOf(n.substring(name.length()) + (isDir ? "/" : "")));
                    }
                }
                return out;
            }
        });

        // shell.getRunningProgram() -> path of currently-executing program (or "" if none)
        shell.set("getRunningProgram", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(shellProgramStack.isEmpty() ? "" : shellProgramStack.peek());
            }
        });

        // shell.run(path, ...) — run program file with args. Returns true/false.
        shell.set("run", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                if (args.narg() == 0) return LuaValue.FALSE;
                String first = args.checkjstring(1);
                // Resolve via aliases + path before falling back to literal path lookup.
                String alias = shellAliases.get(first);
                if (alias != null) first = alias;
                String resolved = resolveProgramOnPath(first);
                if (resolved == null) {
                    // Fall back to literal path resolution (cwd-relative).
                    resolved = first.startsWith("/") ? first :
                        (currentDir[0].equals("/") ? "/" + first : currentDir[0] + "/" + first);
                }
                String content = os.getFileSystem().readFile(resolved);
                if (content == null) {
                    pushOutput("No such program: " + first + "\n");
                    return LuaValue.FALSE;
                }
                shellProgramStack.push(resolved);
                try {
                    Varargs result = executeWithArgs(content, resolved, args.subargs(2));
                    return result != null ? LuaValue.TRUE : LuaValue.FALSE;
                } finally {
                    shellProgramStack.pop();
                }
            }
        });

        // shell.execute(cmd, ...) — alias of run; CC parity name.
        shell.set("execute", shell.get("run"));

        // shell.openTab / switchTab — single-shell stubs
        shell.set("openTab", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) { return LuaValue.valueOf(1); }
        });
        shell.set("switchTab", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) { return NONE; }
        });
        shell.set("exit", new ZeroArgFunction() {
            @Override public LuaValue call() { queueEvent("terminate"); return NONE; }
        });

        globals.set("shell", shell);
    }

    /** Walk shell.path looking for a program by name. Tries name and name.lua. */
    private String resolveProgramOnPath(String name) {
        VirtualFileSystem vfs = os.getFileSystem();
        if (vfs == null) return null;
        if (name.startsWith("/")) {
            if (vfs.exists(name) && !vfs.isDirectory(name)) return name;
            String withLua = name + ".lua";
            if (vfs.exists(withLua) && !vfs.isDirectory(withLua)) return withLua;
            return null;
        }
        for (String dir : shellPath.split(":")) {
            if (dir.isEmpty()) continue;
            String full = dir.endsWith("/") ? dir + name : dir + "/" + name;
            if (vfs.exists(full) && !vfs.isDirectory(full)) return full;
            String withLua = full + ".lua";
            if (vfs.exists(withLua) && !vfs.isDirectory(withLua)) return withLua;
        }
        return null;
    }

    private void installTextUtilsAPI() {
        LuaTable textutils = new LuaTable();

        textutils.set("serialize", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue v) {
                return LuaValue.valueOf(luaToString(v));
            }
        });
        textutils.set("serialise", textutils.get("serialize"));

        globals.set("textutils", textutils);
    }

    private void installGpsAPI() {
        LuaTable gps = new LuaTable();

        // gps.locate() — returns x, y, z of this computer, or nil if no GPS satellite active
        gps.set("locate", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                if (!BluetoothNetwork.hasActiveGps()) return LuaValue.NIL;
                net.minecraft.core.BlockPos pos = BluetoothNetwork.findDevicePos(os.getComputerId());
                if (pos == null) return LuaValue.NIL;
                return LuaValue.varargsOf(new LuaValue[]{
                    LuaValue.valueOf(pos.getX()),
                    LuaValue.valueOf(pos.getY()),
                    LuaValue.valueOf(pos.getZ())
                });
            }
        });

        globals.set("gps", gps);
    }

    private void installBluetoothAPI() {
        LuaTable bt = new LuaTable();

        // bluetooth.getDevices() — returns table of devices in range
        bt.set("getDevices", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                LuaTable result = new LuaTable();
                if (lvl == null || pos == null) return result;

                java.util.List<BluetoothNetwork.DeviceEntry> entries =
                        BluetoothNetwork.getDevicesInRange(lvl, pos, BluetoothNetwork.BLOCK_RANGE);
                int i = 1;
                for (BluetoothNetwork.DeviceEntry d : entries) {
                    if (d.deviceId().equals(os.getComputerId())) continue;
                    LuaTable dev = new LuaTable();
                    dev.set("id", LuaValue.valueOf(d.deviceId().toString()));
                    dev.set("type", LuaValue.valueOf(d.type().getDisplayName()));
                    dev.set("distance", LuaValue.valueOf(Math.sqrt(pos.distSqr(d.pos()))));
                    dev.set("channel", LuaValue.valueOf(d.channel()));
                    dev.set("x", LuaValue.valueOf(d.pos().getX()));
                    dev.set("y", LuaValue.valueOf(d.pos().getY()));
                    dev.set("z", LuaValue.valueOf(d.pos().getZ()));
                    result.set(i++, dev);
                }
                return result;
            }
        });

        // bluetooth.getAllDevices() — returns ALL devices (including unlimited-range ones)
        bt.set("getAllDevices", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable result = new LuaTable();
                java.util.List<BluetoothNetwork.DeviceEntry> entries = BluetoothNetwork.getAllDevices();
                int i = 1;
                for (BluetoothNetwork.DeviceEntry d : entries) {
                    if (d.deviceId().equals(os.getComputerId())) continue;
                    LuaTable dev = new LuaTable();
                    dev.set("id", LuaValue.valueOf(d.deviceId().toString()));
                    dev.set("type", LuaValue.valueOf(d.type().getDisplayName()));
                    dev.set("channel", LuaValue.valueOf(d.channel()));
                    dev.set("dimension", LuaValue.valueOf(d.dimension()));
                    result.set(i++, dev);
                }
                return result;
            }
        });

        // bluetooth.send(targetId, channel, message) — send to specific device
        bt.set("send", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String targetId = args.checkjstring(1);
                int channel = args.checkint(2);
                String message = args.checkjstring(3);
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return LuaValue.FALSE;
                try {
                    java.util.UUID target = java.util.UUID.fromString(targetId);
                    BluetoothNetwork.send(lvl, pos, os.getComputerId(), target, channel, message);
                    return LuaValue.TRUE;
                } catch (IllegalArgumentException e) {
                    return LuaValue.FALSE;
                }
            }
        });

        // bluetooth.broadcast(channel, message) — broadcast to all in range
        bt.set("broadcast", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int channel = args.checkint(1);
                String message = args.checkjstring(2);
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return LuaValue.FALSE;
                BluetoothNetwork.broadcast(lvl, os.getComputerId(), pos, channel, message);
                return LuaValue.TRUE;
            }
        });

        // bluetooth.getChannel() — get current BT channel
        bt.set("getChannel", new ZeroArgFunction() {
            @Override
            public LuaValue call() { return LuaValue.valueOf(os.getBluetoothChannel()); }
        });

        // bluetooth.setChannel(n) — set BT channel
        bt.set("setChannel", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                os.setBluetoothChannel(arg.checkint());
                return NONE;
            }
        });

        // bluetooth.getRange() — get BT range for this device
        bt.set("getRange", new ZeroArgFunction() {
            @Override
            public LuaValue call() { return LuaValue.valueOf(BluetoothNetwork.BLOCK_RANGE); }
        });

        // bluetooth.getID() — get this computer's device ID
        bt.set("getID", new ZeroArgFunction() {
            @Override
            public LuaValue call() { return LuaValue.valueOf(os.getComputerId().toString()); }
        });

        globals.set("bluetooth", bt);
    }

    private void installRednetAPI() {
        LuaTable rednet = new LuaTable();

        // rednet.open() — CC compat: opens the modem (no-op for us, always open)
        rednet.set("open", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) { return NONE; }
        });

        // rednet.close() — CC compat: no-op
        rednet.set("close", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) { return NONE; }
        });

        // rednet.isOpen() — always true
        rednet.set("isOpen", new ZeroArgFunction() {
            @Override
            public LuaValue call() { return LuaValue.TRUE; }
        });

        // rednet.send(targetId, message, protocol) — send to specific device
        rednet.set("send", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String targetId = args.checkjstring(1);
                String message = args.checkjstring(2);
                String protocol = args.optjstring(3, "");
                String payload = protocol.isEmpty() ? message : protocol + ":" + message;
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return LuaValue.FALSE;
                try {
                    java.util.UUID target = java.util.UUID.fromString(targetId);
                    BluetoothNetwork.send(lvl, pos, os.getComputerId(), target, os.getBluetoothChannel(), payload);
                    return LuaValue.TRUE;
                } catch (IllegalArgumentException e) {
                    return LuaValue.FALSE;
                }
            }
        });

        // rednet.broadcast(message, protocol) — broadcast to all
        rednet.set("broadcast", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String message = args.checkjstring(1);
                String protocol = args.optjstring(2, "");
                String payload = protocol.isEmpty() ? message : protocol + ":" + message;
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return LuaValue.FALSE;
                BluetoothNetwork.broadcast(lvl, os.getComputerId(), pos, os.getBluetoothChannel(), payload);
                return LuaValue.TRUE;
            }
        });

        // rednet.host(protocol, hostname) — register as host (stores in VFS for discovery)
        rednet.set("host", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue protocol, LuaValue hostname) {
                String key = "/Windows/System32/rednet_hosts";
                String existing = os.getFileSystem().readFile(key);
                String entry = protocol.checkjstring() + "=" + hostname.checkjstring() + "=" + os.getComputerId().toString();
                String content = (existing != null ? existing + "\n" : "") + entry;
                os.getFileSystem().writeFile(key, content);
                return NONE;
            }
        });

        // rednet.unhost(protocol) — remove all host entries this computer
        // registered for the given protocol. CC semantics: only affects entries
        // owned by the local computer (matching id), so other peers' entries on
        // the shared host file are preserved.
        rednet.set("unhost", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue protocol) {
                String key = "/Windows/System32/rednet_hosts";
                String content = os.getFileSystem().readFile(key);
                if (content == null) return NONE;
                String myId = os.getComputerId().toString();
                String wanted = protocol.checkjstring();
                StringBuilder kept = new StringBuilder();
                boolean first = true;
                for (String line : content.split("\n")) {
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("=", 3);
                    // Drop lines that match (this computer, this protocol).
                    if (parts.length >= 3 && parts[0].equals(wanted) && parts[2].equals(myId)) continue;
                    if (!first) kept.append('\n');
                    kept.append(line);
                    first = false;
                }
                os.getFileSystem().writeFile(key, kept.toString());
                return NONE;
            }
        });

        // rednet.lookup(protocol) — find hosts
        rednet.set("lookup", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue protocol) {
                LuaTable result = new LuaTable();
                String key = "/Windows/System32/rednet_hosts";
                String content = os.getFileSystem().readFile(key);
                if (content == null) return result;
                int i = 1;
                for (String line : content.split("\n")) {
                    String[] parts = line.split("=", 3);
                    if (parts.length >= 3 && parts[0].equals(protocol.checkjstring())) {
                        LuaTable host = new LuaTable();
                        host.set("hostname", LuaValue.valueOf(parts[1]));
                        host.set("id", LuaValue.valueOf(parts[2]));
                        result.set(i++, host);
                    }
                }
                return result;
            }
        });

        globals.set("rednet", rednet);

        // rednet.receive([protocolFilter [, timeout]]) — yields until a matching
        // rednet_message arrives; returns sender, message, protocol. nil on timeout.
        // Implemented in Lua so it can call os.pullEvent (a coroutine yield).
        execute(
            "function rednet.receive(protocolFilter, timeout)\n" +
            "  local timer\n" +
            "  if type(protocolFilter) == 'number' and timeout == nil then\n" +
            "    timeout = protocolFilter; protocolFilter = nil\n" +
            "  end\n" +
            "  if timeout then timer = os.startTimer(timeout) end\n" +
            "  while true do\n" +
            "    local ev, sender, payload, _ch = os.pullEvent()\n" +
            "    if ev == 'rednet_message' then\n" +
            "      local proto, msg\n" +
            "      if type(payload) == 'string' then\n" +
            "        local i = string.find(payload, ':', 1, true)\n" +
            "        if i then proto = string.sub(payload, 1, i-1); msg = string.sub(payload, i+1)\n" +
            "        else proto = nil; msg = payload end\n" +
            "      else msg = payload end\n" +
            "      if not protocolFilter or protocolFilter == proto then\n" +
            "        if timer then os.cancelTimer(timer) end\n" +
            "        return sender, msg, proto\n" +
            "      end\n" +
            "    elseif ev == 'timer' and sender == timer then\n" +
            "      return nil\n" +
            "    elseif ev == 'terminate' then\n" +
            "      error('Terminated', 0)\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
            "=rednet.receive");
    }

    private void installRedstoneAPI() {
        LuaTable rs = new LuaTable();
        String[] SIDES = {"bottom", "top", "north", "south", "west", "east"};

        // redstone.getSides() — returns list of side names
        rs.set("getSides", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable t = new LuaTable();
                for (int i = 0; i < SIDES.length; i++) t.set(i + 1, LuaValue.valueOf(SIDES[i]));
                return t;
            }
        });

        // redstone.getInput(side) — read analog input 0-15
        rs.set("getInput", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue side) {
                int idx = sideToIndex(side.checkjstring(), SIDES);
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null || idx < 0) return LuaValue.valueOf(0);
                net.minecraft.core.Direction dir = net.minecraft.core.Direction.values()[idx];
                return LuaValue.valueOf(lvl.getSignal(pos.relative(dir), dir));
            }
        });

        // redstone.getAnalogInput(side) — alias
        rs.set("getAnalogInput", rs.get("getInput"));

        // redstone.setOutput(side, power) — set analog output 0-15
        rs.set("setOutput", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue side, LuaValue power) {
                int idx = sideToIndex(side.checkjstring(), SIDES);
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                net.minecraft.world.level.Level lvl = os.getLevel();
                if (pos == null || idx < 0) return NONE;
                BluetoothNetwork.setRedstoneOutput(pos, idx, power.checkint());
                if (lvl != null) {
                    net.minecraft.core.Direction dir = net.minecraft.core.Direction.values()[idx];
                    lvl.updateNeighborsAt(pos, lvl.getBlockState(pos).getBlock());
                }
                return NONE;
            }
        });

        // redstone.setAnalogOutput(side, power) — alias
        rs.set("setAnalogOutput", rs.get("setOutput"));

        // redstone.getOutput(side) — read our own output
        rs.set("getOutput", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue side) {
                int idx = sideToIndex(side.checkjstring(), SIDES);
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (pos == null || idx < 0) return LuaValue.valueOf(0);
                return LuaValue.valueOf(BluetoothNetwork.getRedstoneOutput(pos, idx));
            }
        });

        // redstone.getAnalogOutput(side) — alias
        rs.set("getAnalogOutput", rs.get("getOutput"));

        // Bundled cable support
        // redstone.setBundledOutput(side, colorMask)
        rs.set("setBundledOutput", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue side, LuaValue mask) {
                int idx = sideToIndex(side.checkjstring(), SIDES);
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (pos == null || idx < 0) return NONE;
                BluetoothNetwork.setBundledOutput(pos, idx, mask.checkint());
                return NONE;
            }
        });

        // redstone.getBundledOutput(side)
        rs.set("getBundledOutput", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue side) {
                int idx = sideToIndex(side.checkjstring(), SIDES);
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (pos == null || idx < 0) return LuaValue.valueOf(0);
                return LuaValue.valueOf(BluetoothNetwork.getBundledOutput(pos, idx));
            }
        });

        // redstone.getBundledInput(side) — reads from world (requires compatible mod)
        rs.set("getBundledInput", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue side) {
                // Bundled input requires a mod like CC or Project Red to provide signals
                // Returns 0 if no compatible mod is present
                return LuaValue.valueOf(0);
            }
        });

        // redstone.testBundledInput(side, color) — test if a specific color wire is active
        rs.set("testBundledInput", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue side, LuaValue color) {
                return LuaValue.FALSE;
            }
        });

        globals.set("redstone", rs);
        globals.set("rs", rs); // CC alias
    }

    // ── relay API ─────────────────────────────────────────────────────────────
    // Wraps RedstoneLib to control the nearest Redstone Relay over Bluetooth.
    // Side names match redstone API: "bottom","top","north","south","west","east"

    private void installRelayAPI() {
        LuaTable relay = new LuaTable();
        String[] SIDES = {"bottom", "top", "north", "south", "west", "east"};

        // relay.isConnected() — true if a relay is found in Bluetooth range
        relay.set("isConnected", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(RedstoneLib.findRelay(os) != null);
            }
        });

        // relay.getSides() — returns table of side name strings
        relay.set("getSides", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable t = new LuaTable();
                for (int i = 0; i < SIDES.length; i++) t.set(i + 1, LuaValue.valueOf(SIDES[i]));
                return t;
            }
        });

        // relay.setOutput(side, power) — set analog output 0-15 on relay side
        relay.set("setOutput", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue side, LuaValue power) {
                int idx = sideToIndex(side.checkjstring(), SIDES);
                if (idx < 0) return NONE;
                RedstoneLib.setOutput(os, idx, power.checkint());
                return NONE;
            }
        });

        // relay.getOutput(side) — read the relay's current output on that side
        relay.set("getOutput", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue side) {
                int idx = sideToIndex(side.checkjstring(), SIDES);
                if (idx < 0) return LuaValue.valueOf(0);
                return LuaValue.valueOf(RedstoneLib.getOutput(os, idx));
            }
        });

        // relay.getInput(side) — read incoming world redstone signal on relay side
        relay.set("getInput", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue side) {
                int idx = sideToIndex(side.checkjstring(), SIDES);
                if (idx < 0) return LuaValue.valueOf(0);
                return LuaValue.valueOf(RedstoneLib.getInput(os, idx));
            }
        });

        // relay.getAllOutputs() — returns table[1..6] of current output values
        relay.set("getAllOutputs", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable t = new LuaTable();
                for (int i = 0; i < 6; i++) {
                    t.set(i + 1, LuaValue.valueOf(RedstoneLib.getOutput(os, i)));
                }
                return t;
            }
        });

        // relay.getAllInputs() — returns table[1..6] of current input readings
        relay.set("getAllInputs", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable t = new LuaTable();
                int[] inputs = RedstoneLib.getAllInputs(os);
                for (int i = 0; i < inputs.length; i++) {
                    t.set(i + 1, LuaValue.valueOf(inputs[i]));
                }
                return t;
            }
        });

        globals.set("relay", relay);
    }

    // ── buttons API ───────────────────────────────────────────────────────────
    // Wraps ButtonsLib to drive the local computer's built-in virtual 16-button
    // panel. This is the same panel the on-screen Button App controls; toggling
    // buttons here immediately emits redstone + bundled signals on all 6 sides
    // of the computer block.
    //
    //   buttons.set(i, on)          — turn button i (0-15) on/off
    //   buttons.get(i)              — returns boolean
    //   buttons.setAll(mask)        — set all 16 buttons at once (16-bit mask)
    //   buttons.getAll()            — read the full 16-bit mask
    //   buttons.toggle(i)           — flip button i
    //   buttons.setMode(i, mode)    — "toggle" | "momentary" | "timer" | "delay" | "inverted"
    //   buttons.setDuration(i, t)   — ticks used by timer/delay modes (1..6000)
    //   buttons.setLabel(i, text)   — per-button display label (max 16 chars)
    //   buttons.setColor(i, rgb)    — 0xRRGGBB integer, or -1 for default
    //   buttons.setPanelLabel(s)    — rename the whole virtual panel (max 24)
    //   buttons.setChannel(n)       — Bluetooth channel (1..256)

    private void installButtonsAPI() {
        LuaTable buttons = new LuaTable();

        buttons.set("set", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue i, LuaValue on) {
                ButtonsLib.setButton(os, i.checkint(), on.toboolean());
                return NONE;
            }
        });

        buttons.set("get", new OneArgFunction() {
            @Override public LuaValue call(LuaValue i) {
                return LuaValue.valueOf(ButtonsLib.getButton(os, i.checkint()));
            }
        });

        buttons.set("setAll", new OneArgFunction() {
            @Override public LuaValue call(LuaValue mask) {
                ButtonsLib.setAllButtons(os, mask.checkint());
                return NONE;
            }
        });

        buttons.set("getAll", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ButtonsLib.getButtonStates(os));
            }
        });

        buttons.set("toggle", new OneArgFunction() {
            @Override public LuaValue call(LuaValue iv) {
                int i = iv.checkint();
                ButtonsLib.setButton(os, i, !ButtonsLib.getButton(os, i));
                return NONE;
            }
        });

        buttons.set("setMode", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue iv, LuaValue modeVal) {
                int i = iv.checkint();
                String m = modeVal.checkjstring().toUpperCase();
                try {
                    ButtonsLib.setMode(os, i,
                            com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity.ButtonMode.valueOf(m));
                } catch (IllegalArgumentException ignored) {}
                return NONE;
            }
        });

        buttons.set("setDuration", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue i, LuaValue ticks) {
                ButtonsLib.setDuration(os, i.checkint(), ticks.checkint());
                return NONE;
            }
        });

        buttons.set("setLabel", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue i, LuaValue label) {
                ButtonsLib.setButtonLabel(os, i.checkint(), label.checkjstring());
                return NONE;
            }
        });

        buttons.set("setColor", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue i, LuaValue rgb) {
                ButtonsLib.setButtonColor(os, i.checkint(), rgb.checkint());
                return NONE;
            }
        });
        buttons.set("setColour", buttons.get("setColor"));

        buttons.set("setPanelLabel", new OneArgFunction() {
            @Override public LuaValue call(LuaValue label) {
                ButtonsLib.setLabel(os, label.checkjstring());
                return NONE;
            }
        });

        buttons.set("setChannel", new OneArgFunction() {
            @Override public LuaValue call(LuaValue ch) {
                ButtonsLib.setChannel(os, ch.checkint());
                return NONE;
            }
        });

        globals.set("buttons", buttons);
    }

    // ------------------------------------------------------------------
    // robot.* — programmatic control of the RobotEntity hosting this OS.
    // Only active when the OS is hosted by a RobotEntity.
    // ------------------------------------------------------------------
    private void installRobotAPI() {
        LuaTable robot = new LuaTable();

        // Queue a raw command string.
        robot.set("queue", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue cmd) {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.FALSE;
                r.queueCommand(cmd.tojstring());
                return LuaValue.TRUE;
            }
        });

        // Movement commands
        String[] moves = {"forward", "back", "up", "down", "turnLeft", "turnRight",
                          "dig", "digUp", "digDown", "place",
                          "drop", "dropUp", "dropDown",
                          "suck", "suckUp", "suckDown"};
        for (String m : moves) {
            final String cmd = m;
            robot.set(m, new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                    if (r == null) return LuaValue.FALSE;
                    r.queueCommand(cmd);
                    return LuaValue.TRUE;
                }
            });
        }

        robot.set("clear", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.FALSE;
                r.clearCommands();
                return LuaValue.TRUE;
            }
        });

        robot.set("isBusy", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.FALSE;
                return LuaValue.valueOf(r.isBusy());
            }
        });

        robot.set("commandsQueued", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.valueOf(0);
                return LuaValue.valueOf(r.getCommandsQueued());
            }
        });

        robot.set("getFuel", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.valueOf(0);
                return LuaValue.valueOf(r.getEnergyStorage().getEnergyStored());
            }
        });

        robot.set("refuel", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue slot) {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.valueOf(0);
                return LuaValue.valueOf(r.refuel(slot.checkint() - 1));
            }
        });

        robot.set("select", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue slot) {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.FALSE;
                r.setSelectedSlot(slot.checkint() - 1);
                return LuaValue.TRUE;
            }
        });

        robot.set("getSelected", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.valueOf(0);
                return LuaValue.valueOf(r.getSelectedSlot() + 1);
            }
        });

        robot.set("getItemCount", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue slot) {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.valueOf(0);
                int s = slot.checkint() - 1;
                if (s < 0 || s >= r.getInventory().getContainerSize()) return LuaValue.valueOf(0);
                return LuaValue.valueOf(r.getInventory().getItem(s).getCount());
            }
        });

        robot.set("getItemName", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue slot) {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.NIL;
                int s = slot.checkint() - 1;
                if (s < 0 || s >= r.getInventory().getContainerSize()) return LuaValue.NIL;
                net.minecraft.world.item.ItemStack stack = r.getInventory().getItem(s);
                if (stack.isEmpty()) return LuaValue.NIL;
                return LuaValue.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString());
            }
        });

        robot.set("getFacing", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.NIL;
                return LuaValue.valueOf(r.getRobotFacing().getName());
            }
        });

        robot.set("getPos", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.NIL;
                net.minecraft.core.BlockPos p = r.blockPosition();
                return LuaValue.varargsOf(
                        LuaValue.valueOf(p.getX()),
                        LuaValue.valueOf(p.getY()),
                        LuaValue.valueOf(p.getZ()));
            }
        });

        robot.set("detect", new ZeroArgFunction() {
            @Override
            public LuaValue call() { return detectAt(0); }
        });
        robot.set("detectUp", new ZeroArgFunction() {
            @Override
            public LuaValue call() { return detectAt(1); }
        });
        robot.set("detectDown", new ZeroArgFunction() {
            @Override
            public LuaValue call() { return detectAt(-1); }
        });

        robot.set("compare", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.FALSE;
                return LuaValue.valueOf(r.compareBlock(0));
            }
        });
        robot.set("compareUp", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.FALSE;
                return LuaValue.valueOf(r.compareBlock(1));
            }
        });
        robot.set("compareDown", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.FALSE;
                return LuaValue.valueOf(r.compareBlock(-1));
            }
        });

        robot.set("isCharging", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.FALSE;
                return LuaValue.valueOf(r.isCharging());
            }
        });

        // Tool slot — equip an inventory slot into the tool bay, or unequip.
        robot.set("equip", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue slot) {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.FALSE;
                return LuaValue.valueOf(r.equipTool(slot.checkint() - 1));
            }
        });
        robot.set("unequip", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.FALSE;
                return LuaValue.valueOf(r.unequipTool());
            }
        });
        robot.set("getTool", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null) return LuaValue.NIL;
                net.minecraft.world.item.ItemStack t = r.getEquippedTool();
                if (t.isEmpty()) return LuaValue.NIL;
                return LuaValue.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(t.getItem()).toString());
            }
        });

        // Portable scanner — robot.scan([radius]) returns entities table keyed 1..n
        // with fields: type, name, x, y, z, health, maxHealth, player (bool), uuid.
        // Radius clamped to 1..16 (immediate scan).
        robot.set("scan", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null || r.level() == null) return LuaValue.NIL;
                int radius = args.narg() >= 1 ? args.checkint(1) : 8;
                return doEntityScan(r.level(), r.blockPosition(), radius);
            }
        });

        // robot.findCharger() -> x,y,z (or nil) — locates nearest Charging Station via Bluetooth.
        robot.set("findCharger", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null || r.level() == null) return LuaValue.NIL;
                net.minecraft.core.BlockPos pad = com.apocscode.byteblock.network.BluetoothNetwork
                        .findNearestDevice(r.level(), r.blockPosition(),
                                com.apocscode.byteblock.network.BluetoothNetwork.DeviceType.CHARGING_STATION);
                if (pad == null) return LuaValue.NIL;
                return LuaValue.varargsOf(new LuaValue[] {
                        LuaValue.valueOf(pad.getX()),
                        LuaValue.valueOf(pad.getY()),
                        LuaValue.valueOf(pad.getZ()) });
            }
        });

        // robot.goHome() — find nearest Charging Station and queue a goto: command to it.
        // Returns true if a charger was found and the command queued, false otherwise.
        robot.set("goHome", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                if (r == null || r.level() == null) return LuaValue.FALSE;
                net.minecraft.core.BlockPos pad = com.apocscode.byteblock.network.BluetoothNetwork
                        .findNearestDevice(r.level(), r.blockPosition(),
                                com.apocscode.byteblock.network.BluetoothNetwork.DeviceType.CHARGING_STATION);
                if (pad == null) return LuaValue.FALSE;
                // Stand on top of the pad so the station's AABB.inflate(3) covers us.
                r.queueCommand("goto:" + pad.getX() + ":" + (pad.getY() + 1) + ":" + pad.getZ());
                return LuaValue.TRUE;
            }
        });

        globals.set("robot", robot);
    }

    /** Shared scan helper used by robot.scan() and the drone BT scan response. */
    private LuaValue doEntityScan(net.minecraft.world.level.Level lvl,
                                  net.minecraft.core.BlockPos origin, int radius) {
        int clamped = Math.max(1, Math.min(radius, 16));
        com.apocscode.byteblock.scanner.WorldScanData data =
                new com.apocscode.byteblock.scanner.WorldScanData();
        data.setOrigin(origin);
        data.scanEntities(lvl, origin, clamped);
        LuaTable result = new LuaTable();
        int i = 1;
        for (com.apocscode.byteblock.scanner.WorldScanData.EntitySnapshot e : data.getEntities()) {
            LuaTable row = new LuaTable();
            row.set("type", LuaValue.valueOf(e.type()));
            row.set("name", LuaValue.valueOf(e.name() == null ? "" : e.name()));
            row.set("x", LuaValue.valueOf(e.x()));
            row.set("y", LuaValue.valueOf(e.y()));
            row.set("z", LuaValue.valueOf(e.z()));
            row.set("health", LuaValue.valueOf(e.health()));
            row.set("maxHealth", LuaValue.valueOf(e.maxHealth()));
            row.set("player", LuaValue.valueOf(e.isPlayer()));
            row.set("uuid", LuaValue.valueOf(e.uuid() == null ? "" : e.uuid()));
            result.set(i++, row);
        }
        return result;
    }

    /** Returns the RobotEntity hosting this OS, or null. */
    private com.apocscode.byteblock.entity.RobotEntity asRobot() {
        net.minecraft.world.entity.Entity h = os.getHost();
        return (h instanceof com.apocscode.byteblock.entity.RobotEntity r) ? r : null;
    }

    /** Probe block ahead (0), above (+1) or below (-1) the robot. */
    private LuaValue detectAt(int yOffset) {
        com.apocscode.byteblock.entity.RobotEntity r = asRobot();
        if (r == null || r.level() == null) return LuaValue.FALSE;
        net.minecraft.core.BlockPos pos;
        if (yOffset == 0) {
            pos = r.blockPosition().relative(r.getRobotFacing());
        } else if (yOffset > 0) {
            pos = r.blockPosition().above();
        } else {
            pos = r.blockPosition().below();
        }
        return LuaValue.valueOf(!r.level().getBlockState(pos).isAir());
    }

    // ------------------------------------------------------------------
    // drone.* — control nearby drones via Bluetooth on a given channel.
    // Commands are fire-and-forget broadcasts; any drone tuned to the
    // same channel and within range will obey.
    // ------------------------------------------------------------------
    private void installDroneAPI() {
        LuaTable drone = new LuaTable();

        // drone.waypoint(x, y, z [, channel])
        drone.set("waypoint", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                double x = args.checkdouble(1);
                double y = args.checkdouble(2);
                double z = args.checkdouble(3);
                int ch = args.narg() >= 4 ? args.checkint(4) : os.getBluetoothChannel();
                return LuaValue.valueOf(sendDrone(ch, "drone:waypoint:" + x + ":" + y + ":" + z));
            }
        });

        // drone.home([channel])
        drone.set("home", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int ch = args.narg() >= 1 ? args.checkint(1) : os.getBluetoothChannel();
                return LuaValue.valueOf(sendDrone(ch, "drone:home"));
            }
        });

        // drone.clear([channel])
        drone.set("clear", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int ch = args.narg() >= 1 ? args.checkint(1) : os.getBluetoothChannel();
                return LuaValue.valueOf(sendDrone(ch, "drone:clear"));
            }
        });

        // drone.hover(true/false [, channel])
        drone.set("hover", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                boolean h = args.checkboolean(1);
                int ch = args.narg() >= 2 ? args.checkint(2) : os.getBluetoothChannel();
                return LuaValue.valueOf(sendDrone(ch, "drone:hover:" + h));
            }
        });

        // drone.refuel(ticks [, channel]) — remote fuel grant, for ops that stock drones via another system
        drone.set("refuel", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int ticks = args.checkint(1);
                int ch = args.narg() >= 2 ? args.checkint(2) : os.getBluetoothChannel();
                return LuaValue.valueOf(sendDrone(ch, "drone:refuel:" + ticks));
            }
        });

        // drone.pickup(x, y, z [, max [, channel]]) — broadcast a pickup order at target pos
        drone.set("pickup", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int x = args.checkint(1);
                int y = args.checkint(2);
                int z = args.checkint(3);
                int max = args.narg() >= 4 ? args.checkint(4) : 64;
                int ch = args.narg() >= 5 ? args.checkint(5) : os.getBluetoothChannel();
                return LuaValue.valueOf(sendDrone(ch, "drone:pickup:" + x + ":" + y + ":" + z + ":" + max));
            }
        });

        // drone.drop(x, y, z [, max [, channel]]) — broadcast a deposit order at target pos
        drone.set("drop", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int x = args.checkint(1);
                int y = args.checkint(2);
                int z = args.checkint(3);
                int max = args.narg() >= 4 ? args.checkint(4) : 64;
                int ch = args.narg() >= 5 ? args.checkint(5) : os.getBluetoothChannel();
                return LuaValue.valueOf(sendDrone(ch, "drone:drop:" + x + ":" + y + ":" + z + ":" + max));
            }
        });

        // drone.defender(true/false [, channel])
        drone.set("defender", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                boolean on = args.checkboolean(1);
                int ch = args.narg() >= 2 ? args.checkint(2) : os.getBluetoothChannel();
                return LuaValue.valueOf(sendDrone(ch, "drone:defender:" + on));
            }
        });

        // drone.group("name" [, channel]) — assign swarm group (empty string removes)
        drone.set("group", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String g = args.checkjstring(1);
                int ch = args.narg() >= 2 ? args.checkint(2) : os.getBluetoothChannel();
                return LuaValue.valueOf(sendDrone(ch, "drone:group:" + g));
            }
        });

        // drone.swarm("group", "cmd", arg1, arg2, ... [, channel]) — command a whole group
        // Channel is the LAST arg if it's an integer; otherwise the OS channel is used.
        drone.set("swarm", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                if (args.narg() < 2) return LuaValue.FALSE;
                String group = args.checkjstring(1);
                String cmd = args.checkjstring(2);
                StringBuilder sb = new StringBuilder("drone:swarm:").append(group).append(':').append(cmd);
                // Trailing integer is treated as channel; anything else becomes part of the payload.
                int last = args.narg();
                int ch = os.getBluetoothChannel();
                int end = last;
                if (last >= 3 && args.arg(last).isint()) {
                    ch = args.checkint(last);
                    end = last - 1;
                }
                for (int i = 3; i <= end; i++) {
                    sb.append(':').append(args.arg(i).tojstring());
                }
                return LuaValue.valueOf(sendDrone(ch, sb.toString()));
            }
        });

        // drone.variant("cargo" / "defender" / "scout" / "standard" [, channel])
        drone.set("variant", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String name = args.checkjstring(1);
                int ch = args.narg() >= 2 ? args.checkint(2) : os.getBluetoothChannel();
                return LuaValue.valueOf(sendDrone(ch, "drone:variant:" + name));
            }
        });

        // drone.scan([radius [, channel]]) — triggers drone to scan and broadcast results
        // as "drone:scanresult:<uuid>:<type>:<x>:<y>:<z>:<health>:<isPlayer>:<name>"
        // followed by "drone:scandone:<uuid>:<count>". Listen via bluetooth.receive().
        drone.set("scan", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int radius = args.narg() >= 1 ? args.checkint(1) : 8;
                int ch = args.narg() >= 2 ? args.checkint(2) : os.getBluetoothChannel();
                return LuaValue.valueOf(sendDrone(ch, "drone:scan:" + radius));
            }
        });

        // drone.findCharger() -> x,y,z (or nil) — locates nearest Charging Station via Bluetooth.
        drone.set("findCharger", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos here = os.getBlockPos();
                if (lvl == null || here == null) return LuaValue.NIL;
                net.minecraft.core.BlockPos pad = com.apocscode.byteblock.network.BluetoothNetwork
                        .findNearestDevice(lvl, here,
                                com.apocscode.byteblock.network.BluetoothNetwork.DeviceType.CHARGING_STATION);
                if (pad == null) return LuaValue.NIL;
                return LuaValue.varargsOf(new LuaValue[] {
                        LuaValue.valueOf(pad.getX()),
                        LuaValue.valueOf(pad.getY()),
                        LuaValue.valueOf(pad.getZ()) });
            }
        });

        // drone.goHome([channel]) — find nearest Charging Station and broadcast a
        // drone:waypoint to it. Returns true if a charger was found.
        drone.set("goHome", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos here = os.getBlockPos();
                if (lvl == null || here == null) return LuaValue.FALSE;
                net.minecraft.core.BlockPos pad = com.apocscode.byteblock.network.BluetoothNetwork
                        .findNearestDevice(lvl, here,
                                com.apocscode.byteblock.network.BluetoothNetwork.DeviceType.CHARGING_STATION);
                if (pad == null) return LuaValue.FALSE;
                int ch = args.narg() >= 1 ? args.checkint(1) : os.getBluetoothChannel();
                // Hover one block above the pad so the station's AABB.inflate(3) covers it.
                String msg = "drone:waypoint:" + pad.getX() + ":" + (pad.getY() + 1) + ":" + pad.getZ();
                return LuaValue.valueOf(sendDrone(ch, msg));
            }
        });

        globals.set("drone", drone);
    }

    private boolean sendDrone(int channel, String message) {
        net.minecraft.world.level.Level lvl = os.getLevel();
        net.minecraft.core.BlockPos pos = os.getBlockPos();
        if (lvl == null || pos == null) return false;
        BluetoothNetwork.broadcast(lvl, pos, channel, message);
        return true;
    }

    private void installScannerAPI() {
        LuaTable scanner = new LuaTable();

        // scanner.scan([radius]) — trigger a scan. ≤16 = immediate, >16 = incremental
        scanner.set("scan", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScannerBlockEntity be = findNearestScanner();
                if (be == null) return LuaValue.varargsOf(LuaValue.FALSE,
                        LuaValue.valueOf("No scanner in range"));
                int radius = args.optint(1, -1);
                if (radius > 0 && radius <= 16) {
                    be.performImmediateScan(radius);
                } else {
                    be.startScan();
                }
                return LuaValue.TRUE;
            }
        });

        // scanner.getBlock(x,y,z) — returns block name or nil
        scanner.set("getBlock", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScannerBlockEntity be = findNearestScanner();
                if (be == null) return LuaValue.NIL;
                String block = be.getScanData().getBlock(
                        be.getLevel(), args.checkint(1), args.checkint(2), args.checkint(3));
                return block != null ? LuaValue.valueOf(block) : LuaValue.NIL;
            }
        });

        // scanner.getBlocks(x1,y1,z1,x2,y2,z2) — returns table of {x,y,z,name}
        scanner.set("getBlocks", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScannerBlockEntity be = findNearestScanner();
                if (be == null) return new LuaTable();
                net.minecraft.core.BlockPos from = new net.minecraft.core.BlockPos(
                        args.checkint(1), args.checkint(2), args.checkint(3));
                net.minecraft.core.BlockPos to = new net.minecraft.core.BlockPos(
                        args.checkint(4), args.checkint(5), args.checkint(6));
                java.util.Map<net.minecraft.core.BlockPos, String> blocks =
                        be.getScanData().getBlocksInArea(be.getLevel(), from, to);
                LuaTable result = new LuaTable();
                int i = 1;
                for (java.util.Map.Entry<net.minecraft.core.BlockPos, String> entry : blocks.entrySet()) {
                    LuaTable b = new LuaTable();
                    b.set("x", LuaValue.valueOf(entry.getKey().getX()));
                    b.set("y", LuaValue.valueOf(entry.getKey().getY()));
                    b.set("z", LuaValue.valueOf(entry.getKey().getZ()));
                    b.set("name", LuaValue.valueOf(entry.getValue()));
                    result.set(i++, b);
                }
                return result;
            }
        });

        // scanner.findBlock(name, [radius]) — find nearest block of type
        scanner.set("findBlock", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScannerBlockEntity be = findNearestScanner();
                if (be == null) return LuaValue.NIL;
                String name = args.checkjstring(1);
                int radius = args.optint(2, be.getScanRadius());
                net.minecraft.core.BlockPos found = be.getScanData().findBlock(
                        name, be.getBlockPos(), radius);
                if (found == null) return LuaValue.NIL;
                return LuaValue.varargsOf(new LuaValue[]{
                        LuaValue.valueOf(found.getX()),
                        LuaValue.valueOf(found.getY()),
                        LuaValue.valueOf(found.getZ())
                });
            }
        });

        // scanner.findBlocks(name, [radius]) — find all blocks of type
        scanner.set("findBlocks", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScannerBlockEntity be = findNearestScanner();
                if (be == null) return new LuaTable();
                String name = args.checkjstring(1);
                int radius = args.optint(2, be.getScanRadius());
                java.util.List<net.minecraft.core.BlockPos> found =
                        be.getScanData().findBlocks(name, be.getBlockPos(), radius);
                LuaTable result = new LuaTable();
                int i = 1;
                for (net.minecraft.core.BlockPos pos : found) {
                    LuaTable entry = new LuaTable();
                    entry.set("x", LuaValue.valueOf(pos.getX()));
                    entry.set("y", LuaValue.valueOf(pos.getY()));
                    entry.set("z", LuaValue.valueOf(pos.getZ()));
                    result.set(i++, entry);
                }
                return result;
            }
        });

        // scanner.getEntities([radius]) — returns table of entity snapshots
        scanner.set("getEntities", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScannerBlockEntity be = findNearestScanner();
                if (be == null) return new LuaTable();
                java.util.List<WorldScanData.EntitySnapshot> entities = be.getScanData().getEntities();
                int radius = args.optint(1, be.getScanRadius());
                long radiusSq = (long) radius * radius;
                net.minecraft.core.BlockPos origin = be.getBlockPos();

                LuaTable result = new LuaTable();
                int i = 1;
                for (WorldScanData.EntitySnapshot e : entities) {
                    double dx = e.x() - origin.getX();
                    double dy = e.y() - origin.getY();
                    double dz = e.z() - origin.getZ();
                    if (dx * dx + dy * dy + dz * dz > radiusSq) continue;

                    LuaTable entry = new LuaTable();
                    entry.set("type", LuaValue.valueOf(e.type()));
                    entry.set("name", LuaValue.valueOf(e.name()));
                    entry.set("x", LuaValue.valueOf(e.x()));
                    entry.set("y", LuaValue.valueOf(e.y()));
                    entry.set("z", LuaValue.valueOf(e.z()));
                    entry.set("health", LuaValue.valueOf(e.health()));
                    entry.set("maxHealth", LuaValue.valueOf(e.maxHealth()));
                    entry.set("isPlayer", LuaValue.valueOf(e.isPlayer()));
                    entry.set("uuid", LuaValue.valueOf(e.uuid()));
                    result.set(i++, entry);
                }
                return result;
            }
        });

        // scanner.getPlayers([radius]) — returns table of player snapshots
        scanner.set("getPlayers", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScannerBlockEntity be = findNearestScanner();
                if (be == null) return new LuaTable();
                java.util.List<WorldScanData.EntitySnapshot> players = be.getScanData().getPlayers();
                int radius = args.optint(1, be.getScanRadius());
                long radiusSq = (long) radius * radius;
                net.minecraft.core.BlockPos origin = be.getBlockPos();

                LuaTable result = new LuaTable();
                int i = 1;
                for (WorldScanData.EntitySnapshot p : players) {
                    double dx = p.x() - origin.getX();
                    double dy = p.y() - origin.getY();
                    double dz = p.z() - origin.getZ();
                    if (dx * dx + dy * dy + dz * dz > radiusSq) continue;

                    LuaTable entry = new LuaTable();
                    entry.set("name", LuaValue.valueOf(p.name()));
                    entry.set("x", LuaValue.valueOf(p.x()));
                    entry.set("y", LuaValue.valueOf(p.y()));
                    entry.set("z", LuaValue.valueOf(p.z()));
                    entry.set("uuid", LuaValue.valueOf(p.uuid()));
                    result.set(i++, entry);
                }
                return result;
            }
        });

        // scanner.isPassable(x,y,z) — true if entities can pass through
        scanner.set("isPassable", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScannerBlockEntity be = findNearestScanner();
                if (be == null) return LuaValue.NIL;
                return LuaValue.valueOf(be.getScanData().isPassable(
                        be.getLevel(), args.checkint(1), args.checkint(2), args.checkint(3)));
            }
        });

        // scanner.isSolid(x,y,z) — true if block has collision
        scanner.set("isSolid", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScannerBlockEntity be = findNearestScanner();
                if (be == null) return LuaValue.NIL;
                return LuaValue.valueOf(be.getScanData().isSolid(
                        be.getLevel(), args.checkint(1), args.checkint(2), args.checkint(3)));
            }
        });

        // scanner.getPath(x1,y1,z1, x2,y2,z2, [mode]) — A* pathfinding
        // mode: "walk" (default) or "fly"
        scanner.set("getPath", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScannerBlockEntity be = findNearestScanner();
                if (be == null) return new LuaTable();
                net.minecraft.core.BlockPos start = new net.minecraft.core.BlockPos(
                        args.checkint(1), args.checkint(2), args.checkint(3));
                net.minecraft.core.BlockPos end = new net.minecraft.core.BlockPos(
                        args.checkint(4), args.checkint(5), args.checkint(6));
                String modeStr = args.optjstring(7, "walk");
                PathfindingEngine.PathMode mode = modeStr.equalsIgnoreCase("fly")
                        ? PathfindingEngine.PathMode.FLY
                        : PathfindingEngine.PathMode.WALK;

                java.util.List<net.minecraft.core.BlockPos> path =
                        PathfindingEngine.findPath(be.getScanData(), be.getLevel(), start, end, mode);
                LuaTable result = new LuaTable();
                int i = 1;
                for (net.minecraft.core.BlockPos pos : path) {
                    LuaTable wp = new LuaTable();
                    wp.set("x", LuaValue.valueOf(pos.getX()));
                    wp.set("y", LuaValue.valueOf(pos.getY()));
                    wp.set("z", LuaValue.valueOf(pos.getZ()));
                    result.set(i++, wp);
                }
                return result;
            }
        });

        // scanner.getRadius() — current scan radius
        scanner.set("getRadius", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                ScannerBlockEntity be = findNearestScanner();
                return be != null ? LuaValue.valueOf(be.getScanRadius()) : LuaValue.valueOf(0);
            }
        });

        // scanner.setRadius(r) — set scan radius (1-128)
        scanner.set("setRadius", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                ScannerBlockEntity be = findNearestScanner();
                if (be != null) be.setScanRadius(arg.checkint());
                return NONE;
            }
        });

        // scanner.getProgress() — scan progress 0-100
        scanner.set("getProgress", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                ScannerBlockEntity be = findNearestScanner();
                return be != null ? LuaValue.valueOf(be.getScanProgress()) : LuaValue.valueOf(100);
            }
        });

        // scanner.isScanning() — true if background scan in progress
        scanner.set("isScanning", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                ScannerBlockEntity be = findNearestScanner();
                return be != null ? LuaValue.valueOf(be.isScanning()) : LuaValue.FALSE;
            }
        });

        // scanner.getBlockCount() — number of cached blocks
        scanner.set("getBlockCount", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                ScannerBlockEntity be = findNearestScanner();
                return be != null ? LuaValue.valueOf(be.getScanData().getScannedBlockCount())
                        : LuaValue.valueOf(0);
            }
        });

        // scanner.getPosition() — scanner block position (x,y,z)
        scanner.set("getPosition", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScannerBlockEntity be = findNearestScanner();
                if (be == null) return LuaValue.NIL;
                net.minecraft.core.BlockPos pos = be.getBlockPos();
                return LuaValue.varargsOf(new LuaValue[]{
                        LuaValue.valueOf(pos.getX()),
                        LuaValue.valueOf(pos.getY()),
                        LuaValue.valueOf(pos.getZ())
                });
            }
        });

        globals.set("scanner", scanner);
    }

    // ── peripheral API ────────────────────────────────────────────────────

    private void installPeripheralAPI() {
        LuaTable peripheral = new LuaTable();

        // peripheral.isPresent(side) → boolean
        peripheral.set("isPresent", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue side) {
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return LuaValue.FALSE;
                return LuaValue.valueOf(
                    com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                        .findBySide(lvl, pos, side.checkjstring()) != null);
            }
        });

        // peripheral.getType(side) → string | nil
        peripheral.set("getType", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue side) {
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return LuaValue.NIL;
                var result = com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                        .findBySide(lvl, pos, side.checkjstring());
                return result != null ? LuaValue.valueOf(result.getType()) : LuaValue.NIL;
            }
        });

        // peripheral.getMethods(side) → table of method name strings
        peripheral.set("getMethods", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue side) {
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                LuaTable methods = new LuaTable();
                if (lvl == null || pos == null) return methods;
                var result = com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                        .findBySide(lvl, pos, side.checkjstring());
                if (result == null) return methods;
                org.luaj.vm2.LuaTable tbl = result.buildTable(os);
                int i = 1;
                for (org.luaj.vm2.LuaValue key = tbl.next(LuaValue.NIL).arg1();
                     !key.isnil();
                     key = tbl.next(key).arg1()) {
                    methods.set(i++, key);
                }
                return methods;
            }
        });

        // peripheral.wrap(side) → method table | nil
        peripheral.set("wrap", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue side) {
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return LuaValue.NIL;
                var result = com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                        .findBySide(lvl, pos, side.checkjstring());
                if (result == null) return LuaValue.NIL;
                return result.buildTable(os);
            }
        });

        // peripheral.call(side, method, ...) → result
        peripheral.set("call", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return LuaValue.NIL;
                var result = com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                        .findBySide(lvl, pos, args.checkjstring(1));
                if (result == null) return LuaValue.NIL;
                org.luaj.vm2.LuaTable tbl = result.buildTable(os);
                LuaValue fn = tbl.get(args.checkjstring(2));
                if (fn.isnil() || !fn.isfunction()) return LuaValue.NIL;
                return fn.invoke(args.subargs(3));
            }
        });

        // peripheral.find(type [, filter]) — returns wrap tables for every
        // attached peripheral matching the type, as multiple return values.
        // Optional filter(name, wrapped) -> bool further narrows the set.
        // CC semantics: returns nothing when no matches (so `local p = ...` is nil).
        peripheral.set("find", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String wantedType = args.checkjstring(1);
                LuaValue filter = args.arg(2);
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return NONE;
                java.util.List<LuaValue> matches = new java.util.ArrayList<>();
                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    var result = com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                            .find(lvl, pos, dir);
                    if (result == null) continue;
                    if (!wantedType.equals(result.getType())) continue;
                    LuaTable tbl = result.buildTable(os);
                    if (filter.isfunction()) {
                        String name = com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                                .directionToSide(dir);
                        LuaValue keep = filter.call(LuaValue.valueOf(name), tbl);
                        if (!keep.toboolean()) continue;
                    }
                    matches.add(tbl);
                }
                if (matches.isEmpty()) return NONE;
                return LuaValue.varargsOf(matches.toArray(new LuaValue[0]));
            }
        });

        // peripheral.getSides() → table of side names that have a peripheral
        peripheral.set("getSides", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable sides = new LuaTable();
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return sides;
                int i = 1;
                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    var result = com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                            .find(lvl, pos, dir);
                    if (result != null) {
                        sides.set(i++, LuaValue.valueOf(
                            com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                                .directionToSide(dir)));
                    }
                }
                return sides;
            }
        });

        globals.set("peripheral", peripheral);
    }

    /**
     * glasses.* — push HUD widgets to Smart Glasses within BT range on a matching channel.
     *
     *   glasses.setChannel(n)
     *   glasses.getChannel()
     *   glasses.clear()                                    -- clears pending widget list (client also clears on flush)
     *   glasses.addText (id, label, value)
     *   glasses.addBar  (id, label, min, max, value, hexColor)
     *   glasses.addGauge(id, label, min, max, value, hexColor)
     *   glasses.addLight(id, label, hexColor, [value])
     *   glasses.addAlert(id, text, hexColor)
     *   glasses.addTitle(id, text, hexColor)
     *   glasses.addSpark(id, label, {n,n,...}, hexColor)
     *   glasses.set(id, value)                             -- update existing widget value
     *   glasses.flush()                                    -- push list to paired wearers; returns count reached
     *   glasses.wipe()                                     -- clears wearers' HUDs on matching channel
     */
    private void installGlassesAPI() {
        LuaTable glasses = new LuaTable();

        glasses.set("setChannel", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                int ch = v.checkint();
                if (ch < 0) ch = 0; else if (ch > 255) ch = 255;
                glassesChannel = ch;
                return LuaValue.NIL;
            }
        });
        glasses.set("getChannel", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(glassesChannel); }
        });
        glasses.set("clear", new ZeroArgFunction() {
            @Override public LuaValue call() { glassesWidgets.clear(); return LuaValue.NIL; }
        });

        glasses.set("addText", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("text", a.checkjstring(1));
                w.label = a.optjstring(2, "");
                w.value = a.isnil(3) ? "" : a.arg(3).tojstring();
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        glasses.set("addBar", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("bar", a.checkjstring(1));
                w.label = a.optjstring(2, "");
                w.min = a.optdouble(3, 0.0);
                w.max = a.optdouble(4, 1.0);
                w.num = a.optdouble(5, 0.0);
                w.color = parseColor(a.arg(6), 0x2AA7FF);
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        glasses.set("addGauge", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("gauge", a.checkjstring(1));
                w.label = a.optjstring(2, "");
                w.min = a.optdouble(3, 0.0);
                w.max = a.optdouble(4, 1.0);
                w.num = a.optdouble(5, 0.0);
                w.color = parseColor(a.arg(6), 0x00FF88);
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        glasses.set("addLight", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("light", a.checkjstring(1));
                w.label = a.optjstring(2, "");
                w.color = parseColor(a.arg(3), 0x00FF00);
                w.value = a.isnil(4) ? "" : a.arg(4).tojstring();
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        glasses.set("addAlert", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("alert", a.checkjstring(1));
                w.label = a.optjstring(2, "");
                w.color = parseColor(a.arg(3), 0xFF3030);
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        glasses.set("addTitle", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("title", a.checkjstring(1));
                w.label = a.optjstring(2, "");
                w.color = parseColor(a.arg(3), 0xFFFFFF);
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        glasses.set("addSpark", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("spark", a.checkjstring(1));
                w.label = a.optjstring(2, "");
                LuaValue t = a.arg(3);
                if (t.istable()) {
                    LuaTable tt = t.checktable();
                    int n = tt.length();
                    double[] arr = new double[n];
                    for (int i = 0; i < n; i++) arr[i] = tt.get(i + 1).optdouble(0.0);
                    w.spark = arr;
                }
                w.color = parseColor(a.arg(4), 0xFFDD00);
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        // --- Tier 2 widgets ---
        // glasses.addPie(id, label, pct, hexColor)  — 0..1 progress pie
        glasses.set("addPie", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("pie", a.checkjstring(1));
                w.label = a.optjstring(2, "");
                w.num   = a.optdouble(3, 0.0);
                w.min   = 0.0; w.max = 1.0;
                w.color = parseColor(a.arg(4), 0x33DD44);
                w.height = 24;
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        // glasses.addCompass(id, headingDegrees, hexColor)  — 0..360, arrow pointing
        glasses.set("addCompass", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("compass", a.checkjstring(1));
                w.num = a.optdouble(2, 0.0);
                w.color = parseColor(a.arg(3), 0xFF3030);
                w.height = 24;
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        // glasses.addTimer(id, label, remainingSeconds, hexColor)  — countdown
        glasses.set("addTimer", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("timer", a.checkjstring(1));
                w.label = a.optjstring(2, "");
                w.num   = a.optdouble(3, 0.0);
                w.color = parseColor(a.arg(4), 0xFFDD00);
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        // glasses.addAlertT(id, text, hexColor, timeoutSeconds)  — alert with auto-expiry
        glasses.set("addAlertT", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("alert", a.checkjstring(1));
                w.label = a.optjstring(2, "");
                w.color = parseColor(a.arg(3), 0xFF3030);
                double t = a.optdouble(4, 0.0);
                if (t > 0) w.expireMs = System.currentTimeMillis() + (long)(t * 1000.0);
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        // glasses.addMinimap(id, cx, cz, scale, {pt1x,pt1z,pt1color, pt2x,pt2z,pt2color, ...}, hexColor)
        glasses.set("addMinimap", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("minimap", a.checkjstring(1));
                w.num  = a.optdouble(2, 0.0);   // center x
                w.num2 = a.optdouble(3, 0.0);   // center z
                w.max  = a.optdouble(4, 64.0);  // scale (blocks-radius)
                LuaValue pts = a.arg(5);
                if (pts.istable()) {
                    LuaTable tt = pts.checktable();
                    int n = tt.length();
                    double[] arr = new double[n];
                    for (int i = 0; i < n; i++) arr[i] = tt.get(i + 1).optdouble(0.0);
                    w.points = arr;
                }
                w.color = parseColor(a.arg(6), 0x2AA7FF);
                w.height = 56;
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        // glasses.addGraph(id, label, {values}, hexColor) — larger line graph with min/max labels
        glasses.set("addGraph", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("graph", a.checkjstring(1));
                w.label = a.optjstring(2, "");
                LuaValue t = a.arg(3);
                if (t.istable()) {
                    LuaTable tt = t.checktable();
                    int n = tt.length();
                    double[] arr = new double[n];
                    for (int i = 0; i < n; i++) arr[i] = tt.get(i + 1).optdouble(0.0);
                    w.spark = arr;
                }
                w.color = parseColor(a.arg(4), 0x00E5FF);
                w.height = 32;
                glassesWidgets.add(w);
                return LuaValue.NIL;
            }
        });
        glasses.set("set", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String id = a.checkjstring(1);
                LuaValue v = a.arg(2);
                for (GlassesHudAPI.Widget w : glassesWidgets) {
                    if (id.equals(w.id)) {
                        if (v.isnumber()) w.num = v.todouble();
                        w.value = v.tojstring();
                        break;
                    }
                }
                return LuaValue.NIL;
            }
        });
        glasses.set("flush", new ZeroArgFunction() {
            @Override public LuaValue call() {
                int sent = GlassesHudAPI.push(os.getLevel(), os.getBlockPos(), glassesChannel, glassesWidgets);
                return LuaValue.valueOf(sent);
            }
        });
        glasses.set("diag", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(GlassesHudAPI.lastDiag);
            }
        });
        glasses.set("wipe", new ZeroArgFunction() {
            @Override public LuaValue call() {
                int sent = GlassesHudAPI.clear(os.getLevel(), os.getBlockPos(), glassesChannel);
                return LuaValue.valueOf(sent);
            }
        });

        // ──────────────────────────────────────────────────────────────────
        // glasses.canvas() — true-color free-form 2D canvas. Returns a
        // builder table with chainable draw methods. Call :add() to push
        // it into the widget queue, then glasses.flush() to broadcast.
        //
        // Example:
        //   local c = glasses.canvas()
        //   c:rect(20,20, 100,40, 0x224488, true)
        //   c:circle(70,40, 12, 0xFFCC00, true)
        //   c:text(28,28, "HUD", 0xFFFFFF)
        //   c:add(); glasses.flush()
        // ──────────────────────────────────────────────────────────────────
        glasses.set("canvas", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String id = args.optjstring(1, "canvas_" + glassesWidgets.size());
                final java.util.ArrayList<Double> ops = new java.util.ArrayList<>(64);
                LuaTable c = new LuaTable();

                c.set("pixel", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        // self,x,y,color
                        ops.add(1.0); ops.add(a.optdouble(2,0)); ops.add(a.optdouble(3,0));
                        ops.add((double)(parseColor(a.arg(4), 0xFFFFFF) & 0xFFFFFF));
                        return a.arg(1);
                    }
                });
                c.set("line", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        ops.add(2.0);
                        ops.add(a.optdouble(2,0)); ops.add(a.optdouble(3,0));
                        ops.add(a.optdouble(4,0)); ops.add(a.optdouble(5,0));
                        ops.add((double)(parseColor(a.arg(6), 0xFFFFFF) & 0xFFFFFF));
                        return a.arg(1);
                    }
                });
                LuaFunction rectFn = new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        ops.add(3.0);
                        ops.add(a.optdouble(2,0)); ops.add(a.optdouble(3,0));
                        ops.add(a.optdouble(4,0)); ops.add(a.optdouble(5,0));
                        ops.add((double)(parseColor(a.arg(6), 0xFFFFFF) & 0xFFFFFF));
                        ops.add(a.optboolean(7, false) ? 1.0 : 0.0);
                        return a.arg(1);
                    }
                };
                c.set("rect", rectFn);
                c.set("fillRect", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        ops.add(3.0);
                        ops.add(a.optdouble(2,0)); ops.add(a.optdouble(3,0));
                        ops.add(a.optdouble(4,0)); ops.add(a.optdouble(5,0));
                        ops.add((double)(parseColor(a.arg(6), 0xFFFFFF) & 0xFFFFFF));
                        ops.add(1.0);
                        return a.arg(1);
                    }
                });
                c.set("circle", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        ops.add(4.0);
                        ops.add(a.optdouble(2,0)); ops.add(a.optdouble(3,0)); ops.add(a.optdouble(4,1));
                        ops.add((double)(parseColor(a.arg(5), 0xFFFFFF) & 0xFFFFFF));
                        ops.add(a.optboolean(6, false) ? 1.0 : 0.0);
                        return a.arg(1);
                    }
                });
                c.set("fillCircle", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        ops.add(4.0);
                        ops.add(a.optdouble(2,0)); ops.add(a.optdouble(3,0)); ops.add(a.optdouble(4,1));
                        ops.add((double)(parseColor(a.arg(5), 0xFFFFFF) & 0xFFFFFF));
                        ops.add(1.0);
                        return a.arg(1);
                    }
                });
                c.set("triangle", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        ops.add(5.0);
                        ops.add(a.optdouble(2,0)); ops.add(a.optdouble(3,0));
                        ops.add(a.optdouble(4,0)); ops.add(a.optdouble(5,0));
                        ops.add(a.optdouble(6,0)); ops.add(a.optdouble(7,0));
                        ops.add((double)(parseColor(a.arg(8), 0xFFFFFF) & 0xFFFFFF));
                        ops.add(a.optboolean(9, false) ? 1.0 : 0.0);
                        return a.arg(1);
                    }
                });
                c.set("poly", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        // self, {x1,y1,x2,y2,...}, color, filled
                        LuaValue tv = a.arg(2);
                        if (!tv.istable()) return a.arg(1);
                        LuaTable t = tv.checktable();
                        int len = t.length();
                        int n = len / 2;
                        if (n < 2) return a.arg(1);
                        ops.add(6.0);
                        ops.add((double) n);
                        for (int k = 1; k <= n * 2; k++) ops.add(t.get(k).optdouble(0));
                        ops.add((double)(parseColor(a.arg(3), 0xFFFFFF) & 0xFFFFFF));
                        ops.add(a.optboolean(4, false) ? 1.0 : 0.0);
                        return a.arg(1);
                    }
                });
                c.set("text", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        // self, x, y, str, color
                        ops.add(7.0);
                        ops.add(a.optdouble(2,0)); ops.add(a.optdouble(3,0));
                        ops.add((double)(parseColor(a.arg(5), 0xFFFFFF) & 0xFFFFFF));
                        String s = a.optjstring(4, "");
                        ops.add((double) s.length());
                        for (int k = 0; k < s.length(); k++) ops.add((double)(int) s.charAt(k));
                        return a.arg(1);
                    }
                });
                c.set("gradient", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        // self, x, y, w, h, c1, c2, vertical
                        ops.add(8.0);
                        ops.add(a.optdouble(2,0)); ops.add(a.optdouble(3,0));
                        ops.add(a.optdouble(4,0)); ops.add(a.optdouble(5,0));
                        ops.add((double)(parseColor(a.arg(6), 0x000000) & 0xFFFFFF));
                        ops.add((double)(parseColor(a.arg(7), 0xFFFFFF) & 0xFFFFFF));
                        ops.add(a.optboolean(8, true) ? 1.0 : 0.0);
                        return a.arg(1);
                    }
                });
                c.set("bezier", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        // self, x1, y1, cx, cy, x2, y2, color
                        ops.add(9.0);
                        ops.add(a.optdouble(2,0)); ops.add(a.optdouble(3,0));
                        ops.add(a.optdouble(4,0)); ops.add(a.optdouble(5,0));
                        ops.add(a.optdouble(6,0)); ops.add(a.optdouble(7,0));
                        ops.add((double)(parseColor(a.arg(8), 0xFFFFFF) & 0xFFFFFF));
                        return a.arg(1);
                    }
                });
                c.set("image", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        // self, img-table {w=,h=,pixels={...}}, x, y, scale
                        LuaValue iv = a.arg(2);
                        if (!iv.istable()) return a.arg(1);
                        LuaTable t = iv.checktable();
                        int iw = t.get("w").optint(0);
                        int ih = t.get("h").optint(0);
                        LuaValue pv = t.get("pixels");
                        if (iw <= 0 || ih <= 0 || !pv.istable()) return a.arg(1);
                        LuaTable px = pv.checktable();
                        int count = iw * ih;
                        if (px.length() < count) return a.arg(1);
                        ops.add(10.0);
                        ops.add((double) iw); ops.add((double) ih);
                        ops.add(a.optdouble(3, 0)); ops.add(a.optdouble(4, 0));
                        ops.add((double) Math.max(1, a.optint(5, 1)));
                        for (int k = 1; k <= count; k++) ops.add(px.get(k).optdouble(-1));
                        return a.arg(1);
                    }
                });
                c.set("clear", new ZeroArgFunction() {
                    @Override public LuaValue call() { ops.clear(); return c; }
                });
                c.set("add", new ZeroArgFunction() {
                    @Override public LuaValue call() {
                        GlassesHudAPI.Widget w = new GlassesHudAPI.Widget("canvas", id);
                        double[] arr = new double[ops.size()];
                        for (int k = 0; k < arr.length; k++) arr[k] = ops.get(k);
                        w.points = arr;
                        glassesWidgets.add(w);
                        return LuaValue.valueOf(arr.length);
                    }
                });
                return c;
            }
        });

        globals.set("glasses", glasses);
    }

    private static int parseColor(LuaValue v, int fallback) {
        if (v == null || v.isnil()) return fallback;
        if (v.isnumber()) return v.toint() & 0xFFFFFF;
        String s = v.tojstring().trim();
        if (s.isEmpty()) return fallback;
        if (s.startsWith("#")) s = s.substring(1);
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        // named colors
        switch (s.toLowerCase()) {
            case "red":     return 0xFF3030;
            case "green":   return 0x33DD44;
            case "blue":    return 0x2AA7FF;
            case "yellow":  return 0xFFDD00;
            case "orange":  return 0xFF8800;
            case "cyan":    return 0x00E5FF;
            case "magenta": return 0xFF33AA;
            case "white":   return 0xFFFFFF;
            case "gray":
            case "grey":    return 0x888888;
            case "black":   return 0x101010;
            case "purple":  return 0x9B30FF;
        }
        try { return Integer.parseInt(s, 16) & 0xFFFFFF; }
        catch (NumberFormatException ex) { return fallback; }
    }

    /** Find nearest Scanner block entity via Bluetooth network. */
    private ScannerBlockEntity findNearestScanner() {
        net.minecraft.world.level.Level level = os.getLevel();
        net.minecraft.core.BlockPos computerPos = os.getBlockPos();
        if (level == null || computerPos == null) return null;

        java.util.List<BluetoothNetwork.DeviceEntry> devices =
                BluetoothNetwork.getDevicesInRange(level, computerPos, BluetoothNetwork.BLOCK_RANGE);

        ScannerBlockEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (BluetoothNetwork.DeviceEntry d : devices) {
            if (d.type() != BluetoothNetwork.DeviceType.SCANNER) continue;
            double dist = computerPos.distSqr(d.pos());
            if (dist < nearestDist) {
                net.minecraft.world.level.block.entity.BlockEntity be =
                        level.getBlockEntity(d.pos());
                if (be instanceof ScannerBlockEntity s) {
                    nearest = s;
                    nearestDist = dist;
                }
            }
        }
        return nearest;
    }

    private static int sideToIndex(String side, String[] sides) {
        for (int i = 0; i < sides.length; i++) {
            if (sides[i].equalsIgnoreCase(side)) return i;
        }
        return -1;
    }

    // --- CC Color Mapping ---
    // CC uses power-of-2 bitmask colors, we use 0-15 indices

    private int ccColorToIndex(int ccColor) {
        return staticCcColorToIndex(ccColor);
    }

    private static int staticCcColorToIndex(int ccColor) {
        if (ccColor <= 0) return 0;
        int bit = 0;
        int v = ccColor;
        while (v > 1) { v >>= 1; bit++; }
        return Math.min(15, bit);
    }

    private int indexToCcColor(int index) {
        return 1 << (index & 15);
    }

    // --- Execution ---

    /**
     * Execute a Lua script string. Returns the result or null on error.
     */
    public LuaValue execute(String code, String chunkName) {
        try {
            InputStream is = new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8));
            LuaValue chunk = globals.load(is, chunkName, "bt", globals);
            return chunk.call();
        } catch (LuaError e) {
            pushOutput("Lua Error: " + e.getMessage());
            return null;
        } catch (Exception e) {
            pushOutput("Error: " + e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // Coroutine event-loop (CC-style pullEvent/queueEvent/sleep/read/parallel)
    // ========================================================================

    /**
     * Kick off a long-running program as a Lua coroutine. Does NOT block.
     * Call {@link #pump()} or {@link #queueEvent(String, Object...)} to drive it.
     * If the chunk never yields, it runs to completion here and is done on return.
     */
    public void runProgram(String code, String chunkName) {
        if (programThread != null) {
            queueEvent("terminate");
            programThread = null;
            programYielded = false;
            programEventQueue.clear();
        }
        try {
            InputStream is = new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8));
            LuaValue chunk = globals.load(is, chunkName, "bt", globals);
            LuaThread t = new LuaThread(globals, chunk);
            programThread = t;
            programYielded = false;
            resumeProgram(LuaValue.NONE);
        } catch (LuaError e) {
            pushOutput("Lua Error: " + e.getMessage() + "\n");
            programThread = null;
        } catch (Exception e) {
            pushOutput("Error: " + e.getMessage() + "\n");
            programThread = null;
        }
    }

    /** True while a coroutine-based program is running (possibly suspended). */
    public boolean isProgramRunning() {
        return programThread != null || !msTasks.isEmpty();
    }

    /**
     * Push a CC-style event into the program's event queue and drive the pump.
     */
    public void queueEvent(String name, Object... args) {
        LuaValue[] vals = new LuaValue[args.length + 1];
        vals[0] = LuaValue.valueOf(name);
        for (int i = 0; i < args.length; i++) {
            vals[i + 1] = toLua(args[i]);
        }
        Varargs va = LuaValue.varargsOf(vals);
        if (programThread != null) programEventQueue.offer(va);
        for (MsTask t : msTasks) if (t.alive) t.queue.offer(va);
        pump();
    }

    /** Drive the program coroutine and all launched multishell tasks. */
    public void pump() {
        boolean progress = true;
        while (progress) {
            progress = false;
            // Primary shell task.
            while (programThread != null && programYielded && !programEventQueue.isEmpty()) {
                resumeProgram(programEventQueue.poll());
                progress = true;
            }
            // Multishell launched tasks.
            for (MsTask t : msTasks) {
                if (!t.alive) continue;
                while (t.yielded && !t.queue.isEmpty()) {
                    resumeMsTask(t, t.queue.poll());
                    progress = true;
                    if (!t.alive) break;
                }
            }
            msTasks.removeIf(t -> !t.alive);
        }
    }

    private void resumeMsTask(MsTask t, Varargs args) {
        try {
            Varargs r = t.thread.resume(args);
            if (!r.arg(1).toboolean()) {
                String err = r.arg(2).isnil() ? "unknown error" : r.arg(2).tojstring();
                pushOutput("[" + t.title + "] Lua Error: " + err + "\n");
                t.alive = false;
                return;
            }
            if (t.thread.state == null || t.thread.state.status == LuaThread.STATUS_DEAD) {
                t.alive = false;
            } else {
                t.yielded = true;
            }
        } catch (Throwable th) {
            pushOutput("[" + t.title + "] Runtime error: " + th.getMessage() + "\n");
            t.alive = false;
        }
    }

    /** Launch a new multishell task from Lua source code. Returns assigned id. */
    int msLaunch(String code, String chunkName, String title, Varargs progArgs) {
        try {
            InputStream is = new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8));
            final LuaValue chunk = globals.load(is, chunkName, "bt", globals);
            // Wrap chunk so its varargs are program args.
            final Varargs pa = progArgs;
            LuaValue wrapped = new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) { return chunk.invoke(pa); }
            };
            LuaThread th = new LuaThread(globals, wrapped);
            MsTask task = new MsTask(msNextId++, title, th);
            msTasks.add(task);
            if (msFocusId == 0) msFocusId = task.id;
            // Initial resume.
            resumeMsTask(task, LuaValue.NONE);
            return task.id;
        } catch (Exception e) {
            pushOutput("Launch failed: " + e.getMessage() + "\n");
            return -1;
        }
    }

    private void resumeProgram(Varargs args) {
        LuaThread t = programThread;
        if (t == null) return;
        try {
            Varargs r = t.resume(args);
            boolean ok = r.arg(1).toboolean();
            if (!ok) {
                String err = r.arg(2).isnil() ? "unknown error" : r.arg(2).tojstring();
                pushOutput("Lua Error: " + err + "\n");
                programThread = null;
                programYielded = false;
                return;
            }
            LuaThread.State st = t.state;
            if (st == null || st.status == LuaThread.STATUS_DEAD) {
                programThread = null;
                programYielded = false;
            } else {
                programYielded = true;
            }
        } catch (Throwable th) {
            pushOutput("Runtime error: " + th.getMessage() + "\n");
            programThread = null;
            programYielded = false;
        }
    }

    private static LuaValue toLua(Object o) {
        if (o == null) return LuaValue.NIL;
        if (o instanceof LuaValue) return (LuaValue) o;
        if (o instanceof String)   return LuaValue.valueOf((String) o);
        if (o instanceof Integer)  return LuaValue.valueOf((int) (Integer) o);
        if (o instanceof Long)     return LuaValue.valueOf((long) (Long) o);
        if (o instanceof Double)   return LuaValue.valueOf((double) (Double) o);
        if (o instanceof Float)    return LuaValue.valueOf((double) (float) (Float) o);
        if (o instanceof Boolean)  return LuaValue.valueOf((boolean) (Boolean) o);
        return LuaValue.valueOf(o.toString());
    }

    /**
     * Execute a single REPL expression/statement and return display text.
     */
    public String eval(String input) {
        try {
            // Try as expression first (like CC's lua shell)
            InputStream exprStream = new ByteArrayInputStream(
                ("return " + input).getBytes(StandardCharsets.UTF_8));
            try {
                LuaValue chunk = globals.load(exprStream, "=input", "bt", globals);
                LuaValue result = chunk.call();
                if (result != null && !result.isnil()) {
                    return result.tojstring();
                }
                return null;
            } catch (LuaError ignored) {
                // Fall through to statement
            }

            // Try as statement
            InputStream stmtStream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
            LuaValue chunk = globals.load(stmtStream, "=input", "bt", globals);
            chunk.call();
            return null;
        } catch (LuaError e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Drain pending output lines.
     */
    public String drainOutput() {
        if (outputQueue.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = outputQueue.poll()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private void pushOutput(String text) {
        outputQueue.offer(text);
    }

    public Globals getGlobals() {
        return globals;
    }

    // --- Helpers ---

    private static String luaToString(LuaValue v) {
        if (v.istable()) {
            StringBuilder sb = new StringBuilder("{ ");
            LuaTable t = v.checktable();
            boolean first = true;
            for (LuaValue key = t.next(LuaValue.NIL).arg1(); !key.isnil();
                 key = t.next(key).arg1()) {
                if (!first) sb.append(", ");
                first = false;
                if (key.isinttype()) {
                    sb.append(luaToString(t.get(key)));
                } else {
                    sb.append(key.tojstring()).append(" = ").append(luaToString(t.get(key)));
                }
            }
            sb.append(" }");
            return sb.toString();
        }
        if (v.isstring()) return "\"" + v.tojstring() + "\"";
        return v.tojstring();
    }

    // --- pairs()/ipairs() implementations ---

    private static class PairsFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            LuaTable t = args.checktable(1);
            return LuaValue.varargsOf(new NextFunction(t), t, LuaValue.NIL);
        }
    }

    private static class NextFunction extends VarArgFunction {
        private final LuaTable table;
        NextFunction(LuaTable table) { this.table = table; }
        @Override
        public Varargs invoke(Varargs args) {
            LuaValue key = args.arg(2);
            Varargs n = table.next(key);
            return n.arg1().isnil() ? LuaValue.NIL : n;
        }
    }

    private static class IPairsFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            LuaTable t = args.checktable(1);
            return LuaValue.varargsOf(new INextFunction(t), t, LuaValue.valueOf(0));
        }
    }

    private static class INextFunction extends VarArgFunction {
        private final LuaTable table;
        INextFunction(LuaTable table) { this.table = table; }
        @Override
        public Varargs invoke(Varargs args) {
            int idx = args.checkint(2) + 1;
            LuaValue val = table.get(idx);
            if (val.isnil()) return LuaValue.NIL;
            return LuaValue.varargsOf(LuaValue.valueOf(idx), val);
        }
    }

    // =========================================================================
    //  CC:Tweaked Extension APIs
    //  parallel, vector, keys, paintutils, window, settings, turtle (alias),
    //  http, pastebin, wget, help, plus textutils extensions and read() global.
    // =========================================================================

    private void installCCExtensions() {
        extendTextUtils();
        installParallelAPI();
        installVectorAPI();
        installKeysAPI();
        installPaintutilsAPI();
        installWindowAPI();
        installSettingsAPI();
        installTurtleAlias();
        installHttpAPI();
        installPastebinAPI();
        installWgetGlobal();
        installReadGlobal();
        installHelpAPI();
        installMultishellStub();
        installCCParityGaps();
        installDiskAPI();
        installImageAPI();
        installOpenComputersExtras();
        installPreloadModules();
    }

    // ---------- image.* — procedural image creation + NFP/NFT loaders ----------
    // Images are tables shaped: { w=number, h=number, pixels={ARGB1, ARGB2, ...} }
    // Pixels are 24-bit RGB ints (0xRRGGBB) or -1 for transparent.
    // Compatible with glasses canvas:image() and (via toGrid()) with paintutils.drawImage().
    private void installImageAPI() {
        LuaTable img = new LuaTable();

        img.set("create", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int w = a.checkint(1), h = a.checkint(2);
                int fill = parseColor(a.arg(3), -1);
                LuaTable pixels = new LuaTable();
                int n = w * h;
                for (int i = 1; i <= n; i++) pixels.set(i, LuaValue.valueOf(fill));
                LuaTable t = new LuaTable();
                t.set("w", w); t.set("h", h); t.set("pixels", pixels);
                return t;
            }
        });
        img.set("set", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                LuaTable t = a.checktable(1);
                int x = a.checkint(2), y = a.checkint(3);
                int w = t.get("w").toint();
                int h = t.get("h").toint();
                if (x < 0 || y < 0 || x >= w || y >= h) return LuaValue.NIL;
                int color = parseColor(a.arg(4), 0xFFFFFF);
                t.get("pixels").set(y * w + x + 1, LuaValue.valueOf(color));
                return LuaValue.NIL;
            }
        });
        img.set("get", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                LuaTable t = a.checktable(1);
                int x = a.checkint(2), y = a.checkint(3);
                int w = t.get("w").toint();
                return t.get("pixels").get(y * w + x + 1);
            }
        });
        img.set("size", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                LuaTable t = v.checktable();
                return LuaValue.varargsOf(t.get("w"), t.get("h")).arg1();
            }
        });
        img.set("fill", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                LuaTable t = a.checktable(1);
                int color = parseColor(a.arg(2), -1);
                int w = t.get("w").toint(), h = t.get("h").toint();
                LuaTable px = t.get("pixels").checktable();
                for (int i = 1; i <= w * h; i++) px.set(i, LuaValue.valueOf(color));
                return LuaValue.NIL;
            }
        });
        // image.loadNFP(path) — loads a CC paintutils-style image (.nfp), returns { w, h, pixels }
        // with pixels mapped to the 16-color CC palette as RGB ints.
        img.set("loadNFP", new OneArgFunction() {
            @Override public LuaValue call(LuaValue path) {
                String content = os.getFileSystem().readFile(path.checkjstring());
                LuaTable t = new LuaTable();
                if (content == null) {
                    t.set("w", 0); t.set("h", 0); t.set("pixels", new LuaTable());
                    return t;
                }
                String[] lines = content.split("\n", -1);
                int h = lines.length;
                int w = 0;
                for (String ln : lines) if (ln.length() > w) w = ln.length();
                LuaTable pixels = new LuaTable();
                for (int y = 0; y < h; y++) {
                    String ln = lines[y];
                    for (int x = 0; x < w; x++) {
                        int v = -1;
                        if (x < ln.length()) {
                            char ch = ln.charAt(x);
                            if (ch != ' ') {
                                int idx;
                                try { idx = Integer.parseInt(String.valueOf(ch), 16); }
                                catch (NumberFormatException e) { idx = -1; }
                                if (idx >= 0 && idx < 16) {
                                    int argb = TerminalBuffer.PALETTE[idx];
                                    v = argb & 0xFFFFFF;
                                }
                            }
                        }
                        pixels.set(y * w + x + 1, LuaValue.valueOf(v));
                    }
                }
                t.set("w", w); t.set("h", h); t.set("pixels", pixels);
                return t;
            }
        });
        // image.loadNFT(path) — Loads a CC NFT (foreground/background coded text).
        // Returns { w, h, pixels }; uses background color per cell as the pixel.
        img.set("loadNFT", new OneArgFunction() {
            @Override public LuaValue call(LuaValue path) {
                String content = os.getFileSystem().readFile(path.checkjstring());
                LuaTable t = new LuaTable();
                if (content == null) {
                    t.set("w", 0); t.set("h", 0); t.set("pixels", new LuaTable());
                    return t;
                }
                String[] lines = content.split("\n", -1);
                int h = lines.length;
                int w = 0;
                int[][] rows = new int[h][];
                for (int y = 0; y < h; y++) {
                    String ln = lines[y];
                    java.util.ArrayList<Integer> cells = new java.util.ArrayList<>();
                    int bg = -1;
                    int i = 0;
                    while (i < ln.length()) {
                        char c = ln.charAt(i);
                        if (c == '\30' && i + 1 < ln.length()) { i += 2; /* fg, ignored */ }
                        else if (c == '\31' && i + 1 < ln.length()) {
                            char hc = ln.charAt(i + 1);
                            try {
                                int idx = Integer.parseInt(String.valueOf(hc), 16);
                                bg = TerminalBuffer.PALETTE[idx] & 0xFFFFFF;
                            } catch (Exception ignored) { bg = -1; }
                            i += 2;
                        } else { cells.add(c == ' ' ? bg : bg); i++; }
                    }
                    rows[y] = new int[cells.size()];
                    for (int k = 0; k < cells.size(); k++) rows[y][k] = cells.get(k);
                    if (rows[y].length > w) w = rows[y].length;
                }
                LuaTable pixels = new LuaTable();
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int v = (x < rows[y].length) ? rows[y][x] : -1;
                        pixels.set(y * w + x + 1, LuaValue.valueOf(v));
                    }
                }
                t.set("w", w); t.set("h", h); t.set("pixels", pixels);
                return t;
            }
        });
        // image.toGrid(image) — converts an RGB image to a 2D table of CC palette
        // indices (1-based rows/cols), suitable for paintutils.drawImage / drawSprite.
        img.set("toGrid", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                LuaTable t = v.checktable();
                int w = t.get("w").toint(), h = t.get("h").toint();
                LuaTable px = t.get("pixels").checktable();
                LuaTable out = new LuaTable();
                for (int y = 0; y < h; y++) {
                    LuaTable row = new LuaTable();
                    for (int x = 0; x < w; x++) {
                        int p = px.get(y * w + x + 1).optint(-1);
                        if (p < 0) { row.set(x + 1, LuaValue.valueOf(0)); continue; }
                        int idx = nearestPaletteIndex(p);
                        row.set(x + 1, LuaValue.valueOf(1 << idx));
                    }
                    out.set(y + 1, row);
                }
                return out;
            }
        });
        // image.loadPNG(path [, maxW, maxH]) — decodes PNG/JPEG/GIF/BMP via javax.imageio.
        // Optional maxW/maxH downscale (nearest-neighbor) so HUD-sized images don't blow the stack.
        // Returns { w, h, pixels } table where pixels are 0xRRGGBB ints (-1 for fully transparent).
        img.set("loadPNG", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String path = a.checkjstring(1);
                int maxW = a.optint(2, 0);
                int maxH = a.optint(3, 0);
                LuaTable t = new LuaTable();
                try {
                    byte[] bytes = os.getFileSystem().readBytes(path);
                    if (bytes == null) {
                        t.set("w", 0); t.set("h", 0); t.set("pixels", new LuaTable());
                        return LuaValue.varargsOf(t, LuaValue.valueOf("file not found: " + path));
                    }
                    java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(
                        new java.io.ByteArrayInputStream(bytes));
                    if (src == null) {
                        t.set("w", 0); t.set("h", 0); t.set("pixels", new LuaTable());
                        return LuaValue.varargsOf(t, LuaValue.valueOf("unsupported image format"));
                    }
                    int sw = src.getWidth(), sh = src.getHeight();
                    int w = sw, h = sh;
                    if (maxW > 0 && maxH > 0 && (sw > maxW || sh > maxH)) {
                        double rx = (double) maxW / sw, ry = (double) maxH / sh;
                        double r = Math.min(rx, ry);
                        w = Math.max(1, (int) Math.round(sw * r));
                        h = Math.max(1, (int) Math.round(sh * r));
                    }
                    LuaTable pixels = new LuaTable();
                    for (int y = 0; y < h; y++) {
                        int sy = (h == sh) ? y : Math.min(sh - 1, y * sh / h);
                        for (int x = 0; x < w; x++) {
                            int sx = (w == sw) ? x : Math.min(sw - 1, x * sw / w);
                            int argb = src.getRGB(sx, sy);
                            int alpha = (argb >>> 24) & 0xFF;
                            int v = (alpha < 16) ? -1 : (argb & 0xFFFFFF);
                            pixels.set(y * w + x + 1, LuaValue.valueOf(v));
                        }
                    }
                    t.set("w", w); t.set("h", h); t.set("pixels", pixels);
                    return t;
                } catch (Exception e) {
                    t.set("w", 0); t.set("h", 0); t.set("pixels", new LuaTable());
                    return LuaValue.varargsOf(t, LuaValue.valueOf(e.getMessage()));
                }
            }
        });
        // image.savePNG(image, path) — encode image table to PNG bytes via ImageIO.
        img.set("savePNG", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                LuaTable img = a.checktable(1);
                String path = a.checkjstring(2);
                int w = img.get("w").toint(), h = img.get("h").toint();
                LuaTable px = img.get("pixels").checktable();
                java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                    w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int p = px.get(y * w + x + 1).optint(-1);
                        out.setRGB(x, y, p < 0 ? 0 : (0xFF000000 | (p & 0xFFFFFF)));
                    }
                }
                try {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(out, "png", baos);
                    os.getFileSystem().writeBytes(path, baos.toByteArray());
                    return LuaValue.TRUE;
                } catch (Exception e) {
                    return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(e.getMessage()));
                }
            }
        });

        globals.set("image", img);
    }

    private static int nearestPaletteIndex(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int bestIdx = 0, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < TerminalBuffer.PALETTE.length; i++) {
            int p = TerminalBuffer.PALETTE[i];
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            int dr = pr - r, dg = pg - g, db = pb - b;
            int d = dr * dr + dg * dg + db * db;
            if (d < bestDist) { bestDist = d; bestIdx = i; }
        }
        return bestIdx;
    }

    // ---------- OpenComputers-inspired extras: event.* / text.* / uuid.* ----------
    // Pure-Lua libraries borrowed from OpenComputers (ocdoc.cil.li). They sit
    // alongside the CC:Tweaked APIs without conflict (no globals named "event",
    // "text", or "uuid" exist in CC). Built on our existing primitives so they
    // require no scheduler changes.
    //
    //   event.listen(name, fn)   register persistent listener (fires inside event.pull)
    //   event.ignore(name, fn)   unregister
    //   event.timer(secs, fn[, n])  fire fn after delay, optionally repeated n times
    //   event.cancel(timerId)    stop a pending event.timer
    //   event.pull([timeout][, name][, ...])  CC os.pullEvent + OC timeout/filter args
    //   event.pullFiltered([timeout][, filterFn])  custom filter callback
    //   event.pullMultiple(...)  pull next event matching any of the supplied names
    //   event.push(name, ...)    alias of os.queueEvent
    //
    //   text.padLeft(s, n) / text.padRight(s, n) / text.trim(s)
    //   text.tokenize(s)         split on whitespace runs
    //   text.detab(s, tabWidth)  expand \t to spaces aligned to tabWidth
    //   text.wrap(s, w)          wrap to width, returns (firstLine, rest)
    //
    //   uuid.next()              random UUID v4 (8-4-4-4-12 hex)
    private void installOpenComputersExtras() {
        execute(
            "do\n" +
            "  -- ===== event API (OpenComputers-style) =====\n" +
            "  local listeners = {}        -- name -> array of callbacks\n" +
            "  local timerCallbacks = {}   -- timerId -> { fn, interval, remaining }\n" +
            "  event = {}\n" +
            "  function event.listen(name, fn)\n" +
            "    if type(name) ~= 'string' or type(fn) ~= 'function' then return false end\n" +
            "    local list = listeners[name]\n" +
            "    if not list then list = {}; listeners[name] = list end\n" +
            "    for i = 1, #list do if list[i] == fn then return false end end\n" +
            "    list[#list + 1] = fn\n" +
            "    return true\n" +
            "  end\n" +
            "  function event.ignore(name, fn)\n" +
            "    local list = listeners[name]\n" +
            "    if not list then return false end\n" +
            "    for i = 1, #list do\n" +
            "      if list[i] == fn then table.remove(list, i); return true end\n" +
            "    end\n" +
            "    return false\n" +
            "  end\n" +
            "  -- Internal: dispatch to listeners + handle timer callbacks. Returns\n" +
            "  -- true to forward the event to the caller, false to suppress (a\n" +
            "  -- timer fired its callback and the user shouldn't see it).\n" +
            "  local function dispatch(name, ...)\n" +
            "    if name == 'timer' then\n" +
            "      local id = ...\n" +
            "      local cb = timerCallbacks[id]\n" +
            "      if cb then\n" +
            "        local ok, err = pcall(cb.fn)\n" +
            "        if not ok and event.onError then pcall(event.onError, err) end\n" +
            "        cb.remaining = cb.remaining - 1\n" +
            "        if cb.remaining > 0 and cb.interval > 0 then\n" +
            "          local newId = os.startTimer(cb.interval)\n" +
            "          timerCallbacks[newId] = cb\n" +
            "        end\n" +
            "        timerCallbacks[id] = nil\n" +
            "        return false\n" +
            "      end\n" +
            "    end\n" +
            "    local list = listeners[name]\n" +
            "    if list then\n" +
            "      local i = 1\n" +
            "      while i <= #list do\n" +
            "        local fn = list[i]\n" +
            "        local ok, ret = pcall(fn, name, ...)\n" +
            "        if not ok then\n" +
            "          if event.onError then pcall(event.onError, ret) end\n" +
            "          table.remove(list, i)\n" +
            "        elseif ret == false then\n" +
            "          table.remove(list, i)\n" +
            "        else\n" +
            "          i = i + 1\n" +
            "        end\n" +
            "      end\n" +
            "    end\n" +
            "    return true\n" +
            "  end\n" +
            "  function event.timer(interval, fn, times)\n" +
            "    times = times or 1\n" +
            "    local id = os.startTimer(interval)\n" +
            "    timerCallbacks[id] = { fn = fn, interval = interval, remaining = times }\n" +
            "    return id\n" +
            "  end\n" +
            "  function event.cancel(timerId)\n" +
            "    if timerCallbacks[timerId] then\n" +
            "      timerCallbacks[timerId] = nil\n" +
            "      pcall(os.cancelTimer, timerId)\n" +
            "      return true\n" +
            "    end\n" +
            "    return false\n" +
            "  end\n" +
            "  function event.push(...) return os.queueEvent(...) end\n" +
            "  -- event.pull([timeout][, name][, ...filters])\n" +
            "  function event.pull(...)\n" +
            "    local args = {...}\n" +
            "    local i = 1\n" +
            "    local timeout = nil\n" +
            "    if type(args[1]) == 'number' then timeout = args[1]; i = 2 end\n" +
            "    local nameFilter = args[i]\n" +
            "    local extraFilters = {}\n" +
            "    for j = i + 1, #args do extraFilters[j - i] = args[j] end\n" +
            "    local timerId\n" +
            "    if timeout then timerId = os.startTimer(timeout) end\n" +
            "    while true do\n" +
            "      local ev = { os.pullEventRaw() }\n" +
            "      local name = ev[1]\n" +
            "      if name == 'timer' and timerId and ev[2] == timerId then\n" +
            "        return nil\n" +
            "      end\n" +
            "      local rest = {}; for k = 2, #ev do rest[k-1] = ev[k] end\n" +
            "      local forward = dispatch(name, table.unpack(rest))\n" +
            "      if forward then\n" +
            "        local match = true\n" +
            "        if nameFilter and not string.match(tostring(name), nameFilter) then match = false end\n" +
            "        if match then\n" +
            "          for k, v in pairs(extraFilters) do\n" +
            "            if v ~= nil and rest[k] ~= v then match = false; break end\n" +
            "          end\n" +
            "        end\n" +
            "        if match then\n" +
            "          if timerId then pcall(os.cancelTimer, timerId) end\n" +
            "          return name, table.unpack(rest)\n" +
            "        end\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "  function event.pullFiltered(timeout, filter)\n" +
            "    if type(timeout) == 'function' then filter = timeout; timeout = nil end\n" +
            "    local timerId\n" +
            "    if timeout then timerId = os.startTimer(timeout) end\n" +
            "    while true do\n" +
            "      local ev = { os.pullEventRaw() }\n" +
            "      local name = ev[1]\n" +
            "      if name == 'timer' and timerId and ev[2] == timerId then return nil end\n" +
            "      local rest = {}; for k = 2, #ev do rest[k-1] = ev[k] end\n" +
            "      if dispatch(name, table.unpack(rest)) then\n" +
            "        if not filter or filter(name, table.unpack(rest)) then\n" +
            "          if timerId then pcall(os.cancelTimer, timerId) end\n" +
            "          return name, table.unpack(rest)\n" +
            "        end\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "  function event.pullMultiple(...)\n" +
            "    local names = {...}\n" +
            "    local set = {}; for _, n in ipairs(names) do set[n] = true end\n" +
            "    while true do\n" +
            "      local ev = { os.pullEventRaw() }\n" +
            "      local name = ev[1]\n" +
            "      local rest = {}; for k = 2, #ev do rest[k-1] = ev[k] end\n" +
            "      if dispatch(name, table.unpack(rest)) and set[name] then\n" +
            "        return name, table.unpack(rest)\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "\n" +
            "  -- ===== text API (OpenComputers-style) =====\n" +
            "  text = {}\n" +
            "  function text.padRight(s, n)\n" +
            "    s = tostring(s); if #s >= n then return s end\n" +
            "    return s .. string.rep(' ', n - #s)\n" +
            "  end\n" +
            "  function text.padLeft(s, n)\n" +
            "    s = tostring(s); if #s >= n then return s end\n" +
            "    return string.rep(' ', n - #s) .. s\n" +
            "  end\n" +
            "  function text.trim(s)\n" +
            "    s = tostring(s); return (s:gsub('^%s+', ''):gsub('%s+$', ''))\n" +
            "  end\n" +
            "  function text.tokenize(s)\n" +
            "    local out, i = {}, 1\n" +
            "    for tok in tostring(s):gmatch('%S+') do out[i] = tok; i = i + 1 end\n" +
            "    return out\n" +
            "  end\n" +
            "  function text.detab(s, tabWidth)\n" +
            "    tabWidth = tabWidth or 8\n" +
            "    local out, col = {}, 0\n" +
            "    for c in tostring(s):gmatch('.') do\n" +
            "      if c == '\\t' then\n" +
            "        local pad = tabWidth - (col % tabWidth)\n" +
            "        out[#out+1] = string.rep(' ', pad); col = col + pad\n" +
            "      elseif c == '\\n' then\n" +
            "        out[#out+1] = c; col = 0\n" +
            "      else\n" +
            "        out[#out+1] = c; col = col + 1\n" +
            "      end\n" +
            "    end\n" +
            "    return table.concat(out)\n" +
            "  end\n" +
            "  function text.wrap(s, width)\n" +
            "    s = tostring(s); width = width or 80\n" +
            "    if #s <= width then return s, '' end\n" +
            "    -- Try to break at last whitespace within width.\n" +
            "    local cut = width\n" +
            "    for i = width, 1, -1 do\n" +
            "      if s:sub(i, i):match('%s') then cut = i - 1; break end\n" +
            "    end\n" +
            "    local first = s:sub(1, cut)\n" +
            "    local rest  = s:sub(cut + 1):gsub('^%s+', '')\n" +
            "    return first, rest\n" +
            "  end\n" +
            "  function text.wrappedLines(s, width)\n" +
            "    local rest = s\n" +
            "    return function()\n" +
            "      if rest == nil or rest == '' then return nil end\n" +
            "      local line\n" +
            "      line, rest = text.wrap(rest, width)\n" +
            "      return line\n" +
            "    end\n" +
            "  end\n" +
            "\n" +
            "  -- ===== uuid (OpenComputers-style) =====\n" +
            "  uuid = {}\n" +
            "  function uuid.next()\n" +
            "    local hex = '0123456789abcdef'\n" +
            "    local function chars(n)\n" +
            "      local t = {}\n" +
            "      for i = 1, n do\n" +
            "        local r = math.random(1, 16)\n" +
            "        t[i] = hex:sub(r, r)\n" +
            "      end\n" +
            "      return table.concat(t)\n" +
            "    end\n" +
            "    -- v4 layout: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx\n" +
            "    -- where y is 8/9/a/b. Use math.random to choose y.\n" +
            "    local yChars = '89ab'\n" +
            "    local y = yChars:sub(math.random(1, 4), math.random(1, 4))\n" +
            "    return chars(8) .. '-' .. chars(4) .. '-4' .. chars(3)\n" +
            "        .. '-' .. y .. chars(3) .. '-' .. chars(12)\n" +
            "  end\n" +
            "end\n",
            "=oc_extras");
    }

    // ---------- preload bundled Lua modules (basalt, pine3d) ----------
    // Loads .lua resources from /assets/byteblock/lua/ and stuffs the
    // resulting chunk into package.preload[name] so `require("basalt")`
    // and `require("pine3d")` Just Work.
    private void installPreloadModules() {
        LuaValue pkg = globals.get("package");
        if (pkg.isnil()) return;
        LuaValue preload = pkg.get("preload");
        if (preload.isnil()) {
            preload = new LuaTable();
            pkg.set("preload", preload);
        }
        final LuaValue preloadF = preload;
        String[] modules = { "basalt", "pine3d" };
        for (final String name : modules) {
            String src = loadResource("/assets/byteblock/lua/" + name + ".lua");
            if (src == null) continue;
            final String source = src;
            preloadF.set(name, new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    try {
                        LuaValue chunk = globals.load(source, name);
                        return chunk.invoke();
                    } catch (Exception e) {
                        return LuaValue.error("preload " + name + ": " + e.getMessage());
                    }
                }
            });
        }
    }

    private static String loadResource(String path) {
        try (java.io.InputStream in = LuaRuntime.class.getResourceAsStream(path)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    // ---------- CC:Tweaked parity gap fillers ----------
    private void installCCParityGaps() {
        // -- os.* extras --
        LuaValue osv = globals.get("os");
        if (osv.istable()) {
            LuaTable osT = osv.checktable();
            osT.set("cancelTimer", new OneArgFunction() {
                @Override public LuaValue call(LuaValue id) {
                    os.cancelTimer(id.checkint());
                    return LuaValue.NIL;
                }
            });
            osT.set("epoch", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    String kind = a.optjstring(1, "ingame");
                    if ("utc".equalsIgnoreCase(kind) || "local".equalsIgnoreCase(kind)) {
                        return LuaValue.valueOf((double) System.currentTimeMillis());
                    }
                    var lvl = os.getLevel();
                    long dt = lvl != null ? lvl.getDayTime() : 0L;
                    // 24000 ticks per MC day -> 86_400_000 ms per MC day
                    double ms = (dt / 24000.0) * 86_400_000.0;
                    return LuaValue.valueOf(ms);
                }
            });
            osT.set("day", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    String kind = a.optjstring(1, "ingame");
                    if ("utc".equalsIgnoreCase(kind) || "local".equalsIgnoreCase(kind)) {
                        return LuaValue.valueOf(System.currentTimeMillis() / 86_400_000L);
                    }
                    var lvl = os.getLevel();
                    return LuaValue.valueOf(lvl != null ? lvl.getDayTime() / 24000L : 0L);
                }
            });
            osT.set("date", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    String fmt = a.optjstring(1, "%c");
                    long t = a.isnumber(2) ? (long) a.todouble(2) : System.currentTimeMillis() / 1000L;
                    java.util.Date d = new java.util.Date(t * 1000L);
                    boolean utc = fmt.startsWith("!");
                    if (utc) fmt = fmt.substring(1);
                    String javaFmt = fmt
                            .replace("%Y", "yyyy").replace("%m", "MM").replace("%d", "dd")
                            .replace("%H", "HH").replace("%M", "mm").replace("%S", "ss")
                            .replace("%c", "EEE MMM d HH:mm:ss yyyy")
                            .replace("%x", "MM/dd/yy").replace("%X", "HH:mm:ss")
                            .replace("%A", "EEEE").replace("%a", "EEE")
                            .replace("%B", "MMMM").replace("%b", "MMM")
                            .replace("%p", "a").replace("%j", "DDD");
                    try {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(javaFmt);
                        if (utc) sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        return LuaValue.valueOf(sdf.format(d));
                    } catch (Exception e) {
                        return LuaValue.valueOf(d.toString());
                    }
                }
            });
            osT.set("about", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    return LuaValue.valueOf("ByteBlockOS 1.0 (Lua 5.2 / LuaJ, CC:Tweaked-compatible)");
                }
            });
            // os.run(env, path, ...) — load a file with a custom env, invoke with args.
            osT.set("run", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    if (a.narg() < 2) return LuaValue.FALSE;
                    LuaTable env = a.checktable(1);
                    String path = a.checkjstring(2);
                    String content = os.getFileSystem().readFile(path);
                    if (content == null) {
                        pushOutput("os.run: file not found: " + path + "\n");
                        return LuaValue.FALSE;
                    }
                    // Inherit globals as fallback metatable so common APIs still resolve.
                    LuaTable mt = new LuaTable();
                    mt.set(LuaValue.INDEX, globals);
                    env.setmetatable(mt);
                    try {
                        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
                        LuaValue chunk = globals.load(is, path, "bt", env);
                        chunk.invoke(a.subargs(3));
                        return LuaValue.TRUE;
                    } catch (LuaError e) {
                        pushOutput("os.run: " + e.getMessage() + "\n");
                        return LuaValue.FALSE;
                    } catch (Exception e) {
                        pushOutput("os.run: " + e.getMessage() + "\n");
                        return LuaValue.FALSE;
                    }
                }
            });
        }

        // -- fs.* extras --
        LuaValue fsv = globals.get("fs");
        if (fsv.istable()) {
            LuaTable fsT = fsv.checktable();
            final VirtualFileSystem vfs = os.getFileSystem();

            fsT.set("combine", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i <= a.narg(); i++) {
                        String seg = a.tojstring(i);
                        if (seg == null || seg.isEmpty()) continue;
                        if (sb.length() > 0 && !sb.toString().endsWith("/") && !seg.startsWith("/")) sb.append('/');
                        sb.append(seg);
                    }
                    String[] parts = sb.toString().split("/");
                    java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
                    for (String p : parts) {
                        if (p.isEmpty() || p.equals(".")) continue;
                        if (p.equals("..")) { if (!stack.isEmpty()) stack.pollLast(); continue; }
                        stack.addLast(p);
                    }
                    return LuaValue.valueOf(String.join("/", stack));
                }
            });
            fsT.set("attributes", new OneArgFunction() {
                @Override public LuaValue call(LuaValue p) {
                    if (vfs == null) return LuaValue.NIL;
                    String path = p.checkjstring();
                    if (!vfs.exists(path)) return LuaValue.NIL;
                    LuaTable t = new LuaTable();
                    t.set("size", LuaValue.valueOf(vfs.isDirectory(path) ? 0 : vfs.getSize(path)));
                    t.set("isDir", LuaValue.valueOf(vfs.isDirectory(path)));
                    t.set("isReadOnly", LuaValue.FALSE);
                    t.set("created", LuaValue.valueOf(0));
                    t.set("modified", LuaValue.valueOf(0));
                    t.set("modification", LuaValue.valueOf(0));
                    return t;
                }
            });
            fsT.set("isReadOnly", new OneArgFunction() {
                @Override public LuaValue call(LuaValue p) { return LuaValue.FALSE; }
            });
            fsT.set("getDrive", new OneArgFunction() {
                @Override public LuaValue call(LuaValue p) { return LuaValue.valueOf("hdd"); }
            });
            fsT.set("getCapacity", new OneArgFunction() {
                @Override public LuaValue call(LuaValue p) { return LuaValue.valueOf(1_000_000); }
            });
            fsT.set("getFreeSpace", new OneArgFunction() {
                @Override public LuaValue call(LuaValue p) { return LuaValue.valueOf(500_000); }
            });
            fsT.set("complete", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    LuaTable out = new LuaTable();
                    if (vfs == null) return out;
                    String partial = a.optjstring(1, "");
                    String dir = a.optjstring(2, "");
                    boolean inclFiles = a.optboolean(3, true);
                    boolean inclDirs = a.optboolean(4, true);
                    String base = dir.isEmpty() ? "" : (dir.endsWith("/") ? dir : dir + "/");
                    if (!vfs.exists(dir.isEmpty() ? "/" : dir)) return out;
                    int idx = 1;
                    for (String name : vfs.list(dir.isEmpty() ? "/" : dir)) {
                        if (!name.startsWith(partial)) continue;
                        String full = base + name;
                        boolean isDir = vfs.isDirectory(full);
                        if (isDir && !inclDirs) continue;
                        if (!isDir && !inclFiles) continue;
                        String suffix = name.substring(partial.length()) + (isDir ? "/" : "");
                        out.set(idx++, LuaValue.valueOf(suffix));
                    }
                    return out;
                }
            });
            fsT.set("find", new OneArgFunction() {
                @Override public LuaValue call(LuaValue pattern) {
                    LuaTable out = new LuaTable();
                    if (vfs == null) return out;
                    String pat = pattern.checkjstring();
                    String regex = "^" + java.util.regex.Pattern.quote(pat)
                            .replace("*", "\\E[^/]*\\Q").replace("?", "\\E.\\Q") + "$";
                    java.util.regex.Pattern rx;
                    try { rx = java.util.regex.Pattern.compile(regex); } catch (Exception e) { return out; }
                    java.util.ArrayList<String> hits = new java.util.ArrayList<>();
                    findRecursive(vfs, "/", rx, hits, 0);
                    for (int i = 0; i < hits.size(); i++) out.set(i + 1, LuaValue.valueOf(hits.get(i)));
                    return out;
                }
            });
        }

        // -- term.* extras --
        LuaValue termv = globals.get("term");
        if (termv.istable()) {
            LuaTable termT = termv.checktable();
            final TerminalBuffer tb = os.getTerminal();
            termT.set("blit", new ThreeArgFunction() {
                @Override public LuaValue call(LuaValue text, LuaValue fg, LuaValue bg) {
                    if (tb == null) return LuaValue.NIL;
                    String s = text.checkjstring();
                    String f = fg.checkjstring();
                    String b = bg.checkjstring();
                    int n = Math.min(s.length(), Math.min(f.length(), b.length()));
                    int x = tb.getCursorX(), y = tb.getCursorY();
                    int saveFg = tb.getCurrentFg(), saveBg = tb.getCurrentBg();
                    for (int i = 0; i < n; i++) {
                        int fc = Character.digit(f.charAt(i), 16);
                        int bc = Character.digit(b.charAt(i), 16);
                        if (fc < 0) fc = saveFg;
                        if (bc < 0) bc = saveBg;
                        tb.setTextColor(fc);
                        tb.setBackgroundColor(bc);
                        tb.writeAt(x + i, y, String.valueOf(s.charAt(i)));
                    }
                    tb.setTextColor(saveFg);
                    tb.setBackgroundColor(saveBg);
                    tb.setCursorPos(x + n, y);
                    return LuaValue.NIL;
                }
            });
            // Fixed CC palette (no actual mutation – stored in static array)
            final int[] palette = {
                0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2, 0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
                0x999999, 0x4C99B2, 0xB266E5, 0x3366CC, 0x7F664C, 0x57A64E, 0xCC4C4C, 0x111111
            };
            OneArgFunction getPal = new OneArgFunction() {
                @Override public LuaValue call(LuaValue c) {
                    int idx = Integer.numberOfTrailingZeros(Math.max(1, c.checkint())) & 0xF;
                    int rgb = palette[idx];
                    return LuaValue.varargsOf(new LuaValue[]{
                        LuaValue.valueOf(((rgb >> 16) & 0xFF) / 255.0),
                        LuaValue.valueOf(((rgb >> 8) & 0xFF) / 255.0),
                        LuaValue.valueOf((rgb & 0xFF) / 255.0)
                    }).arg1();
                }
            };
            termT.set("getPaletteColor", getPal);
            termT.set("getPaletteColour", getPal);
            termT.set("nativePaletteColor", getPal);
            termT.set("nativePaletteColour", getPal);
            VarArgFunction setPal = new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) { return LuaValue.NIL; } // no-op
            };
            termT.set("setPaletteColor", setPal);
            termT.set("setPaletteColour", setPal);
            // Redirect stubs – ByteBlock has a single terminal context.
            ZeroArgFunction selfTerm = new ZeroArgFunction() {
                @Override public LuaValue call() { return termT; }
            };
            termT.set("current", selfTerm);
            termT.set("native", selfTerm);
            termT.set("redirect", new OneArgFunction() {
                @Override public LuaValue call(LuaValue t) { return termT; }
            });
        }

        // -- peripheral.getNames --
        LuaValue periv = globals.get("peripheral");
        if (periv.istable()) {
            LuaTable periT = periv.checktable();
            periT.set("getNames", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    LuaTable out = new LuaTable();
                    var lvl = os.getLevel();
                    var pos = os.getBlockPos();
                    if (lvl == null || pos == null) return out;
                    var devs = BluetoothNetwork.getDevicesInRange(lvl, pos, BluetoothNetwork.BLOCK_RANGE);
                    int i = 1;
                    for (var d : devs) {
                        out.set(i++, LuaValue.valueOf(d.type().name().toLowerCase() + "_" + d.deviceId().toString().substring(0, 8)));
                    }
                    return out;
                }
            });
        }

        // -- turtle.* extras (added on top of robot alias) --
        LuaValue turtv = globals.get("turtle");
        if (turtv.istable()) {
            LuaTable t = turtv.checktable();
            final String[] queueables = {
                "attack", "attackUp", "attackDown",
                "placeUp", "placeDown",
                "compare", "compareUp", "compareDown",
                "inspect", "inspectUp", "inspectDown"
            };
            for (String name : queueables) {
                if (!t.get(name).isnil()) continue;
                final String cmd = name;
                t.set(name, new ZeroArgFunction() {
                    @Override public LuaValue call() {
                        com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                        if (r == null) return LuaValue.FALSE;
                        r.queueCommand(cmd);
                        return LuaValue.TRUE;
                    }
                });
            }
            if (t.get("transferTo").isnil()) {
                t.set("transferTo", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) {
                        com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                        if (r == null) return LuaValue.FALSE;
                        r.queueCommand("transferTo:" + a.checkint(1) + ":" + a.optint(2, 64));
                        return LuaValue.TRUE;
                    }
                });
            }
            if (t.get("craft").isnil()) {
                t.set("craft", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs a) { return LuaValue.FALSE; }
                });
            }
            if (t.get("getItemSpace").isnil()) {
                t.set("getItemSpace", new OneArgFunction() {
                    @Override public LuaValue call(LuaValue slot) {
                        com.apocscode.byteblock.entity.RobotEntity r = asRobot();
                        if (r == null) return LuaValue.valueOf(0);
                        int s = slot.checkint() - 1;
                        if (s < 0 || s >= r.getInventory().getContainerSize()) return LuaValue.valueOf(0);
                        var stack = r.getInventory().getItem(s);
                        int max = stack.isEmpty() ? 64 : stack.getMaxStackSize();
                        return LuaValue.valueOf(max - stack.getCount());
                    }
                });
            }
            if (t.get("getSelectedSlot").isnil() && !t.get("getSelected").isnil()) {
                t.set("getSelectedSlot", t.get("getSelected"));
            }
        }

        // -- shell.* — real implementations live in installShellAPI(); no stubs here. --
    }

    private static void findRecursive(VirtualFileSystem vfs, String dir, java.util.regex.Pattern rx,
                                      java.util.List<String> out, int depth) {
        if (depth > 32 || vfs == null) return;
        if (!vfs.exists(dir) || !vfs.isDirectory(dir)) return;
        for (String name : vfs.list(dir)) {
            String full = dir.endsWith("/") ? dir + name : dir + "/" + name;
            if (rx.matcher(full).matches() || rx.matcher(name).matches()) out.add(full);
            if (vfs.isDirectory(full)) findRecursive(vfs, full, rx, out, depth + 1);
        }
    }

    // ---------- disk API (CC:Tweaked compat over BluetoothNetwork drives) ----------
    private void installDiskAPI() {
        LuaTable disk = new LuaTable();
        disk.set("isPresent", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.FALSE; }
        });
        disk.set("hasData", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.FALSE; }
        });
        disk.set("getMountPath", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.valueOf("/disk"); }
        });
        disk.set("setLabel", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue side, LuaValue label) { return LuaValue.NIL; }
        });
        disk.set("getLabel", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.NIL; }
        });
        disk.set("getID", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.NIL; }
        });
        disk.set("hasAudio", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.FALSE; }
        });
        disk.set("getAudioTitle", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.NIL; }
        });
        disk.set("playAudio", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.NIL; }
        });
        disk.set("stopAudio", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.NIL; }
        });
        disk.set("eject", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) { return LuaValue.NIL; }
        });
        globals.set("disk", disk);
    }

    // ---------- textutils extensions ----------
    private void extendTextUtils() {
        LuaValue tuv = globals.get("textutils");
        if (!tuv.istable()) return;
        LuaTable tu = tuv.checktable();

        tu.set("unserialize", new OneArgFunction() {
            @Override public LuaValue call(LuaValue s) {
                try {
                    String src = "return " + s.tojstring();
                    LuaValue chunk = globals.load(src, "=unserialize", globals);
                    return chunk.call();
                } catch (LuaError e) { return LuaValue.NIL; }
            }
        });
        tu.set("unserialise", tu.get("unserialize"));

        tu.set("serializeJSON", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) { return LuaValue.valueOf(luaToJson(v)); }
        });
        tu.set("serialiseJSON", tu.get("serializeJSON"));

        tu.set("unserializeJSON", new OneArgFunction() {
            @Override public LuaValue call(LuaValue s) {
                try { return jsonToLua(s.tojstring()); }
                catch (RuntimeException e) { return LuaValue.NIL; }
            }
        });
        tu.set("unserialiseJSON", tu.get("unserializeJSON"));

        tu.set("slowWrite", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String text = a.arg1().tojstring();
                for (int i = 0; i < text.length(); i++) pushOutput(String.valueOf(text.charAt(i)));
                return NONE;
            }
        });
        tu.set("slowPrint", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                pushOutput(a.arg1().tojstring() + "\n");
                return NONE;
            }
        });

        tu.set("pagedPrint", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                pushOutput(a.arg1().tojstring() + "\n");
                return LuaValue.valueOf(1);
            }
        });

        tu.set("tabulate", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                for (int i = 1; i <= a.narg(); i++) {
                    LuaValue row = a.arg(i);
                    if (row.istable()) {
                        StringBuilder sb = new StringBuilder();
                        LuaTable t = row.checktable();
                        for (int j = 1; j <= t.length(); j++) {
                            if (j > 1) sb.append("  ");
                            sb.append(t.get(j).tojstring());
                        }
                        pushOutput(sb.append('\n').toString());
                    }
                }
                return NONE;
            }
        });
        tu.set("pagedTabulate", tu.get("tabulate"));

        tu.set("formatTime", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                double t = a.optdouble(1, 0.0);
                boolean twentyFour = a.optboolean(2, false);
                int hours = (int) Math.floor(t) % 24;
                int mins = (int) Math.floor((t - Math.floor(t)) * 60) % 60;
                if (twentyFour) return LuaValue.valueOf(String.format("%02d:%02d", hours, mins));
                String ampm = hours < 12 ? "AM" : "PM";
                int h12 = hours % 12; if (h12 == 0) h12 = 12;
                return LuaValue.valueOf(String.format("%d:%02d %s", h12, mins, ampm));
            }
        });

        tu.set("urlEncode", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                try { return LuaValue.valueOf(java.net.URLEncoder.encode(v.tojstring(), java.nio.charset.StandardCharsets.UTF_8)); }
                catch (Exception e) { return v; }
            }
        });

        // textutils.complete(partial [, environment]) — returns list of identifier
        // completions, mirroring CC:Tweaked. Walks dotted/colon paths through
        // tables in the supplied environment (defaulting to globals) and returns
        // the suffixes that complete the partial token. Functions get "(", tables
        // get "." or ":" depending on traversal.
        tu.set("complete", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String partial = a.optjstring(1, "");
                LuaValue env = a.arg(2);
                if (!env.istable()) env = globals;
                LuaTable out = new LuaTable();
                if (partial == null) partial = "";
                // Split at last separator (. or :). Everything before is the table
                // path; everything after is the prefix to match against keys.
                int lastDot = partial.lastIndexOf('.');
                int lastColon = partial.lastIndexOf(':');
                int split = Math.max(lastDot, lastColon);
                LuaValue scope = env;
                String prefix;
                boolean colonScope = false;
                if (split >= 0) {
                    String path = partial.substring(0, split);
                    prefix = partial.substring(split + 1);
                    colonScope = (split == lastColon);
                    // Walk the dotted path.
                    String[] parts = path.split("[.:]");
                    for (String p : parts) {
                        if (p.isEmpty()) continue;
                        LuaValue next = scope.get(p);
                        if (!next.istable()) { return out; }
                        scope = next;
                    }
                } else {
                    prefix = partial;
                }
                if (!scope.istable()) return out;
                LuaTable st = scope.checktable();
                LuaValue k = LuaValue.NIL;
                int idx = 1;
                while (true) {
                    Varargs n;
                    try { n = st.next(k); }
                    catch (LuaError e) { break; }
                    k = n.arg1();
                    if (k.isnil()) break;
                    if (!k.isstring()) continue;
                    String key = k.tojstring();
                    if (!key.startsWith(prefix)) continue;
                    String suffix = key.substring(prefix.length());
                    LuaValue v = n.arg(2);
                    if (v.isfunction()) {
                        suffix = suffix + "(";
                    } else if (v.istable()) {
                        // For tables, suggest both . and : continuations.
                        out.set(idx++, LuaValue.valueOf(suffix + "."));
                        if (!colonScope) out.set(idx++, LuaValue.valueOf(suffix + ":"));
                        continue;
                    }
                    out.set(idx++, LuaValue.valueOf(suffix));
                }
                return out;
            }
        });

        // CC:Tweaked sentinels for JSON serialization edge cases. empty_json_array
        // forces an empty table to encode as `[]` instead of `{}`. json_null
        // encodes as the literal `null`.
        LuaTable emptyJsonArray = new LuaTable();
        LuaTable emptyJsonMt = new LuaTable();
        emptyJsonMt.set("__name", LuaValue.valueOf("empty_json_array"));
        emptyJsonArray.setmetatable(emptyJsonMt);
        tu.set("empty_json_array", emptyJsonArray);

        LuaTable jsonNull = new LuaTable();
        LuaTable jsonNullMt = new LuaTable();
        jsonNullMt.set("__name", LuaValue.valueOf("json_null"));
        jsonNull.setmetatable(jsonNullMt);
        tu.set("json_null", jsonNull);
    }

    // ---------- parallel ----------
    private void installParallelAPI() {
        LuaTable p = new LuaTable();
        // CC semantics rely on coroutines + event loop, which the current runtime
        // does not suspend. We invoke each function sequentially and return on
        // True parallel via nested coroutines. Each argument function runs as its
        // own coroutine. Events from the outer scheduler are broadcast to every
        // child in turn. waitForAny returns when any child dies; waitForAll
        // returns when all have died.
        p.set("waitForAny", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                return runParallel(a, true);
            }
        });
        p.set("waitForAll", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                return runParallel(a, false);
            }
        });
        globals.set("parallel", p);
    }

    /** Drive a set of child coroutines with events from the outer scheduler. */
    private Varargs runParallel(Varargs a, boolean any) {
        int n = a.narg();
        if (n == 0) return LuaValue.NONE;
        LuaThread[] threads = new LuaThread[n];
        boolean[] dead = new boolean[n];
        String[] filters = new String[n];
        int aliveCount = 0;
        for (int i = 0; i < n; i++) {
            LuaValue f = a.arg(i + 1);
            if (!f.isfunction()) { dead[i] = true; continue; }
            threads[i] = new LuaThread(globals, f);
            aliveCount++;
        }
        // Initial resume for each.
        for (int i = 0; i < n; i++) {
            if (dead[i]) continue;
            try {
                Varargs r = threads[i].resume(LuaValue.NONE);
                if (!r.arg(1).toboolean()) {
                    throw new LuaError(r.arg(2).tojstring());
                }
                if (threads[i].state == null || threads[i].state.status == LuaThread.STATUS_DEAD) {
                    dead[i] = true; aliveCount--;
                    if (any) return LuaValue.valueOf(i + 1);
                } else {
                    // Filter is the first yielded value if any (CC ignores filters here).
                    filters[i] = null;
                }
            } catch (LuaError e) {
                dead[i] = true; aliveCount--;
                if (any) throw e;
            }
        }
        // Dispatch loop.
        while (aliveCount > 0) {
            Varargs evt = globals.running.state.lua_yield(LuaValue.NONE);
            String en = evt.arg1().isstring() ? evt.arg1().tojstring() : "";
            if ("terminate".equals(en)) throw new LuaError("Terminated");
            for (int i = 0; i < n; i++) {
                if (dead[i]) continue;
                try {
                    Varargs r = threads[i].resume(evt);
                    if (!r.arg(1).toboolean()) {
                        throw new LuaError(r.arg(2).tojstring());
                    }
                    if (threads[i].state == null || threads[i].state.status == LuaThread.STATUS_DEAD) {
                        dead[i] = true; aliveCount--;
                        if (any) return LuaValue.valueOf(i + 1);
                    }
                } catch (LuaError e) {
                    dead[i] = true; aliveCount--;
                    if (any) throw e;
                }
            }
        }
        return LuaValue.NONE;
    }

    // ---------- vector ----------
    private void installVectorAPI() {
        LuaTable vector = new LuaTable();
        final LuaTable mt = new LuaTable();

        LuaValue vnew = new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                LuaTable t = new LuaTable();
                t.set("x", LuaValue.valueOf(a.optdouble(1, 0.0)));
                t.set("y", LuaValue.valueOf(a.optdouble(2, 0.0)));
                t.set("z", LuaValue.valueOf(a.optdouble(3, 0.0)));
                t.setmetatable(mt);
                return t;
            }
        };
        vector.set("new", vnew);

        // methods
        LuaTable idx = new LuaTable();
        idx.set("add", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue a, LuaValue b) {
                return ((VarArgFunction) vnew).invoke(LuaValue.varargsOf(
                    LuaValue.valueOf(a.get("x").todouble() + b.get("x").todouble()),
                    LuaValue.valueOf(a.get("y").todouble() + b.get("y").todouble()),
                    LuaValue.valueOf(a.get("z").todouble() + b.get("z").todouble())
                )).arg1();
            }
        });
        idx.set("sub", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue a, LuaValue b) {
                return ((VarArgFunction) vnew).invoke(LuaValue.varargsOf(
                    LuaValue.valueOf(a.get("x").todouble() - b.get("x").todouble()),
                    LuaValue.valueOf(a.get("y").todouble() - b.get("y").todouble()),
                    LuaValue.valueOf(a.get("z").todouble() - b.get("z").todouble())
                )).arg1();
            }
        });
        idx.set("mul", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue a, LuaValue s) {
                double k = s.todouble();
                return ((VarArgFunction) vnew).invoke(LuaValue.varargsOf(
                    LuaValue.valueOf(a.get("x").todouble() * k),
                    LuaValue.valueOf(a.get("y").todouble() * k),
                    LuaValue.valueOf(a.get("z").todouble() * k)
                )).arg1();
            }
        });
        idx.set("dot", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue a, LuaValue b) {
                return LuaValue.valueOf(
                    a.get("x").todouble() * b.get("x").todouble()
                  + a.get("y").todouble() * b.get("y").todouble()
                  + a.get("z").todouble() * b.get("z").todouble());
            }
        });
        idx.set("cross", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue a, LuaValue b) {
                double ax=a.get("x").todouble(), ay=a.get("y").todouble(), az=a.get("z").todouble();
                double bx=b.get("x").todouble(), by=b.get("y").todouble(), bz=b.get("z").todouble();
                return ((VarArgFunction) vnew).invoke(LuaValue.varargsOf(
                    LuaValue.valueOf(ay*bz - az*by),
                    LuaValue.valueOf(az*bx - ax*bz),
                    LuaValue.valueOf(ax*by - ay*bx)
                )).arg1();
            }
        });
        idx.set("length", new OneArgFunction() {
            @Override public LuaValue call(LuaValue a) {
                double x=a.get("x").todouble(), y=a.get("y").todouble(), z=a.get("z").todouble();
                return LuaValue.valueOf(Math.sqrt(x*x + y*y + z*z));
            }
        });
        idx.set("normalize", new OneArgFunction() {
            @Override public LuaValue call(LuaValue a) {
                double x=a.get("x").todouble(), y=a.get("y").todouble(), z=a.get("z").todouble();
                double len = Math.sqrt(x*x + y*y + z*z);
                if (len < 1e-9) len = 1;
                return ((VarArgFunction) vnew).invoke(LuaValue.varargsOf(
                    LuaValue.valueOf(x/len), LuaValue.valueOf(y/len), LuaValue.valueOf(z/len))).arg1();
            }
        });
        idx.set("round", new OneArgFunction() {
            @Override public LuaValue call(LuaValue a) {
                return ((VarArgFunction) vnew).invoke(LuaValue.varargsOf(
                    LuaValue.valueOf(Math.round(a.get("x").todouble())),
                    LuaValue.valueOf(Math.round(a.get("y").todouble())),
                    LuaValue.valueOf(Math.round(a.get("z").todouble()))
                )).arg1();
            }
        });
        idx.set("tostring", new OneArgFunction() {
            @Override public LuaValue call(LuaValue a) {
                return LuaValue.valueOf(a.get("x").tojstring() + "," + a.get("y").tojstring() + "," + a.get("z").tojstring());
            }
        });
        mt.set("__index", idx);

        globals.set("vector", vector);
    }

    // ---------- keys ----------
    private void installKeysAPI() {
        LuaTable keys = new LuaTable();
        // Letters
        for (char c = 'a'; c <= 'z'; c++) keys.set(String.valueOf(c), LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_A + (c - 'a')));
        // Digits
        for (int i = 0; i <= 9; i++) keys.set(String.valueOf((char)('0' + i)), LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_0 + i));
        // Common named
        int[][] named = {
            {org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE, 'S'}, // sentinel ignored
        };
        keys.set("space",    LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE));
        keys.set("enter",    LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER));
        keys.set("tab",      LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB));
        keys.set("backspace",LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE));
        keys.set("escape",   LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE));
        keys.set("left",     LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT));
        keys.set("right",    LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT));
        keys.set("up",       LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_UP));
        keys.set("down",     LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN));
        keys.set("leftShift",LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT));
        keys.set("rightShift",LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT));
        keys.set("leftCtrl", LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL));
        keys.set("rightCtrl",LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL));
        keys.set("leftAlt",  LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT));
        keys.set("rightAlt", LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT));
        for (int i = 1; i <= 12; i++) keys.set("f" + i, LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_F1 + (i - 1)));
        keys.set("home", LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME));
        keys.set("end",  LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_END));
        keys.set("pageUp",   LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP));
        keys.set("pageDown", LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN));
        keys.set("insert",   LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_INSERT));
        keys.set("delete",   LuaValue.valueOf(org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE));
        keys.set("getName", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                int code = v.checkint();
                String name = org.lwjgl.glfw.GLFW.glfwGetKeyName(code, 0);
                return name != null ? LuaValue.valueOf(name) : LuaValue.valueOf("key#" + code);
            }
        });
        globals.set("keys", keys);
    }

    // ---------- paintutils ----------
    private void installPaintutilsAPI() {
        TerminalBuffer tb = os.getTerminal();
        LuaTable p = new LuaTable();

        p.set("drawPixel", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int x = a.checkint(1) - 1;
                int y = a.checkint(2) - 1;
                if (!a.isnil(3)) tb.setBackgroundColor(ccColorToIndex(a.checkint(3)));
                if (x >= 0 && y >= 0 && x < TerminalBuffer.WIDTH && y < TerminalBuffer.HEIGHT) {
                    tb.setCursorPos(x, y);
                    pushOutput(" ");
                }
                return NONE;
            }
        });
        // paintutils.writeText(text [, fg [, bg]]) — writes text at cursor; optional colors.
        p.set("writeText", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String s = a.checkjstring(1);
                if (!a.isnil(2)) tb.setTextColor(ccColorToIndex(a.checkint(2)));
                if (!a.isnil(3)) tb.setBackgroundColor(ccColorToIndex(a.checkint(3)));
                pushOutput(s);
                return NONE;
            }
        });
        p.set("drawLine", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int x0 = a.checkint(1) - 1, y0 = a.checkint(2) - 1;
                int x1 = a.checkint(3) - 1, y1 = a.checkint(4) - 1;
                if (!a.isnil(5)) tb.setBackgroundColor(ccColorToIndex(a.checkint(5)));
                int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
                int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
                int err = dx + dy;
                while (true) {
                    if (x0 >= 0 && y0 >= 0 && x0 < TerminalBuffer.WIDTH && y0 < TerminalBuffer.HEIGHT) {
                        tb.setCursorPos(x0, y0);
                        pushOutput(" ");
                    }
                    if (x0 == x1 && y0 == y1) break;
                    int e2 = 2 * err;
                    if (e2 >= dy) { err += dy; x0 += sx; }
                    if (e2 <= dx) { err += dx; y0 += sy; }
                }
                return NONE;
            }
        });
        p.set("drawBox", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int x1 = a.checkint(1), y1 = a.checkint(2), x2 = a.checkint(3), y2 = a.checkint(4);
                LuaValue col = a.arg(5);
                drawRect(tb, x1, y1, x2, y2, col, false);
                return NONE;
            }
        });
        p.set("drawFilledBox", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int x1 = a.checkint(1), y1 = a.checkint(2), x2 = a.checkint(3), y2 = a.checkint(4);
                LuaValue col = a.arg(5);
                drawRect(tb, x1, y1, x2, y2, col, true);
                return NONE;
            }
        });
        p.set("loadImage", new OneArgFunction() {
            @Override public LuaValue call(LuaValue path) {
                String content = os.getFileSystem().readFile(path.checkjstring());
                LuaTable img = new LuaTable();
                if (content == null) return img;
                int row = 1;
                for (String line : content.split("\n")) {
                    LuaTable r = new LuaTable();
                    for (int i = 0; i < line.length(); i++) {
                        int v;
                        try { v = Integer.parseInt(String.valueOf(line.charAt(i)), 16); }
                        catch (NumberFormatException e) { v = 0; }
                        r.set(i + 1, LuaValue.valueOf(1 << v));
                    }
                    img.set(row++, r);
                }
                return img;
            }
        });
        p.set("drawImage", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                LuaValue img = a.arg(1);
                int ox = a.checkint(2) - 1, oy = a.checkint(3) - 1;
                if (!img.istable()) return NONE;
                LuaTable t = img.checktable();
                for (int r = 1; r <= t.length(); r++) {
                    LuaValue row = t.get(r);
                    if (!row.istable()) continue;
                    LuaTable rr = row.checktable();
                    for (int c = 1; c <= rr.length(); c++) {
                        int col = rr.get(c).optint(0);
                        if (col == 0) continue;
                        tb.setBackgroundColor(ccColorToIndex(col));
                        int x = ox + c - 1, y = oy + r - 1;
                        if (x >= 0 && y >= 0 && x < TerminalBuffer.WIDTH && y < TerminalBuffer.HEIGHT) {
                            tb.setCursorPos(x, y);
                            pushOutput(" ");
                        }
                    }
                }
                return NONE;
            }
        });
        // ── ByteBlock extensions: shapes that vanilla CC paintutils lacks ──
        p.set("drawCircle", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int cx = a.checkint(1) - 1, cy = a.checkint(2) - 1;
                int r  = Math.max(1, a.checkint(3));
                if (!a.isnil(4)) tb.setBackgroundColor(ccColorToIndex(a.checkint(4)));
                int r2 = r * r, rIn = (r - 1) * (r - 1);
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int d = dx*dx + dy*dy;
                        if (d > r2 || d < rIn) continue;
                        plotPixel(tb, cx + dx, cy + dy);
                    }
                }
                return NONE;
            }
        });
        p.set("drawFilledCircle", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int cx = a.checkint(1) - 1, cy = a.checkint(2) - 1;
                int r  = Math.max(1, a.checkint(3));
                if (!a.isnil(4)) tb.setBackgroundColor(ccColorToIndex(a.checkint(4)));
                int r2 = r * r;
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (dx*dx + dy*dy > r2) continue;
                        plotPixel(tb, cx + dx, cy + dy);
                    }
                }
                return NONE;
            }
        });
        p.set("drawTriangle", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int x1 = a.checkint(1)-1, y1 = a.checkint(2)-1;
                int x2 = a.checkint(3)-1, y2 = a.checkint(4)-1;
                int x3 = a.checkint(5)-1, y3 = a.checkint(6)-1;
                if (!a.isnil(7)) tb.setBackgroundColor(ccColorToIndex(a.checkint(7)));
                drawLineGrid(tb, x1, y1, x2, y2);
                drawLineGrid(tb, x2, y2, x3, y3);
                drawLineGrid(tb, x3, y3, x1, y1);
                return NONE;
            }
        });
        p.set("drawFilledTriangle", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int x1 = a.checkint(1)-1, y1 = a.checkint(2)-1;
                int x2 = a.checkint(3)-1, y2 = a.checkint(4)-1;
                int x3 = a.checkint(5)-1, y3 = a.checkint(6)-1;
                if (!a.isnil(7)) tb.setBackgroundColor(ccColorToIndex(a.checkint(7)));
                fillTriangleGrid(tb, x1, y1, x2, y2, x3, y3);
                return NONE;
            }
        });
        p.set("drawPolygon", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                LuaValue pts = a.arg(1);
                if (!pts.istable()) return NONE;
                LuaTable t = pts.checktable();
                int n = t.length() / 2;
                if (n < 2) return NONE;
                if (!a.isnil(2)) tb.setBackgroundColor(ccColorToIndex(a.checkint(2)));
                int[] xs = new int[n], ys = new int[n];
                for (int k = 0; k < n; k++) {
                    xs[k] = t.get(k*2 + 1).toint() - 1;
                    ys[k] = t.get(k*2 + 2).toint() - 1;
                }
                boolean filled = a.optboolean(3, false);
                if (filled && n >= 3) fillPolygonGrid(tb, xs, ys);
                else for (int k = 0; k < n; k++) drawLineGrid(tb, xs[k], ys[k], xs[(k+1)%n], ys[(k+1)%n]);
                return NONE;
            }
        });
        // drawSprite(img, x, y [, transparent_color_index]) — img is a 2D table of CC color indices
        // Skips cells equal to the transparent color (default 0 = none).
        p.set("drawSprite", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                LuaValue img = a.arg(1);
                int ox = a.checkint(2) - 1, oy = a.checkint(3) - 1;
                int trans = a.optint(4, 0);
                if (!img.istable()) return NONE;
                LuaTable t = img.checktable();
                for (int r = 1; r <= t.length(); r++) {
                    LuaValue row = t.get(r);
                    if (!row.istable()) continue;
                    LuaTable rr = row.checktable();
                    for (int c = 1; c <= rr.length(); c++) {
                        int col = rr.get(c).optint(0);
                        if (col == trans) continue;
                        tb.setBackgroundColor(ccColorToIndex(col));
                        int x = ox + c - 1, y = oy + r - 1;
                        if (x >= 0 && y >= 0 && x < TerminalBuffer.WIDTH && y < TerminalBuffer.HEIGHT) {
                            tb.setCursorPos(x, y);
                            pushOutput(" ");
                        }
                    }
                }
                return NONE;
            }
        });
        globals.set("paintutils", p);
    }

    // ── Char-grid pixel helpers used by extended paintutils ──────────────
    private void plotPixel(TerminalBuffer tb, int x, int y) {
        if (x < 0 || y < 0 || x >= TerminalBuffer.WIDTH || y >= TerminalBuffer.HEIGHT) return;
        tb.setCursorPos(x, y);
        pushOutput(" ");
    }
    private void drawLineGrid(TerminalBuffer tb, int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int safety = 0;
        while (safety++ < 4000) {
            plotPixel(tb, x0, y0);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }
    private void fillTriangleGrid(TerminalBuffer tb, int x1, int y1, int x2, int y2, int x3, int y3) {
        int minY = Math.min(y1, Math.min(y2, y3));
        int maxY = Math.max(y1, Math.max(y2, y3));
        for (int y = minY; y <= maxY; y++) {
            int xa = Integer.MAX_VALUE, xb = Integer.MIN_VALUE;
            int[][] edges = { {x1,y1,x2,y2}, {x2,y2,x3,y3}, {x3,y3,x1,y1} };
            for (int[] e : edges) {
                int ey1=e[1], ey2=e[3];
                if ((ey1 <= y && ey2 > y) || (ey2 <= y && ey1 > y)) {
                    double t = (double)(y - ey1) / (ey2 - ey1);
                    int xi = (int) Math.round(e[0] + t * (e[2] - e[0]));
                    if (xi < xa) xa = xi;
                    if (xi > xb) xb = xi;
                }
            }
            if (xa <= xb) for (int x = xa; x <= xb; x++) plotPixel(tb, x, y);
        }
    }
    private void fillPolygonGrid(TerminalBuffer tb, int[] xs, int[] ys) {
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int v : ys) { if (v < minY) minY = v; if (v > maxY) maxY = v; }
        int n = xs.length;
        int[] nodeX = new int[n];
        for (int y = minY; y <= maxY; y++) {
            int nodes = 0;
            int j = n - 1;
            for (int k = 0; k < n; k++) {
                if ((ys[k] < y && ys[j] >= y) || (ys[j] < y && ys[k] >= y)) {
                    double t = (double)(y - ys[k]) / (ys[j] - ys[k]);
                    nodeX[nodes++] = (int) Math.round(xs[k] + t * (xs[j] - xs[k]));
                }
                j = k;
            }
            for (int a = 0; a < nodes - 1; a++)
                for (int b = a + 1; b < nodes; b++)
                    if (nodeX[a] > nodeX[b]) { int t = nodeX[a]; nodeX[a] = nodeX[b]; nodeX[b] = t; }
            for (int k = 0; k + 1 < nodes; k += 2)
                for (int x = nodeX[k]; x <= nodeX[k+1]; x++) plotPixel(tb, x, y);
        }
    }

    private static void drawRect(TerminalBuffer tb, int x1, int y1, int x2, int y2, LuaValue col, boolean filled) {
        if (!col.isnil()) tb.setBackgroundColor(staticCcColorToIndex(col.checkint()));
        int ax = Math.min(x1, x2) - 1, bx = Math.max(x1, x2) - 1;
        int ay = Math.min(y1, y2) - 1, by = Math.max(y1, y2) - 1;
        for (int y = ay; y <= by; y++) {
            for (int x = ax; x <= bx; x++) {
                if (!filled && x != ax && x != bx && y != ay && y != by) continue;
                if (x >= 0 && y >= 0 && x < TerminalBuffer.WIDTH && y < TerminalBuffer.HEIGHT) {
                    tb.setCursorPos(x, y);
                    tb.hLine(x, x, y, ' ');
                }
            }
        }
    }

    // ---------- window (simplified subregion redirect) ----------
    private void installWindowAPI() {
        LuaTable window = new LuaTable();
        final TerminalBuffer tb = os.getTerminal();

        window.set("create", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                // parent ignored; we always target the root term
                final int ox = a.checkint(2) - 1;
                final int oy = a.checkint(3) - 1;
                final int w  = a.checkint(4);
                final int h  = a.checkint(5);
                final boolean[] visibleStart = { a.optboolean(6, true) };
                LuaTable win = new LuaTable();
                // Window's own backing buffer (so setVisible(false) can defer flushes).
                final char[][] wChars = new char[h][w];
                final int[][]  wFg    = new int[h][w];
                final int[][]  wBg    = new int[h][w];
                for (int yy = 0; yy < h; yy++) {
                    java.util.Arrays.fill(wChars[yy], ' ');
                    java.util.Arrays.fill(wFg[yy], 0);   // white
                    java.util.Arrays.fill(wBg[yy], 15);  // black
                }
                final int[] cx = {0}, cy = {0};
                final int[] curFg = {0}, curBg = {15};
                final int[] origin = { ox, oy };
                final boolean[] visible = { visibleStart[0] };

                final Runnable flushAll = () -> {
                    if (!visible[0]) return;
                    int prevFg = tb.getCurrentFg();
                    int prevBg = tb.getCurrentBg();
                    int prevCx = tb.getCursorX();
                    int prevCy = tb.getCursorY();
                    for (int yy = 0; yy < h; yy++) {
                        int ay = origin[1] + yy;
                        if (ay < 0 || ay >= TerminalBuffer.HEIGHT) continue;
                        for (int xx = 0; xx < w; xx++) {
                            int ax = origin[0] + xx;
                            if (ax < 0 || ax >= TerminalBuffer.WIDTH) continue;
                            tb.setTextColor(wFg[yy][xx]);
                            tb.setBackgroundColor(wBg[yy][xx]);
                            tb.setCursorPos(ax, ay);
                            tb.write(String.valueOf(wChars[yy][xx]));
                        }
                    }
                    tb.setTextColor(prevFg);
                    tb.setBackgroundColor(prevBg);
                    tb.setCursorPos(prevCx, prevCy);
                };

                final java.util.function.IntConsumer flushRow = yy -> {
                    if (!visible[0]) return;
                    int ay = origin[1] + yy;
                    if (ay < 0 || ay >= TerminalBuffer.HEIGHT) return;
                    int prevFg = tb.getCurrentFg();
                    int prevBg = tb.getCurrentBg();
                    int prevCx = tb.getCursorX();
                    int prevCy = tb.getCursorY();
                    for (int xx = 0; xx < w; xx++) {
                        int ax = origin[0] + xx;
                        if (ax < 0 || ax >= TerminalBuffer.WIDTH) continue;
                        tb.setTextColor(wFg[yy][xx]);
                        tb.setBackgroundColor(wBg[yy][xx]);
                        tb.setCursorPos(ax, ay);
                        tb.write(String.valueOf(wChars[yy][xx]));
                    }
                    tb.setTextColor(prevFg);
                    tb.setBackgroundColor(prevBg);
                    tb.setCursorPos(prevCx, prevCy);
                };

                win.set("write", new OneArgFunction() {
                    @Override public LuaValue call(LuaValue v) {
                        String s = v.tojstring();
                        int written = 0;
                        if (cy[0] >= 0 && cy[0] < h) {
                            for (int i = 0; i < s.length() && cx[0] < w; i++, cx[0]++) {
                                if (cx[0] < 0) continue;
                                wChars[cy[0]][cx[0]] = s.charAt(i);
                                wFg[cy[0]][cx[0]] = curFg[0];
                                wBg[cy[0]][cx[0]] = curBg[0];
                                written++;
                            }
                            if (written > 0) flushRow.accept(cy[0]);
                        }
                        return NONE;
                    }
                });
                win.set("blit", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs args) {
                        String s  = args.checkjstring(1);
                        String fg = args.checkjstring(2);
                        String bg = args.checkjstring(3);
                        if (cy[0] < 0 || cy[0] >= h) return NONE;
                        for (int i = 0; i < s.length() && cx[0] < w; i++, cx[0]++) {
                            if (cx[0] < 0) continue;
                            wChars[cy[0]][cx[0]] = s.charAt(i);
                            wFg[cy[0]][cx[0]] = parseHex(i < fg.length() ? fg.charAt(i) : '0', curFg[0]);
                            wBg[cy[0]][cx[0]] = parseHex(i < bg.length() ? bg.charAt(i) : 'f', curBg[0]);
                        }
                        flushRow.accept(cy[0]);
                        return NONE;
                    }
                });
                win.set("setCursorPos", new TwoArgFunction() {
                    @Override public LuaValue call(LuaValue x, LuaValue y) {
                        cx[0] = x.checkint() - 1; cy[0] = y.checkint() - 1; return NONE;
                    }
                });
                win.set("getCursorPos", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs args) {
                        return LuaValue.varargsOf(LuaValue.valueOf(cx[0]+1), LuaValue.valueOf(cy[0]+1));
                    }
                });
                win.set("getSize", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs args) {
                        return LuaValue.varargsOf(LuaValue.valueOf(w), LuaValue.valueOf(h));
                    }
                });
                win.set("clear", new ZeroArgFunction() {
                    @Override public LuaValue call() {
                        for (int yy = 0; yy < h; yy++) {
                            for (int xx = 0; xx < w; xx++) {
                                wChars[yy][xx] = ' ';
                                wFg[yy][xx] = curFg[0];
                                wBg[yy][xx] = curBg[0];
                            }
                        }
                        cx[0] = 0; cy[0] = 0;
                        flushAll.run();
                        return NONE;
                    }
                });
                win.set("clearLine", new ZeroArgFunction() {
                    @Override public LuaValue call() {
                        if (cy[0] < 0 || cy[0] >= h) return NONE;
                        for (int xx = 0; xx < w; xx++) {
                            wChars[cy[0]][xx] = ' ';
                            wFg[cy[0]][xx] = curFg[0];
                            wBg[cy[0]][xx] = curBg[0];
                        }
                        flushRow.accept(cy[0]);
                        return NONE;
                    }
                });
                win.set("setVisible", new OneArgFunction() {
                    @Override public LuaValue call(LuaValue v) {
                        boolean newVis = v.toboolean();
                        if (newVis && !visible[0]) {
                            visible[0] = true;
                            flushAll.run();
                        } else {
                            visible[0] = newVis;
                        }
                        return NONE;
                    }
                });
                win.set("isVisible", new ZeroArgFunction() {
                    @Override public LuaValue call() { return LuaValue.valueOf(visible[0]); }
                });
                win.set("redraw", new ZeroArgFunction() {
                    @Override public LuaValue call() { flushAll.run(); return NONE; }
                });
                win.set("setTextColor", new OneArgFunction() {
                    @Override public LuaValue call(LuaValue v) { curFg[0] = ccColorToIndex(v.checkint()); return NONE; }
                });
                win.set("setBackgroundColor", new OneArgFunction() {
                    @Override public LuaValue call(LuaValue v) { curBg[0] = ccColorToIndex(v.checkint()); return NONE; }
                });
                win.set("getTextColor", new ZeroArgFunction() {
                    @Override public LuaValue call() { return LuaValue.valueOf(indexToCcColor(curFg[0])); }
                });
                win.set("getBackgroundColor", new ZeroArgFunction() {
                    @Override public LuaValue call() { return LuaValue.valueOf(indexToCcColor(curBg[0])); }
                });
                win.set("setTextColour", win.get("setTextColor"));
                win.set("setBackgroundColour", win.get("setBackgroundColor"));
                win.set("getTextColour", win.get("getTextColor"));
                win.set("getBackgroundColour", win.get("getBackgroundColor"));
                win.set("isColor", new ZeroArgFunction() { @Override public LuaValue call() { return LuaValue.TRUE; } });
                win.set("isColour", win.get("isColor"));
                win.set("scroll", new OneArgFunction() {
                    @Override public LuaValue call(LuaValue v) {
                        int n = v.checkint();
                        if (n == 0) return NONE;
                        if (n > 0) {
                            for (int yy = 0; yy < h - n; yy++) {
                                System.arraycopy(wChars[yy + n], 0, wChars[yy], 0, w);
                                System.arraycopy(wFg[yy + n], 0, wFg[yy], 0, w);
                                System.arraycopy(wBg[yy + n], 0, wBg[yy], 0, w);
                            }
                            for (int yy = Math.max(0, h - n); yy < h; yy++) {
                                java.util.Arrays.fill(wChars[yy], ' ');
                                java.util.Arrays.fill(wFg[yy], curFg[0]);
                                java.util.Arrays.fill(wBg[yy], curBg[0]);
                            }
                        } else {
                            int m = -n;
                            for (int yy = h - 1; yy >= m; yy--) {
                                System.arraycopy(wChars[yy - m], 0, wChars[yy], 0, w);
                                System.arraycopy(wFg[yy - m], 0, wFg[yy], 0, w);
                                System.arraycopy(wBg[yy - m], 0, wBg[yy], 0, w);
                            }
                            for (int yy = 0; yy < Math.min(h, m); yy++) {
                                java.util.Arrays.fill(wChars[yy], ' ');
                                java.util.Arrays.fill(wFg[yy], curFg[0]);
                                java.util.Arrays.fill(wBg[yy], curBg[0]);
                            }
                        }
                        flushAll.run();
                        return NONE;
                    }
                });
                win.set("reposition", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs args) {
                        origin[0] = args.checkint(1) - 1;
                        origin[1] = args.checkint(2) - 1;
                        flushAll.run();
                        return NONE;
                    }
                });
                win.set("getPosition", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs args) {
                        return LuaValue.varargsOf(
                            LuaValue.valueOf(origin[0] + 1),
                            LuaValue.valueOf(origin[1] + 1));
                    }
                });
                win.set("setCursorBlink", new OneArgFunction() {
                    @Override public LuaValue call(LuaValue v) {
                        tb.setCursorBlink(v.toboolean()); return NONE;
                    }
                });
                // Initial visible flush: paint blank window
                if (visible[0]) flushAll.run();
                return win;
            }
        });

        globals.set("window", window);
    }

    private static int parseHex(char c, int fallback) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return fallback;
    }

    // ---------- settings ----------
    private void installSettingsAPI() {
        LuaTable settings = new LuaTable();
        final java.util.Map<String, LuaValue> store = new java.util.HashMap<>();
        final String path = "/Users/User/.settings";

        // Load existing
        String raw = os.getFileSystem().readFile(path);
        if (raw != null) {
            try {
                LuaValue t = jsonToLua(raw);
                if (t.istable()) {
                    LuaTable tt = t.checktable();
                    LuaValue k = LuaValue.NIL;
                    while (true) {
                        Varargs n = tt.next(k);
                        k = n.arg1(); if (k.isnil()) break;
                        store.put(k.tojstring(), n.arg(2));
                    }
                }
            } catch (Exception ignored) {}
        }

        settings.set("define", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String name = a.checkjstring(1);
                if (!store.containsKey(name)) {
                    LuaValue def = a.arg(2).istable() ? a.arg(2).get("default") : LuaValue.NIL;
                    store.put(name, def.isnil() ? LuaValue.NIL : def);
                }
                return NONE;
            }
        });
        settings.set("get", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                LuaValue v = store.get(a.checkjstring(1));
                if (v == null || v.isnil()) return a.arg(2);
                return v;
            }
        });
        settings.set("set", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                store.put(a.checkjstring(1), a.arg(2));
                return NONE;
            }
        });
        settings.set("unset", new OneArgFunction() {
            @Override public LuaValue call(LuaValue k) { store.remove(k.tojstring()); return NONE; }
        });
        settings.set("clear", new ZeroArgFunction() {
            @Override public LuaValue call() { store.clear(); return NONE; }
        });
        settings.set("getNames", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable t = new LuaTable();
                int i = 1;
                for (String k : store.keySet()) t.set(i++, LuaValue.valueOf(k));
                return t;
            }
        });
        settings.set("load", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String p = a.optjstring(1, path);
                String s = os.getFileSystem().readFile(p);
                if (s == null) return LuaValue.FALSE;
                try {
                    LuaValue t = jsonToLua(s);
                    if (t.istable()) {
                        LuaTable tt = t.checktable();
                        LuaValue k = LuaValue.NIL;
                        while (true) {
                            Varargs n = tt.next(k);
                            k = n.arg1(); if (k.isnil()) break;
                            store.put(k.tojstring(), n.arg(2));
                        }
                    }
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });
        settings.set("save", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String p = a.optjstring(1, path);
                LuaTable t = new LuaTable();
                for (java.util.Map.Entry<String, LuaValue> e : store.entrySet()) t.set(e.getKey(), e.getValue());
                os.getFileSystem().writeFile(p, luaToJson(t));
                return LuaValue.TRUE;
            }
        });

        globals.set("settings", settings);
    }

    // ---------- turtle alias to robot ----------
    private void installTurtleAlias() {
        LuaValue robot = globals.get("robot");
        if (robot.istable()) {
            // Simple alias — turtle and robot share the same methods.
            globals.set("turtle", robot);
        }
    }

    // ---------- http ----------
    private void installHttpAPI() {
        LuaTable http = new LuaTable();
        // Enabled by default per user request.
        http.set("checkURL", new OneArgFunction() {
            @Override public LuaValue call(LuaValue u) {
                try { java.net.URI.create(u.checkjstring()).toURL(); return LuaValue.TRUE; }
                catch (Exception e) { return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(e.getMessage())).arg1(); }
            }
        });
        http.set("get", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String url = a.checkjstring(1);
                try {
                    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                    java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url));
                    if (a.istable(2)) {
                        LuaTable hdrs = a.checktable(2);
                        LuaValue k = LuaValue.NIL;
                        while (true) {
                            Varargs n = hdrs.next(k);
                            k = n.arg1(); if (k.isnil()) break;
                            rb.header(k.tojstring(), n.arg(2).tojstring());
                        }
                    }
                    java.net.http.HttpResponse<String> resp = client.send(rb.GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                    return httpResponseTable(resp);
                } catch (Exception e) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(e.getMessage()));
                }
            }
        });
        http.set("post", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String url = a.checkjstring(1);
                String body = a.optjstring(2, "");
                try {
                    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                    java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
                    if (a.istable(3)) {
                        LuaTable hdrs = a.checktable(3);
                        LuaValue k = LuaValue.NIL;
                        while (true) {
                            Varargs n = hdrs.next(k);
                            k = n.arg1(); if (k.isnil()) break;
                            rb.header(k.tojstring(), n.arg(2).tojstring());
                        }
                    }
                    java.net.http.HttpResponse<String> resp = client.send(rb.build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                    return httpResponseTable(resp);
                } catch (Exception e) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(e.getMessage()));
                }
            }
        });
        http.set("request", http.get("get"));
        // http.websocket(url [, headers]) — opens a WebSocket. Returns a handle with:
        //   send(msg [, binary])  — send text or binary frame
        //   receive([timeout])    — yields until a message arrives; returns msg, isBinary
        //   close()               — closes the socket
        // Incoming messages also fire "websocket_message" events (url, msg, isBinary)
        // and "websocket_closed" (url, reason, code) on close.
        http.set("websocket", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                final String url = a.checkjstring(1);
                try {
                    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                    java.net.http.WebSocket.Builder wb = client.newWebSocketBuilder();
                    if (a.istable(2)) {
                        LuaTable hdrs = a.checktable(2);
                        LuaValue k = LuaValue.NIL;
                        while (true) {
                            Varargs n = hdrs.next(k);
                            k = n.arg1(); if (k.isnil()) break;
                            wb.header(k.tojstring(), n.arg(2).tojstring());
                        }
                    }
                    final java.util.concurrent.ConcurrentLinkedQueue<Object[]> inbox =
                        new java.util.concurrent.ConcurrentLinkedQueue<>();
                    final java.net.http.WebSocket.Listener listener = new java.net.http.WebSocket.Listener() {
                        private final StringBuilder textBuf = new StringBuilder();
                        @Override public java.util.concurrent.CompletionStage<?> onText(
                                java.net.http.WebSocket ws, CharSequence data, boolean last) {
                            textBuf.append(data);
                            if (last) {
                                String msg = textBuf.toString();
                                textBuf.setLength(0);
                                inbox.offer(new Object[]{msg, Boolean.FALSE});
                                queueEvent("websocket_message", url, msg, Boolean.FALSE);
                            }
                            ws.request(1);
                            return null;
                        }
                        @Override public java.util.concurrent.CompletionStage<?> onBinary(
                                java.net.http.WebSocket ws, java.nio.ByteBuffer data, boolean last) {
                            byte[] b = new byte[data.remaining()];
                            data.get(b);
                            String s = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
                            inbox.offer(new Object[]{s, Boolean.TRUE});
                            queueEvent("websocket_message", url, s, Boolean.TRUE);
                            ws.request(1);
                            return null;
                        }
                        @Override public java.util.concurrent.CompletionStage<?> onClose(
                                java.net.http.WebSocket ws, int code, String reason) {
                            inbox.offer(new Object[]{null, null, code, reason});
                            queueEvent("websocket_closed", url, reason == null ? "" : reason, code);
                            return null;
                        }
                        @Override public void onError(java.net.http.WebSocket ws, Throwable error) {
                            queueEvent("websocket_closed", url, error.getMessage() == null ? "error" : error.getMessage(), -1);
                        }
                    };
                    final java.net.http.WebSocket socket = wb.buildAsync(java.net.URI.create(url), listener)
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);

                    LuaTable t = new LuaTable();
                    t.set("send", new VarArgFunction() {
                        @Override public Varargs invoke(Varargs args) {
                            String msg = args.checkjstring(1);
                            boolean binary = args.optboolean(2, false);
                            try {
                                if (binary) {
                                    socket.sendBinary(java.nio.ByteBuffer.wrap(
                                        msg.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)), true).join();
                                } else {
                                    socket.sendText(msg, true).join();
                                }
                                return LuaValue.TRUE;
                            } catch (Exception e) {
                                return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(e.getMessage()));
                            }
                        }
                    });
                    t.set("receive", new VarArgFunction() {
                        @Override public Varargs invoke(Varargs args) {
                            // If a message is already buffered, return it immediately.
                            Object[] msg = inbox.poll();
                            if (msg != null) {
                                if (msg[0] == null) return LuaValue.NIL; // closed
                                return LuaValue.varargsOf(
                                    LuaValue.valueOf((String) msg[0]),
                                    LuaValue.valueOf((Boolean) msg[1]));
                            }
                            // Otherwise yield until one arrives.
                            double timeout = args.optdouble(1, 0.0);
                            int timerId = timeout > 0 ? os.startTimer(timeout) : -1;
                            while (true) {
                                Varargs evt = globals.running.state.lua_yield(LuaValue.NONE);
                                String n = evt.arg1().isstring() ? evt.arg1().tojstring() : "";
                                if ("terminate".equals(n)) throw new LuaError("Terminated");
                                if ("websocket_message".equals(n) && evt.arg(2).tojstring().equals(url)) {
                                    Object[] m = inbox.poll();
                                    if (m != null && m[0] != null) {
                                        return LuaValue.varargsOf(
                                            LuaValue.valueOf((String) m[0]),
                                            LuaValue.valueOf((Boolean) m[1]));
                                    }
                                }
                                if ("websocket_closed".equals(n) && evt.arg(2).tojstring().equals(url)) {
                                    return LuaValue.NIL;
                                }
                                if ("timer".equals(n) && timerId != -1 && evt.arg(2).toint() == timerId) {
                                    return LuaValue.NIL;
                                }
                            }
                        }
                    });
                    t.set("close", new ZeroArgFunction() {
                        @Override public LuaValue call() {
                            try { socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "bye").join(); }
                            catch (Exception ignored) {}
                            return NONE;
                        }
                    });
                    return t;
                } catch (Exception e) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(e.getMessage()));
                }
            }
        });
        globals.set("http", http);
    }

    private static Varargs httpResponseTable(java.net.http.HttpResponse<String> resp) {
        LuaTable t = new LuaTable();
        final String body = resp.body();
        t.set("readAll", new ZeroArgFunction() { @Override public LuaValue call() { return LuaValue.valueOf(body); } });
        t.set("getResponseCode", new ZeroArgFunction() { @Override public LuaValue call() { return LuaValue.valueOf(resp.statusCode()); } });
        LuaTable headers = new LuaTable();
        resp.headers().map().forEach((k, v) -> { if (!v.isEmpty()) headers.set(k, LuaValue.valueOf(v.get(0))); });
        t.set("getResponseHeaders", new ZeroArgFunction() { @Override public LuaValue call() { return headers; } });
        t.set("close", new ZeroArgFunction() { @Override public LuaValue call() { return NONE; } });
        return t;
    }

    // ---------- pastebin ----------
    private void installPastebinAPI() {
        LuaTable pb = new LuaTable();
        pb.set("get", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String id = a.checkjstring(1);
                try {
                    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("https://pastebin.com/raw/" + id)).GET().build();
                    java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("HTTP " + resp.statusCode()));
                    return LuaValue.valueOf(resp.body());
                } catch (Exception e) { return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(e.getMessage())); }
            }
        });
        // pastebin.download(id, path) — fetch and save to VFS
        pb.set("download", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                Varargs got = ((VarArgFunction) pb.get("get")).invoke(LuaValue.varargsOf(new LuaValue[]{a.arg(1)}));
                if (got.arg1().isnil()) return got;
                os.getFileSystem().writeFile(a.checkjstring(2), got.arg1().tojstring());
                return LuaValue.TRUE;
            }
        });
        pb.set("run", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                Varargs got = ((VarArgFunction) pb.get("get")).invoke(LuaValue.varargsOf(new LuaValue[]{a.arg(1)}));
                if (got.arg1().isnil()) return got;
                try {
                    LuaValue chunk = globals.load(got.arg1().tojstring(), "=pastebin", globals);
                    return chunk.invoke(a.subargs(2));
                } catch (LuaError e) { return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(e.getMessage())); }
            }
        });
        // pastebin.put(path [, name]) — uploads file content using dev key from settings.
        // Set dev key: settings.set("pastebin.dev_key", "YOUR_KEY"); settings.save()
        pb.set("put", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String file = a.checkjstring(1);
                String name = a.optjstring(2, file);
                String code = os.getFileSystem().readFile(file);
                if (code == null) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("File not found: " + file));
                }
                LuaValue settings = globals.get("settings");
                if (settings.isnil()) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("settings API unavailable"));
                }
                LuaValue keyV = settings.get("get").call(LuaValue.valueOf("pastebin.dev_key"));
                if (keyV.isnil() || keyV.tojstring().isEmpty()) {
                    return LuaValue.varargsOf(LuaValue.NIL,
                        LuaValue.valueOf("No pastebin.dev_key set. Use: settings.set('pastebin.dev_key', '<key>'); settings.save()"));
                }
                LuaValue userV = settings.get("get").call(LuaValue.valueOf("pastebin.user_key"));
                try {
                    StringBuilder form = new StringBuilder();
                    form.append("api_dev_key=").append(urlEnc(keyV.tojstring()));
                    form.append("&api_option=paste");
                    form.append("&api_paste_code=").append(urlEnc(code));
                    form.append("&api_paste_name=").append(urlEnc(name));
                    form.append("&api_paste_format=lua");
                    form.append("&api_paste_private=0"); // public
                    if (!userV.isnil() && !userV.tojstring().isEmpty()) {
                        form.append("&api_user_key=").append(urlEnc(userV.tojstring()));
                    }
                    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("https://pastebin.com/api/api_post.php"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form.toString()))
                        .build();
                    java.net.http.HttpResponse<String> resp = client.send(req,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                    String body = resp.body();
                    if (resp.statusCode() != 200 || body.startsWith("Bad API request")) {
                        return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(body));
                    }
                    // Pastebin returns a full URL; return just the ID.
                    String id = body.contains("/") ? body.substring(body.lastIndexOf('/') + 1).trim() : body.trim();
                    return LuaValue.valueOf(id);
                } catch (Exception e) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(e.getMessage()));
                }
            }
        });
        globals.set("pastebin", pb);
        installGistAPI();
    }

    // ---------- gist (GitHub Gists, anonymous read; auth token optional for write) ----------
    // gist.get(idOrUrl [, file]) -> content, err
    // gist.run(idOrUrl [, args...]) -> ...
    // gist.put(path [, name [, public]]) -> id, err   (requires settings.set("gist.token", "..."))
    // gist.download(idOrUrl, path [, file]) -> true|false, err
    private void installGistAPI() {
        LuaTable g = new LuaTable();
        final java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

        g.set("get", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String id = extractGistId(a.checkjstring(1));
                String wantFile = a.optjstring(2, null);
                try {
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("https://api.github.com/gists/" + id))
                        .header("Accept", "application/vnd.github+json")
                        .GET().build();
                    java.net.http.HttpResponse<String> resp = client.send(req,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200)
                        return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("HTTP " + resp.statusCode()));
                    // Quick & dirty: pull "raw_url" of first (or named) file out of the JSON.
                    String body = resp.body();
                    int filesIdx = body.indexOf("\"files\"");
                    if (filesIdx < 0) return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("no files in gist"));
                    String rawUrl;
                    if (wantFile != null) {
                        int fIdx = body.indexOf("\"" + wantFile + "\"", filesIdx);
                        if (fIdx < 0) return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("file not in gist"));
                        rawUrl = extractJsonString(body, "raw_url", fIdx);
                    } else {
                        rawUrl = extractJsonString(body, "raw_url", filesIdx);
                    }
                    if (rawUrl == null) return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("raw_url missing"));
                    java.net.http.HttpRequest rawReq = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(rawUrl)).GET().build();
                    java.net.http.HttpResponse<String> raw = client.send(rawReq,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (raw.statusCode() != 200)
                        return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("HTTP " + raw.statusCode()));
                    return LuaValue.valueOf(raw.body());
                } catch (Exception e) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(e.getMessage()));
                }
            }
        });
        g.set("download", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                Varargs got = ((VarArgFunction) g.get("get")).invoke(LuaValue.varargsOf(new LuaValue[]{a.arg(1), a.arg(3).isnil() ? LuaValue.NIL : a.arg(3)}));
                if (got.arg1().isnil()) return got;
                os.getFileSystem().writeFile(a.checkjstring(2), got.arg1().tojstring());
                return LuaValue.TRUE;
            }
        });
        g.set("run", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                Varargs got = ((VarArgFunction) g.get("get")).invoke(LuaValue.varargsOf(new LuaValue[]{a.arg(1)}));
                if (got.arg1().isnil()) return got;
                try {
                    LuaValue chunk = globals.load(got.arg1().tojstring(), "=gist", globals);
                    return chunk.invoke(a.subargs(2));
                } catch (LuaError e) { return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(e.getMessage())); }
            }
        });
        g.set("put", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String path = a.checkjstring(1);
                String name = a.optjstring(2, path.substring(path.lastIndexOf('/') + 1));
                boolean isPublic = a.optboolean(3, false);
                String content = os.getFileSystem().readFile(path);
                if (content == null) return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("file not found"));
                String token = settingsGetString("gist.token", "");
                if (token.isEmpty())
                    return LuaValue.varargsOf(LuaValue.NIL,
                        LuaValue.valueOf("set settings: gist.token = \"<github PAT>\""));
                String json = "{\"public\":" + isPublic + ",\"files\":{\""
                    + jsonEscape(name) + "\":{\"content\":\"" + jsonEscape(content) + "\"}}}";
                try {
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("https://api.github.com/gists"))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build();
                    java.net.http.HttpResponse<String> resp = client.send(req,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() / 100 != 2)
                        return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("HTTP " + resp.statusCode()));
                    String id = extractJsonString(resp.body(), "id", 0);
                    return LuaValue.valueOf(id == null ? "" : id);
                } catch (Exception e) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(e.getMessage()));
                }
            }
        });
        globals.set("gist", g);
    }

    private static String extractGistId(String s) {
        if (s == null) return "";
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static String extractJsonString(String body, String key, int fromIdx) {
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle, fromIdx);
        if (k < 0) return null;
        int colon = body.indexOf(':', k);
        if (colon < 0) return null;
        int q1 = body.indexOf('"', colon);
        if (q1 < 0) return null;
        StringBuilder out = new StringBuilder();
        int i = q1 + 1;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char n = body.charAt(i + 1);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    default -> out.append(n);
                }
                i += 2;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
                i++;
            }
        }
        return null;
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private String settingsGetString(String key, String defaultValue) {
        LuaValue settings = globals.get("settings");
        if (settings.isnil()) return defaultValue;
        LuaValue get = settings.get("get");
        if (get.isnil() || !get.isfunction()) return defaultValue;
        try {
            LuaValue v = get.call(LuaValue.valueOf(key));
            return v.isnil() ? defaultValue : v.tojstring();
        } catch (Exception e) { return defaultValue; }
    }

    private static String urlEnc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------- wget global ----------
    private void installWgetGlobal() {
        // CC: wget <url> [filename], also wget run <url> [args...]
        globals.set("wget", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                if (a.narg() == 0) {
                    pushOutput("Usage: wget <url> [filename]\n       wget run <url> [args...]\n");
                    return NONE;
                }
                String first = a.arg(1).tojstring();
                boolean runMode = first.equals("run");
                String url = runMode ? a.checkjstring(2) : first;
                try {
                    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                    java.net.http.HttpResponse<String> resp = client.send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) {
                        pushOutput("wget: HTTP " + resp.statusCode() + "\n");
                        return LuaValue.FALSE;
                    }
                    if (runMode) {
                        try {
                            LuaValue chunk = globals.load(resp.body(), "=wget", globals);
                            return chunk.invoke(a.subargs(3));
                        } catch (LuaError e) {
                            pushOutput("wget run: " + e.getMessage() + "\n");
                            return LuaValue.FALSE;
                        }
                    }
                    String name = a.optjstring(2, url.substring(url.lastIndexOf('/') + 1));
                    if (name.isEmpty()) name = "download";
                    String path = name.startsWith("/") ? name : "/Users/User/" + name;
                    os.getFileSystem().writeFile(path, resp.body());
                    pushOutput("Downloaded " + resp.body().length() + " bytes to " + path + "\n");
                    return LuaValue.TRUE;
                } catch (Exception e) {
                    pushOutput("wget: " + e.getMessage() + "\n");
                    return LuaValue.FALSE;
                }
            }
        });
    }

    // ---------- read() ----------
    private void installReadGlobal() {
        // CC read([replaceChar[, history[, completeFn[, default]]]])
        // Yields on char / key / paste events, builds a line, returns on Enter.
        globals.set("read", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                String replaceChar = (a.arg(1).isstring() && !a.arg(1).tojstring().isEmpty())
                    ? a.arg(1).tojstring().substring(0, 1) : null;
                LuaTable history = a.arg(2).istable() ? a.arg(2).checktable() : null;
                String def = a.arg(4).isstring() ? a.arg(4).tojstring() : "";

                StringBuilder buf = new StringBuilder(def);
                int historyIdx = history == null ? 0 : history.length() + 1;
                // Echo any default.
                if (!def.isEmpty()) {
                    pushOutput(replaceChar != null ? replaceChar.repeat(def.length()) : def);
                }
                while (true) {
                    Varargs evt = globals.running.state.lua_yield(LuaValue.NONE);
                    String n = evt.arg1().isstring() ? evt.arg1().tojstring() : "";
                    if ("terminate".equals(n)) throw new LuaError("Terminated");
                    switch (n) {
                        case "char" -> {
                            String c = evt.arg(2).isstring() ? evt.arg(2).tojstring() : "";
                            if (!c.isEmpty()) {
                                buf.append(c);
                                pushOutput(replaceChar != null ? replaceChar : c);
                            }
                        }
                        case "paste" -> {
                            String p = evt.arg(2).isstring() ? evt.arg(2).tojstring() : "";
                            buf.append(p);
                            pushOutput(replaceChar != null ? replaceChar.repeat(p.length()) : p);
                        }
                        case "key" -> {
                            int k = evt.arg(2).toint();
                            if (k == 257 || k == 335) {          // Enter
                                pushOutput("\n");
                                return LuaValue.valueOf(buf.toString());
                            } else if (k == 259) {                // Backspace
                                if (buf.length() > 0) {
                                    buf.deleteCharAt(buf.length() - 1);
                                    pushOutput("\b \b");
                                }
                            } else if (k == 265 && history != null) { // Up
                                if (historyIdx > 1) {
                                    historyIdx--;
                                    LuaValue h = history.get(historyIdx);
                                    if (h.isstring()) {
                                        // Clear current line visually with backspaces.
                                        for (int i = 0; i < buf.length(); i++) pushOutput("\b \b");
                                        buf.setLength(0);
                                        buf.append(h.tojstring());
                                        pushOutput(replaceChar != null
                                            ? replaceChar.repeat(buf.length()) : buf.toString());
                                    }
                                }
                            } else if (k == 264 && history != null) { // Down
                                if (historyIdx < history.length()) {
                                    historyIdx++;
                                    LuaValue h = history.get(historyIdx);
                                    for (int i = 0; i < buf.length(); i++) pushOutput("\b \b");
                                    buf.setLength(0);
                                    if (h.isstring()) {
                                        buf.append(h.tojstring());
                                        pushOutput(replaceChar != null
                                            ? replaceChar.repeat(buf.length()) : buf.toString());
                                    }
                                } else if (historyIdx == history.length()) {
                                    historyIdx++;
                                    for (int i = 0; i < buf.length(); i++) pushOutput("\b \b");
                                    buf.setLength(0);
                                }
                            }
                        }
                        default -> { /* ignore */ }
                    }
                }
            }
        });
        globals.set("printError", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                pushOutput(a.arg(1).tojstring() + "\n");
                return NONE;
            }
        });
    }

    // ---------- help ----------
    private void installHelpAPI() {
        LuaTable help = new LuaTable();
        final String[] topics = {
            "term","fs","os","colors","shell","textutils","gps","bluetooth","rednet","redstone","relay",
            "buttons","robot","drone","scanner","peripheral","glasses","parallel","vector","keys",
            "paintutils","window","settings","turtle","http","pastebin","wget"
        };
        help.set("topics", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable t = new LuaTable();
                for (int i = 0; i < topics.length; i++) t.set(i + 1, LuaValue.valueOf(topics[i]));
                return t;
            }
        });
        help.set("lookup", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                String q = v.tojstring();
                for (String t : topics) if (t.equals(q)) return LuaValue.valueOf(t);
                return LuaValue.NIL;
            }
        });
        help.set("path", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf("/rom/help"); }
        });
        help.set("setPath", new OneArgFunction() { @Override public LuaValue call(LuaValue v) { return NONE; } });

        // help.peripherals() — list connected peripherals as { side = type, ... }
        help.set("peripherals", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable out = new LuaTable();
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return out;
                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    var r = com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                        .find(lvl, pos, dir);
                    if (r != null) {
                        out.set(com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                                  .directionToSide(dir),
                                LuaValue.valueOf(r.getType()));
                    }
                }
                return out;
            }
        });

        // help.peripheralMethods(side) — list method names on the peripheral on that side.
        help.set("peripheralMethods", new OneArgFunction() {
            @Override public LuaValue call(LuaValue side) {
                LuaTable out = new LuaTable();
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return out;
                var r = com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                    .findBySide(lvl, pos, side.checkjstring());
                if (r == null) return out;
                org.luaj.vm2.LuaTable tbl = r.buildTable(os);
                java.util.List<String> names = new java.util.ArrayList<>();
                for (LuaValue k = tbl.next(LuaValue.NIL).arg1();
                     !k.isnil();
                     k = tbl.next(k).arg1()) {
                    if (k.isstring()) names.add(k.tojstring());
                }
                java.util.Collections.sort(names);
                for (int i = 0; i < names.size(); i++) {
                    out.set(i + 1, LuaValue.valueOf(names.get(i)));
                }
                return out;
            }
        });

        globals.set("help", help);
    }

    // ---------- multishell stub ----------
    private void installMultishellStub() {
        LuaTable ms = new LuaTable();
        ms.set("getCount", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(1 + msTasks.size());
            }
        });
        ms.set("getCurrent", new ZeroArgFunction() {
            @Override public LuaValue call() {
                // There's no simple way to determine which task is calling;
                // report focus to keep CC compat for most uses.
                return LuaValue.valueOf(msFocusId == 0 ? 1 : msFocusId);
            }
        });
        ms.set("getFocus", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(msFocusId == 0 ? 1 : msFocusId);
            }
        });
        ms.set("setFocus", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                int id = v.checkint();
                if (id == 1 || id == 0) { msFocusId = 0; return LuaValue.TRUE; }
                for (MsTask t : msTasks) {
                    if (t.id == id && t.alive) { msFocusId = id; return LuaValue.TRUE; }
                }
                return LuaValue.FALSE;
            }
        });
        ms.set("getTitle", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int id = a.checkint(1);
                if (id == 1) return LuaValue.valueOf("shell");
                for (MsTask t : msTasks) if (t.id == id) return LuaValue.valueOf(t.title);
                return LuaValue.NIL;
            }
        });
        ms.set("setTitle", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int id = a.checkint(1);
                String title = a.checkjstring(2);
                for (MsTask t : msTasks) if (t.id == id) { t.title = title; return NONE; }
                return NONE;
            }
        });
        // multishell.launch(env, path, ...)
        //   env is an optional table (ignored — we use shared globals).
        //   path is the .lua file to execute.
        //   Additional args forwarded to the chunk.
        ms.set("launch", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int pathArg = a.arg(1).istable() ? 2 : 1;
                String path = a.checkjstring(pathArg);
                String content = os.getFileSystem().readFile(path);
                if (content == null) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("File not found: " + path));
                }
                String title = path;
                int slash = path.lastIndexOf('/');
                if (slash >= 0) title = path.substring(slash + 1);
                int dot = title.lastIndexOf('.');
                if (dot > 0) title = title.substring(0, dot);
                int id = msLaunch(content, path, title, a.subargs(pathArg + 1));
                return LuaValue.valueOf(id);
            }
        });
        globals.set("multishell", ms);
    }

    // =========================================================================
    //  JSON helpers (used by textutils, settings, pastebin)
    // =========================================================================

    private static String luaToJson(LuaValue v) {
        StringBuilder sb = new StringBuilder();
        writeJson(v, sb);
        return sb.toString();
    }

    private static void writeJson(LuaValue v, StringBuilder sb) {
        if (v.isnil())           { sb.append("null"); return; }
        if (v.isboolean())       { sb.append(v.toboolean() ? "true" : "false"); return; }
        if (v.isnumber())        { sb.append(v.tojstring()); return; }
        if (v.isstring())        { jsonEscape(v.tojstring(), sb); return; }
        if (v.istable()) {
            LuaTable t = v.checktable();
            // CC sentinels: textutils.empty_json_array and textutils.json_null
            // are tagged via metatable __name. Honor them so callers can force
            // empty array / explicit null encodings.
            LuaValue mt = t.getmetatable();
            if (mt != null && mt.istable()) {
                LuaValue nm = mt.get("__name");
                if (nm.isstring()) {
                    String name = nm.tojstring();
                    if ("json_null".equals(name))         { sb.append("null"); return; }
                    if ("empty_json_array".equals(name))  { sb.append("[]"); return; }
                }
            }
            int len = t.length();
            boolean asArray = len > 0;
            // Determine if keys are all 1..len
            if (asArray) {
                for (int i = 1; i <= len; i++) {
                    if (t.get(i).isnil()) { asArray = false; break; }
                }
            }
            if (asArray) {
                sb.append('[');
                for (int i = 1; i <= len; i++) {
                    if (i > 1) sb.append(',');
                    writeJson(t.get(i), sb);
                }
                sb.append(']');
            } else {
                sb.append('{');
                boolean first = true;
                LuaValue k = LuaValue.NIL;
                while (true) {
                    Varargs n = t.next(k);
                    k = n.arg1(); if (k.isnil()) break;
                    if (!first) sb.append(',');
                    first = false;
                    jsonEscape(k.tojstring(), sb);
                    sb.append(':');
                    writeJson(n.arg(2), sb);
                }
                sb.append('}');
            }
            return;
        }
        sb.append("null");
    }

    private static void jsonEscape(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static LuaValue jsonToLua(String s) {
        int[] idx = {0};
        skipWs(s, idx);
        LuaValue v = parseJson(s, idx);
        return v;
    }

    private static LuaValue parseJson(String s, int[] idx) {
        skipWs(s, idx);
        if (idx[0] >= s.length()) return LuaValue.NIL;
        char c = s.charAt(idx[0]);
        if (c == '{') return parseJsonObject(s, idx);
        if (c == '[') return parseJsonArray(s, idx);
        if (c == '"') return LuaValue.valueOf(parseJsonString(s, idx));
        if (c == 't' || c == 'f') return parseJsonBool(s, idx);
        if (c == 'n') { idx[0] += 4; return LuaValue.NIL; }
        return parseJsonNumber(s, idx);
    }

    private static LuaValue parseJsonObject(String s, int[] idx) {
        LuaTable t = new LuaTable();
        idx[0]++; skipWs(s, idx);
        if (idx[0] < s.length() && s.charAt(idx[0]) == '}') { idx[0]++; return t; }
        while (idx[0] < s.length()) {
            skipWs(s, idx);
            String k = parseJsonString(s, idx);
            skipWs(s, idx);
            if (idx[0] < s.length() && s.charAt(idx[0]) == ':') idx[0]++;
            LuaValue v = parseJson(s, idx);
            t.set(k, v);
            skipWs(s, idx);
            if (idx[0] < s.length() && s.charAt(idx[0]) == ',') { idx[0]++; continue; }
            if (idx[0] < s.length() && s.charAt(idx[0]) == '}') { idx[0]++; break; }
            break;
        }
        return t;
    }

    private static LuaValue parseJsonArray(String s, int[] idx) {
        LuaTable t = new LuaTable();
        idx[0]++; int i = 1;
        skipWs(s, idx);
        if (idx[0] < s.length() && s.charAt(idx[0]) == ']') { idx[0]++; return t; }
        while (idx[0] < s.length()) {
            LuaValue v = parseJson(s, idx);
            t.set(i++, v);
            skipWs(s, idx);
            if (idx[0] < s.length() && s.charAt(idx[0]) == ',') { idx[0]++; continue; }
            if (idx[0] < s.length() && s.charAt(idx[0]) == ']') { idx[0]++; break; }
            break;
        }
        return t;
    }

    private static String parseJsonString(String s, int[] idx) {
        StringBuilder sb = new StringBuilder();
        if (idx[0] < s.length() && s.charAt(idx[0]) == '"') idx[0]++;
        while (idx[0] < s.length()) {
            char c = s.charAt(idx[0]++);
            if (c == '"') break;
            if (c == '\\' && idx[0] < s.length()) {
                char esc = s.charAt(idx[0]++);
                switch (esc) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/');  break;
                    case 'u':
                        if (idx[0] + 4 <= s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(idx[0], idx[0] + 4), 16));
                            idx[0] += 4;
                        }
                        break;
                    default: sb.append(esc);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    private static LuaValue parseJsonBool(String s, int[] idx) {
        if (s.startsWith("true", idx[0]))  { idx[0] += 4; return LuaValue.TRUE; }
        if (s.startsWith("false", idx[0])) { idx[0] += 5; return LuaValue.FALSE; }
        return LuaValue.FALSE;
    }

    private static LuaValue parseJsonNumber(String s, int[] idx) {
        int start = idx[0];
        while (idx[0] < s.length()) {
            char c = s.charAt(idx[0]);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || Character.isDigit(c)) idx[0]++;
            else break;
        }
        try { return LuaValue.valueOf(Double.parseDouble(s.substring(start, idx[0]))); }
        catch (NumberFormatException e) { return LuaValue.valueOf(0); }
    }

    private static void skipWs(String s, int[] idx) {
        while (idx[0] < s.length() && Character.isWhitespace(s.charAt(idx[0]))) idx[0]++;
    }
}

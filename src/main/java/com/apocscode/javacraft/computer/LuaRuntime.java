package com.apocscode.javacraft.computer;

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
 * Lua 5.2 scripting runtime for JavaCraft computers.
 * Wraps LuaJ and exposes CC-style APIs: term, fs, os, shell, colors, peripheral.
 */
public class LuaRuntime {

    private final JavaOS os;
    private final Globals globals;
    private final ArrayBlockingQueue<String> outputQueue = new ArrayBlockingQueue<>(512);

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
                int ticks = Math.max(1, (int)(secs * 20));
                os.startTimer(secs);
                return NONE;
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
                // Non-blocking: just starts a timer. The shell handles the actual wait.
                os.startTimer(arg.optdouble(1.0));
                return NONE;
            }
        });

        osTable.set("version", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf("JavaCraft 1.0 (Lua 5.2)"); }
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

        globals.set("colors", colors);
        globals.set("colours", colors);
    }

    private void installShellAPI() {
        LuaTable shell = new LuaTable();
        final String[] currentDir = {"/"};

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
        shell.set("run", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String path = args.checkjstring(1);
                String resolved = path.startsWith("/") ? path :
                    (currentDir[0].equals("/") ? "/" + path : currentDir[0] + "/" + path);
                String content = os.getFileSystem().readFile(resolved);
                if (content == null) {
                    pushOutput("File not found: " + resolved);
                    return LuaValue.FALSE;
                }
                LuaValue result = execute(content, resolved);
                return result != null ? LuaValue.TRUE : LuaValue.FALSE;
            }
        });

        globals.set("shell", shell);
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

    // --- CC Color Mapping ---
    // CC uses power-of-2 bitmask colors, we use 0-15 indices

    private int ccColorToIndex(int ccColor) {
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
}

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
            @Override public LuaValue call() { return LuaValue.valueOf("ByteBlock 1.0 (Lua 5.2)"); }
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

        globals.set("robot", robot);
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
                org.luaj.vm2.LuaTable tbl = result.buildTable();
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
                return result.buildTable();
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
                org.luaj.vm2.LuaTable tbl = result.buildTable();
                LuaValue fn = tbl.get(args.checkjstring(2));
                if (fn.isnil() || !fn.isfunction()) return LuaValue.NIL;
                return fn.invoke(args.subargs(3));
            }
        });

        // peripheral.find(type) → method table | nil
        // Scans all 6 sides, returns the first peripheral of the requested type.
        peripheral.set("find", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue type) {
                net.minecraft.world.level.Level lvl = os.getLevel();
                net.minecraft.core.BlockPos pos = os.getBlockPos();
                if (lvl == null || pos == null) return LuaValue.NIL;
                var result = com.apocscode.byteblock.computer.peripheral.PeripheralRegistry
                        .findByType(lvl, pos, type.checkjstring());
                return result != null ? result.buildTable() : LuaValue.NIL;
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

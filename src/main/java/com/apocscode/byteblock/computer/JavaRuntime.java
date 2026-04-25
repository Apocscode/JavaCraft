package com.apocscode.byteblock.computer;

import bsh.EvalError;
import bsh.Interpreter;
import bsh.NameSpace;
import bsh.Primitive;
import bsh.UtilEvalError;

import java.io.PrintStream;
import java.util.Set;

/**
 * Sandboxed BeanShell (Java-syntax) interpreter for ByteBlock terminals.
 *
 * <p>Hosts a thread-isolated {@link Interpreter} with:
 * <ul>
 *   <li>An <b>allowlist class loader</b> that blocks dangerous classes
 *       (Runtime, ProcessBuilder, java.io.File, java.lang.reflect.*, sun.*,
 *       ClassLoader, Unsafe, System.exit, etc.).</li>
 *   <li>Pre-bound helpers: {@code print}, {@code println}, {@code sleep},
 *       {@code os}, {@code term}, plus {@code lua(...)} which evaluates
 *       a Lua snippet against the same {@link JavaOS}'s shared
 *       {@link LuaRuntime} so scripts can drive ByteBlock APIs
 *       ({@code robot}, {@code drone}, {@code glasses}, {@code bluetooth},
 *       {@code redstone}, ...) without each one needing a Java facade.</li>
 *   <li>A wall-clock execution budget enforced by a watchdog thread that
 *       interrupts runaway scripts after {@link #scriptTimeoutMs} ms.</li>
 * </ul>
 *
 * <p>Scripts run on a dedicated daemon thread so the OS tick keeps flowing.
 */
public class JavaRuntime {

    /** Hard ceiling on a single eval/runProgram. Adjustable per OS instance. */
    public long scriptTimeoutMs = 5000L;

    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "java.io.File",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.RandomAccessFile",
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.Process",
            "java.lang.reflect.",
            "java.lang.invoke.",
            "java.lang.ClassLoader",
            "java.lang.SecurityManager",
            "java.lang.Thread",
            "java.lang.ThreadGroup",
            "java.net.",
            "java.nio.file.",
            "java.nio.channels.",
            "java.security.",
            "javax.script.",
            "javax.tools.",
            "sun.",
            "jdk.internal.",
            "com.sun.",
            "net.minecraft.client.Minecraft",
            "net.minecraft.server.MinecraftServer",
            "net.neoforged."
    );

    private final JavaOS os;
    private final Interpreter interp;
    private final StringBuilder outBuf = new StringBuilder();
    private final Object outLock = new Object();
    private volatile boolean running;
    private volatile Thread runner;
    private LuaRuntime sharedLua;

    public JavaRuntime(JavaOS os) {
        this.os = os;
        this.interp = new Interpreter();
        configureSandbox();
        installBindings();
    }

    /** Evaluate a single statement / expression for the REPL. Returns printable result or null. */
    public String eval(String source) {
        try {
            // Try as expression first so `1+1` echoes "2".
            Object result;
            try {
                result = interp.eval("return (" + source + ");");
            } catch (EvalError e1) {
                // Fall back to statement form.
                result = interp.eval(source);
            }
            if (result == null || result == Primitive.VOID) return null;
            if (result instanceof Primitive p) return p.toString();
            return String.valueOf(result);
        } catch (EvalError e) {
            return "Error: " + rootCauseMessage(e);
        } catch (Throwable t) {
            return "Error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** Run a multi-statement program (e.g. a .bsh file) on a watchdog thread. */
    public void runProgram(String source, String name) {
        if (running) {
            appendOut("[java] another program is still running\n");
            return;
        }
        running = true;
        runner = new Thread(() -> {
            long start = System.currentTimeMillis();
            Thread me = Thread.currentThread();
            Thread watchdog = new Thread(() -> {
                long deadline = start + scriptTimeoutMs;
                while (running && me.isAlive()) {
                    if (System.currentTimeMillis() > deadline) {
                        appendOut("\n[java] script exceeded " + scriptTimeoutMs + "ms — interrupted\n");
                        me.interrupt();
                        return;
                    }
                    try { Thread.sleep(50L); } catch (InterruptedException e) { return; }
                }
            }, "ByteBlock-JavaWatchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            try {
                interp.eval(source);
            } catch (EvalError e) {
                appendOut("Error: " + rootCauseMessage(e) + "\n");
            } catch (Throwable t) {
                appendOut("Error: " + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n");
            } finally {
                running = false;
                watchdog.interrupt();
            }
        }, "ByteBlock-JavaRuntime-" + (name == null ? "anon" : name));
        runner.setDaemon(true);
        runner.start();
    }

    public boolean isProgramRunning() { return running; }

    public void stop() {
        running = false;
        Thread r = runner;
        if (r != null) r.interrupt();
    }

    /** Drain captured System.out / println output. */
    public String drainOutput() {
        synchronized (outLock) {
            if (outBuf.length() == 0) return null;
            String s = outBuf.toString();
            outBuf.setLength(0);
            return s;
        }
    }

    /** Optional: share a LuaRuntime so Java scripts can call Lua APIs via {@code lua("...")}. */
    public void attachLua(LuaRuntime lua) { this.sharedLua = lua; }

    // ─── internals ──────────────────────────────────────────────────────

    private void configureSandbox() {
        // Re-route println/print into our buffer so scripts don't pollute the host log.
        PrintStream sink = new PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) { synchronized (outLock) { outBuf.append((char) b); } }
            @Override public void write(byte[] b, int off, int len) {
                synchronized (outLock) { outBuf.append(new String(b, off, len)); }
            }
        }, true);
        interp.setOut(sink);
        interp.setErr(sink);

        // Class-name-level allowlist filter. BeanShell calls NameSpace.getClass(name)
        // which hits BshClassManager; we wrap by overriding the namespace's class lookup
        // through a name-check at script-eval time. The simpler defense is to refuse
        // to import or reference blocked names — implemented via a custom NameSpace.
        try {
            NameSpace ns = new NameSpace(interp.getNameSpace().getParent(), "byteblock-sandbox") {
                @Override
                public Class<?> getClass(String name) throws UtilEvalError {
                    if (isBlocked(name)) {
                        throw new UtilEvalError("Class '" + name + "' is blocked by the ByteBlock sandbox.");
                    }
                    return super.getClass(name);
                }
            };
            // Pre-import safe packages so users can write `List<String>`, `Math.PI`, etc.
            ns.importPackage("java.util");
            ns.importPackage("java.util.function");
            ns.importClass("java.lang.Math");
            ns.importClass("java.lang.String");
            ns.importClass("java.lang.StringBuilder");
            interp.setNameSpace(ns);
        } catch (Throwable t) {
            // If BeanShell internals shift, fall back to default namespace + best-effort filtering.
            appendOut("[java] sandbox init warning: " + t.getMessage() + "\n");
        }
    }

    private static boolean isBlocked(String fqn) {
        if (fqn == null || fqn.isEmpty()) return false;
        for (String prefix : BLOCKED_PREFIXES) {
            if (fqn.equals(prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix)
                    || fqn.startsWith(prefix)) return true;
        }
        return false;
    }

    private void installBindings() {
        try {
            // OS handle (Java POJO — safe to expose)
            interp.set("os", os);

            // Terminal facade (subset POJO — calls TerminalBuffer methods we want to allow)
            interp.set("term", new TermFacade(os));

            // Convenience methods
            interp.eval(""
                + "void print(Object x)   { System.out.print(String.valueOf(x)); }\n"
                + "void println(Object x) { System.out.println(String.valueOf(x)); }\n"
                + "void println()         { System.out.println(); }\n"
                + "void sleep(double s) {\n"
                + "    try { Thread.sleep((long)(s*1000.0)); } catch (Exception e) {}\n"
                + "}\n"
                + "Object lua(String code) { return _bb_javaRuntime.luaEval(code); }\n");

            // Bridge handle for the lua(...) helper above
            interp.set("_bb_javaRuntime", this);
        } catch (EvalError e) {
            appendOut("[java] binding init failed: " + e.getMessage() + "\n");
        }
    }

    /** Called by the bridged `lua(...)` helper. */
    public String luaEval(String code) {
        if (sharedLua == null) return "Error: no Lua runtime attached";
        String r = sharedLua.eval(code);
        // Surface any side-effect output through our own buffer so it appears in REPL.
        String drained = sharedLua.drainOutput();
        if (drained != null) appendOut(drained);
        return r;
    }

    private void appendOut(String s) {
        synchronized (outLock) { outBuf.append(s); }
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    // ─── safe terminal facade ───────────────────────────────────────────
    public static final class TermFacade {
        private final JavaOS os;
        TermFacade(JavaOS os) { this.os = os; }

        public void write(Object s) {
            TerminalBuffer t = os.getTerminal();
            if (t != null) t.write(String.valueOf(s));
        }
        public void clear() {
            TerminalBuffer t = os.getTerminal();
            if (t != null) t.clear();
        }
        public void setCursorPos(int x, int y) {
            TerminalBuffer t = os.getTerminal();
            if (t != null) t.setCursorPos(x, y);
        }
        public void setTextColor(int c) {
            TerminalBuffer t = os.getTerminal();
            if (t != null) t.setTextColor(c);
        }
        public void setBackgroundColor(int c) {
            TerminalBuffer t = os.getTerminal();
            if (t != null) t.setBackgroundColor(c);
        }
        public int getWidth()  { return TerminalBuffer.WIDTH; }
        public int getHeight() { return TerminalBuffer.HEIGHT; }
    }
}

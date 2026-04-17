package com.apocscode.javacraft.computer.programs;

import com.apocscode.javacraft.computer.*;

/**
 * Interactive Lua REPL shell for JavaCraft OS.
 * CC-compatible Lua 5.2 environment with term/fs/os/colors APIs.
 * Supports both interactive REPL and running .lua files.
 */
public class LuaShellProgram extends OSProgram {

    private LuaRuntime lua;
    private final StringBuilder inputBuffer = new StringBuilder();
    private final StringBuilder outputBuffer = new StringBuilder();
    private int scrollOffset = 0;
    private boolean needsRedraw = true;
    private final java.util.List<String> history = new java.util.ArrayList<>();
    private int historyIndex = -1;

    // Optional: run a file immediately on launch
    private final String runFile;

    public LuaShellProgram() {
        super("Lua");
        this.runFile = null;
    }

    /**
     * Launch and immediately execute a .lua file.
     */
    public LuaShellProgram(String filePath) {
        super("Lua");
        this.runFile = filePath;
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        this.lua = new LuaRuntime(os);

        appendOutput("JavaCraft Lua 5.2 Shell\n");
        appendOutput("CC-style APIs: term, fs, os, colors, shell\n");
        appendOutput("Type 'exit' to close, 'help' for commands.\n\n");

        if (runFile != null) {
            appendOutput("Running: " + runFile + "\n");
            String content = os.getFileSystem().readFile(runFile);
            if (content != null) {
                lua.execute(content, runFile);
                drainLuaOutput();
                appendOutput("\n-- Program finished --\n");
            } else {
                appendOutput("File not found: " + runFile + "\n");
            }
        }
    }

    @Override
    public boolean tick() {
        drainLuaOutput();
        return running;
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case CHAR -> {
                char c = event.getString(0).charAt(0);
                inputBuffer.append(c);
                needsRedraw = true;
            }
            case KEY -> handleKey(event.getInt(0));
            case MOUSE_SCROLL -> {
                int dir = event.getInt(0);
                scrollOffset = Math.max(0, scrollOffset + dir);
                needsRedraw = true;
            }
            default -> {}
        }
    }

    private void handleKey(int keyCode) {
        switch (keyCode) {
            case 259 -> { // Backspace
                if (!inputBuffer.isEmpty()) {
                    inputBuffer.deleteCharAt(inputBuffer.length() - 1);
                    needsRedraw = true;
                }
            }
            case 257, 335 -> { // Enter
                String input = inputBuffer.toString().trim();
                inputBuffer.setLength(0);
                appendOutput("lua> " + input + "\n");
                if (!input.isEmpty()) {
                    history.add(input);
                    historyIndex = history.size();
                    executeInput(input);
                }
                needsRedraw = true;
            }
            case 265 -> { // Up — history
                if (!history.isEmpty() && historyIndex > 0) {
                    historyIndex--;
                    inputBuffer.setLength(0);
                    inputBuffer.append(history.get(historyIndex));
                    needsRedraw = true;
                }
            }
            case 264 -> { // Down — history
                if (historyIndex < history.size() - 1) {
                    historyIndex++;
                    inputBuffer.setLength(0);
                    inputBuffer.append(history.get(historyIndex));
                } else {
                    historyIndex = history.size();
                    inputBuffer.setLength(0);
                }
                needsRedraw = true;
            }
        }
    }

    private void executeInput(String input) {
        // Built-in commands
        if (input.equals("exit") || input.equals("quit")) {
            running = false;
            return;
        }
        if (input.equals("help")) {
            appendOutput("=== Lua Shell Commands ===\n");
            appendOutput("  exit          Close Lua shell\n");
            appendOutput("  help          Show this help\n");
            appendOutput("  run <file>    Execute a .lua file\n");
            appendOutput("  clear         Clear screen\n");
            appendOutput("=== CC APIs Available ===\n");
            appendOutput("  term.write(s), term.clear()\n");
            appendOutput("  term.setCursorPos(x,y)\n");
            appendOutput("  term.setTextColor(colors.X)\n");
            appendOutput("  term.setBackgroundColor(colors.X)\n");
            appendOutput("  term.getSize(), term.scroll(n)\n");
            appendOutput("  fs.list(p), fs.exists(p)\n");
            appendOutput("  fs.open(p,m), fs.makeDir(p)\n");
            appendOutput("  fs.delete(p), fs.copy(s,d)\n");
            appendOutput("  os.clock(), os.startTimer(s)\n");
            appendOutput("  os.getComputerID/Label()\n");
            appendOutput("  os.shutdown(), os.reboot()\n");
            appendOutput("  colors.white/red/blue/...\n");
            appendOutput("  shell.dir(), shell.run(f)\n");
            appendOutput("  print(...), write(...)\n");
            appendOutput("  sleep(n), textutils.serialize(t)\n");
            return;
        }
        if (input.equals("clear") || input.equals("cls")) {
            outputBuffer.setLength(0);
            scrollOffset = 0;
            needsRedraw = true;
            return;
        }
        if (input.startsWith("run ")) {
            String file = input.substring(4).trim();
            String content = os.getFileSystem().readFile(file);
            if (content == null) {
                appendOutput("File not found: " + file + "\n");
            } else {
                lua.execute(content, file);
                drainLuaOutput();
            }
            return;
        }

        // Evaluate as Lua
        String result = lua.eval(input);
        drainLuaOutput();
        if (result != null) {
            appendOutput(result + "\n");
        }
    }

    private void drainLuaOutput() {
        String out = lua.drainOutput();
        if (out != null) {
            appendOutput(out);
            if (!out.endsWith("\n")) appendOutput("\n");
        }
    }

    private void appendOutput(String text) {
        outputBuffer.append(text);
        if (outputBuffer.length() > 10000) {
            outputBuffer.delete(0, outputBuffer.length() - 8000);
        }
        scrollOffset = 0;
        needsRedraw = true;
    }

    @Override
    public void render(TerminalBuffer buf) {
        if (!needsRedraw) return;
        needsRedraw = false;

        buf.setTextColor(0);  // white
        buf.setBackgroundColor(15); // black
        buf.clear();

        String display = outputBuffer.toString() + "lua> " + inputBuffer.toString();
        String[] lines = display.split("\n", -1);

        int totalLines = lines.length;
        int visibleLines = TerminalBuffer.HEIGHT;

        int maxScroll = Math.max(0, totalLines - visibleLines);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        int startLine = maxScroll - scrollOffset;

        for (int y = 0; y < visibleLines && (startLine + y) < totalLines; y++) {
            String line = lines[startLine + y];
            buf.setCursorPos(0, y);
            if (line.startsWith("lua> ")) {
                buf.setTextColor(4); // yellow prompt
                buf.write("lua> ");
                buf.setTextColor(0); // white input
                if (line.length() > 5) {
                    buf.write(line.substring(5, Math.min(line.length(), TerminalBuffer.WIDTH)));
                }
            } else if (line.startsWith("Error:") || line.startsWith("Lua Error:")) {
                buf.setTextColor(14); // red errors
                buf.write(line.substring(0, Math.min(line.length(), TerminalBuffer.WIDTH)));
            } else {
                buf.setTextColor(0);
                buf.write(line.substring(0, Math.min(line.length(), TerminalBuffer.WIDTH)));
            }
        }
    }
}

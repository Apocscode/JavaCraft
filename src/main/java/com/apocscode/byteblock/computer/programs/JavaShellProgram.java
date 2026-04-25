package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.JavaRuntime;
import com.apocscode.byteblock.computer.LuaRuntime;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;

/**
 * Interactive Java (BeanShell) REPL for ByteBlock OS.
 *
 * <p>Runs Java syntax through a sandboxed {@link JavaRuntime}. ByteBlock APIs
 * (robot/drone/glasses/bluetooth/redstone/...) are reachable via the
 * {@code lua("…")} helper which forwards to a shared {@link LuaRuntime}.
 *
 * <p>Built-in REPL commands: {@code exit}, {@code help}, {@code clear},
 * {@code run <file.bsh>}.
 */
public class JavaShellProgram extends OSProgram {

    private JavaRuntime java;
    private LuaRuntime sharedLua;
    private final StringBuilder inputBuffer = new StringBuilder();
    private final StringBuilder outputBuffer = new StringBuilder();
    private int scrollOffset = 0;
    private boolean needsRedraw = true;
    private final java.util.List<String> history = new java.util.ArrayList<>();
    private int historyIndex = -1;
    private boolean programFinishedPrinted = false;

    private final String runFile;

    public JavaShellProgram() { super("Java"); this.runFile = null; }
    public JavaShellProgram(String filePath) { super("Java"); this.runFile = filePath; }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        this.java = new JavaRuntime(os);
        this.sharedLua = new LuaRuntime(os);
        this.java.attachLua(sharedLua);

        appendOutput("ByteBlock Java (BeanShell 2.0) Shell\n");
        appendOutput("Sandbox: file/network/reflection blocked\n");
        appendOutput("Helpers: print(x), println(x), sleep(s), term, os, lua(\"…\")\n");
        appendOutput("Type 'exit' to close, 'help' for commands.\n\n");

        if (runFile != null) {
            appendOutput("Running: " + runFile + "\n");
            String content = os.getFileSystem().readFile(runFile);
            if (content != null) {
                java.runProgram(content, runFile);
                drainOutput();
            } else {
                appendOutput("File not found: " + runFile + "\n");
            }
        }
    }

    @Override
    public boolean tick() {
        drainOutput();
        if (runFile != null && java != null && !java.isProgramRunning() && !programFinishedPrinted) {
            appendOutput("\n-- Program finished --\n");
            programFinishedPrinted = true;
            needsRedraw = true;
        }
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
            case 259 -> {
                if (!inputBuffer.isEmpty()) {
                    inputBuffer.deleteCharAt(inputBuffer.length() - 1);
                    needsRedraw = true;
                }
            }
            case 257, 335 -> {
                String input = inputBuffer.toString().trim();
                inputBuffer.setLength(0);
                appendOutput("java> " + input + "\n");
                if (!input.isEmpty()) {
                    history.add(input);
                    historyIndex = history.size();
                    executeInput(input);
                }
                needsRedraw = true;
            }
            case 265 -> {
                if (!history.isEmpty() && historyIndex > 0) {
                    historyIndex--;
                    inputBuffer.setLength(0);
                    inputBuffer.append(history.get(historyIndex));
                    needsRedraw = true;
                }
            }
            case 264 -> {
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
        if (input.equals("exit") || input.equals("quit")) { running = false; return; }
        if (input.equals("clear") || input.equals("cls")) {
            outputBuffer.setLength(0); scrollOffset = 0; needsRedraw = true; return;
        }
        if (input.equals("help")) {
            appendOutput("=== Java Shell Commands ===\n");
            appendOutput("  exit            Close shell\n");
            appendOutput("  clear           Clear screen\n");
            appendOutput("  run <file.bsh>  Execute a Java/BeanShell file\n");
            appendOutput("=== In-script helpers ===\n");
            appendOutput("  println(\"hi\")\n");
            appendOutput("  sleep(0.5)\n");
            appendOutput("  term.write(\"x\"); term.setTextColor(4);\n");
            appendOutput("  lua(\"robot.forward()\");\n");
            appendOutput("  lua(\"glasses.canvas():circle(50,50,20,0xFF0000):add(); glasses.flush()\");\n");
            appendOutput("=== Sandbox blocks ===\n");
            appendOutput("  java.io.File, Runtime, ProcessBuilder, reflection,\n");
            appendOutput("  ClassLoader, java.net.*, java.nio.file.*, sun.*, jdk.internal.*\n");
            return;
        }
        if (input.startsWith("run ")) {
            String file = input.substring(4).trim();
            String content = os.getFileSystem().readFile(file);
            if (content == null) {
                appendOutput("File not found: " + file + "\n");
            } else {
                programFinishedPrinted = false;
                java.runProgram(content, file);
                drainOutput();
            }
            return;
        }
        String result = java.eval(input);
        drainOutput();
        if (result != null) appendOutput(result + "\n");
    }

    private void drainOutput() {
        String out = java.drainOutput();
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
        buf.setTextColor(0);
        buf.setBackgroundColor(15);
        buf.clear();

        String display = outputBuffer.toString() + "java> " + inputBuffer.toString();
        String[] lines = display.split("\n", -1);

        int totalLines = lines.length;
        int visibleLines = TerminalBuffer.HEIGHT;
        int maxScroll = Math.max(0, totalLines - visibleLines);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        int startLine = maxScroll - scrollOffset;

        for (int y = 0; y < visibleLines && (startLine + y) < totalLines; y++) {
            String line = lines[startLine + y];
            buf.setCursorPos(0, y);
            if (line.startsWith("java> ")) {
                buf.setTextColor(9); // cyan prompt
                buf.write("java> ");
                buf.setTextColor(0);
                if (line.length() > 6) {
                    buf.write(line.substring(6, Math.min(line.length(), TerminalBuffer.WIDTH)));
                }
            } else if (line.startsWith("Error:")) {
                buf.setTextColor(14);
                buf.write(line.substring(0, Math.min(line.length(), TerminalBuffer.WIDTH)));
            } else {
                buf.setTextColor(0);
                buf.write(line.substring(0, Math.min(line.length(), TerminalBuffer.WIDTH)));
            }
        }
    }
}

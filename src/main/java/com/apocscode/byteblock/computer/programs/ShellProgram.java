package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;

import java.util.List;

/**
 * Command-line shell for ByteBlock OS.
 * Handles user input, command parsing, and built-in commands.
 */
public class ShellProgram extends OSProgram {

    private final StringBuilder inputBuffer = new StringBuilder();
    private final StringBuilder outputBuffer = new StringBuilder();
    private String currentDir = "/home";
    private int scrollOffset = 0;
    private boolean needsRedraw = true;
    private final java.util.List<String> history = new java.util.ArrayList<>();
    private int historyIndex = -1;

    public ShellProgram() {
        super("Shell");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        appendOutput("ByteBlock OS v1.0\n");
        appendOutput("Type 'help' for a list of commands.\n");
        appendOutput("\n");
    }

    @Override
    public boolean tick() {
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
            case KEY -> {
                int keyCode = event.getInt(0);
                handleKey(keyCode);
            }
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
            case 257, 335 -> { // Enter, Numpad Enter
                String cmd = inputBuffer.toString().trim();
                inputBuffer.setLength(0);
                appendOutput(getPrompt() + cmd + "\n");
                if (!cmd.isEmpty()) {
                    history.add(cmd);
                    historyIndex = history.size();
                    executeCommand(cmd);
                }
                needsRedraw = true;
            }
            case 265 -> { // Up arrow — history
                if (!history.isEmpty() && historyIndex > 0) {
                    historyIndex--;
                    inputBuffer.setLength(0);
                    inputBuffer.append(history.get(historyIndex));
                    needsRedraw = true;
                }
            }
            case 264 -> { // Down arrow — history
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

    private void executeCommand(String commandLine) {
        String[] parts = commandLine.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "help" -> cmdHelp();
            case "ls", "dir" -> cmdLs(args);
            case "cd" -> cmdCd(args);
            case "pwd" -> appendOutput(currentDir + "\n");
            case "cat", "type" -> cmdCat(args);
            case "mkdir" -> cmdMkdir(args);
            case "rm", "del" -> cmdRm(args);
            case "cp", "copy" -> cmdCp(args);
            case "mv", "move" -> cmdMv(args);
            case "touch" -> cmdTouch(args);
            case "write" -> cmdWrite(args);
            case "clear", "cls" -> { outputBuffer.setLength(0); scrollOffset = 0; }
            case "edit" -> cmdEdit(args);
            case "desktop" -> cmdDesktop();
            case "label" -> cmdLabel(args);
            case "id" -> appendOutput("Computer ID: " + os.getComputerId().toString().substring(0, 8) + "\n");
            case "reboot" -> os.reboot();
            case "shutdown" -> os.shutdown();
            case "bt" -> cmdBluetooth(args);
            case "run" -> cmdRun(args);
            case "lua" -> cmdLua(args);
            case "mkshortcut" -> cmdMkshortcut(args);
            case "puzzle" -> cmdPuzzle(args);
            case "ide" -> cmdIde(args);
            case "exit" -> running = false;
            default -> appendOutput("Unknown command: " + cmd + "\nType 'help' for commands.\n");
        }
    }

    private void cmdHelp() {
        appendOutput("=== ByteBlock Shell Commands ===\n");
        appendOutput(" help          Show this help\n");
        appendOutput(" ls [path]     List directory\n");
        appendOutput(" cd <path>     Change directory\n");
        appendOutput(" pwd           Print working directory\n");
        appendOutput(" cat <file>    Display file contents\n");
        appendOutput(" mkdir <path>  Create directory\n");
        appendOutput(" rm <path>     Delete file or directory\n");
        appendOutput(" cp <s> <d>    Copy file\n");
        appendOutput(" mv <s> <d>    Move file\n");
        appendOutput(" touch <file>  Create empty file\n");
        appendOutput(" write <file>  Write text (then content)\n");
        appendOutput(" edit <file>   Open text editor\n");
        appendOutput(" desktop       Switch to desktop\n");
        appendOutput(" clear         Clear screen\n");
        appendOutput(" label [name]  Get/set computer label\n");
        appendOutput(" id            Show computer ID\n");
        appendOutput(" bt <args>     Bluetooth commands\n");
        appendOutput(" run <file>    Run a program file\n");
        appendOutput(" lua [file]    Open Lua shell / run .lua\n");
        appendOutput(" mkshortcut    Create desktop shortcut\n");
        appendOutput(" puzzle [file] Open puzzle IDE\n");
        appendOutput(" ide [file]    Open text IDE\n");
        appendOutput(" reboot        Reboot the computer\n");
        appendOutput(" shutdown      Shut down\n");
        appendOutput(" exit          Close shell\n");
    }

    private void cmdLs(String args) {
        String path = args.isEmpty() ? currentDir : resolvePath(args);
        List<String> entries = os.getFileSystem().list(path);
        if (entries == null) {
            appendOutput("Not a directory: " + path + "\n");
            return;
        }
        if (entries.isEmpty()) {
            appendOutput("(empty)\n");
            return;
        }
        for (String entry : entries) {
            appendOutput("  " + entry + "\n");
        }
    }

    private void cmdCd(String args) {
        if (args.isEmpty()) {
            currentDir = "/home";
            return;
        }
        String target = resolvePath(args);
        if (os.getFileSystem().isDirectory(target)) {
            currentDir = target;
        } else {
            appendOutput("Not a directory: " + target + "\n");
        }
    }

    private void cmdCat(String args) {
        if (args.isEmpty()) { appendOutput("Usage: cat <file>\n"); return; }
        String path = resolvePath(args);
        String content = os.getFileSystem().readFile(path);
        if (content == null) {
            appendOutput("File not found: " + path + "\n");
        } else {
            appendOutput(content);
            if (!content.endsWith("\n")) appendOutput("\n");
        }
    }

    private void cmdMkdir(String args) {
        if (args.isEmpty()) { appendOutput("Usage: mkdir <path>\n"); return; }
        String path = resolvePath(args);
        if (os.getFileSystem().mkdir(path)) {
            appendOutput("Created: " + path + "\n");
        } else {
            appendOutput("Failed to create: " + path + "\n");
        }
    }

    private void cmdRm(String args) {
        if (args.isEmpty()) { appendOutput("Usage: rm <path>\n"); return; }
        String path = resolvePath(args);
        if (os.getFileSystem().delete(path)) {
            appendOutput("Deleted: " + path + "\n");
        } else {
            appendOutput("Failed to delete: " + path + "\n");
        }
    }

    private void cmdCp(String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) { appendOutput("Usage: cp <source> <dest>\n"); return; }
        if (os.getFileSystem().copy(resolvePath(parts[0]), resolvePath(parts[1]))) {
            appendOutput("Copied.\n");
        } else {
            appendOutput("Copy failed.\n");
        }
    }

    private void cmdMv(String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) { appendOutput("Usage: mv <source> <dest>\n"); return; }
        if (os.getFileSystem().move(resolvePath(parts[0]), resolvePath(parts[1]))) {
            appendOutput("Moved.\n");
        } else {
            appendOutput("Move failed.\n");
        }
    }

    private void cmdTouch(String args) {
        if (args.isEmpty()) { appendOutput("Usage: touch <file>\n"); return; }
        String path = resolvePath(args);
        if (!os.getFileSystem().exists(path)) {
            os.getFileSystem().writeFile(path, "");
            appendOutput("Created: " + path + "\n");
        } else {
            appendOutput("Already exists: " + path + "\n");
        }
    }

    private void cmdWrite(String args) {
        // write <filename> <content...>
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) { appendOutput("Usage: write <file> <content>\n"); return; }
        String path = resolvePath(parts[0]);
        os.getFileSystem().writeFile(path, parts[1]);
        appendOutput("Written to: " + path + "\n");
    }

    private void cmdEdit(String args) {
        if (args.isEmpty()) { appendOutput("Usage: edit <file>\n"); return; }
        String path = resolvePath(args);
        os.launchProgram(new EditProgram(path));
    }

    private void cmdDesktop() {
        // Switch to desktop program if one exists, otherwise launch one
        for (OSProgram proc : os.getProcesses()) {
            if (proc instanceof DesktopProgram) {
                os.setForegroundProgram(proc);
                return;
            }
        }
        os.launchProgram(new DesktopProgram());
    }

    private void cmdLabel(String args) {
        if (args.isEmpty()) {
            appendOutput("Label: " + os.getLabel() + "\n");
        } else {
            os.setLabel(args);
            appendOutput("Label set to: " + args + "\n");
        }
    }

    private void cmdBluetooth(String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length == 0 || parts[0].isEmpty()) {
            appendOutput("Usage: bt send <ch> <msg> | bt channel [ch]\n");
            return;
        }
        switch (parts[0].toLowerCase()) {
            case "send" -> {
                String[] sendParts = parts.length > 1 ? parts[1].split("\\s+", 2) : new String[0];
                if (sendParts.length < 2) {
                    appendOutput("Usage: bt send <channel> <message>\n");
                    return;
                }
                try {
                    int ch = Integer.parseInt(sendParts[0]);
                    com.apocscode.byteblock.network.BluetoothNetwork.broadcastFromDevice(
                        os.getComputerId(), ch, sendParts[1]);
                    appendOutput("Sent on channel " + ch + "\n");
                } catch (NumberFormatException e) {
                    appendOutput("Invalid channel number.\n");
                }
            }
            case "channel" -> {
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    try {
                        os.setBluetoothChannel(Integer.parseInt(parts[1].trim()));
                        appendOutput("BT channel set to " + os.getBluetoothChannel() + "\n");
                    } catch (NumberFormatException e) {
                        appendOutput("Invalid channel number.\n");
                    }
                } else {
                    appendOutput("BT channel: " + os.getBluetoothChannel() + "\n");
                }
            }
            default -> appendOutput("Unknown bt command. Use: send, channel\n");
        }
    }

    private void cmdRun(String args) {
        if (args.isEmpty()) { appendOutput("Usage: run <file>\n"); return; }
        String path = resolvePath(args);
        String content = os.getFileSystem().readFile(path);
        if (content == null) {
            appendOutput("File not found: " + path + "\n");
        } else if (path.endsWith(".lua")) {
            os.launchProgram(new LuaShellProgram(path));
        } else {
            appendOutput("(Script execution not yet available \u2014 Groovy engine pending)\n");
            appendOutput("File contents (" + content.length() + " bytes):\n");
            appendOutput(content);
            if (!content.endsWith("\n")) appendOutput("\n");
        }
    }

    private void cmdLua(String args) {
        if (args.isEmpty()) {
            os.launchProgram(new LuaShellProgram());
        } else {
            String path = resolvePath(args);
            os.launchProgram(new LuaShellProgram(path));
        }
    }

    private void cmdPuzzle(String args) {
        if (args.isEmpty()) {
            os.launchProgram(new PuzzleProgram());
        } else {
            String path = resolvePath(args);
            os.launchProgram(new PuzzleProgram(path));
        }
    }

    private void cmdIde(String args) {
        if (args.isEmpty()) {
            os.launchProgram(new TextIDEProgram());
        } else {
            String path = resolvePath(args);
            os.launchProgram(new TextIDEProgram(path));
        }
    }
    private void cmdMkshortcut(String args) {
        if (args.isEmpty()) {
            appendOutput("Usage: mkshortcut <name> <target> [icon] [color]\n");
            appendOutput("  mkshortcut icons   \u2014 list available icons\n");
            appendOutput("  mkshortcut colors  \u2014 list available colors\n");
            return;
        }
        String[] parts = args.split("\\s+");
        if (parts[0].equalsIgnoreCase("icons")) {
            appendOutput("Available icons (0-15):\n");
            for (int i = 0; i < DesktopProgram.ICON_CHARS.length; i++) {
                appendOutput("  " + i + ": " + DesktopProgram.ICON_CHARS[i]
                    + " " + DesktopProgram.ICON_LABELS[i] + "\n");
            }
            return;
        }
        if (parts[0].equalsIgnoreCase("colors")) {
            appendOutput("Available colors (0-15):\n");
            String[] names = {"White","Orange","Magenta","L.Blue","Yellow","Lime",
                "Pink","Gray","L.Gray","Cyan","Purple","Blue","Brown","Green","Red","Black"};
            for (int i = 0; i < 16; i++) {
                appendOutput("  " + i + ": " + names[i] + "\n");
            }
            return;
        }
        if (parts.length < 2) {
            appendOutput("Usage: mkshortcut <name> <target> [icon] [color]\n");
            return;
        }
        String name = parts[0];
        String target = parts[1];
        int icon = parts.length > 2 ? parseIntSafe(parts[2], 0) : 0;
        int color = parts.length > 3 ? parseIntSafe(parts[3], 0) : 0;
        icon = Math.max(0, Math.min(15, icon));
        color = Math.max(0, Math.min(15, color));
        String safeName = name.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
        String content = "name=" + name + "\ntarget=" + target
            + "\nicon=" + icon + "\ncolor=" + color + "\n";
        os.getFileSystem().writeFile("/desktop/" + safeName + ".lnk", content);
        appendOutput("Shortcut created: " + name + " -> " + target + "\n");
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
    private String resolvePath(String input) {
        input = input.trim();
        if (input.startsWith("/")) return input;
        if (currentDir.equals("/")) return "/" + input;
        return currentDir + "/" + input;
    }

    private String getPrompt() {
        // Shorten path for display
        String dir = currentDir;
        if (dir.startsWith("/home")) {
            dir = "~" + dir.substring(5);
        }
        return dir + "> ";
    }

    private void appendOutput(String text) {
        outputBuffer.append(text);
        // Cap buffer to prevent unbounded growth
        if (outputBuffer.length() > 10000) {
            outputBuffer.delete(0, outputBuffer.length() - 8000);
        }
        needsRedraw = true;
    }

    @Override
    public void render(TerminalBuffer buf) {
        if (!needsRedraw) return;
        needsRedraw = false;

        buf.setTextColor(0);  // white
        buf.setBackgroundColor(15); // black
        buf.clear();

        // Build display text = output + prompt + input
        String display = outputBuffer.toString() + getPrompt() + inputBuffer.toString();
        String[] lines = display.split("\n", -1);

        int totalLines = lines.length;
        int visibleLines = TerminalBuffer.HEIGHT;

        // Auto-scroll to bottom unless user scrolled up
        int maxScroll = Math.max(0, totalLines - visibleLines);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        int startLine = maxScroll - scrollOffset;

        for (int y = 0; y < visibleLines && (startLine + y) < totalLines; y++) {
            String line = lines[startLine + y];
            buf.setCursorPos(0, y);
            // Color the prompt green
            if (line.contains("> ") && (startLine + y) < totalLines) {
                int promptEnd = line.indexOf("> ") + 2;
                buf.setTextColor(5); // lime
                buf.write(line.substring(0, Math.min(promptEnd, TerminalBuffer.WIDTH)));
                buf.setTextColor(0); // white
                if (promptEnd < line.length()) {
                    buf.write(line.substring(promptEnd, Math.min(line.length(), TerminalBuffer.WIDTH)));
                }
            } else {
                buf.write(line.substring(0, Math.min(line.length(), TerminalBuffer.WIDTH)));
            }
        }

        // Position cursor at end of input
        String lastLine = getPrompt() + inputBuffer.toString();
        int cx = lastLine.length() % TerminalBuffer.WIDTH;
        buf.setCursorPos(cx, Math.min(TerminalBuffer.HEIGHT - 1, visibleLines - 1));
    }
}

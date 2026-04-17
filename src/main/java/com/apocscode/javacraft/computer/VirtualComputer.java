package com.apocscode.javacraft.computer;

import com.apocscode.javacraft.block.entity.ComputerBlockEntity;
import com.apocscode.javacraft.network.BluetoothNetwork;

/**
 * The virtual computer runtime. Manages program execution,
 * sandboxed scripting, and API access for a single computer block.
 * 
 * Currently a stub — will be backed by Groovy scripting engine
 * with AST security sandbox in Phase 2.
 */
public class VirtualComputer {
    private final ComputerBlockEntity host;
    private boolean running = false;
    private String currentProgram = null;
    private int ticksSinceStart = 0;

    public VirtualComputer(ComputerBlockEntity host) {
        this.host = host;
    }

    /**
     * Called every server tick. Handles program execution timing,
     * event queue processing, and Bluetooth message polling.
     */
    public void tick() {
        if (!running) return;
        ticksSinceStart++;

        // Poll Bluetooth inbox
        BluetoothNetwork.Message msg = BluetoothNetwork.receive(host.getComputerId());
        if (msg != null) {
            host.print("[BT ch" + msg.channel() + "] " + msg.content());
        }
    }

    /**
     * Execute a program from the virtual filesystem.
     */
    public boolean runProgram(String path) {
        String source = host.readFile(path);
        if (source == null) {
            host.print("Error: File not found: " + path);
            return false;
        }
        currentProgram = path;
        running = true;
        ticksSinceStart = 0;
        host.print("Running: " + path);
        // TODO: Groovy engine execution
        host.print("> Program loaded (" + source.length() + " chars). Engine not yet implemented.");
        running = false;
        return true;
    }

    /**
     * Execute a single line of code (REPL mode).
     */
    public String execute(String code) {
        // TODO: Groovy engine REPL
        host.print("> " + code);
        host.print("  [Engine not yet implemented]");
        return "OK";
    }

    public void stop() {
        running = false;
        currentProgram = null;
        host.print("Program stopped.");
    }

    public boolean isRunning() { return running; }
    public String getCurrentProgram() { return currentProgram; }
    public int getTicksSinceStart() { return ticksSinceStart; }
}

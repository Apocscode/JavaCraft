package com.apocscode.byteblock.computer;

/**
 * Legacy stub — replaced by JavaOS.
 * Kept for reference; will be removed or repurposed for Groovy scripting engine.
 */
public class VirtualComputer {
    private boolean running = false;
    private String currentProgram = null;
    private int ticksSinceStart = 0;

    public void tick() {
        if (!running) return;
        ticksSinceStart++;
    }

    public boolean runProgram(String path) {
        return false;
    }

    public String execute(String code) {
        return "Engine not yet implemented";
    }

    public void stop() {
        running = false;
        currentProgram = null;
    }

    public boolean isRunning() { return running; }
    public String getCurrentProgram() { return currentProgram; }
    public int getTicksSinceStart() { return ticksSinceStart; }
}

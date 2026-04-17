package com.apocscode.javacraft.computer;

/**
 * Represents a running program/process on a JavaCraft computer.
 * Programs receive events and render to either a terminal window or the full screen.
 */
public abstract class OSProgram {

    protected JavaOS os;
    protected String name;
    protected boolean running;
    private boolean isBackground;

    public OSProgram(String name) {
        this.name = name;
        this.running = true;
        this.isBackground = false;
    }

    /** Called once when the program starts */
    public abstract void init(JavaOS os);

    /** Called each tick (20 tps). Return false to terminate. */
    public abstract boolean tick();

    /** Called when an event is dispatched to this program */
    public abstract void handleEvent(OSEvent event);

    /** Called to render this program's output to the terminal buffer */
    public abstract void render(TerminalBuffer buffer);

    /** Called when the program is terminated */
    public void shutdown() {
        running = false;
    }

    public String getName() { return name; }
    public boolean isRunning() { return running; }
    public boolean isBackground() { return isBackground; }
    public void setBackground(boolean bg) { this.isBackground = bg; }
    public void setOS(JavaOS os) { this.os = os; }
}

package com.apocscode.byteblock.computer;

/**
 * Represents a running program/process on a ByteBlock computer.
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

    /** Called to render this program's output to the terminal buffer (legacy) */
    public abstract void render(TerminalBuffer buffer);

    /**
     * Render to a pixel buffer (640x400). Override this for graphical programs.
     * Default implementation bridges from the old text-mode render() method.
     */
    public void renderGraphics(PixelBuffer pb) {
        TerminalBuffer tb = new TerminalBuffer();
        render(tb);
        pb.rasterizeTerminal(tb);
        // Propagate cursor blink info
        this.lastCursorX = tb.getCursorX();
        this.lastCursorY = tb.getCursorY();
        this.lastCursorBlink = tb.isCursorBlink();
    }

    // Cursor state from last render (used by ComputerScreen)
    private int lastCursorX, lastCursorY;
    private boolean lastCursorBlink;

    public int getLastCursorX() { return lastCursorX; }
    public int getLastCursorY() { return lastCursorY; }
    public boolean isLastCursorBlink() { return lastCursorBlink; }

    protected void setCursorInfo(int x, int y, boolean blink) {
        this.lastCursorX = x;
        this.lastCursorY = y;
        this.lastCursorBlink = blink;
    }

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

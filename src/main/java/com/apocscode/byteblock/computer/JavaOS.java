package com.apocscode.byteblock.computer;

import com.apocscode.byteblock.computer.programs.DesktopProgram;
import com.apocscode.byteblock.computer.programs.ShellProgram;
import com.apocscode.byteblock.network.BluetoothNetwork;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JavaOS — the operating system kernel for ByteBlock computers.
 * Manages the event queue, process table, file system, terminal, and timers.
 */
public class JavaOS {

    public enum State { BOOT, RUNNING, SHUTDOWN }

    private State state;
    private final TerminalBuffer terminal;
    private final VirtualFileSystem fileSystem;
    private final UUID computerId;
    private String label;
    private int bluetoothChannel;
    private float textScale = 2.0f;

    // Process management
    private final List<OSProgram> processes;
    private OSProgram foregroundProgram;

    // Event queue
    private final Deque<OSEvent> eventQueue;
    private static final int MAX_EVENTS = 256;

    // Timer system
    private final Map<Integer, Long> timers; // timerId -> tick when it fires
    private int nextTimerId;
    private long tickCount;

    // Boot animation
    private int bootTick;
    private static final int BOOT_DURATION = 40; // 2 seconds

    public JavaOS(UUID computerId) {
        this.computerId = computerId;
        this.terminal = new TerminalBuffer();
        this.fileSystem = new VirtualFileSystem();
        this.label = "Computer";
        this.bluetoothChannel = 1;
        this.processes = new ArrayList<>();
        this.eventQueue = new ArrayDeque<>();
        this.timers = new HashMap<>();
        this.nextTimerId = 1;
        this.tickCount = 0;
        this.state = State.BOOT;
        this.bootTick = 0;

        installSystemPrograms();
    }

    private void installSystemPrograms() {
        // Write built-in program source files to /system/programs/
        fileSystem.installSystemFile("/system/programs/shell", "Built-in shell program");
        fileSystem.installSystemFile("/system/programs/edit", "Built-in text editor");
        fileSystem.installSystemFile("/system/programs/explorer", "Built-in file explorer");
        fileSystem.installSystemFile("/system/programs/settings", "Built-in settings");
        fileSystem.installSystemFile("/system/programs/paint", "Built-in paint program");
        fileSystem.installSystemFile("/system/programs/lua", "Built-in Lua 5.2 shell");
        fileSystem.installSystemFile("/system/programs/puzzle", "Built-in puzzle IDE");
        fileSystem.installSystemFile("/system/programs/ide", "Built-in text IDE");

        // Create default startup file
        if (!fileSystem.exists("/home/startup")) {
            fileSystem.writeFile("/home/startup", "-- Startup script\nprint(\"Welcome to JavaOS!\")\n");
        }
    }

    // --- Tick / Main Loop ---

    public void tick() {
        tickCount++;

        switch (state) {
            case BOOT -> tickBoot();
            case RUNNING -> tickRunning();
            case SHUTDOWN -> { /* nothing */ }
        }
    }

    private void tickBoot() {
        bootTick++;
        if (bootTick == 1) {
            terminal.setTextColor(4); // yellow
            terminal.setBackgroundColor(15); // black
            terminal.clear();
            terminal.setCursorPos(17, 8);
            terminal.write("ByteBlock OS");
            terminal.setTextColor(8); // light gray
            terminal.setCursorPos(18, 10);
            terminal.write("Loading...");
        }
        if (bootTick >= BOOT_DURATION) {
            state = State.RUNNING;
            terminal.setTextColor(0);
            terminal.setBackgroundColor(15);
            terminal.clear();
            // Launch the desktop/shell
            launchProgram(new DesktopProgram());
        }
    }

    private void tickRunning() {
        // Fire timers
        Iterator<Map.Entry<Integer, Long>> it = timers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> entry = it.next();
            if (tickCount >= entry.getValue()) {
                pushEvent(new OSEvent(OSEvent.Type.TIMER, entry.getKey()));
                it.remove();
            }
        }

        // Poll Bluetooth inbox
        BluetoothNetwork.Message msg = BluetoothNetwork.receive(computerId);
        while (msg != null) {
            pushEvent(new OSEvent(OSEvent.Type.BLUETOOTH, msg.channel(), msg.content(), 0));
            msg = BluetoothNetwork.receive(computerId);
        }

        // Dispatch events to foreground program
        while (!eventQueue.isEmpty()) {
            OSEvent event = eventQueue.poll();
            if (event.getType() == OSEvent.Type.TERMINATE) {
                if (foregroundProgram != null) {
                    foregroundProgram.shutdown();
                }
                continue;
            }
            if (event.getType() == OSEvent.Type.SHUTDOWN) {
                shutdown();
                return;
            }
            if (event.getType() == OSEvent.Type.REBOOT) {
                reboot();
                return;
            }
            if (foregroundProgram != null && foregroundProgram.isRunning()) {
                foregroundProgram.handleEvent(event);
            }
        }

        // Tick all processes
        List<OSProgram> toRemove = new ArrayList<>();
        for (OSProgram proc : processes) {
            if (proc.isRunning()) {
                if (!proc.tick()) {
                    proc.shutdown();
                    toRemove.add(proc);
                }
            } else {
                toRemove.add(proc);
            }
        }
        processes.removeAll(toRemove);

        // If foreground program died, fall back to desktop
        if (foregroundProgram != null && !foregroundProgram.isRunning()) {
            foregroundProgram = null;
            if (processes.isEmpty()) {
                launchProgram(new DesktopProgram());
            } else {
                // Focus the last non-background process
                for (int i = processes.size() - 1; i >= 0; i--) {
                    if (!processes.get(i).isBackground()) {
                        foregroundProgram = processes.get(i);
                        break;
                    }
                }
                if (foregroundProgram == null) {
                    launchProgram(new DesktopProgram());
                }
            }
        }

        // Render foreground
        if (foregroundProgram != null) {
            foregroundProgram.render(terminal);
        }
    }

    // --- Process Management ---

    public void launchProgram(OSProgram program) {
        program.setOS(this);
        program.init(this);
        processes.add(program);
        if (!program.isBackground()) {
            foregroundProgram = program;
        }
    }

    public void killProgram(OSProgram program) {
        program.shutdown();
        processes.remove(program);
        if (foregroundProgram == program) {
            foregroundProgram = null;
        }
    }

    public void launchShell() {
        launchProgram(new ShellProgram());
    }

    public List<OSProgram> getProcesses() {
        return new ArrayList<>(processes);
    }

    public OSProgram getForegroundProgram() {
        return foregroundProgram;
    }

    public void setForegroundProgram(OSProgram program) {
        if (processes.contains(program)) {
            foregroundProgram = program;
        }
    }

    // --- Event System ---

    public void pushEvent(OSEvent event) {
        if (eventQueue.size() < MAX_EVENTS) {
            eventQueue.add(event);
        }
    }

    // --- Timer System ---

    public int startTimer(double seconds) {
        int id = nextTimerId++;
        long fireTick = tickCount + (long)(seconds * 20);
        timers.put(id, fireTick);
        return id;
    }

    public void cancelTimer(int timerId) {
        timers.remove(timerId);
    }

    // --- OS Commands ---

    public void shutdown() {
        state = State.SHUTDOWN;
        for (OSProgram proc : processes) {
            proc.shutdown();
        }
        processes.clear();
        foregroundProgram = null;
        terminal.setTextColor(0);
        terminal.setBackgroundColor(15);
        terminal.clear();
        terminal.setCursorPos(18, 9);
        terminal.write("Shutting down...");
    }

    public void reboot() {
        for (OSProgram proc : processes) {
            proc.shutdown();
        }
        processes.clear();
        foregroundProgram = null;
        eventQueue.clear();
        timers.clear();
        nextTimerId = 1;
        tickCount = 0;
        bootTick = 0;
        state = State.BOOT;
    }

    // --- Getters ---

    public TerminalBuffer getTerminal() { return terminal; }
    public VirtualFileSystem getFileSystem() { return fileSystem; }
    public UUID getComputerId() { return computerId; }
    public State getState() { return state; }
    public long getTickCount() { return tickCount; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public int getBluetoothChannel() { return bluetoothChannel; }
    public void setBluetoothChannel(int ch) { this.bluetoothChannel = ch; }

    public float getTextScale() { return textScale; }
    public void setTextScale(float s) { this.textScale = Math.max(1.0f, Math.min(3.0f, s)); }

    public boolean isRunning() { return state == State.RUNNING; }
    public boolean isBooting() { return state == State.BOOT; }
    public boolean isShutdown() { return state == State.SHUTDOWN; }
}

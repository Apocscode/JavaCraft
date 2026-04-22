package com.apocscode.byteblock.computer;

import com.apocscode.byteblock.block.entity.DriveBlockEntity;
import com.apocscode.byteblock.computer.programs.DesktopProgram;
import com.apocscode.byteblock.computer.programs.ShellProgram;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JavaOS — the operating system kernel for ByteBlock computers.
 * Manages the event queue, process table, file system, terminal, and timers.
 */
public class JavaOS {

    public enum State { BOOT, RUNNING, SHUTDOWN }

    private State state;
    private final TerminalBuffer terminal;
    private final PixelBuffer pixelBuffer;
    private final VirtualFileSystem fileSystem;
    private final UUID computerId;
    private String label;
    private int bluetoothChannel;
    private float textScale = 2.0f;

    // Drive mount system — maps drive letters (D, E, ...) to detected drives
    private final Map<Character, DriveBlockEntity> mountedDrives = new LinkedHashMap<>();

    // World reference (set each tick by block entity)
    private transient net.minecraft.world.level.Level level;
    private transient net.minecraft.core.BlockPos blockPos;

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
        this.pixelBuffer = new PixelBuffer();
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
        // Write built-in program stubs to /Program Files/
        fileSystem.installSystemFile("/Program Files/shell", "Built-in shell program");
        fileSystem.installSystemFile("/Program Files/edit", "Built-in text editor");
        fileSystem.installSystemFile("/Program Files/explorer", "Built-in file explorer");
        fileSystem.installSystemFile("/Program Files/settings", "Built-in settings");
        fileSystem.installSystemFile("/Program Files/paint", "Built-in paint program");
        fileSystem.installSystemFile("/Program Files/lua", "Built-in Lua 5.2 shell");
        fileSystem.installSystemFile("/Program Files/puzzle", "Built-in puzzle IDE");
        fileSystem.installSystemFile("/Program Files/ide", "Built-in text IDE");
        fileSystem.installSystemFile("/Program Files/calculator", "Built-in calculator");
        fileSystem.installSystemFile("/Program Files/task_manager", "Built-in task manager");
        fileSystem.installSystemFile("/Program Files/bluetooth", "Built-in bluetooth manager");
        fileSystem.installSystemFile("/Program Files/me_dashboard", "Built-in AE2 ME Network dashboard");
        fileSystem.installSystemFile("/Program Files/create_dashboard", "Built-in Create mod machine dashboard");

        // Create default Documents startup file
        if (!fileSystem.exists("/Users/User/Documents/startup")) {
            fileSystem.writeFile("/Users/User/Documents/startup",
                "-- Startup script\nprint(\"Welcome to ByteOS!\")\n");
        }

        // Tiny test program for the IDE
        if (!fileSystem.exists("/Users/User/Documents/test.lua")) {
            fileSystem.writeFile("/Users/User/Documents/test.lua",
                "-- test.lua: IDE feature tester\n"
              + "\n"
              + "local function greet(name)\n"
              + "  print(\"Hello, \" .. name .. \"!\")\n"
              + "end\n"
              + "\n"
              + "local function add(a, b)\n"
              + "  return a + b\n"
              + "end\n"
              + "\n"
              + "-- math test\n"
              + "local x = add(3, 7)\n"
              + "print(\"3 + 7 = \" .. tostring(x))\n"
              + "\n"
              + "-- loop test\n"
              + "for i = 1, 5 do\n"
              + "  greet(\"User\" .. i)\n"
              + "end\n"
              + "\n"
              + "-- table test\n"
              + "local items = {\"pickaxe\", \"torch\", \"cobblestone\"}\n"
              + "for _, item in ipairs(items) do\n"
              + "  print(\"  > \" .. item)\n"
              + "end\n"
              + "\n"
              + "print(\"All tests passed!\")\n");
        }

        // Lamp test script — demonstrates relay API with 3 lamps wired to the relay
        if (!fileSystem.exists("/Users/User/Documents/lamp_test.lua")) {
            fileSystem.writeFile("/Users/User/Documents/lamp_test.lua",
                "-- lamp_test.lua\n"
              + "-- Controls 3 lamps wired to the Redstone Relay.\n"
              + "-- Default sides: top lamp = \"top\", side lamps = \"north\" and \"south\".\n"
              + "-- Change TOP, SIDE1, SIDE2 to match your actual wiring.\n"
              + "\n"
              + "local TOP   = \"top\"\n"
              + "local SIDE1 = \"north\"\n"
              + "local SIDE2 = \"south\"\n"
              + "\n"
              + "print(\"=== Lamp Test ===\")\n"
              + "\n"
              + "if not relay.isConnected() then\n"
              + "  print(\"ERROR: No relay found in Bluetooth range.\")\n"
              + "  print(\"Place the Redstone Relay near this computer.\")\n"
              + "  return\n"
              + "end\n"
              + "\n"
              + "print(\"Relay connected!\")\n"
              + "\n"
              + "-- Show current outputs on all 6 sides\n"
              + "print(\"Current relay outputs:\")\n"
              + "local sides = relay.getSides()\n"
              + "for i = 1, #sides do\n"
              + "  local s   = sides[i]\n"
              + "  local out = relay.getOutput(s)\n"
              + "  local inp = relay.getInput(s)\n"
              + "  print(\"  \" .. s .. \": out=\" .. out .. \"  in=\" .. inp)\n"
              + "end\n"
              + "print(\"\")\n"
              + "\n"
              + "-- Toggle: if all 3 lamps are off, turn them ON; otherwise turn OFF\n"
              + "local t  = relay.getOutput(TOP)\n"
              + "local s1 = relay.getOutput(SIDE1)\n"
              + "local s2 = relay.getOutput(SIDE2)\n"
              + "local allOff = (t == 0 and s1 == 0 and s2 == 0)\n"
              + "local level  = allOff and 15 or 0\n"
              + "\n"
              + "relay.setOutput(TOP,   level)\n"
              + "relay.setOutput(SIDE1, level)\n"
              + "relay.setOutput(SIDE2, level)\n"
              + "\n"
              + "print(\"Set all 3 lamps \" .. (allOff and \"ON  (power=15)\" or \"OFF (power=0)\"))\n"
              + "\n"
              + "-- Verify write\n"
              + "print(\"Verify:\")\n"
              + "print(\"  \" .. TOP   .. \" = \" .. relay.getOutput(TOP))\n"
              + "print(\"  \" .. SIDE1 .. \" = \" .. relay.getOutput(SIDE1))\n"
              + "print(\"  \" .. SIDE2 .. \" = \" .. relay.getOutput(SIDE2))\n"
              + "print(\"\")\n"
              + "print(\"Run lamp_test again to toggle.\")\n");
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
        // Scan for drives every 2 seconds
        if (tickCount % 40 == 0) scanDrives();

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
            double dist = msg.senderPos() != null ? Math.sqrt(msg.senderPos().distSqr(new net.minecraft.core.BlockPos(0, 0, 0))) : 0;
            String senderId = msg.senderId() != null ? msg.senderId().toString() : "";
            pushEvent(new OSEvent(OSEvent.Type.BLUETOOTH, msg.channel(), msg.content(), senderId));
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

        // Render foreground into pixel buffer
        if (foregroundProgram != null) {
            foregroundProgram.renderGraphics(pixelBuffer);
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
    public PixelBuffer getPixelBuffer() { return pixelBuffer; }
    public VirtualFileSystem getFileSystem() { return fileSystem; }
    public UUID getComputerId() { return computerId; }
    public State getState() { return state; }
    public long getTickCount() { return tickCount; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public int getBluetoothChannel() { return bluetoothChannel; }
    public void setBluetoothChannel(int ch) { this.bluetoothChannel = ch; }

    public net.minecraft.world.level.Level getLevel() { return level; }
    public net.minecraft.core.BlockPos getBlockPos() { return blockPos; }
    public void setWorldContext(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        this.level = level;
        this.blockPos = pos;
    }

    // --- Drive Mount System ---

    private void scanDrives() {
        mountedDrives.clear();
        if (level == null || blockPos == null) return;
        char letter = 'D';
        Set<net.minecraft.core.BlockPos> seen = new HashSet<>();

        // Adjacent drives (directly touching the computer)
        for (Direction dir : Direction.values()) {
            BlockEntity be = level.getBlockEntity(blockPos.relative(dir));
            if (be instanceof DriveBlockEntity drive && drive.hasDisk()) {
                mountedDrives.put(letter++, drive);
                seen.add(be.getBlockPos());
                if (letter > 'Z') return;
            }
        }

        // Bluetooth-range drives
        List<BluetoothNetwork.DeviceEntry> devices =
            BluetoothNetwork.getDevicesInRange(level, blockPos, BluetoothNetwork.BLOCK_RANGE);
        for (BluetoothNetwork.DeviceEntry d : devices) {
            if (d.type() == BluetoothNetwork.DeviceType.DRIVE && !seen.contains(d.pos())) {
                BlockEntity be = level.getBlockEntity(d.pos());
                if (be instanceof DriveBlockEntity drive && drive.hasDisk()) {
                    mountedDrives.put(letter++, drive);
                    seen.add(d.pos());
                    if (letter > 'Z') return;
                }
            }
        }
    }

    public Map<Character, DriveBlockEntity> getMountedDrives() {
        return Collections.unmodifiableMap(mountedDrives);
    }

    public DriveBlockEntity getDrive(char letter) {
        return mountedDrives.get(letter);
    }

    public float getTextScale() { return textScale; }
    public void setTextScale(float s) { this.textScale = Math.max(1.0f, Math.min(3.0f, s)); }

    public boolean isRunning() { return state == State.RUNNING; }
    public boolean isBooting() { return state == State.BOOT; }
    public boolean isShutdown() { return state == State.SHUTDOWN; }

    // Clipboard (program → system)
    private String clipboard;
    private String clipboardOut;
    public void setClipboard(String text) { this.clipboard = text; this.clipboardOut = text; }
    public String getClipboard() { return clipboard; }
    public String consumeClipboard() { String t = clipboardOut; clipboardOut = null; return t; }
}

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

    // Optional entity host (set by entity-hosted computers like RobotEntity)
    private transient net.minecraft.world.entity.Entity host;

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

        // Virtual Button Panel demo + docs for the built-in 16-button panel on this computer.
        if (!fileSystem.exists("/Users/User/Documents/button_demo.lua")) {
            fileSystem.writeFile("/Users/User/Documents/button_demo.lua",
                "-- button_demo.lua\n"
              + "-- Drives this computer's built-in Virtual Button Panel.\n"
              + "-- The computer emits redstone + bundled signals on all 6 sides\n"
              + "-- based on which of the 16 buttons are ON.\n"
              + "\n"
              + "print(\"=== Virtual Button Panel Demo ===\")\n"
              + "\n"
              + "-- Rename the panel and a few buttons\n"
              + "buttons.setPanelLabel(\"Demo Panel\")\n"
              + "buttons.setLabel(0, \"Lamp\")\n"
              + "buttons.setLabel(1, \"Door\")\n"
              + "buttons.setLabel(2, \"Alarm\")\n"
              + "\n"
              + "-- Give them distinct colors (0xRRGGBB)\n"
              + "buttons.setColor(0, 0xFFD700) -- gold\n"
              + "buttons.setColor(1, 0x00AAFF) -- cyan\n"
              + "buttons.setColor(2, 0xFF3030) -- red\n"
              + "\n"
              + "-- Button 2 momentarily pulses (auto-off after 4 ticks)\n"
              + "buttons.setMode(2, \"momentary\")\n"
              + "\n"
              + "-- Button 3 runs a 40-tick (2s) timer when toggled on\n"
              + "buttons.setMode(3, \"timer\")\n"
              + "buttons.setDuration(3, 40)\n"
              + "buttons.setLabel(3, \"2s Timer\")\n"
              + "\n"
              + "-- Toggle the first three buttons on\n"
              + "local mask = buttons.getAll()\n"
              + "if mask == 0 then\n"
              + "  buttons.set(0, true)\n"
              + "  buttons.set(1, true)\n"
              + "  print(\"Buttons 0 + 1 ON. Bundled output = 0x\" .. string.format(\"%04X\", buttons.getAll()))\n"
              + "else\n"
              + "  buttons.setAll(0)\n"
              + "  print(\"All buttons OFF.\")\n"
              + "end\n"
              + "\n"
              + "print(\"Open the Button App in the Start Menu — 'This Computer' will be at the top of the list.\")\n");
        }

        // Built-in documentation for the buttons API
        if (!fileSystem.exists("/Users/User/Documents/docs/buttons.md")) {
            fileSystem.writeFile("/Users/User/Documents/docs/buttons.md",
                "# buttons — Virtual Button Panel API\n"
              + "\n"
              + "Every Computer block carries a built-in 16-button panel. The same panel\n"
              + "that the on-screen Button App drives is exposed to programs through the\n"
              + "global `buttons` table.\n"
              + "\n"
              + "When buttons are ON, the computer block emits redstone (analog) and\n"
              + "bundled-cable signals on all 6 sides, and broadcasts\n"
              + "`button_press:<i>:<0|1>:<Color>` events on its Bluetooth channel.\n"
              + "\n"
              + "## Functions\n"
              + "\n"
              + "| Call                              | Description                           |\n"
              + "|-----------------------------------|---------------------------------------|\n"
              + "| `buttons.set(i, on)`              | Turn button i (0-15) on/off           |\n"
              + "| `buttons.get(i)`                  | Returns boolean                       |\n"
              + "| `buttons.toggle(i)`               | Flip button i                         |\n"
              + "| `buttons.setAll(mask)`            | Set all 16 buttons (16-bit mask)      |\n"
              + "| `buttons.getAll()`                | Read the full mask                    |\n"
              + "| `buttons.setMode(i, mode)`        | \"toggle\" / \"momentary\" / \"timer\" / \"delay\" / \"inverted\" |\n"
              + "| `buttons.setDuration(i, ticks)`   | 1..6000 ticks, used by timer/delay    |\n"
              + "| `buttons.setLabel(i, text)`       | Per-button label (max 16 chars)       |\n"
              + "| `buttons.setColor(i, rgb)`        | 0xRRGGBB, or -1 for default           |\n"
              + "| `buttons.setPanelLabel(text)`     | Rename the panel (max 24 chars)       |\n"
              + "| `buttons.setChannel(n)`           | Bluetooth channel (1..256)            |\n"
              + "\n"
              + "## Button modes\n"
              + "\n"
              + "- **toggle**    — classic on/off (default)\n"
              + "- **momentary** — pulses ON for 4 ticks then auto-off\n"
              + "- **timer**     — stays ON for `duration` ticks then auto-off\n"
              + "- **delay**     — waits `duration` ticks then toggles\n"
              + "- **inverted**  — output is negated (ON means low, OFF means high)\n"
              + "\n"
              + "## Example\n"
              + "\n"
              + "```lua\n"
              + "buttons.setPanelLabel(\"Base Controls\")\n"
              + "buttons.setLabel(0, \"Lamp\")\n"
              + "buttons.setColor(0, 0xFFD700)\n"
              + "buttons.set(0, true)\n"
              + "```\n"
              + "\n"
              + "See also `/Users/User/Documents/button_demo.lua`.\n");
        }

        // Robot demo (only meaningful when this OS is hosted by a RobotEntity;
        // on normal computers the calls return false)
        if (!fileSystem.exists("/Users/User/Documents/robot_demo.lua")) {
            fileSystem.writeFile("/Users/User/Documents/robot_demo.lua",
                "-- robot_demo.lua — queues a small dig-and-move routine\n"
              + "-- Only runs on a RobotEntity. Normal computers: robot.* returns false.\n"
              + "if not robot or not robot.queue then\n"
              + "  print(\"No robot API (not hosted by a robot).\")\n"
              + "  return\n"
              + "end\n"
              + "\n"
              + "print(\"Fuel: \" .. robot.getFuel())\n"
              + "print(\"Facing: \" .. tostring(robot.getFacing()))\n"
              + "\n"
              + "for i = 1, 3 do\n"
              + "  robot.dig()\n"
              + "  robot.forward()\n"
              + "end\n"
              + "robot.turnLeft()\n"
              + "print(\"Queued: \" .. robot.commandsQueued() .. \" commands\")\n");
        }

        // Drone demo — broadcasts BT commands to any drone on the OS channel.
        if (!fileSystem.exists("/Users/User/Documents/drone_demo.lua")) {
            fileSystem.writeFile("/Users/User/Documents/drone_demo.lua",
                "-- drone_demo.lua — sends a patrol pattern via Bluetooth\n"
              + "-- Any DroneEntity tuned to this computer's channel obeys.\n"
              + "local ch = os.getComputerChannel and os.getComputerChannel() or 1\n"
              + "print(\"Broadcasting on channel \" .. ch)\n"
              + "\n"
              + "drone.clear()\n"
              + "drone.waypoint(100, 70, 100)\n"
              + "drone.waypoint(120, 70, 100)\n"
              + "drone.waypoint(120, 70, 120)\n"
              + "drone.waypoint(100, 70, 120)\n"
              + "drone.home()\n"
              + "print(\"Patrol queued.\")\n");
        }

        // Robot API docs
        if (!fileSystem.exists("/Users/User/Documents/docs/robot.md")) {
            fileSystem.writeFile("/Users/User/Documents/docs/robot.md",
                "# robot API\n"
              + "\n"
              + "Available only when the OS is hosted by a RobotEntity. All calls\n"
              + "return `false` / `0` / `nil` on non-robot computers so scripts\n"
              + "can safely feature-detect with `if robot and robot.queue then ...`.\n"
              + "\n"
              + "Commands are appended to an internal queue (max 256) and executed\n"
              + "one per tick while the robot has ≥10 FE stored.\n"
              + "\n"
              + "## Movement / action\n"
              + "| Fn | Effect |\n"
              + "|---|---|\n"
              + "| `robot.forward()` | Step 1 block forward |\n"
              + "| `robot.back()` | Step 1 block backward |\n"
              + "| `robot.up()` / `robot.down()` | Step vertically |\n"
              + "| `robot.turnLeft()` / `robot.turnRight()` | Rotate 90° |\n"
              + "| `robot.dig()` / `robot.digUp()` / `robot.digDown()` | Mine block, drops into inventory |\n"
              + "| `robot.place()` | Place selected slot in front |\n"
              + "| `robot.queue(str)` | Append a raw command string |\n"
              + "| `robot.clear()` | Empty the command queue |\n"
              + "\n"
              + "## Inspection\n"
              + "| Fn | Returns |\n"
              + "|---|---|\n"
              + "| `robot.isBusy()` | `true` if queue non-empty |\n"
              + "| `robot.commandsQueued()` | queue length |\n"
              + "| `robot.getFuel()` | stored FE |\n"
              + "| `robot.getFacing()` | `north`/`south`/`east`/`west` |\n"
              + "| `robot.getPos()` | returns `x, y, z` (multi-return) |\n"
              + "| `robot.detect()` / `detectUp()` / `detectDown()` | `true` if solid block there |\n"
              + "\n"
              + "## Inventory (1-indexed slots 1..16)\n"
              + "| Fn | Purpose |\n"
              + "|---|---|\n"
              + "| `robot.select(slot)` | Set active slot |\n"
              + "| `robot.getSelected()` | Current active slot |\n"
              + "| `robot.getItemCount(slot)` | Stack size in slot |\n"
              + "| `robot.getItemName(slot)` | Registry id, e.g. `minecraft:cobblestone` |\n"
              + "| `robot.refuel(slot)` | Burn a fuel item → FE. Returns FE added. |\n"
              + "\n"
              + "Fuel values: coal/charcoal 1600, blaze rod 2400, coal block 16000, lava bucket 20000.\n");
        }

        // Drone API docs
        if (!fileSystem.exists("/Users/User/Documents/docs/drone.md")) {
            fileSystem.writeFile("/Users/User/Documents/docs/drone.md",
                "# drone API\n"
              + "\n"
              + "All calls are fire-and-forget Bluetooth broadcasts on the chosen\n"
              + "channel (defaults to the OS's current channel). Any DroneEntity\n"
              + "listening on that channel within range will obey.\n"
              + "\n"
              + "| Fn | Effect |\n"
              + "|---|---|\n"
              + "| `drone.waypoint(x, y, z [, ch])` | Append a flight waypoint |\n"
              + "| `drone.home([ch])` | Clear waypoints & return to home |\n"
              + "| `drone.clear([ch])` | Clear waypoints, stay in place |\n"
              + "| `drone.hover(bool [, ch])` | Toggle idle hover |\n"
              + "| `drone.refuel(ticks [, ch])` | Remote fuel grant (0..72000) |\n"
              + "\n"
              + "## Binding drones to a channel\n"
              + "1. Right-click a drone with a fuel item (coal, blaze rod, lava\n"
              + "   bucket) to fuel it.\n"
              + "2. The drone registers on BT under its own UUID and listens on\n"
              + "   its own channel (shown when you right-click it bare-handed).\n"
              + "3. Set this computer's channel to match with `bluetooth.setChannel(n)`\n"
              + "   or use `drone.waypoint(x, y, z, n)` to target a specific channel.\n"
              + "\n"
              + "## Protocol (for custom senders)\n"
              + "Plain strings on the drone's channel:\n"
              + "`drone:waypoint:<x>:<y>:<z>` · `drone:home` · `drone:clear`\n"
              + "`drone:hover:<true|false>` · `drone:refuel:<ticks>`\n");
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

    public net.minecraft.world.entity.Entity getHost() { return host; }
    public void setHost(net.minecraft.world.entity.Entity host) { this.host = host; }

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

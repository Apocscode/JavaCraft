package com.apocscode.byteblock.computer;

import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity;
import com.apocscode.byteblock.block.entity.RedstoneRelayBlockEntity;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * RedstoneLib — comprehensive redstone control API for ByteBlock programs.
 *
 * Programs use this library to:
 *   - Control analog redstone output (0-15) on relay sides
 *   - Read world redstone input from relay sides
 *   - Control 16-color bundled cable channels
 *   - Control button panel states
 *   - Evaluate logic gates (AND, OR, XOR, NOT, NAND, NOR, etc.)
 *   - Signal manipulation (comparator, multiplexer, pulse detection)
 *
 * Side mapping (matches Direction.get3DDataValue()):
 *   0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST
 *
 * Usage from programs:
 *   RedstoneLib.setOutput(os, side, power);
 *   int input = RedstoneLib.getInput(os, side);
 *   RedstoneLib.setBundledColor(os, side, 14, true);  // set red channel
 */
public class RedstoneLib {

    // ==================== Device Discovery ====================

    /**
     * Find the nearest Redstone Relay block to this computer.
     * Returns the BlockPos or null if no relay is in range.
     */
    public static BlockPos findRelay(JavaOS os) {
        Level level = os.getLevel();
        BlockPos computerPos = os.getBlockPos();
        if (level == null || computerPos == null) return null;
        return BluetoothNetwork.findNearestDevice(level, computerPos,
                BluetoothNetwork.DeviceType.REDSTONE_RELAY);
    }

    /**
     * Find the nearest Button Panel block to this computer.
     */
    public static BlockPos findButtonPanel(JavaOS os) {
        Level level = os.getLevel();
        BlockPos computerPos = os.getBlockPos();
        if (level == null || computerPos == null) return null;
        return BluetoothNetwork.findNearestDevice(level, computerPos,
                BluetoothNetwork.DeviceType.BUTTON_PANEL);
    }

    // ==================== Analog Redstone Output ====================

    /**
     * Set analog redstone output (0-15) on a relay side.
     * @param side 0=down, 1=up, 2=north, 3=south, 4=west, 5=east
     * @param power signal strength 0-15
     */
    public static void setOutput(JavaOS os, int side, int power) {
        BlockPos relayPos = findRelay(os);
        if (relayPos != null) {
            BluetoothNetwork.setRedstoneOutput(relayPos, side, power);
        }
    }

    /**
     * Set output on a relay side by direction name.
     */
    public static void setOutput(JavaOS os, String sideName, int power) {
        setOutput(os, parseSide(sideName), power);
    }

    /**
     * Get current output setting for a relay side.
     */
    public static int getOutput(JavaOS os, int side) {
        BlockPos relayPos = findRelay(os);
        if (relayPos == null) return 0;
        return BluetoothNetwork.getRedstoneOutput(relayPos, side);
    }

    /**
     * Set all 6 sides to the same output power.
     */
    public static void setAllOutputs(JavaOS os, int power) {
        BlockPos relayPos = findRelay(os);
        if (relayPos != null) {
            for (int i = 0; i < 6; i++) {
                BluetoothNetwork.setRedstoneOutput(relayPos, i, power);
            }
        }
    }

    // ==================== Analog Redstone Input ====================

    /**
     * Read world redstone input for a relay side.
     * Returns the signal strength the relay sees from that direction.
     */
    public static int getInput(JavaOS os, int side) {
        BlockPos relayPos = findRelay(os);
        if (relayPos == null) return 0;
        Level level = os.getLevel();
        if (level == null) return 0;
        if (level.getBlockEntity(relayPos) instanceof RedstoneRelayBlockEntity relay) {
            return relay.getInput(side);
        }
        return 0;
    }

    /**
     * Read input by direction name.
     */
    public static int getInput(JavaOS os, String sideName) {
        return getInput(os, parseSide(sideName));
    }

    /**
     * Get all 6 input values as an array.
     */
    public static int[] getAllInputs(JavaOS os) {
        BlockPos relayPos = findRelay(os);
        if (relayPos == null) return new int[6];
        Level level = os.getLevel();
        if (level == null) return new int[6];
        if (level.getBlockEntity(relayPos) instanceof RedstoneRelayBlockEntity relay) {
            return relay.getInputs().clone();
        }
        return new int[6];
    }

    // ==================== Bundled Cable Output ====================

    /**
     * Set the full 16-bit bundled cable mask for a side.
     * Each bit represents one color channel (bit 0=white, bit 15=black).
     */
    public static void setBundledOutput(JavaOS os, int side, int colorMask) {
        BlockPos relayPos = findRelay(os);
        if (relayPos != null) {
            BluetoothNetwork.setBundledOutput(relayPos, side, colorMask);
        }
    }

    /**
     * Get the current bundled output mask for a side.
     */
    public static int getBundledOutput(JavaOS os, int side) {
        BlockPos relayPos = findRelay(os);
        if (relayPos == null) return 0;
        return BluetoothNetwork.getBundledOutput(relayPos, side);
    }

    /**
     * Set a single color channel on/off in the bundled output.
     * @param color 0-15 (0=white, 1=orange, ... 14=red, 15=black)
     */
    public static void setBundledColor(JavaOS os, int side, int color, boolean on) {
        BlockPos relayPos = findRelay(os);
        if (relayPos == null) return;
        int mask = BluetoothNetwork.getBundledOutput(relayPos, side);
        if (on) {
            mask |= (1 << (color & 0xF));
        } else {
            mask &= ~(1 << (color & 0xF));
        }
        BluetoothNetwork.setBundledOutput(relayPos, side, mask);
    }

    /**
     * Check if a specific color channel is active in the bundled output.
     */
    public static boolean getBundledColor(JavaOS os, int side, int color) {
        int mask = getBundledOutput(os, side);
        return (mask & (1 << (color & 0xF))) != 0;
    }

    // ==================== Button Panel Control ====================

    /**
     * Set a button's state on the nearest button panel.
     * @param index button index 0-15
     * @param lit true = on, false = off
     */
    public static void setButton(JavaOS os, int index, boolean lit) {
        BlockPos panelPos = findButtonPanel(os);
        if (panelPos == null) return;
        Level level = os.getLevel();
        if (level != null && level.getBlockEntity(panelPos) instanceof ButtonPanelBlockEntity panel) {
            panel.setButton(index, lit);
        }
    }

    /**
     * Get a button's current state.
     */
    public static boolean getButton(JavaOS os, int index) {
        BlockPos panelPos = findButtonPanel(os);
        if (panelPos == null) return false;
        Level level = os.getLevel();
        if (level != null && level.getBlockEntity(panelPos) instanceof ButtonPanelBlockEntity panel) {
            return panel.isButtonOn(index);
        }
        return false;
    }

    /**
     * Get all button states as a 16-bit mask.
     */
    public static int getButtonStates(JavaOS os) {
        BlockPos panelPos = findButtonPanel(os);
        if (panelPos == null) return 0;
        Level level = os.getLevel();
        if (level != null && level.getBlockEntity(panelPos) instanceof ButtonPanelBlockEntity panel) {
            return panel.getButtonStates();
        }
        return 0;
    }

    /**
     * Set all button states from a 16-bit mask.
     */
    public static void setAllButtons(JavaOS os, int mask) {
        BlockPos panelPos = findButtonPanel(os);
        if (panelPos == null) return;
        Level level = os.getLevel();
        if (level != null && level.getBlockEntity(panelPos) instanceof ButtonPanelBlockEntity panel) {
            for (int i = 0; i < 16; i++) {
                panel.setButton(i, (mask & (1 << i)) != 0);
            }
        }
    }

    // ==================== Logic Gates (Analog 0-15) ====================
    // These operate on standard redstone signal levels.
    // Useful for building virtual circuitry in programs.

    /** AND gate: minimum of two signals */
    public static int AND(int a, int b) { return Math.min(a, b); }

    /** OR gate: maximum of two signals */
    public static int OR(int a, int b) { return Math.max(a, b); }

    /** NOT gate: invert signal (15 - value) */
    public static int NOT(int a) { return 15 - Math.min(15, Math.max(0, a)); }

    /** XOR gate: output 15 if exactly one input is active */
    public static int XOR(int a, int b) { return (a > 0) != (b > 0) ? 15 : 0; }

    /** NAND gate */
    public static int NAND(int a, int b) { return NOT(AND(a, b)); }

    /** NOR gate */
    public static int NOR(int a, int b) { return NOT(OR(a, b)); }

    /** XNOR gate */
    public static int XNOR(int a, int b) { return NOT(XOR(a, b)); }

    /** Buffer: normalize to either 0 or 15 */
    public static int BUFFER(int a) { return a > 0 ? 15 : 0; }

    // ==================== Logic Gates (Digital/Boolean) ====================
    // For bundled cable logic where each channel is on/off.

    public static boolean dAND(boolean a, boolean b) { return a && b; }
    public static boolean dOR(boolean a, boolean b)  { return a || b; }
    public static boolean dNOT(boolean a) { return !a; }
    public static boolean dXOR(boolean a, boolean b) { return a ^ b; }
    public static boolean dNAND(boolean a, boolean b) { return !(a && b); }
    public static boolean dNOR(boolean a, boolean b)  { return !(a || b); }
    public static boolean dXNOR(boolean a, boolean b) { return a == b; }

    // ==================== Signal Manipulation ====================

    /** Comparator mode: compare — output A if A >= B, else 0 */
    public static int COMPARE(int a, int b) { return a >= b ? a : 0; }

    /** Comparator mode: subtract — output A - B (min 0) */
    public static int SUBTRACT(int a, int b) { return Math.max(0, a - b); }

    /** Multiplexer: if selector > 0 output B, else output A */
    public static int MUX(int selector, int a, int b) { return selector > 0 ? b : a; }

    /** Clamp a value to 0-15 range */
    public static int CLAMP(int value) { return Math.min(15, Math.max(0, value)); }

    /** Random signal 0-15 */
    public static int RANDOM() { return (int) (Math.random() * 16); }

    /** Random signal with max */
    public static int RANDOM(int max) { return (int) (Math.random() * (Math.min(16, max + 1))); }

    // ==================== Edge Detection ====================
    // Programs call these each tick with current and previous values
    // to detect signal transitions.

    /** Rising edge: returns true when signal goes from 0 to >0 */
    public static boolean RISING(int current, int previous) {
        return current > 0 && previous == 0;
    }

    /** Falling edge: returns true when signal goes from >0 to 0 */
    public static boolean FALLING(int current, int previous) {
        return current == 0 && previous > 0;
    }

    /** Any change: returns true when signal value changes */
    public static boolean CHANGED(int current, int previous) {
        return current != previous;
    }

    // ==================== RS Latch / Flip-Flop ====================

    /**
     * RS Latch: Set/Reset latch with priority to Set.
     * @param set signal to set (turn on)
     * @param reset signal to reset (turn off)
     * @param current current latch state
     * @return new latch state
     */
    public static boolean LATCH(boolean set, boolean reset, boolean current) {
        if (set) return true;
        if (reset) return false;
        return current;
    }

    /**
     * Toggle latch: toggles state on rising edge of input.
     * @param trigger current trigger signal
     * @param prevTrigger previous trigger signal (for edge detection)
     * @param current current latch state
     */
    public static boolean TOGGLE(boolean trigger, boolean prevTrigger, boolean current) {
        if (trigger && !prevTrigger) return !current;
        return current;
    }

    // ==================== Counter ====================

    /**
     * Counter: increment value on rising edge, wrap at max.
     * @param trigger current trigger signal
     * @param prevTrigger previous trigger
     * @param currentCount current count
     * @param max maximum count (exclusive)
     * @return new count value
     */
    public static int COUNTER(boolean trigger, boolean prevTrigger, int currentCount, int max) {
        if (trigger && !prevTrigger) {
            return (currentCount + 1) % max;
        }
        return currentCount;
    }

    // ==================== Utility ====================

    /**
     * Parse a side name to its numeric index.
     * Accepts: down/0, up/1, north/2, south/3, west/4, east/5
     */
    public static int parseSide(String name) {
        if (name == null || name.isEmpty()) return 0;
        return switch (name.toLowerCase().trim()) {
            case "down", "bottom", "d", "0" -> 0;
            case "up", "top", "u", "1" -> 1;
            case "north", "n", "2" -> 2;
            case "south", "s", "3" -> 3;
            case "west", "w", "4" -> 4;
            case "east", "e", "5" -> 5;
            default -> {
                try { yield Math.min(5, Math.max(0, Integer.parseInt(name))); }
                catch (NumberFormatException e) { yield 0; }
            }
        };
    }

    /**
     * Get the side name from its numeric index.
     */
    public static String sideName(int side) {
        return switch (side) {
            case 0 -> "down";
            case 1 -> "up";
            case 2 -> "north";
            case 3 -> "south";
            case 4 -> "west";
            case 5 -> "east";
            default -> "unknown";
        };
    }

    /**
     * Color index to name.
     */
    public static String colorName(int color) {
        return switch (color & 0xF) {
            case 0 -> "white";
            case 1 -> "orange";
            case 2 -> "magenta";
            case 3 -> "light_blue";
            case 4 -> "yellow";
            case 5 -> "lime";
            case 6 -> "pink";
            case 7 -> "gray";
            case 8 -> "light_gray";
            case 9 -> "cyan";
            case 10 -> "purple";
            case 11 -> "blue";
            case 12 -> "brown";
            case 13 -> "green";
            case 14 -> "red";
            case 15 -> "black";
            default -> "unknown";
        };
    }
}

package com.apocscode.byteblock.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bluetooth (Rednet) — wireless networking between ByteBlock devices.
 * Devices register themselves and can broadcast/receive messages
 * on numbered channels. Range-limited by device type.
 *
 * Standard blocks: 15-block radius.
 * Drones / Robots: unlimited range, cross-dimensional.
 */
public class BluetoothNetwork {
    public static final int BLOCK_RANGE = 15;
    public static final int UNLIMITED_RANGE = Integer.MAX_VALUE;

    /** Device types for the Bluetooth network */
    public enum DeviceType {
        COMPUTER("Computer", BLOCK_RANGE),
        GPS("GPS Satellite", UNLIMITED_RANGE),
        SCANNER("Scanner", BLOCK_RANGE),
        DRIVE("Tape Drive", BLOCK_RANGE),
        PRINTER("Printer", BLOCK_RANGE),
        CHARGING_STATION("Charging Station", BLOCK_RANGE),
        PERIPHERAL("Peripheral", BLOCK_RANGE),
        ROBOT("Robot", UNLIMITED_RANGE),
        DRONE("Drone", UNLIMITED_RANGE),
        REDSTONE_RELAY("Redstone Relay", BLOCK_RANGE),
        BUTTON_PANEL("Button Panel", UNLIMITED_RANGE),
        MONITOR("Monitor", BLOCK_RANGE),
        BYTE_CHEST("ByteChest", BLOCK_RANGE);

        private final String displayName;
        private final int range;
        DeviceType(String displayName, int range) {
            this.displayName = displayName;
            this.range = range;
        }
        public String getDisplayName() { return displayName; }
        public int getRange() { return range; }
    }

    /**
     * Registry of active devices: dimension -> list of registered devices.
     */
    private static final Map<String, List<DeviceEntry>> devices = new ConcurrentHashMap<>();

    /**
     * Pending messages: deviceId -> list of incoming messages.
     */
    private static final Map<UUID, Queue<Message>> inbox = new ConcurrentHashMap<>();

    /**
     * Global GPS registry — GPS blocks register here with no range/dimension limits.
     */
    private static final Map<UUID, GpsEntry> gpsDevices = new ConcurrentHashMap<>();

    /**
     * ByteChest label registry — deviceId → player-defined label.
     * Empty / missing entries fall back to the default chest name.
     */
    private static final Map<UUID, String> chestLabels = new ConcurrentHashMap<>();

    /**
     * Redstone signal cache: blockPos hash -> side -> power level (0-15).
     * Computers write here; the ComputerBlockEntity reads it for block updates.
     */
    private static final Map<String, int[]> redstoneOutputs = new ConcurrentHashMap<>();

    /**
     * Bundled cable signal cache: blockPos hash -> side -> 16-bit color mask.
     */
    private static final Map<String, int[]> bundledOutputs = new ConcurrentHashMap<>();

    public record GpsEntry(UUID deviceId, BlockPos pos, String dimension, long lastSeen) {}

    /**
     * Register a device on the network (called each tick by active devices).
     */
    public static void register(Level level, UUID deviceId, BlockPos pos, int channel, DeviceType type) {
        if (level == null) return;
        String dim = level.dimension().location().toString();
        devices.computeIfAbsent(dim, k -> Collections.synchronizedList(new ArrayList<>()));
        List<DeviceEntry> dimDevices = devices.get(dim);

        synchronized (dimDevices) {
            dimDevices.removeIf(d -> d.deviceId.equals(deviceId));
            dimDevices.add(new DeviceEntry(deviceId, pos, channel, level.getGameTime(), type, dim));
        }
    }

    /**
     * Register one device on multiple channels for the same tick.
     * Existing entries for the same device are replaced.
     */
    public static void registerMulti(Level level, UUID deviceId, BlockPos pos, Collection<Integer> channels, DeviceType type) {
        if (level == null) return;
        String dim = level.dimension().location().toString();
        devices.computeIfAbsent(dim, k -> Collections.synchronizedList(new ArrayList<>()));
        List<DeviceEntry> dimDevices = devices.get(dim);

        synchronized (dimDevices) {
            dimDevices.removeIf(d -> d.deviceId.equals(deviceId));
            for (int channel : channels) {
                dimDevices.add(new DeviceEntry(deviceId, pos, channel, level.getGameTime(), type, dim));
            }
        }
    }

    /** Backward-compatible register (defaults to COMPUTER type) */
    public static void register(Level level, UUID deviceId, BlockPos pos, int channel) {
        register(level, deviceId, pos, channel, DeviceType.COMPUTER);
    }

    /**
     * Broadcast a message to all devices on the given channel within sender's range.
     */
    public static void broadcast(Level level, UUID senderId, BlockPos senderPos, int channel, String message) {
        if (level == null) return;
        String dim = level.dimension().location().toString();
        DeviceEntry senderEntry = findEntry(senderId);
        int range = senderEntry != null ? senderEntry.type.getRange() : BLOCK_RANGE;

        // Same-dimension devices
        List<DeviceEntry> dimDevices = devices.getOrDefault(dim, List.of());
        synchronized (dimDevices) {
            for (DeviceEntry device : dimDevices) {
                if (device.deviceId.equals(senderId)) continue;
                if (device.channel == channel && isInRange(senderPos, device.pos, range)) {
                    enqueue(device.deviceId, channel, message, senderPos, level.getGameTime(), senderId);
                }
            }
        }

        // Cross-dimensional for unlimited-range senders
        if (range == UNLIMITED_RANGE) {
            for (Map.Entry<String, List<DeviceEntry>> entry : devices.entrySet()) {
                if (entry.getKey().equals(dim)) continue;
                List<DeviceEntry> otherDim = entry.getValue();
                synchronized (otherDim) {
                    for (DeviceEntry device : otherDim) {
                        if (device.channel == channel) {
                            enqueue(device.deviceId, channel, message, senderPos, 0, senderId);
                        }
                    }
                }
            }
        }
    }

    /** Backward-compatible broadcast (by position, no sender filtering) */
    public static void broadcast(Level level, BlockPos senderPos, int channel, String message) {
        if (level == null) return;
        String dim = level.dimension().location().toString();
        List<DeviceEntry> dimDevices = devices.getOrDefault(dim, List.of());

        synchronized (dimDevices) {
            for (DeviceEntry device : dimDevices) {
                if (device.channel == channel && isInRange(senderPos, device.pos, BLOCK_RANGE)) {
                    enqueue(device.deviceId, channel, message, senderPos, level.getGameTime(), null);
                }
            }
        }
    }

    /**
     * Broadcast a message by sender device UUID (looks up sender position from registry).
     */
    public static void broadcastFromDevice(UUID senderId, int channel, String message) {
        DeviceEntry senderEntry = findEntry(senderId);
        if (senderEntry == null) return;
        int range = senderEntry.type.getRange();

        for (Map.Entry<String, List<DeviceEntry>> entry : devices.entrySet()) {
            boolean sameDim = entry.getKey().equals(senderEntry.dimension);
            if (!sameDim && range != UNLIMITED_RANGE) continue;

            List<DeviceEntry> dimDevices = entry.getValue();
            synchronized (dimDevices) {
                for (DeviceEntry device : dimDevices) {
                    if (device.deviceId.equals(senderId)) continue;
                    if (device.channel == channel && (range == UNLIMITED_RANGE || isInRange(senderEntry.pos, device.pos, range))) {
                        enqueue(device.deviceId, channel, message, senderEntry.pos, 0, senderId);
                    }
                }
            }
        }
    }

    /**
     * Send a direct message to a specific device ID.
     */
    public static void send(Level level, BlockPos senderPos, UUID senderId, UUID targetId, int channel, String message) {
        enqueue(targetId, channel, message, senderPos, level != null ? level.getGameTime() : 0, senderId);
    }

    /** Backward-compatible send */
    public static void send(Level level, BlockPos senderPos, UUID targetId, String message) {
        send(level, senderPos, null, targetId, -1, message);
    }

    /**
     * Poll the inbox for a device. Returns next message or null.
     */
    public static Message receive(UUID deviceId) {
        Queue<Message> queue = inbox.get(deviceId);
        return queue != null ? queue.poll() : null;
    }

    /**
     * Get all devices in range of a given position/dimension.
     */
    public static List<DeviceEntry> getDevicesInRange(Level level, BlockPos pos, int range) {
        if (level == null) return List.of();
        String dim = level.dimension().location().toString();
        List<DeviceEntry> dimDevices = devices.getOrDefault(dim, List.of());
        List<DeviceEntry> result = new ArrayList<>();
        synchronized (dimDevices) {
            for (DeviceEntry d : dimDevices) {
                if (isInRange(pos, d.pos, range)) result.add(d);
            }
        }
        return result;
    }

    /**
     * Get all devices across all dimensions (for unlimited-range queries).
     */
    public static List<DeviceEntry> getAllDevices() {
        List<DeviceEntry> result = new ArrayList<>();
        for (List<DeviceEntry> dimDevices : devices.values()) {
            synchronized (dimDevices) {
                result.addAll(dimDevices);
            }
        }
        return result;
    }

    /**
     * Get all devices in a dimension on a given channel.
     */
    public static List<DeviceEntry> getDevicesOnChannel(Level level, int channel) {
        if (level == null) return List.of();
        String dim = level.dimension().location().toString();
        List<DeviceEntry> dimDevices = devices.getOrDefault(dim, List.of());
        List<DeviceEntry> result = new ArrayList<>();
        synchronized (dimDevices) {
            for (DeviceEntry d : dimDevices) {
                if (d.channel == channel) result.add(d);
            }
        }
        return result;
    }

    /**
     * Check if any Computer device is within standard BT range of a position.
     */
    public static boolean isComputerInRange(Level level, BlockPos pos) {
        if (level == null) return false;
        List<DeviceEntry> nearby = getDevicesInRange(level, pos, BLOCK_RANGE);
        for (DeviceEntry d : nearby) {
            if (d.type == DeviceType.COMPUTER) return true;
        }
        return false;
    }

    /**
     * Purge stale device entries (not updated in 200 ticks = 10 seconds).
     * Call from server tick handler.
     */
    public static void cleanup(Level level) {
        if (level == null) return;
        String dim = level.dimension().location().toString();
        List<DeviceEntry> dimDevices = devices.get(dim);
        if (dimDevices == null) return;
        long now = level.getGameTime();
        synchronized (dimDevices) {
            dimDevices.removeIf(d -> now - d.lastSeen > 200);
        }
        cleanupGps(now);
    }

    // --- GPS Global Network (no range/dimension restrictions) ---

    /**
     * Register a GPS satellite on the global network.
     * Called each tick by GPS block entities.
     */
    public static void registerGps(UUID deviceId, BlockPos pos, String dimension, long gameTime) {
        gpsDevices.put(deviceId, new GpsEntry(deviceId, pos, dimension, gameTime));
    }

    /**
     * Broadcast a message globally to ALL devices on the given channel,
     * across ALL dimensions with no range limit. Used by GPS blocks.
     */
    public static void broadcastGlobal(int channel, String message, BlockPos senderPos) {
        for (Map.Entry<String, List<DeviceEntry>> entry : devices.entrySet()) {
            List<DeviceEntry> dimDevices = entry.getValue();
            synchronized (dimDevices) {
                for (DeviceEntry device : dimDevices) {
                    if (device.channel == channel) {
                        enqueue(device.deviceId, channel, message, senderPos, 0, null);
                    }
                }
            }
        }
    }

    /**
     * Check if any GPS satellite is active on the network.
     */
    public static boolean hasActiveGps() {
        return !gpsDevices.isEmpty();
    }

    /**
     * Find a device's position by UUID, searching all dimensions.
     * Returns the BlockPos or null if not found.
     */
    public static BlockPos findDevicePos(UUID deviceId) {
        DeviceEntry entry = findEntry(deviceId);
        return entry != null ? entry.pos : null;
    }

    /**
     * Find a DeviceEntry by UUID across all dimensions.
     */
    public static DeviceEntry findEntry(UUID deviceId) {
        for (List<DeviceEntry> dimDevices : devices.values()) {
            synchronized (dimDevices) {
                for (DeviceEntry d : dimDevices) {
                    if (d.deviceId.equals(deviceId)) return d;
                }
            }
        }
        return null;
    }

    /**
     * Purge stale GPS entries (not updated in 200 ticks).
     */
    public static void cleanupGps(long gameTime) {
        gpsDevices.entrySet().removeIf(e -> gameTime - e.getValue().lastSeen > 200);
    }

    /**
     * Find all devices of a specific type within their declared range of a position.
     * Returns positions sorted nearest-first.
     */
    public static List<BlockPos> findAllDevices(Level level, BlockPos from, DeviceType type) {
        if (level == null) return List.of();
        String dim = level.dimension().location().toString();
        List<DeviceEntry> dimDevices = devices.getOrDefault(dim, List.of());
        long rangeSq = (long) type.getRange() * type.getRange();
        List<BlockPos> result = new ArrayList<>();
        synchronized (dimDevices) {
            for (DeviceEntry d : dimDevices) {
                if (d.type == type && d.pos.distSqr(from) <= rangeSq) {
                    result.add(d.pos);
                }
            }
        }
        result.sort(Comparator.comparingDouble(pos -> pos.distSqr(from)));
        return result;
    }

    /**
     * Find the nearest device of a specific type within its range.
     */
    public static BlockPos findNearestDevice(Level level, BlockPos from, DeviceType type) {
        if (level == null) return null;
        String dim = level.dimension().location().toString();
        List<DeviceEntry> dimDevices = devices.getOrDefault(dim, List.of());
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        synchronized (dimDevices) {
            for (DeviceEntry d : dimDevices) {
                if (d.type == type) {
                    double dist = d.pos.distSqr(from);
                    if (dist < nearestDist && dist <= (long) type.getRange() * type.getRange()) {
                        nearestDist = dist;
                        nearest = d.pos;
                    }
                }
            }
        }
        return nearest;
    }

    // --- ByteChest labels ---

    /** Update the label for a ByteChest deviceId (empty string clears). */
    public static void setChestLabel(UUID deviceId, String label) {
        if (deviceId == null) return;
        if (label == null || label.isEmpty()) chestLabels.remove(deviceId);
        else chestLabels.put(deviceId, label);
    }

    /** Lookup the label for a ByteChest deviceId; "" if unset. */
    public static String getChestLabel(UUID deviceId) {
        if (deviceId == null) return "";
        String s = chestLabels.get(deviceId);
        return s == null ? "" : s;
    }

    /**
     * Find the nearest ByteChest position with the given label (case-insensitive)
     * within {@code range} blocks of {@code from} in the same dimension. Null if none.
     */
    public static BlockPos findChestPosByLabel(Level level, BlockPos from, int range, String label) {
        if (level == null || label == null || label.isEmpty()) return null;
        String dim = level.dimension().location().toString();
        List<DeviceEntry> dimDevices = devices.getOrDefault(dim, List.of());
        long rangeSq = (long) range * range;
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        synchronized (dimDevices) {
            for (DeviceEntry d : dimDevices) {
                if (d.type != DeviceType.BYTE_CHEST) continue;
                String l = chestLabels.get(d.deviceId);
                if (l == null || !l.equalsIgnoreCase(label)) continue;
                double dist = d.pos.distSqr(from);
                if (dist < nearestDist && dist <= rangeSq) {
                    nearestDist = dist;
                    nearest = d.pos;
                }
            }
        }
        return nearest;
    }

    /** Snapshot record for {@link #listLabeledChests}. */
    public record LabeledChest(UUID deviceId, BlockPos pos, String label) {}

    /**
     * List every ByteChest in {@code range} of {@code from} (same dimension), labeled or not.
     * Returned positions are sorted nearest-first.
     */
    public static List<LabeledChest> listLabeledChests(Level level, BlockPos from, int range) {
        if (level == null) return List.of();
        String dim = level.dimension().location().toString();
        List<DeviceEntry> dimDevices = devices.getOrDefault(dim, List.of());
        long rangeSq = (long) range * range;
        List<LabeledChest> result = new ArrayList<>();
        synchronized (dimDevices) {
            for (DeviceEntry d : dimDevices) {
                if (d.type != DeviceType.BYTE_CHEST) continue;
                if (d.pos.distSqr(from) > rangeSq) continue;
                String l = chestLabels.getOrDefault(d.deviceId, "");
                result.add(new LabeledChest(d.deviceId, d.pos, l));
            }
        }
        result.sort(Comparator.comparingDouble(c -> c.pos().distSqr(from)));
        return result;
    }

    // --- Redstone Output ---

    /** Set analog redstone output for a side (0=down,1=up,2=north,3=south,4=west,5=east) */
    public static void setRedstoneOutput(BlockPos pos, int side, int power) {
        String key = pos.toShortString();
        int[] sides = redstoneOutputs.computeIfAbsent(key, k -> new int[6]);
        sides[Math.min(5, Math.max(0, side))] = Math.min(15, Math.max(0, power));
    }

    /** Get analog redstone output. Returns 0-15. */
    public static int getRedstoneOutput(BlockPos pos, int side) {
        int[] sides = redstoneOutputs.get(pos.toShortString());
        if (sides == null) return 0;
        return sides[Math.min(5, Math.max(0, side))];
    }

    /** Set bundled cable output for a side (16-bit color mask) */
    public static void setBundledOutput(BlockPos pos, int side, int colorMask) {
        String key = pos.toShortString();
        int[] sides = bundledOutputs.computeIfAbsent(key, k -> new int[6]);
        sides[Math.min(5, Math.max(0, side))] = colorMask & 0xFFFF;
    }

    /** Get bundled cable output mask. Returns 16-bit color bitmask. */
    public static int getBundledOutput(BlockPos pos, int side) {
        int[] sides = bundledOutputs.get(pos.toShortString());
        if (sides == null) return 0;
        return sides[Math.min(5, Math.max(0, side))];
    }

    // --- Internal Helpers ---

    private static void enqueue(UUID targetId, int channel, String message, BlockPos senderPos, long timestamp, UUID senderId) {
        inbox.computeIfAbsent(targetId, k -> new LinkedList<>());
        Queue<Message> queue = inbox.get(targetId);
        queue.add(new Message(channel, message, senderPos, timestamp, senderId));
        while (queue.size() > 64) queue.poll();
    }

    private static boolean isInRange(BlockPos a, BlockPos b, int range) {
        if (range == UNLIMITED_RANGE) return true;
        return a.distSqr(b) <= (long) range * range;
    }

    // --- Inner types ---

    public record DeviceEntry(UUID deviceId, BlockPos pos, int channel, long lastSeen, DeviceType type, String dimension) {}

    public record Message(int channel, String content, BlockPos senderPos, long timestamp, UUID senderId) {}
}

package com.apocscode.javacraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bluetooth (Rednet) — wireless networking between JavaCraft devices.
 * Devices register themselves and can broadcast/receive messages
 * on numbered channels. Range-limited (default 256 blocks).
 */
public class BluetoothNetwork {
    private static final int DEFAULT_RANGE = 256;

    /**
     * Registry of active devices: dimension -> list of registered devices.
     */
    private static final Map<String, List<DeviceEntry>> devices = new ConcurrentHashMap<>();

    /**
     * Pending messages: deviceId -> list of incoming messages.
     */
    private static final Map<UUID, Queue<Message>> inbox = new ConcurrentHashMap<>();

    /**
     * Register a device on the network (called each tick by active devices).
     */
    public static void register(Level level, UUID deviceId, BlockPos pos, int channel) {
        if (level == null) return;
        String dim = level.dimension().location().toString();
        devices.computeIfAbsent(dim, k -> Collections.synchronizedList(new ArrayList<>()));
        List<DeviceEntry> dimDevices = devices.get(dim);

        // Update or insert
        synchronized (dimDevices) {
            dimDevices.removeIf(d -> d.deviceId.equals(deviceId));
            dimDevices.add(new DeviceEntry(deviceId, pos, channel, level.getGameTime()));
        }
    }

    /**
     * Broadcast a message to all devices on the given channel within range.
     */
    public static void broadcast(Level level, BlockPos senderPos, int channel, String message) {
        if (level == null) return;
        String dim = level.dimension().location().toString();
        List<DeviceEntry> dimDevices = devices.getOrDefault(dim, List.of());

        synchronized (dimDevices) {
            for (DeviceEntry device : dimDevices) {
                if (device.channel == channel && isInRange(senderPos, device.pos)) {
                    inbox.computeIfAbsent(device.deviceId, k -> new LinkedList<>());
                    Queue<Message> queue = inbox.get(device.deviceId);
                    queue.add(new Message(channel, message, senderPos, level.getGameTime()));
                    // Cap inbox size
                    while (queue.size() > 64) queue.poll();
                }
            }
        }
    }

    /**
     * Send a direct message to a specific device ID on its channel.
     */
    public static void send(Level level, BlockPos senderPos, UUID targetId, String message) {
        inbox.computeIfAbsent(targetId, k -> new LinkedList<>());
        Queue<Message> queue = inbox.get(targetId);
        queue.add(new Message(-1, message, senderPos, level != null ? level.getGameTime() : 0));
        while (queue.size() > 64) queue.poll();
    }

    /**
     * Poll the inbox for a device. Returns next message or null.
     */
    public static Message receive(UUID deviceId) {
        Queue<Message> queue = inbox.get(deviceId);
        return queue != null ? queue.poll() : null;
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
    }

    private static boolean isInRange(BlockPos a, BlockPos b) {
        return a.distSqr(b) <= (long) DEFAULT_RANGE * DEFAULT_RANGE;
    }

    // --- Inner types ---

    public record DeviceEntry(UUID deviceId, BlockPos pos, int channel, long lastSeen) {}

    public record Message(int channel, String content, BlockPos senderPos, long timestamp) {}
}

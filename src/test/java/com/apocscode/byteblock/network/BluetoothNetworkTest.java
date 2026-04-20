package com.apocscode.byteblock.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BluetoothNetwork — static wireless network.
 * Tests Level-free APIs: GPS registry, send/receive, findDevicePos, broadcastGlobal.
 * We manipulate the static maps directly through the public API.
 */
class BluetoothNetworkTest {

    @BeforeEach
    void clearNetwork() {
        // Wipe all state between tests by cleaning up with a far-future game time
        BluetoothNetwork.cleanupGps(Long.MAX_VALUE);
        // For the device registry we can't easily clear since register() needs Level,
        // but we can at least test GPS and messaging APIs that don't need Level.
    }

    // --- GPS Registry ---

    @Test
    void noGpsByDefault() {
        assertFalse(BluetoothNetwork.hasActiveGps());
    }

    @Test
    void registerGpsMakesItActive() {
        UUID id = UUID.randomUUID();
        BluetoothNetwork.registerGps(id, new BlockPos(100, 64, 200), "minecraft:overworld", 1000L);
        assertTrue(BluetoothNetwork.hasActiveGps());
    }

    @Test
    void cleanupGpsRemovesStaleEntries() {
        UUID id = UUID.randomUUID();
        BluetoothNetwork.registerGps(id, new BlockPos(0, 0, 0), "minecraft:overworld", 100L);
        assertTrue(BluetoothNetwork.hasActiveGps());

        // Cleanup at time 100 + 201 = 301 should purge it (stale after 200 ticks)
        BluetoothNetwork.cleanupGps(301L);
        assertFalse(BluetoothNetwork.hasActiveGps());
    }

    @Test
    void recentGpsSurvivesCleanup() {
        UUID id = UUID.randomUUID();
        BluetoothNetwork.registerGps(id, new BlockPos(0, 0, 0), "minecraft:overworld", 100L);

        // Cleanup at time 200 — only 100 ticks old, should still be alive (< 200 threshold)
        BluetoothNetwork.cleanupGps(200L);
        assertTrue(BluetoothNetwork.hasActiveGps());
    }

    @Test
    void multipleGpsDevices() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        BluetoothNetwork.registerGps(id1, new BlockPos(0, 64, 0), "minecraft:overworld", 100L);
        BluetoothNetwork.registerGps(id2, new BlockPos(1000, 64, 1000), "minecraft:the_nether", 100L);
        assertTrue(BluetoothNetwork.hasActiveGps());

        // Remove one by aging it out
        BluetoothNetwork.registerGps(id2, new BlockPos(1000, 64, 1000), "minecraft:the_nether", 500L);
        BluetoothNetwork.cleanupGps(301L); // id1 is stale (100+200 < 301), id2 still alive
        assertTrue(BluetoothNetwork.hasActiveGps()); // id2 still there
    }

    // --- Messaging (send/receive, no Level needed) ---

    @Test
    void sendAndReceiveDirectMessage() {
        UUID target = UUID.randomUUID();
        BlockPos senderPos = new BlockPos(10, 64, 10);

        BluetoothNetwork.send(null, senderPos, target, "hello");
        BluetoothNetwork.Message msg = BluetoothNetwork.receive(target);

        assertNotNull(msg);
        assertEquals("hello", msg.content());
        assertEquals(senderPos, msg.senderPos());
        assertEquals(-1, msg.channel()); // direct messages use channel -1
    }

    @Test
    void receiveEmptyInboxReturnsNull() {
        UUID target = UUID.randomUUID();
        assertNull(BluetoothNetwork.receive(target));
    }

    @Test
    void messagesAreOrderedFIFO() {
        UUID target = UUID.randomUUID();
        BlockPos pos = new BlockPos(0, 0, 0);

        BluetoothNetwork.send(null, pos, target, "first");
        BluetoothNetwork.send(null, pos, target, "second");
        BluetoothNetwork.send(null, pos, target, "third");

        assertEquals("first", BluetoothNetwork.receive(target).content());
        assertEquals("second", BluetoothNetwork.receive(target).content());
        assertEquals("third", BluetoothNetwork.receive(target).content());
        assertNull(BluetoothNetwork.receive(target));
    }

    @Test
    void inboxCappedAt64Messages() {
        UUID target = UUID.randomUUID();
        BlockPos pos = new BlockPos(0, 0, 0);

        for (int i = 0; i < 100; i++) {
            BluetoothNetwork.send(null, pos, target, "msg" + i);
        }

        // Should have dropped the oldest, keeping 64
        int count = 0;
        while (BluetoothNetwork.receive(target) != null) count++;
        assertEquals(64, count);
    }
}

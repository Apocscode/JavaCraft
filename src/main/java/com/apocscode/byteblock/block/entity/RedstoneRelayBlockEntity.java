package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.RedstoneRelayBlock;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Block entity for the Redstone Relay.
 * Each tick:
 *  - Registers on Bluetooth as REDSTONE_RELAY
 *  - Reads output values from BluetoothNetwork cache → pushes to neighbors
 *  - Reads world redstone input from all 6 sides → stores for program queries
 *  - Fires "redstone_changed" BT message when inputs change
 */
public class RedstoneRelayBlockEntity extends BlockEntity {
    private UUID deviceId = UUID.randomUUID();
    private boolean connected = false;

    /** Last-known output per side (0-5), used to detect changes and push neighbor updates */
    private final int[] lastOutputs = new int[6];

    /** Current world redstone input per side (0-5) */
    private final int[] inputs = new int[6];

    /** Flag set by neighborChanged to force an input re-read */
    private boolean inputDirty = true;

    public RedstoneRelayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.REDSTONE_RELAY.get(), pos, state);
    }

    public void serverTick() {
        if (level == null || level.isClientSide()) return;

        // Register on Bluetooth every tick (heartbeat)
        BluetoothNetwork.register(level, deviceId, worldPosition, 1,
                BluetoothNetwork.DeviceType.REDSTONE_RELAY);

        // Check for connected computer every second
        if (level.getGameTime() % 20 == 0) {
            boolean nowConnected = BluetoothNetwork.isComputerInRange(level, worldPosition);
            if (nowConnected != connected) {
                connected = nowConnected;
                BlockState current = level.getBlockState(worldPosition);
                level.setBlockAndUpdate(worldPosition,
                        current.setValue(RedstoneRelayBlock.CONNECTED, connected));
            }
        }

        // Read outputs from BT cache and push to neighbors if changed
        boolean outputChanged = false;
        for (int i = 0; i < 6; i++) {
            int newOut = BluetoothNetwork.getRedstoneOutput(worldPosition, i);
            if (newOut != lastOutputs[i]) {
                lastOutputs[i] = newOut;
                outputChanged = true;
            }
        }
        if (outputChanged) {
            level.updateNeighborsAt(worldPosition, level.getBlockState(worldPosition).getBlock());
            setChanged();
        }

        // Read world redstone inputs (on neighbor change or every 4 ticks)
        if (inputDirty || level.getGameTime() % 4 == 0) {
            inputDirty = false;
            boolean inputChanged = false;
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = worldPosition.relative(dir);
                // Read the signal from the neighboring block
                int signal = level.getSignal(neighborPos, dir);
                int side = dir.get3DDataValue();
                if (signal != inputs[side]) {
                    inputs[side] = signal;
                    inputChanged = true;
                }
            }
            if (inputChanged) {
                // Broadcast change notification to linked computers
                StringBuilder msg = new StringBuilder("redstone_changed");
                for (int i = 0; i < 6; i++) {
                    msg.append(":").append(inputs[i]);
                }
                BluetoothNetwork.broadcastFromDevice(deviceId, 1, msg.toString());
                setChanged();
            }
        }

        // Process incoming BT messages (for commands from programs)
        BluetoothNetwork.Message btMsg = BluetoothNetwork.receive(deviceId);
        while (btMsg != null) {
            processMessage(btMsg);
            btMsg = BluetoothNetwork.receive(deviceId);
        }
    }

    private void processMessage(BluetoothNetwork.Message msg) {
        // Programs can send "rs_set:<side>:<power>" to set output
        String content = msg.content();
        if (content.startsWith("rs_set:")) {
            String[] parts = content.split(":");
            if (parts.length >= 3) {
                try {
                    int side = Integer.parseInt(parts[1]);
                    int power = Integer.parseInt(parts[2]);
                    BluetoothNetwork.setRedstoneOutput(worldPosition, side, power);
                } catch (NumberFormatException ignored) {}
            }
        } else if (content.startsWith("rs_bundled:")) {
            String[] parts = content.split(":");
            if (parts.length >= 3) {
                try {
                    int side = Integer.parseInt(parts[1]);
                    int colorMask = Integer.parseInt(parts[2]);
                    BluetoothNetwork.setBundledOutput(worldPosition, side, colorMask);
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    public void markInputDirty() { this.inputDirty = true; }
    public boolean isConnected() { return connected; }
    public int getOutput(int side) { return lastOutputs[Math.min(5, Math.max(0, side))]; }
    public int getInput(int side) { return inputs[Math.min(5, Math.max(0, side))]; }
    public int[] getInputs() { return inputs; }
    public UUID getDeviceId() { return deviceId; }

    // --- NBT persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("DeviceId", deviceId);
        tag.putBoolean("Connected", connected);
        tag.putIntArray("Inputs", inputs.clone());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("DeviceId")) deviceId = tag.getUUID("DeviceId");
        if (tag.contains("Connected")) connected = tag.getBoolean("Connected");
        if (tag.contains("Inputs")) {
            int[] saved = tag.getIntArray("Inputs");
            System.arraycopy(saved, 0, inputs, 0, Math.min(saved.length, 6));
        }
    }

    // --- Client sync ---

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}

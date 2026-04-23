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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Block entity for the Redstone Relay.
 * Each tick:
 *  - Registers on Bluetooth as REDSTONE_RELAY
 *  - Reads output values from BluetoothNetwork cache -> pushes to neighbors
 *  - Reads world redstone input from all 6 sides -> stores for program queries
 *  - Fires "redstone_changed" BT message when inputs change
 */
public class RedstoneRelayBlockEntity extends BlockEntity {
    private UUID deviceId = UUID.randomUUID();
    private boolean connected = false;

    /** Last-known output per side (0-5), used to detect changes and push neighbor updates */
    private final int[] lastOutputs = new int[6];

    /** Current world redstone input per side (0-5) */
    private final int[] inputs = new int[6];

    /** Per-face BT channels: side index mapping (0=down,1=up,2=north,3=south,4=west,5=east). */
    private final int[] faceChannels = {4, 3, 1, 2, 5, 6};

    /** Per-face bundled mode toggle. Bundled faces map BT channels 1..16 to bundle colors. */
    private final boolean[] bundledFaces = new boolean[6];

    /** Latched output state for channel-driven analog faces (0..15). */
    private final int[] faceWirelessOutput = new int[6];

    /** Latched bundled mask for bundled faces (16-bit color mask). */
    private final int[] faceBundledMask = new int[6];

    /** Flag set by neighborChanged to force an input re-read */
    private boolean inputDirty = true;

    public RedstoneRelayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.REDSTONE_RELAY.get(), pos, state);
    }

    public void serverTick() {
        if (level == null || level.isClientSide()) return;

        // Register on Bluetooth for all active channels this relay listens on.
        BluetoothNetwork.registerMulti(level, deviceId, worldPosition, buildListenChannels(),
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

        // Process incoming BT messages (button panel broadcasts and program commands)
        BluetoothNetwork.Message btMsg = BluetoothNetwork.receive(deviceId);
        while (btMsg != null) {
            processMessage(btMsg);
            btMsg = BluetoothNetwork.receive(deviceId);
        }

        // Apply channel-driven outputs into the global relay output caches.
        applyWirelessFaceOutputs();

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
    }

    private Set<Integer> buildListenChannels() {
        Set<Integer> channels = new LinkedHashSet<>();
        channels.add(1);
        for (int side = 0; side < 6; side++) {
            if (bundledFaces[side]) {
                for (int ch = 1; ch <= 16; ch++) channels.add(ch);
            } else if (faceChannels[side] >= 1 && faceChannels[side] <= 256) {
                channels.add(faceChannels[side]);
            }
        }
        return channels;
    }

    private void applyWirelessFaceOutputs() {
        for (int side = 0; side < 6; side++) {
            if (bundledFaces[side]) {
                int mask = faceBundledMask[side] & 0xFFFF;
                BluetoothNetwork.setBundledOutput(worldPosition, side, mask);
                BluetoothNetwork.setRedstoneOutput(worldPosition, side, mask != 0 ? 15 : 0);
            } else if (faceChannels[side] >= 1 && faceChannels[side] <= 256) {
                int out = Math.min(15, Math.max(0, faceWirelessOutput[side]));
                BluetoothNetwork.setRedstoneOutput(worldPosition, side, out);
                BluetoothNetwork.setBundledOutput(worldPosition, side, 0);
            }
        }
    }

    private void processMessage(BluetoothNetwork.Message msg) {
        String content = msg.content();

        // Programs can send "rs_set:<side>:<power>" to set output.
        if (content.startsWith("rs_set:")) {
            String[] parts = content.split(":");
            if (parts.length >= 3) {
                try {
                    int side = Integer.parseInt(parts[1]);
                    int power = Integer.parseInt(parts[2]);
                    BluetoothNetwork.setRedstoneOutput(worldPosition, side, power);
                } catch (NumberFormatException ignored) {}
            }
            return;
        }

        // Programs can send "rs_bundled:<side>:<colorMask>" to set bundled output.
        if (content.startsWith("rs_bundled:")) {
            String[] parts = content.split(":");
            if (parts.length >= 3) {
                try {
                    int side = Integer.parseInt(parts[1]);
                    int colorMask = Integer.parseInt(parts[2]);
                    BluetoothNetwork.setBundledOutput(worldPosition, side, colorMask);
                } catch (NumberFormatException ignored) {}
            }
            return;
        }

        // Button panel event: "button_press:<index>:<0|1>:<colorName>"
        if (!content.startsWith("button_press:")) return;

        String[] parts = content.split(":");
        if (parts.length < 3) return;

        boolean nowOn;
        try {
            nowOn = "1".equals(parts[2]);
        } catch (Exception ignored) {
            return;
        }

        int channel = msg.channel();
        if (channel < 1 || channel > 256) return;

        for (int side = 0; side < 6; side++) {
            if (bundledFaces[side]) {
                if (channel >= 1 && channel <= 16) {
                    int bit = 1 << (channel - 1);
                    if (nowOn) {
                        faceBundledMask[side] |= bit;
                    } else {
                        faceBundledMask[side] &= ~bit;
                    }
                }
            } else if (faceChannels[side] == channel) {
                faceWirelessOutput[side] = nowOn ? 15 : 0;
            }
        }
    }

    public void markInputDirty() { this.inputDirty = true; }
    public boolean isConnected() { return connected; }
    public int getOutput(int side) { return lastOutputs[Math.min(5, Math.max(0, side))]; }
    public int getInput(int side) { return inputs[Math.min(5, Math.max(0, side))]; }
    public int[] getInputs() { return inputs; }
    public UUID getDeviceId() { return deviceId; }

    public int getFaceChannel(int side) {
        return faceChannels[Math.min(5, Math.max(0, side))];
    }

    public boolean isBundledFace(int side) {
        return bundledFaces[Math.min(5, Math.max(0, side))];
    }

    public int[] getFaceChannels() {
        return faceChannels.clone();
    }

    public boolean[] getBundledFaces() {
        return bundledFaces.clone();
    }

    public int getFaceBundledMask(int side) {
        return faceBundledMask[Math.min(5, Math.max(0, side))] & 0xFFFF;
    }

    public byte[] getBundledSignalArray(int side) {
        int idx = Math.min(5, Math.max(0, side));
        int mask = faceBundledMask[idx] & 0xFFFF;
        byte[] signal = new byte[16];
        if (!bundledFaces[idx]) return signal;
        for (int i = 0; i < 16; i++) {
            signal[i] = (byte) ((mask & (1 << i)) != 0 ? 15 : 0);
        }
        return signal;
    }

    public void setFaceConfig(int side, int channel, boolean bundled) {
        int idx = Math.min(5, Math.max(0, side));
        faceChannels[idx] = Math.max(0, Math.min(256, channel));
        bundledFaces[idx] = bundled;
        if (bundled) {
            faceWirelessOutput[idx] = 0;
        } else {
            faceBundledMask[idx] = 0;
        }
        setChanged();
        syncToClient();
    }

    public void setFaceConfigs(int[] channels, boolean[] bundled) {
        for (int i = 0; i < 6; i++) {
            int ch = (channels != null && i < channels.length) ? channels[i] : faceChannels[i];
            boolean b = bundled != null && i < bundled.length && bundled[i];
            faceChannels[i] = Math.max(0, Math.min(256, ch));
            bundledFaces[i] = b;
            if (b) {
                faceWirelessOutput[i] = 0;
            } else {
                faceBundledMask[i] = 0;
            }
        }
        setChanged();
        syncToClient();
    }

    private void syncToClient() {
        if (level == null) return;
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
    }

    // --- NBT persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("DeviceId", deviceId);
        tag.putBoolean("Connected", connected);
        tag.putIntArray("Inputs", inputs.clone());
        tag.putIntArray("FaceChannels", faceChannels.clone());
        byte[] bundledBytes = new byte[6];
        for (int i = 0; i < 6; i++) bundledBytes[i] = (byte) (bundledFaces[i] ? 1 : 0);
        tag.putByteArray("BundledFaces", bundledBytes);
        tag.putIntArray("FaceWirelessOutput", faceWirelessOutput.clone());
        tag.putIntArray("FaceBundledMask", faceBundledMask.clone());
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
        if (tag.contains("FaceChannels")) {
            int[] saved = tag.getIntArray("FaceChannels");
            System.arraycopy(saved, 0, faceChannels, 0, Math.min(saved.length, 6));
            for (int i = 0; i < 6; i++) faceChannels[i] = Math.max(0, Math.min(256, faceChannels[i]));
        }
        if (tag.contains("BundledFaces")) {
            Arrays.fill(bundledFaces, false);
            byte[] saved = tag.getByteArray("BundledFaces");
            for (int i = 0; i < Math.min(saved.length, 6); i++) {
                bundledFaces[i] = saved[i] != 0;
            }
        }
        if (tag.contains("FaceWirelessOutput")) {
            int[] saved = tag.getIntArray("FaceWirelessOutput");
            System.arraycopy(saved, 0, faceWirelessOutput, 0, Math.min(saved.length, 6));
            for (int i = 0; i < 6; i++) faceWirelessOutput[i] = Math.max(0, Math.min(15, faceWirelessOutput[i]));
        }
        if (tag.contains("FaceBundledMask")) {
            int[] saved = tag.getIntArray("FaceBundledMask");
            System.arraycopy(saved, 0, faceBundledMask, 0, Math.min(saved.length, 6));
            for (int i = 0; i < 6; i++) faceBundledMask[i] &= 0xFFFF;
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

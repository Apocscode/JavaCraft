package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.UUID;

/**
 * Block entity for the Button Panel.
 * Stores 16 button states with configurable modes (toggle, momentary, timer, delay, inverted).
 * Broadcasts events via Bluetooth, accepts commands, and outputs redstone/bundled cable signals.
 */
public class ButtonPanelBlockEntity extends BlockEntity {
    private UUID deviceId = UUID.randomUUID();

    /** 16-bit mask: bit i = button i is lit/active */
    private int buttonStates = 0;

    /** Button behavior modes */
    public enum ButtonMode {
        TOGGLE,      // Click on, click off (default)
        MOMENTARY,   // On for a short pulse (~4 ticks), then off
        TIMER,       // On for N ticks, then auto-off
        DELAY,       // Wait N ticks after press, then toggle
        INVERTED;    // Same as toggle but redstone output is inverted

        private static final ButtonMode[] VALUES = values();
        public static ButtonMode fromOrdinal(int ord) {
            return (ord >= 0 && ord < VALUES.length) ? VALUES[ord] : TOGGLE;
        }
        public ButtonMode next() { return VALUES[(ordinal() + 1) % VALUES.length]; }
    }

    /** Per-button mode */
    private final ButtonMode[] modes = new ButtonMode[16];
    /** Per-button timer/delay duration in ticks (default 20 = 1 second) */
    private final int[] durations = new int[16];
    /** Active countdown timers (server-side only) */
    private final int[] countdowns = new int[16];
    /** Delay-pending flags: button is waiting for delay to expire before toggling */
    private final boolean[] delayPending = new boolean[16];

    /** Bluetooth channel (configurable, default 1) */
    private int channel = 1;

    /** User-assigned display label (max 24 chars, persisted in NBT) */
    private String label = "";

    /** Color names for chat messages */
    private static final String[] COLOR_NAMES = {
        "White", "Orange", "Magenta", "Light Blue", "Yellow", "Lime",
        "Pink", "Gray", "Light Gray", "Cyan", "Purple", "Blue",
        "Brown", "Green", "Red", "Black"
    };

    public ButtonPanelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BUTTON_PANEL.get(), pos, state);
        Arrays.fill(modes, ButtonMode.TOGGLE);
        Arrays.fill(durations, 20); // 1 second default
    }

    public void serverTick() {
        if (level == null || level.isClientSide()) return;

        // Register on Bluetooth every tick (heartbeat)
        BluetoothNetwork.register(level, deviceId, worldPosition, channel,
                BluetoothNetwork.DeviceType.BUTTON_PANEL);

        // Process incoming BT messages (set_button commands from programs)
        BluetoothNetwork.Message msg = BluetoothNetwork.receive(deviceId);
        while (msg != null) {
            processMessage(msg);
            msg = BluetoothNetwork.receive(deviceId);
        }

        // Process countdowns (momentary, timer, delay)
        boolean changed = false;
        for (int i = 0; i < 16; i++) {
            if (countdowns[i] > 0) {
                countdowns[i]--;
                if (countdowns[i] <= 0) {
                    if (delayPending[i]) {
                        // Delay expired: now toggle the button
                        delayPending[i] = false;
                        boolean wasOn = (buttonStates & (1 << i)) != 0;
                        if (wasOn) {
                            buttonStates &= ~(1 << i);
                        } else {
                            buttonStates |= (1 << i);
                        }
                        broadcastButtonEvent(i, !wasOn);
                        changed = true;
                    } else {
                        // Momentary/Timer expired: turn off
                        if ((buttonStates & (1 << i)) != 0) {
                            buttonStates &= ~(1 << i);
                            broadcastButtonEvent(i, false);
                            changed = true;
                        }
                    }
                }
            }
        }
        if (changed) {
            updateRedstoneOutputs();
            syncToClient();
        }
    }

    private void processMessage(BluetoothNetwork.Message msg) {
        String content = msg.content();
        if (content.startsWith("set_button:")) {
            // Format: "set_button:<index>:<0or1>"
            String[] parts = content.split(":");
            if (parts.length >= 3) {
                try {
                    int index = Integer.parseInt(parts[1]);
                    boolean on = "1".equals(parts[2]);
                    if (index >= 0 && index < 16) {
                        setButton(index, on);
                    }
                } catch (NumberFormatException ignored) {}
            }
        } else if (content.startsWith("set_buttons:")) {
            // Format: "set_buttons:<16-bit mask>"
            String[] parts = content.split(":");
            if (parts.length >= 2) {
                try {
                    int mask = Integer.parseInt(parts[1]) & 0xFFFF;
                    if (mask != buttonStates) {
                        buttonStates = mask;
                        updateRedstoneOutputs();
                        syncToClient();
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    /**
     * Activate a button (called from block interaction) — mode-aware.
     */
    public void toggleButton(int index, Player player) {
        if (index < 0 || index >= 16) return;
        ButtonMode mode = modes[index];
        boolean wasOn = (buttonStates & (1 << index)) != 0;

        switch (mode) {
            case TOGGLE, INVERTED -> {
                // Simple toggle
                boolean nowOn = !wasOn;
                if (nowOn) {
                    buttonStates |= (1 << index);
                } else {
                    buttonStates &= ~(1 << index);
                }
                broadcastButtonEvent(index, nowOn);
            }
            case MOMENTARY -> {
                // Turn on, auto-off after 4 ticks
                buttonStates |= (1 << index);
                countdowns[index] = 4;
                delayPending[index] = false;
                broadcastButtonEvent(index, true);
            }
            case TIMER -> {
                if (wasOn) {
                    // If already on, cancel timer and turn off
                    buttonStates &= ~(1 << index);
                    countdowns[index] = 0;
                    broadcastButtonEvent(index, false);
                } else {
                    // Turn on and start timer
                    buttonStates |= (1 << index);
                    countdowns[index] = durations[index];
                    delayPending[index] = false;
                    broadcastButtonEvent(index, true);
                }
            }
            case DELAY -> {
                if (delayPending[index]) {
                    // Cancel pending delay
                    countdowns[index] = 0;
                    delayPending[index] = false;
                } else {
                    // Start delay countdown, don't toggle yet
                    countdowns[index] = durations[index];
                    delayPending[index] = true;
                }
            }
        }

        updateRedstoneOutputs();
        syncToClient();
    }

    private void broadcastButtonEvent(int index, boolean nowOn) {
        String msg = "button_press:" + index + ":" + (nowOn ? "1" : "0") + ":" + COLOR_NAMES[index];
        BluetoothNetwork.broadcastFromDevice(deviceId, channel, msg);
    }

    /**
     * Update the BluetoothNetwork redstone/bundled cache for this panel's position.
     * Analog output = count of active buttons (0-15).
     * Bundled output = button states as 16-bit color mask.
     */
    private void updateRedstoneOutputs() {
        if (level == null || level.isClientSide()) return;
        int activeCount = Integer.bitCount(buttonStates & 0xFFFF);
        int analog = Math.min(15, activeCount);

        // Calculate effective output (invert buttons with INVERTED mode contribute inverted)
        int effectiveMask = 0;
        for (int i = 0; i < 16; i++) {
            boolean on = (buttonStates & (1 << i)) != 0;
            if (modes[i] == ButtonMode.INVERTED) on = !on;
            if (on) effectiveMask |= (1 << i);
        }
        int effectiveCount = Integer.bitCount(effectiveMask);
        int effectiveAnalog = Math.min(15, effectiveCount);

        // Set on all 6 sides
        for (int side = 0; side < 6; side++) {
            BluetoothNetwork.setRedstoneOutput(worldPosition, side, effectiveAnalog);
            BluetoothNetwork.setBundledOutput(worldPosition, side, effectiveMask);
        }

        // Notify neighbors of redstone change
        level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
    }

    /**
     * Set a specific button state (called from program commands).
     */
    public void setButton(int index, boolean on) {
        if (index < 0 || index >= 16) return;
        boolean current = (buttonStates & (1 << index)) != 0;
        if (current == on) return;

        if (on) {
            buttonStates |= (1 << index);
        } else {
            buttonStates &= ~(1 << index);
        }
        updateRedstoneOutputs();
        syncToClient();
    }

    // --- Getters ---

    public boolean isButtonOn(int index) {
        if (index < 0 || index >= 16) return false;
        return (buttonStates & (1 << index)) != 0;
    }

    public int getButtonStates() { return buttonStates; }
    public UUID getDeviceId() { return deviceId; }
    public int getChannel() { return channel; }
    public ButtonMode getMode(int index) {
        return (index >= 0 && index < 16) ? modes[index] : ButtonMode.TOGGLE;
    }
    public int getDuration(int index) {
        return (index >= 0 && index < 16) ? durations[index] : 20;
    }

    // --- Configuration setters (called from config GUI payload) ---

    public void setChannel(int ch) {
        this.channel = Math.max(1, Math.min(256, ch));
        syncToClient();
    }

    public void setMode(int index, ButtonMode mode) {
        if (index >= 0 && index < 16) {
            modes[index] = mode;
            countdowns[index] = 0;
            delayPending[index] = false;
            syncToClient();
        }
    }

    public void setDuration(int index, int ticks) {
        if (index >= 0 && index < 16) {
            durations[index] = Math.max(1, Math.min(6000, ticks));
            syncToClient();
        }
    }

    public String getLabel() { return label == null ? "" : label; }

    public void setLabel(String lbl) {
        this.label = (lbl == null) ? "" : lbl.length() > 24 ? lbl.substring(0, 24) : lbl;
        syncToClient();
    }

    /** Set all 16 button states at once from a bitmask. */
    public void setAllButtons(int mask) {
        int newStates = mask & 0xFFFF;
        if (newStates == buttonStates) return;
        buttonStates = newStates;
        updateRedstoneOutputs();
        syncToClient();
    }

    // --- Client sync ---

    private void syncToClient() {
        if (level != null && !level.isClientSide()) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // --- NBT persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("DeviceId", deviceId);
        tag.putInt("ButtonStates", buttonStates);
        tag.putInt("Channel", channel);
        if (!label.isEmpty()) tag.putString("Label", label);

        // Save modes as int array
        int[] modeOrds = new int[16];
        for (int i = 0; i < 16; i++) modeOrds[i] = modes[i].ordinal();
        tag.putIntArray("Modes", modeOrds);
        tag.putIntArray("Durations", durations.clone());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("DeviceId")) deviceId = tag.getUUID("DeviceId");
        if (tag.contains("ButtonStates")) buttonStates = tag.getInt("ButtonStates");
        if (tag.contains("Channel")) channel = tag.getInt("Channel");
        if (tag.contains("Label")) label = tag.getString("Label");

        if (tag.contains("Modes")) {
            int[] modeOrds = tag.getIntArray("Modes");
            for (int i = 0; i < Math.min(16, modeOrds.length); i++) {
                modes[i] = ButtonMode.fromOrdinal(modeOrds[i]);
            }
        }
        if (tag.contains("Durations")) {
            int[] dur = tag.getIntArray("Durations");
            System.arraycopy(dur, 0, durations, 0, Math.min(16, dur.length));
        }
    }

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

    @Override
    public void setRemoved() {
        super.setRemoved();
        // Clear redstone output cache when removed
        if (level != null && !level.isClientSide()) {
            for (int i = 0; i < 6; i++) {
                BluetoothNetwork.setRedstoneOutput(worldPosition, i, 0);
                BluetoothNetwork.setBundledOutput(worldPosition, i, 0);
            }
        }
    }
}

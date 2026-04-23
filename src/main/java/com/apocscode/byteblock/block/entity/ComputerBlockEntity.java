package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.ComputerBlock;
import com.apocscode.byteblock.computer.JavaOS;
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
 * Block entity for the Computer. Holds the JavaOS instance which manages
 * the terminal, filesystem, processes, and Bluetooth networking.
 *
 * Also implements {@link IButtonPanel}: every computer carries a virtual
 * 16-button panel that the on-screen Button App can drive, that emits
 * redstone + bundled cable signals on all 6 sides of the computer block,
 * and that programs can control via the {@code ButtonsLib} API.
 */
public class ComputerBlockEntity extends BlockEntity implements IButtonPanel {
    private UUID computerId = UUID.randomUUID();
    private boolean powered = true;
    private JavaOS os;

    // ── Virtual Button Panel state ───────────────────────────────────────────
    /** Distinct UUID so the computer shows up as a separate BUTTON_PANEL device on Bluetooth. */
    private UUID panelDeviceId = UUID.randomUUID();
    private int buttonStates = 0;
    private final ButtonPanelBlockEntity.ButtonMode[] modes =
            new ButtonPanelBlockEntity.ButtonMode[16];
    private final int[] durations    = new int[16];
    private final int[] countdowns   = new int[16];
    private final boolean[] delayPending = new boolean[16];
    private final String[] buttonLabels  = new String[16];
    private final int[] buttonColors    = new int[16];
    private String panelLabel = "This Computer";
    private int panelChannel = 1;

    private static final String[] COLOR_NAMES = {
        "White", "Orange", "Magenta", "Light Blue", "Yellow", "Lime",
        "Pink", "Gray", "Light Gray", "Cyan", "Purple", "Blue",
        "Brown", "Green", "Red", "Black"
    };

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COMPUTER.get(), pos, state);
        os = new JavaOS(computerId);
        Arrays.fill(modes, ButtonPanelBlockEntity.ButtonMode.TOGGLE);
        Arrays.fill(durations, 20);
        Arrays.fill(buttonLabels, "");
        Arrays.fill(buttonColors, -1);
    }

    public void serverTick() {
        if (level != null && !level.isClientSide() && level.getGameTime() % 20 == 0) {
            BlockState current = level.getBlockState(worldPosition);
            if (current.getValue(ComputerBlock.CONNECTED) != powered) {
                level.setBlockAndUpdate(worldPosition, current.setValue(ComputerBlock.CONNECTED, powered));
            }
        }
        if (!powered) return;
        os.setWorldContext(level, worldPosition);
        os.tick();
        if (level != null && !level.isClientSide() && os.getFileSystem().isDirty()) {
            os.getFileSystem().clearDirty();
            setChanged();
        }
        BluetoothNetwork.register(level, computerId, worldPosition, os.getBluetoothChannel(), BluetoothNetwork.DeviceType.COMPUTER);

        // Register virtual button panel as a separate BT device so the Button App picks it up.
        if (level != null && !level.isClientSide()) {
            BluetoothNetwork.register(level, panelDeviceId, worldPosition, panelChannel,
                    BluetoothNetwork.DeviceType.BUTTON_PANEL);
            tickVirtualPanel();
        }
    }

    // ── Virtual panel tick (countdown modes, receive BT commands) ────────────

    private void tickVirtualPanel() {
        BluetoothNetwork.Message msg = BluetoothNetwork.receive(panelDeviceId);
        while (msg != null) {
            processPanelMessage(msg);
            msg = BluetoothNetwork.receive(panelDeviceId);
        }

        boolean changed = false;
        for (int i = 0; i < 16; i++) {
            if (countdowns[i] > 0) {
                countdowns[i]--;
                if (countdowns[i] <= 0) {
                    if (delayPending[i]) {
                        delayPending[i] = false;
                        boolean wasOn = (buttonStates & (1 << i)) != 0;
                        if (wasOn) buttonStates &= ~(1 << i);
                        else       buttonStates |=  (1 << i);
                        broadcastButtonEvent(i, !wasOn);
                        changed = true;
                    } else if ((buttonStates & (1 << i)) != 0) {
                        buttonStates &= ~(1 << i);
                        broadcastButtonEvent(i, false);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            updateRedstoneOutputs();
            syncToClient();
        }
    }

    private void processPanelMessage(BluetoothNetwork.Message msg) {
        String content = msg.content();
        if (content.startsWith("set_button:")) {
            String[] parts = content.split(":");
            if (parts.length >= 3) {
                try {
                    int idx = Integer.parseInt(parts[1]);
                    boolean on = "1".equals(parts[2]);
                    setButton(idx, on);
                } catch (NumberFormatException ignored) {}
            }
        } else if (content.startsWith("set_buttons:")) {
            String[] parts = content.split(":");
            if (parts.length >= 2) {
                try {
                    setAllButtons(Integer.parseInt(parts[1]));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private void broadcastButtonEvent(int index, boolean nowOn) {
        String msg = "button_press:" + index + ":" + (nowOn ? "1" : "0") + ":" + COLOR_NAMES[index];
        BluetoothNetwork.broadcastFromDevice(panelDeviceId, panelChannel, msg);
    }

    private void updateRedstoneOutputs() {
        if (level == null || level.isClientSide()) return;
        int effectiveMask = 0;
        for (int i = 0; i < 16; i++) {
            boolean on = (buttonStates & (1 << i)) != 0;
            if (modes[i] == ButtonPanelBlockEntity.ButtonMode.INVERTED) on = !on;
            if (on) effectiveMask |= (1 << i);
        }
        int analog = Math.min(15, Integer.bitCount(effectiveMask));
        for (int side = 0; side < 6; side++) {
            BluetoothNetwork.setRedstoneOutput(worldPosition, side, analog);
            BluetoothNetwork.setBundledOutput(worldPosition, side, effectiveMask);
        }
        level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
    }

    private void syncToClient() {
        if (level != null && !level.isClientSide()) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public JavaOS getOS() { return os; }
    public UUID getComputerId() { return computerId; }
    public boolean isPowered() { return powered; }
    public void setPowered(boolean powered) { this.powered = powered; setChanged(); }

    // ── IButtonPanel implementation ──────────────────────────────────────────

    @Override public int getButtonStates() { return buttonStates; }

    @Override public boolean isButtonOn(int index) {
        return index >= 0 && index < 16 && (buttonStates & (1 << index)) != 0;
    }

    @Override public ButtonPanelBlockEntity.ButtonMode getMode(int index) {
        return (index >= 0 && index < 16) ? modes[index] : ButtonPanelBlockEntity.ButtonMode.TOGGLE;
    }

    @Override public int getDuration(int index) {
        return (index >= 0 && index < 16) ? durations[index] : 20;
    }

    @Override public String getButtonLabel(int index) {
        if (index < 0 || index >= 16) return "";
        return buttonLabels[index] == null ? "" : buttonLabels[index];
    }

    @Override public int getButtonColor(int index) {
        return (index >= 0 && index < 16) ? buttonColors[index] : -1;
    }

    @Override public String getLabel() { return panelLabel == null ? "" : panelLabel; }
    @Override public int getChannel() { return panelChannel; }

    @Override public void setButton(int index, boolean on) {
        if (index < 0 || index >= 16) return;
        boolean current = (buttonStates & (1 << index)) != 0;
        if (current == on) return;
        if (on) buttonStates |= (1 << index);
        else    buttonStates &= ~(1 << index);
        updateRedstoneOutputs();
        syncToClient();
    }

    @Override public void setAllButtons(int mask) {
        int next = mask & 0xFFFF;
        if (next == buttonStates) return;
        buttonStates = next;
        updateRedstoneOutputs();
        syncToClient();
    }

    @Override public void toggleButton(int index, Player player) {
        if (index < 0 || index >= 16) return;
        ButtonPanelBlockEntity.ButtonMode mode = modes[index];
        boolean wasOn = (buttonStates & (1 << index)) != 0;

        switch (mode) {
            case TOGGLE, INVERTED -> {
                boolean nowOn = !wasOn;
                if (nowOn) buttonStates |=  (1 << index);
                else       buttonStates &= ~(1 << index);
                broadcastButtonEvent(index, nowOn);
            }
            case MOMENTARY -> {
                buttonStates |= (1 << index);
                countdowns[index] = 4;
                delayPending[index] = false;
                broadcastButtonEvent(index, true);
            }
            case TIMER -> {
                if (wasOn) {
                    buttonStates &= ~(1 << index);
                    countdowns[index] = 0;
                    broadcastButtonEvent(index, false);
                } else {
                    buttonStates |= (1 << index);
                    countdowns[index] = durations[index];
                    delayPending[index] = false;
                    broadcastButtonEvent(index, true);
                }
            }
            case DELAY -> {
                if (delayPending[index]) {
                    countdowns[index] = 0;
                    delayPending[index] = false;
                } else {
                    countdowns[index] = durations[index];
                    delayPending[index] = true;
                }
            }
        }
        updateRedstoneOutputs();
        syncToClient();
    }

    @Override public void setMode(int index, ButtonPanelBlockEntity.ButtonMode mode) {
        if (index < 0 || index >= 16) return;
        modes[index] = mode;
        countdowns[index] = 0;
        delayPending[index] = false;
        updateRedstoneOutputs();
        syncToClient();
    }

    @Override public void setDuration(int index, int ticks) {
        if (index < 0 || index >= 16) return;
        durations[index] = Math.max(1, Math.min(6000, ticks));
        syncToClient();
    }

    @Override public void setButtonLabel(int index, String label) {
        if (index < 0 || index >= 16) return;
        String v = label == null ? "" : label;
        if (v.length() > 16) v = v.substring(0, 16);
        buttonLabels[index] = v;
        syncToClient();
    }

    @Override public void setButtonColor(int index, int rgb) {
        if (index < 0 || index >= 16) return;
        buttonColors[index] = (rgb < 0) ? -1 : (rgb & 0xFFFFFF);
        syncToClient();
    }

    @Override public void setLabel(String label) {
        String v = label == null ? "" : label;
        if (v.length() > 24) v = v.substring(0, 24);
        panelLabel = v;
        syncToClient();
    }

    @Override public void setChannel(int channel) {
        panelChannel = Math.max(1, Math.min(256, channel));
        syncToClient();
    }

    // --- NBT Persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("ComputerId", computerId);
        tag.putBoolean("Powered", powered);
        tag.putString("Label", os.getLabel());
        tag.putInt("BluetoothChannel", os.getBluetoothChannel());
        tag.put("Filesystem", os.getFileSystem().save());

        // Virtual button panel
        tag.putUUID("PanelDeviceId", panelDeviceId);
        tag.putInt("PanelButtonStates", buttonStates);
        tag.putInt("PanelChannel", panelChannel);
        if (panelLabel != null && !panelLabel.isEmpty()) tag.putString("PanelLabel", panelLabel);
        int[] modeOrds = new int[16];
        for (int i = 0; i < 16; i++) modeOrds[i] = modes[i].ordinal();
        tag.putIntArray("PanelModes", modeOrds);
        tag.putIntArray("PanelDurations", durations.clone());
        tag.putIntArray("PanelButtonColors", buttonColors.clone());
        net.minecraft.nbt.ListTag lblList = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < 16; i++) {
            lblList.add(net.minecraft.nbt.StringTag.valueOf(
                    buttonLabels[i] == null ? "" : buttonLabels[i]));
        }
        tag.put("PanelButtonLabels", lblList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("ComputerId")) computerId = tag.getUUID("ComputerId");
        if (tag.contains("Powered")) powered = tag.getBoolean("Powered");

        // Recreate OS with persisted ID
        os = new JavaOS(computerId);
        if (tag.contains("Label")) os.setLabel(tag.getString("Label"));
        if (tag.contains("BluetoothChannel")) os.setBluetoothChannel(tag.getInt("BluetoothChannel"));
        if (tag.contains("Filesystem", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            os.getFileSystem().load(tag.getCompound("Filesystem"));
        }

        // Virtual button panel
        if (tag.contains("PanelDeviceId")) panelDeviceId = tag.getUUID("PanelDeviceId");
        if (tag.contains("PanelButtonStates")) buttonStates = tag.getInt("PanelButtonStates") & 0xFFFF;
        if (tag.contains("PanelChannel")) panelChannel = tag.getInt("PanelChannel");
        if (tag.contains("PanelLabel")) panelLabel = tag.getString("PanelLabel");
        if (tag.contains("PanelModes")) {
            int[] ords = tag.getIntArray("PanelModes");
            for (int i = 0; i < Math.min(16, ords.length); i++) {
                modes[i] = ButtonPanelBlockEntity.ButtonMode.fromOrdinal(ords[i]);
            }
        }
        if (tag.contains("PanelDurations")) {
            int[] dur = tag.getIntArray("PanelDurations");
            System.arraycopy(dur, 0, durations, 0, Math.min(16, dur.length));
        }
        if (tag.contains("PanelButtonColors")) {
            int[] cols = tag.getIntArray("PanelButtonColors");
            for (int i = 0; i < Math.min(16, cols.length); i++) buttonColors[i] = cols[i];
        }
        if (tag.contains("PanelButtonLabels")) {
            net.minecraft.nbt.ListTag lblList = tag.getList("PanelButtonLabels", net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < Math.min(16, lblList.size()); i++) {
                buttonLabels[i] = lblList.getString(i);
            }
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
        if (level != null && !level.isClientSide()) {
            for (int i = 0; i < 6; i++) {
                BluetoothNetwork.setRedstoneOutput(worldPosition, i, 0);
                BluetoothNetwork.setBundledOutput(worldPosition, i, 0);
            }
        }
    }
}

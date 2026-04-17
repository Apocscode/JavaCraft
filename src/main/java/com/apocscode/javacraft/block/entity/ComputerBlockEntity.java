package com.apocscode.javacraft.block.entity;

import com.apocscode.javacraft.JavaCraft;
import com.apocscode.javacraft.computer.VirtualComputer;
import com.apocscode.javacraft.init.ModBlockEntities;
import com.apocscode.javacraft.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Block entity for the Computer. Holds the virtual machine state,
 * terminal output buffer, program storage, and Bluetooth network ID.
 */
public class ComputerBlockEntity extends BlockEntity {
    private String computerLabel = "Computer";
    private UUID computerId = UUID.randomUUID();
    private final List<String> terminalOutput = new ArrayList<>();
    private final VirtualComputer virtualComputer = new VirtualComputer(this);
    private boolean powered = true;
    private int bluetoothChannel = 0;

    // Virtual filesystem: maps path -> file content
    private final CompoundTag filesystem = new CompoundTag();

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COMPUTER.get(), pos, state);
    }

    public void serverTick() {
        if (!powered) return;
        virtualComputer.tick();
        BluetoothNetwork.register(level, computerId, worldPosition, bluetoothChannel);
    }

    public void openTerminal(Player player) {
        player.sendSystemMessage(Component.literal(
                "[" + computerLabel + "] Terminal ready. ID: " + computerId.toString().substring(0, 8)));
        // TODO: Open actual GUI screen via networking packet
    }

    // --- Terminal API ---

    public void print(String text) {
        terminalOutput.add(text);
        if (terminalOutput.size() > 200) {
            terminalOutput.remove(0);
        }
        setChanged();
    }

    public void clearTerminal() {
        terminalOutput.clear();
        setChanged();
    }

    public List<String> getTerminalOutput() {
        return List.copyOf(terminalOutput);
    }

    // --- File System API ---

    public void writeFile(String path, String content) {
        filesystem.putString(path, content);
        setChanged();
    }

    public String readFile(String path) {
        return filesystem.contains(path, Tag.TAG_STRING) ? filesystem.getString(path) : null;
    }

    public boolean deleteFile(String path) {
        if (filesystem.contains(path)) {
            filesystem.remove(path);
            setChanged();
            return true;
        }
        return false;
    }

    public List<String> listFiles() {
        return new ArrayList<>(filesystem.getAllKeys());
    }

    // --- Bluetooth ---

    public UUID getComputerId() { return computerId; }
    public int getBluetoothChannel() { return bluetoothChannel; }
    public void setBluetoothChannel(int channel) { this.bluetoothChannel = channel; setChanged(); }
    public String getComputerLabel() { return computerLabel; }
    public void setComputerLabel(String label) { this.computerLabel = label; setChanged(); }
    public boolean isPowered() { return powered; }
    public void setPowered(boolean powered) { this.powered = powered; setChanged(); }
    public VirtualComputer getVirtualComputer() { return virtualComputer; }

    // --- NBT Persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("Label", computerLabel);
        tag.putUUID("ComputerId", computerId);
        tag.putBoolean("Powered", powered);
        tag.putInt("BluetoothChannel", bluetoothChannel);
        tag.put("Filesystem", filesystem.copy());

        ListTag outputTag = new ListTag();
        for (String line : terminalOutput) {
            outputTag.add(StringTag.valueOf(line));
        }
        tag.put("TerminalOutput", outputTag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Label")) computerLabel = tag.getString("Label");
        if (tag.contains("ComputerId")) computerId = tag.getUUID("ComputerId");
        if (tag.contains("Powered")) powered = tag.getBoolean("Powered");
        if (tag.contains("BluetoothChannel")) bluetoothChannel = tag.getInt("BluetoothChannel");

        if (tag.contains("Filesystem", Tag.TAG_COMPOUND)) {
            CompoundTag fs = tag.getCompound("Filesystem");
            filesystem.getAllKeys().forEach(filesystem::remove);
            for (String key : fs.getAllKeys()) {
                filesystem.putString(key, fs.getString(key));
            }
        }

        terminalOutput.clear();
        if (tag.contains("TerminalOutput", Tag.TAG_LIST)) {
            ListTag outputTag = tag.getList("TerminalOutput", Tag.TAG_STRING);
            for (int i = 0; i < outputTag.size(); i++) {
                terminalOutput.add(outputTag.getString(i));
            }
        }
    }
}

package com.apocscode.byteblock.client;

import com.apocscode.byteblock.menu.DriveMenu;
import com.apocscode.byteblock.network.RenameDiskPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drive GUI screen — 1 disk slot + disk label editing + file info.
 */
public class DriveScreen extends AbstractContainerScreen<DriveMenu> {

    private EditBox labelField;
    private boolean hasDisk;

    public DriveScreen(DriveMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 186;
        this.inventoryLabelY = 92;
    }

    @Override
    protected void init() {
        super.init();
        labelField = new EditBox(this.font, leftPos + 34, topPos + 22, 134, 14, Component.literal("Label"));
        labelField.setMaxLength(32);
        labelField.setBordered(true);
        addRenderableWidget(labelField);
        syncLabelFromSlot();
    }

    private void syncLabelFromSlot() {
        ItemStack disk = menu.getSlot(0).getItem();
        hasDisk = !disk.isEmpty();
        if (hasDisk) {
            CompoundTag tag = disk.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            String label = tag.contains("Label") ? tag.getString("Label") : "";
            if (!labelField.isFocused()) {
                labelField.setValue(label);
            }
            labelField.setEditable(true);
        } else {
            if (!labelField.isFocused()) labelField.setValue("");
            labelField.setEditable(false);
        }
    }

    @Override
    public void containerTick() {
        super.containerTick();
        syncLabelFromSlot();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (labelField.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { // Enter / Numpad Enter
                sendRename();
                labelField.setFocused(false);
                return true;
            }
            return true; // Consume all keys when label field is focused
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void sendRename() {
        if (!hasDisk) return;
        PacketDistributor.sendToServer(new RenameDiskPayload(menu.getDrivePos(), labelField.getValue()));
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        // Main background
        gui.fill(x, y, x + imageWidth, y + imageHeight, 0xFFC6C6C6);

        // 3D border
        gui.fill(x, y, x + imageWidth, y + 1, 0xFFFFFFFF);
        gui.fill(x, y, x + 1, y + imageHeight, 0xFFFFFFFF);
        gui.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, 0xFF555555);
        gui.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, 0xFF555555);

        // Label caption
        gui.drawString(font, "Label:", x + 8, y + 25, 0xFF404040, false);

        // Centered disk slot
        renderSlotBg(gui, x + 79, y + 41);

        // Disk info
        if (hasDisk) {
            ItemStack disk = menu.getSlot(0).getItem();
            CompoundTag tag = disk.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            int fileCount = tag.getCompound("Files").getAllKeys().size();
            String info = fileCount + " file" + (fileCount != 1 ? "s" : "") + " on disk";
            gui.drawString(font, info, x + 8, y + 66, 0xFF404040, false);
            gui.drawString(font, "Press Enter to rename", x + 8, y + 78, 0xFF999999, false);
        } else {
            gui.drawString(font, "Insert a disk", x + 55, y + 66, 0xFF999999, false);
        }

        // Separator line
        gui.fill(x + 7, y + 89, x + imageWidth - 7, y + 90, 0xFF999999);

        // Player inventory slots
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                renderSlotBg(gui, x + 7 + col * 18, y + 103 + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            renderSlotBg(gui, x + 7 + col * 18, y + 161);
        }
    }

    private void renderSlotBg(GuiGraphics gui, int x, int y) {
        gui.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
        gui.fill(x, y, x + 18, y + 1, 0xFF373737);
        gui.fill(x, y, x + 1, y + 18, 0xFF373737);
        gui.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        renderTooltip(gui, mouseX, mouseY);
    }
}

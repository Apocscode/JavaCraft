package com.apocscode.byteblock.client;

import com.apocscode.byteblock.menu.RobotMenu;
import com.apocscode.byteblock.network.SetEntityLabelPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Robot inventory screen — cargo grid + tool slots + battery + label EditBox.
 */
public class RobotScreen extends AbstractContainerScreen<RobotMenu> {

    private EditBox labelField;
    private boolean labelSynced;

    public RobotScreen(RobotMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 186;
        this.inventoryLabelY = 92;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        labelField = new EditBox(this.font, leftPos + 36, topPos + 4, 134, 12, Component.literal("Label"));
        labelField.setMaxLength(32);
        labelField.setBordered(false);
        labelField.setTextColor(0xFFFFFFFF);
        addRenderableWidget(labelField);
        if (!labelSynced) {
            var name = menu.getRobot().getCustomName();
            labelField.setValue(name != null ? name.getString() : "");
            labelSynced = true;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (labelField != null && labelField.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { // Enter
                sendLabel();
                labelField.setFocused(false);
                return true;
            }
            return labelField.keyPressed(keyCode, scanCode, modifiers)
                || labelField.canConsumeInput();
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (labelField != null) sendLabel();
        super.onClose();
    }

    private void sendLabel() {
        if (labelField == null) return;
        PacketDistributor.sendToServer(new SetEntityLabelPayload(menu.getRobot().getId(), labelField.getValue()));
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        // Background
        gui.fill(x, y, x + imageWidth, y + imageHeight, 0xFFC6C6C6);
        // Border
        gui.fill(x, y, x + imageWidth, y + 1, 0xFFFFFFFF);
        gui.fill(x, y, x + 1, y + imageHeight, 0xFFFFFFFF);
        gui.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, 0xFF555555);
        gui.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, 0xFF555555);

        // "Name" caption next to label edit box
        gui.drawString(font, "Name:", x + 8, y + 6, 0xFF404040, false);

        // Cargo grid background (4×4)
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                renderSlotBg(gui, x + 61 + col * 18, y + 17 + row * 18);
            }
        }
        // Accessory column (left)
        renderSlotBg(gui, x + 7, y + 17);  // left tool
        renderSlotBg(gui, x + 7, y + 35);  // right tool
        renderSlotBg(gui, x + 7, y + 71);  // battery (gap = upgrade row reserved)

        // Tool labels
        gui.drawString(font, "L", x + 26, y + 22, 0xFF404040, false);
        gui.drawString(font, "R", x + 26, y + 40, 0xFF404040, false);
        gui.drawString(font, "FE", x + 26, y + 76, 0xFF404040, false);

        // Energy bar — right side of cargo grid
        int barX = x + 138, barY = y + 17, barW = 10, barH = 72;
        gui.fill(barX, barY, barX + barW, barY + barH, 0xFF373737);
        var energy = menu.getRobot().getEnergyStorage();
        int max = Math.max(1, energy.getMaxEnergyStored());
        int pct = energy.getEnergyStored() * barH / max;
        int fillColor = pct < barH / 5 ? 0xFFFF4040 : pct < barH / 2 ? 0xFFFFC040 : 0xFF40D0FF;
        gui.fill(barX + 1, barY + barH - pct, barX + barW - 1, barY + barH - 1, fillColor);

        // Separator above player inventory
        gui.fill(x + 7, y + 89, x + imageWidth - 7, y + 90, 0xFF999999);

        // Player inventory + hotbar slot bgs
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
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        // Draw inventory label only — title is replaced by the EditBox.
        gui.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        // Energy tooltip
        int barX = leftPos + 138, barY = topPos + 17;
        if (mouseX >= barX && mouseX < barX + 10 && mouseY >= barY && mouseY < barY + 72) {
            var e = menu.getRobot().getEnergyStorage();
            gui.renderTooltip(this.font,
                Component.literal(e.getEnergyStored() + " / " + e.getMaxEnergyStored() + " FE"),
                mouseX, mouseY);
        }
        renderTooltip(gui, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        // Defocus label box if click is outside it.
        if (labelField != null && !labelField.isMouseOver(mouseX, mouseY) && labelField.isFocused()) {
            sendLabel();
            labelField.setFocused(false);
        }
        return handled;
    }

    // Provide an unused dummy reference to silence warnings if needed.
    @SuppressWarnings("unused")
    private static void touch(ItemStack s) {}
}

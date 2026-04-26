package com.apocscode.byteblock.client;

import com.apocscode.byteblock.menu.DroneMenu;
import com.apocscode.byteblock.network.SetEntityLabelPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drone inventory screen — 3×3 cargo + battery + label EditBox + fuel bar.
 */
public class DroneScreen extends AbstractContainerScreen<DroneMenu> {

    private EditBox labelField;
    private boolean labelSynced;

    public DroneScreen(DroneMenu menu, Inventory playerInv, Component title) {
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
            var name = menu.getDrone().getCustomName();
            labelField.setValue(name != null ? name.getString() : "");
            labelSynced = true;
        }

        // Customize tab — opens paint picker for this drone (in floating header above frame)
        addRenderableWidget(Button.builder(Component.literal("Paint"),
                b -> this.minecraft.setScreen(new DroneCustomizeScreen(menu.getDrone())))
            .pos(leftPos + 6, topPos - 22)
            .size(60, 18)
            .build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (labelField != null && labelField.isFocused()) {
            if (keyCode == 257 || keyCode == 335) {
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
        PacketDistributor.sendToServer(new SetEntityLabelPayload(menu.getDrone().getId(), labelField.getValue()));
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        // Floating header panel for the Paint button.
        int hY = y - 24;
        gui.fill(x, hY, x + imageWidth, hY + 22, 0xFFC6C6C6);
        gui.fill(x, hY, x + imageWidth, hY + 1, 0xFFFFFFFF);
        gui.fill(x, hY, x + 1, hY + 22, 0xFFFFFFFF);
        gui.fill(x + imageWidth - 1, hY, x + imageWidth, hY + 22, 0xFF555555);

        gui.fill(x, y, x + imageWidth, y + imageHeight, 0xFFC6C6C6);
        gui.fill(x, y, x + imageWidth, y + 1, 0xFFFFFFFF);
        gui.fill(x, y, x + 1, y + imageHeight, 0xFFFFFFFF);
        gui.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, 0xFF555555);
        gui.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, 0xFF555555);

        gui.drawString(font, "Name:", x + 8, y + 6, 0xFF404040, false);

        // Cargo 3×3
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                renderSlotBg(gui, x + 61 + col * 18, y + 17 + row * 18);
            }
        }
        // Battery
        renderSlotBg(gui, x + 7, y + 35);
        gui.drawString(font, "FE", x + 26, y + 40, 0xFF404040, false);
        renderSlotBg(gui, x + 7, y + 55);
        gui.drawString(font, "G", x + 26, y + 60, 0xFF404040, false);

        // Fuel bar
        int barX = x + 138, barY = y + 17, barW = 10, barH = 54;
        gui.fill(barX, barY, barX + barW, barY + barH, 0xFF373737);
        int fuel = menu.getDrone().getFuelTicks();
        int maxFuel = 72000;
        int pct = fuel * barH / maxFuel;
        // Gradient: red < 10% → orange → yellow → light green → green at full.
        for (int i = 0; i < pct; i++) {
            float frac = i / (float) barH;
            int color = RobotScreen.chargeColor(frac);
            int yRow = barY + barH - 1 - i;
            gui.fill(barX + 1, yRow, barX + barW - 1, yRow + 1, color);
        }

        gui.fill(x + 7, y + 89, x + imageWidth - 7, y + 90, 0xFF999999);

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
        gui.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        int barX = leftPos + 138, barY = topPos + 17;
        if (mouseX >= barX && mouseX < barX + 10 && mouseY >= barY && mouseY < barY + 54) {
            int fuel = menu.getDrone().getFuelTicks();
            gui.renderTooltip(this.font,
                Component.literal("Fuel: " + (fuel / 20) + "s"),
                mouseX, mouseY);
        }
        renderTooltip(gui, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (labelField != null && !labelField.isMouseOver(mouseX, mouseY) && labelField.isFocused()) {
            sendLabel();
            labelField.setFocused(false);
        }
        return handled;
    }
}

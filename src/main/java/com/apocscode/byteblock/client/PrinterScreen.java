package com.apocscode.byteblock.client;

import com.apocscode.byteblock.menu.PrinterMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Printer GUI screen — renders 2 slots (input media + output) with an arrow between them.
 */
public class PrinterScreen extends AbstractContainerScreen<PrinterMenu> {

    public PrinterScreen(PrinterMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        // Main background (light gray, MC-style)
        gui.fill(x, y, x + imageWidth, y + imageHeight, 0xFFC6C6C6);

        // 3D border effect
        gui.fill(x, y, x + imageWidth, y + 1, 0xFFFFFFFF);
        gui.fill(x, y, x + 1, y + imageHeight, 0xFFFFFFFF);
        gui.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, 0xFF555555);
        gui.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, 0xFF555555);

        // Input slot background (dark inset)
        renderSlotBg(gui, x + 55, y + 34);

        // Output slot background
        renderSlotBg(gui, x + 115, y + 34);

        // Arrow between slots
        int arrowX = x + 79;
        int arrowY = y + 37;
        gui.fill(arrowX, arrowY, arrowX + 22, arrowY + 1, 0xFF404040);
        gui.fill(arrowX, arrowY + 1, arrowX + 22, arrowY + 2, 0xFF404040);
        // Arrowhead
        gui.fill(arrowX + 18, arrowY - 2, arrowX + 22, arrowY + 4, 0xFF404040);

        // Separator line above player inventory
        gui.fill(x + 7, y + 77, x + imageWidth - 7, y + 78, 0xFF999999);

        // Player inventory slot backgrounds
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                renderSlotBg(gui, x + 7 + col * 18, y + 83 + row * 18);
            }
        }
        // Hotbar
        for (int col = 0; col < 9; col++) {
            renderSlotBg(gui, x + 7 + col * 18, y + 141);
        }
    }

    private void renderSlotBg(GuiGraphics gui, int x, int y) {
        // Dark inset slot (18x18 with 1px border)
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

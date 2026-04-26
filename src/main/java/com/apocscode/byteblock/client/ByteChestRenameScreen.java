package com.apocscode.byteblock.client;

import com.apocscode.byteblock.network.RenameByteChestPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Modal screen for renaming a ByteChest. Opened via shift-right-click on the
 * chest with an empty main hand. Sends a {@link RenameByteChestPayload} on OK.
 */
public class ByteChestRenameScreen extends Screen {

    private static final int W = 220;
    private static final int H = 110;

    private final BlockPos pos;
    private final String initialLabel;
    private EditBox labelField;

    public ByteChestRenameScreen(BlockPos pos, String currentLabel) {
        super(Component.literal("Rename ByteChest"));
        this.pos = pos;
        this.initialLabel = currentLabel == null ? "" : currentLabel;
    }

    @Override
    protected void init() {
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;

        labelField = new EditBox(this.font, left + 12, top + 36, W - 24, 18,
                Component.literal("Label"));
        labelField.setMaxLength(32);
        labelField.setValue(initialLabel);
        labelField.setFocused(true);
        addRenderableWidget(labelField);
        setInitialFocus(labelField);

        addRenderableWidget(Button.builder(Component.literal("OK"), b -> commit())
                .bounds(left + 12, top + H - 28, 90, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + W - 102, top + H - 28, 90, 20)
                .build());
    }

    private void commit() {
        String label = labelField.getValue() == null ? "" : labelField.getValue().trim();
        PacketDistributor.sendToServer(new RenameByteChestPayload(pos, label));
        onClose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter / numpad Enter
            commit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g, mx, my, pt);
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;

        // Panel
        g.fill(left, top, left + W, top + H, 0xFF1E1E1E);
        g.fill(left, top, left + W, top + 1, 0xFF3A3A3A);
        g.fill(left, top + H - 1, left + W, top + H, 0xFF3A3A3A);
        g.fill(left, top, left + 1, top + H, 0xFF3A3A3A);
        g.fill(left + W - 1, top, left + W, top + H, 0xFF3A3A3A);

        // Title
        g.drawString(this.font, "Rename ByteChest", left + 12, top + 10, 0xFFE0E0E0, false);
        g.drawString(this.font, "Label (max 32 chars):", left + 12, top + 24, 0xFFA0A0A0, false);

        super.render(g, mx, my, pt);
    }
}

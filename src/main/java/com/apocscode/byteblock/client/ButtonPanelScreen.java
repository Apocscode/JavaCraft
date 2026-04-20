package com.apocscode.byteblock.client;

import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity;
import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity.ButtonMode;
import com.apocscode.byteblock.network.ButtonConfigPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Button Panel configuration GUI — opened by sneak+right-clicking the panel block.
 * Shows a 4×4 button grid (click to select), mode picker, duration field, and channel setting.
 */
public class ButtonPanelScreen extends Screen {

    private final BlockPos panelPos;
    private int selectedButton = 0;

    // Cached config from block entity
    private final ButtonMode[] modes = new ButtonMode[16];
    private final int[] durations = new int[16];
    private int channel = 1;

    // Widgets
    private Button modeButton;
    private EditBox durationField;
    private EditBox channelField;

    // Layout
    private int guiLeft, guiTop;
    private static final int GUI_WIDTH = 260;
    private static final int GUI_HEIGHT = 200;
    private static final int GRID_SIZE = 120;
    private static final int GRID_LEFT = 12;
    private static final int GRID_TOP = 30;
    private static final int CELL = 28;
    private static final int GAP = 2;

    // Button colors matching the panel renderer
    private static final int[] BUTTON_COLORS = {
        0xFFF9FFFE, 0xFFF9801D, 0xFFC74EBD, 0xFF3AB3DA,
        0xFFFED83D, 0xFF80C71F, 0xFFF38BAA, 0xFF474F52,
        0xFF9D9D97, 0xFF169C9C, 0xFF8932B8, 0xFF3C44AA,
        0xFF835432, 0xFF5E7C16, 0xFFB02E26, 0xFF1D1D21
    };
    private static final String[] COLOR_NAMES = {
        "White", "Orange", "Magenta", "Lt Blue", "Yellow", "Lime",
        "Pink", "Gray", "Lt Gray", "Cyan", "Purple", "Blue",
        "Brown", "Green", "Red", "Black"
    };

    public ButtonPanelScreen(BlockPos pos) {
        super(Component.literal("Button Panel Config"));
        this.panelPos = pos;

        // Load config from client-side block entity
        if (Minecraft.getInstance().level != null &&
            Minecraft.getInstance().level.getBlockEntity(pos) instanceof ButtonPanelBlockEntity panel) {
            for (int i = 0; i < 16; i++) {
                modes[i] = panel.getMode(i);
                durations[i] = panel.getDuration(i);
            }
            channel = panel.getChannel();
        } else {
            for (int i = 0; i < 16; i++) {
                modes[i] = ButtonMode.TOGGLE;
                durations[i] = 20;
            }
        }
    }

    @Override
    protected void init() {
        guiLeft = (width - GUI_WIDTH) / 2;
        guiTop = (height - GUI_HEIGHT) / 2;

        int rightCol = guiLeft + GRID_LEFT + GRID_SIZE + 16;
        int rightWidth = GUI_WIDTH - GRID_SIZE - GRID_LEFT - 28;

        // Mode cycle button
        modeButton = Button.builder(Component.literal(modes[selectedButton].name()),
                b -> cycleMode())
                .bounds(rightCol, guiTop + 52, rightWidth, 20)
                .build();
        addRenderableWidget(modeButton);

        // Duration field
        durationField = new EditBox(font, rightCol, guiTop + 94, rightWidth, 16,
                Component.literal("Duration"));
        durationField.setMaxLength(5);
        durationField.setValue(String.valueOf(durations[selectedButton]));
        addRenderableWidget(durationField);

        // Channel field
        channelField = new EditBox(font, rightCol, guiTop + 134, 40, 16,
                Component.literal("Channel"));
        channelField.setMaxLength(3);
        channelField.setValue(String.valueOf(channel));
        addRenderableWidget(channelField);

        // Apply button
        addRenderableWidget(Button.builder(Component.literal("Apply"),
                b -> applyConfig())
                .bounds(rightCol, guiTop + GUI_HEIGHT - 28, rightWidth / 2 - 2, 20)
                .build());

        // Done button
        addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> onClose())
                .bounds(rightCol + rightWidth / 2 + 2, guiTop + GUI_HEIGHT - 28, rightWidth / 2 - 2, 20)
                .build());

        updateWidgets();
    }

    private void cycleMode() {
        modes[selectedButton] = modes[selectedButton].next();
        updateWidgets();
    }

    private void updateWidgets() {
        if (modeButton != null) {
            modeButton.setMessage(Component.literal(modes[selectedButton].name()));
        }
        if (durationField != null) {
            durationField.setValue(String.valueOf(durations[selectedButton]));
            // Duration only relevant for TIMER and DELAY
            boolean needsDuration = modes[selectedButton] == ButtonMode.TIMER
                    || modes[selectedButton] == ButtonMode.DELAY;
            durationField.setEditable(needsDuration);
        }
    }

    private void applyConfig() {
        // Parse duration
        try {
            int dur = Integer.parseInt(durationField.getValue());
            durations[selectedButton] = Math.max(1, Math.min(6000, dur));
        } catch (NumberFormatException ignored) {}

        // Parse channel
        try {
            int ch = Integer.parseInt(channelField.getValue());
            channel = Math.max(1, Math.min(256, ch));
        } catch (NumberFormatException ignored) {}

        // Send packet to server
        PacketDistributor.sendToServer(new ButtonConfigPayload(
                panelPos, selectedButton, modes[selectedButton].ordinal(),
                durations[selectedButton], channel));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Check if clicked on the grid
        int gx = guiLeft + GRID_LEFT;
        int gy = guiTop + GRID_TOP;
        if (mouseX >= gx && mouseX < gx + GRID_SIZE && mouseY >= gy && mouseY < gy + GRID_SIZE) {
            int col = (int) ((mouseX - gx) / (CELL + GAP));
            int row = (int) ((mouseY - gy) / (CELL + GAP));
            if (col >= 0 && col < 4 && row >= 0 && row < 4) {
                // Auto-apply previous before switching
                applyConfig();
                selectedButton = row * 4 + col;
                updateWidgets();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // Darken background
        renderBackground(gui, mouseX, mouseY, partialTick);

        int x = guiLeft;
        int y = guiTop;

        // Main panel background
        gui.fill(x, y, x + GUI_WIDTH, y + GUI_HEIGHT, 0xFF1E1E2E);
        // Border
        gui.fill(x, y, x + GUI_WIDTH, y + 1, 0xFF3A4A6A);
        gui.fill(x, y, x + 1, y + GUI_HEIGHT, 0xFF3A4A6A);
        gui.fill(x + GUI_WIDTH - 1, y, x + GUI_WIDTH, y + GUI_HEIGHT, 0xFF0A0A15);
        gui.fill(x, y + GUI_HEIGHT - 1, x + GUI_WIDTH, y + GUI_HEIGHT, 0xFF0A0A15);

        // Title bar
        gui.fill(x + 1, y + 1, x + GUI_WIDTH - 1, y + 22, 0xFF2A2A3E);
        gui.drawString(font, "Button Panel Config", x + 8, y + 7, 0xFF5588DD, false);

        // Channel display in title bar
        String chStr = "CH " + channel;
        gui.drawString(font, chStr, x + GUI_WIDTH - font.width(chStr) - 8, y + 7, 0xFF44CCDD, false);

        // Draw 4×4 button grid
        int gx = x + GRID_LEFT;
        int gy = y + GRID_TOP;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int idx = row * 4 + col;
                int bx = gx + col * (CELL + GAP);
                int by = gy + row * (CELL + GAP);

                // Button color fill
                gui.fill(bx, by, bx + CELL, by + CELL, BUTTON_COLORS[idx]);

                // Selection highlight
                if (idx == selectedButton) {
                    // White border for selected
                    gui.fill(bx - 2, by - 2, bx + CELL + 2, by - 1, 0xFFFFFFFF);
                    gui.fill(bx - 2, by + CELL + 1, bx + CELL + 2, by + CELL + 2, 0xFFFFFFFF);
                    gui.fill(bx - 2, by - 1, bx - 1, by + CELL + 1, 0xFFFFFFFF);
                    gui.fill(bx + CELL + 1, by - 1, bx + CELL + 2, by + CELL + 1, 0xFFFFFFFF);
                }

                // Mode letter indicator
                String letter = switch (modes[idx]) {
                    case TOGGLE -> "T";
                    case MOMENTARY -> "M";
                    case TIMER -> "R";
                    case DELAY -> "D";
                    case INVERTED -> "I";
                };
                // Shadow for readability
                gui.drawString(font, letter, bx + 2, by + 2, 0xFF000000, false);
                gui.drawString(font, letter, bx + 1, by + 1, 0xFFFFFFFF, false);
            }
        }

        // Right side labels
        int rightCol = x + GRID_LEFT + GRID_SIZE + 16;

        // Selected button info
        gui.drawString(font, "Button " + selectedButton, rightCol, y + 30, 0xFFCCCCCC, false);
        gui.drawString(font, COLOR_NAMES[selectedButton], rightCol + font.width("Button " + selectedButton + " "),
                y + 30, BUTTON_COLORS[selectedButton], false);

        // Mode label
        gui.drawString(font, "Mode:", rightCol, y + 42, 0xFF888899, false);

        // Duration label
        boolean needsDuration = modes[selectedButton] == ButtonMode.TIMER
                || modes[selectedButton] == ButtonMode.DELAY;
        String durLabel = needsDuration ? "Duration (ticks):" : "Duration (N/A):";
        gui.drawString(font, durLabel, rightCol, y + 82, 0xFF888899, false);

        // Channel label
        gui.drawString(font, "BT Channel:", rightCol, y + 124, 0xFF888899, false);

        // Mode description
        String desc = switch (modes[selectedButton]) {
            case TOGGLE -> "Click on / click off";
            case MOMENTARY -> "Short pulse (4 ticks)";
            case TIMER -> "On for " + durations[selectedButton] + "t, then off";
            case DELAY -> "Wait " + durations[selectedButton] + "t, then toggle";
            case INVERTED -> "Toggle, inverted output";
        };
        gui.drawString(font, desc, rightCol, y + 115, 0xFF666677, false);

        // Render widgets (buttons, text fields)
        super.render(gui, mouseX, mouseY, partialTick);

        // Tooltip on hover over buttons
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int idx = row * 4 + col;
                int bx = gx + col * (CELL + GAP);
                int by = gy + row * (CELL + GAP);
                if (mouseX >= bx && mouseX < bx + CELL && mouseY >= by && mouseY < by + CELL) {
                    gui.renderTooltip(font,
                            Component.literal(COLOR_NAMES[idx] + " — " + modes[idx].name()),
                            (int) mouseX, (int) mouseY);
                }
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // Auto-apply on close
        applyConfig();
        super.onClose();
    }
}

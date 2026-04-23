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
 * Dedicated single-button configuration GUI.
 *
 * Opened by shift+right-clicking a SPECIFIC button face on a ButtonPanel block.
 * Each button has its own instance of this GUI, titled with that button's color.
 */
public class ButtonPanelScreen extends Screen {

    private final BlockPos panelPos;
    private final int buttonIndex;

    private ButtonMode mode;
    private int duration;
    private String label;
    private int colorOverride;   // -1 = default, else 0xRRGGBB
    private int channel;
    private String panelLabel;

    // Widgets
    private EditBox labelField;
    private Button modeButton;
    private EditBox durationField;
    private EditBox channelField;

    // Layout
    private int guiLeft, guiTop;
    private static final int GUI_WIDTH = 260;
    private static final int GUI_HEIGHT = 260;

    // Row Y offsets (all relative to guiTop). Each widget has its own row with
    // no overlap with the one below it.
    private static final int ROW_LABEL_LBL    =  36;
    private static final int ROW_LABEL_FIELD  =  46;   // 16h -> ends 62
    private static final int ROW_MODE_LBL     =  68;
    private static final int ROW_MODE_BTN     =  78;   // 18h -> ends 96
    private static final int ROW_MODE_DESC    = 102;   // 9h  -> ends 111
    private static final int ROW_DUR_LBL      = 116;
    private static final int ROW_DUR_FIELD    = 126;   // 16h -> ends 142
    private static final int ROW_COLOR_LBL    = 150;
    private static final int ROW_PALETTE      = 160;   // two rows of 14h -> ends 190
    private static final int ROW_CHANNEL_LBL  = 198;
    private static final int ROW_CHANNEL_FLD  = 208;   // 16h -> ends 224
    private static final int ROW_BUTTONS      = 232;   // 20h -> ends 252

    // Color palette layout
    private static final int PALETTE_SWATCH = 14;
    private static final int PALETTE_GAP = 2;

    private static final int[] BUTTON_COLORS = {
        0xFFF9FFFE, 0xFFF9801D, 0xFFC74EBD, 0xFF3AB3DA,
        0xFFFED83D, 0xFF80C71F, 0xFFF38BAA, 0xFF474F52,
        0xFF9D9D97, 0xFF169C9C, 0xFF8932B8, 0xFF3C44AA,
        0xFF835432, 0xFF5E7C16, 0xFFB02E26, 0xFF1D1D21
    };
    private static final String[] COLOR_NAMES = {
        "White", "Orange", "Magenta", "Light Blue", "Yellow", "Lime",
        "Pink", "Gray", "Light Gray", "Cyan", "Purple", "Blue",
        "Brown", "Green", "Red", "Black"
    };

    public ButtonPanelScreen(BlockPos pos, int buttonIndex) {
        super(Component.literal(COLOR_NAMES[buttonIndex] + " Button"));
        this.panelPos = pos;
        this.buttonIndex = buttonIndex;

        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockEntity(pos) instanceof ButtonPanelBlockEntity panel) {
            this.mode = panel.getMode(buttonIndex);
            this.duration = panel.getDuration(buttonIndex);
            this.label = panel.getButtonLabel(buttonIndex);
            this.colorOverride = panel.getButtonColor(buttonIndex);
            this.channel = panel.getChannel();
            this.panelLabel = panel.getLabel();
        } else {
            this.mode = ButtonMode.TOGGLE;
            this.duration = 20;
            this.label = "";
            this.colorOverride = -1;
            this.channel = 1;
            this.panelLabel = "";
        }
    }

    @Override
    protected void init() {
        guiLeft = (width - GUI_WIDTH) / 2;
        guiTop = (height - GUI_HEIGHT) / 2;

        int fieldX = guiLeft + 12;
        int fieldW = GUI_WIDTH - 24;

        // Label field
        labelField = new EditBox(font, fieldX, guiTop + ROW_LABEL_FIELD, fieldW, 16, Component.literal("Label"));
        labelField.setMaxLength(16);
        labelField.setValue(label == null ? "" : label);
        addRenderableWidget(labelField);

        // Mode cycle button
        modeButton = Button.builder(Component.literal(mode.name()), b -> cycleMode())
                .bounds(fieldX, guiTop + ROW_MODE_BTN, fieldW, 18)
                .build();
        addRenderableWidget(modeButton);

        // Duration field
        durationField = new EditBox(font, fieldX, guiTop + ROW_DUR_FIELD, fieldW, 16, Component.literal("Duration"));
        durationField.setMaxLength(5);
        durationField.setValue(String.valueOf(duration));
        addRenderableWidget(durationField);
        updateDurationEditable();

        // Channel field
        channelField = new EditBox(font, fieldX, guiTop + ROW_CHANNEL_FLD, 50, 16, Component.literal("Channel"));
        channelField.setMaxLength(3);
        channelField.setValue(String.valueOf(channel));
        addRenderableWidget(channelField);

        // Apply + Done
        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> applyConfig())
                .bounds(fieldX, guiTop + ROW_BUTTONS, fieldW / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(fieldX + fieldW / 2 + 2, guiTop + ROW_BUTTONS, fieldW / 2 - 2, 20).build());
    }

    private void cycleMode() {
        mode = mode.next();
        modeButton.setMessage(Component.literal(mode.name()));
        updateDurationEditable();
    }

    private void updateDurationEditable() {
        boolean needs = (mode == ButtonMode.TIMER || mode == ButtonMode.DELAY);
        if (durationField != null) durationField.setEditable(needs);
    }

    private void applyConfig() {
        try {
            int d = Integer.parseInt(durationField.getValue());
            duration = Math.max(1, Math.min(6000, d));
        } catch (NumberFormatException ignored) {}
        try {
            int c = Integer.parseInt(channelField.getValue());
            channel = Math.max(1, Math.min(256, c));
        } catch (NumberFormatException ignored) {}
        label = labelField.getValue();

        PacketDistributor.sendToServer(new ButtonConfigPayload(
                panelPos, buttonIndex, mode.ordinal(), duration, channel,
                panelLabel == null ? "" : panelLabel,
                label == null ? "" : label,
                colorOverride));
    }

    private int getPaletteX() { return guiLeft + 12; }
    private int getPaletteY() { return guiTop + ROW_PALETTE; }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Palette (8 across, 2 rows)
        int palX = getPaletteX();
        int palY = getPaletteY();
        for (int i = 0; i < 16; i++) {
            int sx = palX + (i % 8) * (PALETTE_SWATCH + PALETTE_GAP);
            int sy = palY + (i / 8) * (PALETTE_SWATCH + PALETTE_GAP);
            if (mouseX >= sx && mouseX < sx + PALETTE_SWATCH
                    && mouseY >= sy && mouseY < sy + PALETTE_SWATCH) {
                colorOverride = BUTTON_COLORS[i] & 0xFFFFFF;
                return true;
            }
        }
        // "X" reset
        int resetX = palX + 8 * (PALETTE_SWATCH + PALETTE_GAP) + 6;
        if (mouseX >= resetX && mouseX < resetX + PALETTE_SWATCH
                && mouseY >= palY && mouseY < palY + PALETTE_SWATCH) {
            colorOverride = -1;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // Skip renderBackground() — bypass blur pipeline entirely.
        // Full-screen opaque fill so nothing from the world bleeds through.
        gui.fill(0, 0, width, height, 0xFF101018);

        int x = guiLeft, y = guiTop;

        // Panel (fully opaque, slightly lighter to improve text contrast)
        gui.fill(x, y, x + GUI_WIDTH, y + GUI_HEIGHT, 0xFF2A2A3C);
        // 2-tone bevel border
        gui.fill(x, y, x + GUI_WIDTH, y + 1, 0xFF8090B0);
        gui.fill(x, y, x + 1, y + GUI_HEIGHT, 0xFF8090B0);
        gui.fill(x + GUI_WIDTH - 1, y, x + GUI_WIDTH, y + GUI_HEIGHT, 0xFF101020);
        gui.fill(x, y + GUI_HEIGHT - 1, x + GUI_WIDTH, y + GUI_HEIGHT, 0xFF101020);

        // ── Title bar tinted with the button's effective color ──────────────
        int effColor = (colorOverride >= 0) ? (0xFF000000 | colorOverride) : BUTTON_COLORS[buttonIndex];
        int r = (effColor >> 16) & 0xFF;
        int g = (effColor >> 8)  & 0xFF;
        int b =  effColor        & 0xFF;

        // Darkened tint (40%) for the bar background
        int barBg = 0xFF000000 | (((r * 2) / 5) << 16) | (((g * 2) / 5) << 8) | ((b * 2) / 5);
        gui.fill(x + 1, y + 1, x + GUI_WIDTH - 1, y + 30, barBg);

        // Full-color chip
        gui.fill(x + 8, y + 8, x + 24, y + 24, effColor);
        gui.fill(x + 8, y + 8, x + 24, y + 9, 0xFFFFFFFF);
        gui.fill(x + 8, y + 8, x + 9, y + 24, 0xFFFFFFFF);
        gui.fill(x + 23, y + 8, x + 24, y + 24, 0xFF000000);
        gui.fill(x + 8, y + 23, x + 24, y + 24, 0xFF000000);

        // Contrast-aware text color
        int barLum = ((((r * 2) / 5) * 299) + (((g * 2) / 5) * 587) + (((b * 2) / 5) * 114)) / 1000;
        int titleFg = barLum > 110 ? 0xFF000000 : 0xFFFFFFFF;

        String colorName = (colorOverride >= 0) ? "Custom" : COLOR_NAMES[buttonIndex];
        gui.drawString(font, colorName + " Button", x + 32, y + 8, titleFg, true);
        gui.drawString(font, "#" + (buttonIndex + 1), x + 32, y + 18, titleFg, true);

        String chStr = "CH " + channel;
        gui.drawString(font, chStr, x + GUI_WIDTH - font.width(chStr) - 8, y + 12, titleFg, true);

        // ── Field labels ───────────────────────────────────────────────────
        int lx = x + 12;
        gui.drawString(font, "Label:", lx, y + ROW_LABEL_LBL, 0xFFAABBCC, true);
        gui.drawString(font, "Mode:",  lx, y + ROW_MODE_LBL,  0xFFAABBCC, true);

        // Mode description (its own row, no overlap with Duration)
        String desc = switch (mode) {
            case TOGGLE    -> "Click to toggle on/off.";
            case MOMENTARY -> "Short pulse (4 ticks) on press.";
            case TIMER     -> "On for " + duration + "t (" + String.format("%.1fs", duration / 20.0) + "), then off.";
            case DELAY     -> "Wait " + duration + "t, then toggle.";
            case INVERTED  -> "Toggle; output is inverted.";
        };
        gui.drawString(font, desc, lx, y + ROW_MODE_DESC, 0xFF778899, true);

        boolean needsDuration = (mode == ButtonMode.TIMER || mode == ButtonMode.DELAY);
        String durCaption = needsDuration
                ? "Duration (ticks, 20 = 1s):"
                : "Duration (unused for " + mode.name() + "):";
        gui.drawString(font, durCaption, lx, y + ROW_DUR_LBL,
                needsDuration ? 0xFFAABBCC : 0xFF556677, true);

        // ── Color palette ──────────────────────────────────────────────────
        gui.drawString(font, "Color:", lx, y + ROW_COLOR_LBL, 0xFFAABBCC, true);
        int palX = getPaletteX();
        int palY = getPaletteY();
        for (int i = 0; i < 16; i++) {
            int sx = palX + (i % 8) * (PALETTE_SWATCH + PALETTE_GAP);
            int sy = palY + (i / 8) * (PALETTE_SWATCH + PALETTE_GAP);
            gui.fill(sx, sy, sx + PALETTE_SWATCH, sy + PALETTE_SWATCH, BUTTON_COLORS[i]);
            if (colorOverride >= 0 && (BUTTON_COLORS[i] & 0xFFFFFF) == colorOverride) {
                gui.fill(sx - 1, sy - 1, sx + PALETTE_SWATCH + 1, sy,                         0xFFFFFFFF);
                gui.fill(sx - 1, sy + PALETTE_SWATCH, sx + PALETTE_SWATCH + 1, sy + PALETTE_SWATCH + 1, 0xFFFFFFFF);
                gui.fill(sx - 1, sy, sx, sy + PALETTE_SWATCH,                                 0xFFFFFFFF);
                gui.fill(sx + PALETTE_SWATCH, sy, sx + PALETTE_SWATCH + 1, sy + PALETTE_SWATCH, 0xFFFFFFFF);
            } else {
                gui.fill(sx, sy, sx + PALETTE_SWATCH, sy + 1, 0xFF000000);
                gui.fill(sx, sy, sx + 1, sy + PALETTE_SWATCH, 0xFF000000);
            }
        }
        // Reset swatch
        int resetX = palX + 8 * (PALETTE_SWATCH + PALETTE_GAP) + 6;
        gui.fill(resetX, palY, resetX + PALETTE_SWATCH, palY + PALETTE_SWATCH, 0xFF333344);
        gui.drawString(font, "X", resetX + 4, palY + 3,
                colorOverride < 0 ? 0xFFFFCC44 : 0xFFAAAAAA, true);

        // Channel label
        gui.drawString(font, "BT Channel:", x + 12, y + ROW_CHANNEL_LBL, 0xFFAABBCC, true);

        super.render(gui, mouseX, mouseY, partialTick);
    }

    // Suppress the default background (blur + dim) entirely.
    // Screen.render() calls renderBackground() before rendering widgets — without this
    // no-op it re-draws the blur ON TOP of our fills and labels every frame.
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // intentionally empty
    }

    @Override
    public boolean isPauseScreen() { return true; }

    @Override
    public void onClose() {
        applyConfig();
        super.onClose();
    }
}

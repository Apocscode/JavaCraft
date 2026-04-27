package com.apocscode.byteblock.client;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Paint picker for the monitor frame/bezel. Opened from {@link MonitorConfigScreen} via the
 * "Paint" button. Presents a grid of preset swatches (the 16 standard CC palette colors plus
 * common neutrals) and a text field for a custom hex color. Closing the screen returns to the
 * parent and reports the chosen color through the supplied callback; the parent's "Apply"
 * button is what ultimately sends the change to the server.
 *
 * <p>This screen never sends a network packet itself — it only updates the parent's pending
 * frame color. That keeps all monitor mutations going through {@code MonitorConfigPayload}.
 */
public class MonitorPaintScreen extends Screen {

    /** 4 × 6 swatch grid: 16 CC palette colors + 8 useful neutrals/accents. */
    private static final int[] PRESETS = new int[] {
        // Row 1 – CC palette (lighter half)
        0xFFF0F0F0, // white
        0xFFF2B233, // orange
        0xFFE57FD8, // magenta
        0xFF99B2F2, // light blue
        0xFFDEDE6C, // yellow
        0xFF7FCC19, // lime
        // Row 2 – CC palette (mids)
        0xFFF2B2CC, // pink
        0xFF4C4C4C, // gray
        0xFF999999, // light gray
        0xFF4C99B2, // cyan
        0xFFB266E5, // purple
        0xFF3366CC, // blue
        // Row 3 – CC palette (darker half) + neutrals
        0xFF7F664C, // brown
        0xFF57A64E, // green
        0xFFCC4C4C, // red
        0xFF111111, // black
        0xFFCCCCCC, // default housing gray
        0xFFFFFFFF, // pure white (no tint)
        // Row 4 – accents
        0xFFFFD700, // gold
        0xFFC0C0C0, // silver
        0xFF8B4513, // saddle brown
        0xFF1E1E2E, // deep navy
        0xFF202225, // dark slate
        0xFF000000, // jet black
    };

    private static final int COLS = 6;

    private final Screen parent;
    private final IntConsumer onPicked;
    private int currentColor;
    private EditBox hexBox;

    public MonitorPaintScreen(Screen parent, int initialColor, IntConsumer onPicked) {
        super(Component.literal("Paint Monitor Frame"));
        this.parent = parent;
        this.currentColor = 0xFF000000 | (initialColor & 0x00FFFFFF);
        this.onPicked = onPicked;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;

        // Swatch grid centered around screen middle. Each swatch is 32×24 with a 4px gap.
        int sw = 32, sh = 24, gap = 4;
        int gridW = COLS * sw + (COLS - 1) * gap;
        int gridX = cx - gridW / 2;
        int gridY = this.height / 2 - 70;

        for (int i = 0; i < PRESETS.length; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int x = gridX + col * (sw + gap);
            int y = gridY + row * (sh + gap);
            final int c = 0xFF000000 | (PRESETS[i] & 0x00FFFFFF);
            // Render the swatch as a button background — the button itself is invisible/empty
            // so we draw the color in render(); we only need it for click detection here.
            Button swatch = Button.builder(Component.literal(""), b -> selectColor(c))
                    .bounds(x, y, sw, sh).build();
            addRenderableWidget(swatch);
        }

        // Custom hex input: "#RRGGBB"
        int hexY = gridY + 4 * (sh + gap) + 8;
        hexBox = new EditBox(this.font, cx - 70, hexY, 100, 18, Component.literal("Hex"));
        hexBox.setMaxLength(8);
        hexBox.setHint(Component.literal("#RRGGBB"));
        hexBox.setValue(String.format("#%06X", currentColor & 0xFFFFFF));
        hexBox.setResponder(this::onHexChanged);
        addRenderableWidget(hexBox);

        Button applyHex = Button.builder(Component.literal("Set"), b -> applyHex())
                .bounds(cx + 36, hexY - 1, 40, 20).build();
        addRenderableWidget(applyHex);

        // Confirm / Back
        Button confirm = Button.builder(Component.literal("Confirm"), b -> {
            onPicked.accept(currentColor);
            close();
        }).bounds(cx - 100, hexY + 36, 90, 20).build();
        addRenderableWidget(confirm);

        Button back = Button.builder(Component.literal("Cancel"), b -> close())
                .bounds(cx + 10, hexY + 36, 90, 20).build();
        addRenderableWidget(back);
    }

    private void selectColor(int argb) {
        currentColor = argb;
        if (hexBox != null) {
            hexBox.setValue(String.format("#%06X", currentColor & 0xFFFFFF));
        }
    }

    private void onHexChanged(String s) {
        // Live-preview if the user types a complete valid hex; otherwise wait for "Set".
        Integer parsed = parseHex(s);
        if (parsed != null) currentColor = parsed;
    }

    private void applyHex() {
        if (hexBox == null) return;
        Integer parsed = parseHex(hexBox.getValue());
        if (parsed != null) currentColor = parsed;
    }

    private static Integer parseHex(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        try {
            int rgb = Integer.parseInt(s, 16);
            return 0xFF000000 | (rgb & 0x00FFFFFF);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void close() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        // ESC returns to parent without re-applying (Confirm/Cancel are explicit).
        close();
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        int cx = this.width / 2;

        // Title
        gfx.drawCenteredString(this.font, this.title, cx, this.height / 2 - 110, 0xFFFFFFFF);
        gfx.drawCenteredString(this.font,
                Component.literal("Pick a frame color or enter a custom hex").withStyle(s -> s.withColor(0xAAAAAA)),
                cx, this.height / 2 - 96, 0xFFAAAAAA);

        // Re-draw the swatch fill + border on top of the (transparent) buttons. Highlight
        // the currently selected swatch.
        int sw = 32, sh = 24, gap = 4;
        int gridW = COLS * sw + (COLS - 1) * gap;
        int gridX = cx - gridW / 2;
        int gridY = this.height / 2 - 70;
        for (int i = 0; i < PRESETS.length; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int x = gridX + col * (sw + gap);
            int y = gridY + row * (sh + gap);
            int c = 0xFF000000 | (PRESETS[i] & 0x00FFFFFF);
            // Border
            int border = (c == currentColor) ? 0xFFFFFFFF : 0xFF202020;
            gfx.fill(x - 1, y - 1, x + sw + 1, y + sh + 1, border);
            // Swatch fill
            gfx.fill(x, y, x + sw, y + sh, c);
        }

        // Current color preview next to hex input
        int hexY = gridY + 4 * (sh + gap) + 8;
        int prevX = cx + 80;
        gfx.fill(prevX, hexY, prevX + 22, hexY + 18, 0xFF000000);
        gfx.fill(prevX + 1, hexY + 1, prevX + 21, hexY + 17, currentColor);

        gfx.drawString(this.font, "Custom:", cx - 130, hexY + 5, 0xFF80E0FF, false);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

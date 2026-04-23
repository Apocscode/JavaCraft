package com.apocscode.byteblock.computer;

import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity;
import com.apocscode.byteblock.block.entity.IButtonPanel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Programmatic API for the local computer's built-in virtual Button Panel.
 *
 * <p>Each Computer block carries a 16-button virtual panel (the same one driven
 * by the on-screen Button App). Programs can read and drive it from Java via
 * this helper. Changes are immediately reflected in the physical world: the
 * computer block emits redstone and bundled-cable signals on all 6 sides based
 * on the current button mask, and events are broadcast on Bluetooth so remote
 * devices can react.</p>
 *
 * <p>All methods are no-ops (or return safe defaults) if the host computer
 * block entity isn't loaded or no longer implements {@link IButtonPanel}.</p>
 */
public final class ButtonsLib {
    private ButtonsLib() {}

    /** Resolve the local virtual button panel, or {@code null} if unavailable. */
    private static IButtonPanel local(JavaOS os) {
        if (os == null) return null;
        Level level = os.getLevel();
        BlockPos pos = os.getBlockPos();
        if (level == null || pos == null) return null;
        BlockEntity be = level.getBlockEntity(pos);
        return (be instanceof IButtonPanel panel) ? panel : null;
    }

    // ── State ────────────────────────────────────────────────────────────────

    /** Turn a single button (0-15) on or off. */
    public static void setButton(JavaOS os, int index, boolean on) {
        IButtonPanel p = local(os);
        if (p != null) p.setButton(index, on);
    }

    /** Read a single button (0-15). */
    public static boolean getButton(JavaOS os, int index) {
        IButtonPanel p = local(os);
        return p != null && p.isButtonOn(index);
    }

    /** Set all 16 buttons at once using a bit mask. */
    public static void setAllButtons(JavaOS os, int mask) {
        IButtonPanel p = local(os);
        if (p != null) p.setAllButtons(mask);
    }

    /** Read the 16-bit button mask. */
    public static int getButtonStates(JavaOS os) {
        IButtonPanel p = local(os);
        return p == null ? 0 : p.getButtonStates();
    }

    // ── Configuration ────────────────────────────────────────────────────────

    /** Set a button's mode (TOGGLE, MOMENTARY, TIMER, DELAY, INVERTED). */
    public static void setMode(JavaOS os, int index, ButtonPanelBlockEntity.ButtonMode mode) {
        IButtonPanel p = local(os);
        if (p != null) p.setMode(index, mode);
    }

    /** Set the duration (ticks, 1..6000) used by TIMER and DELAY modes. */
    public static void setDuration(JavaOS os, int index, int ticks) {
        IButtonPanel p = local(os);
        if (p != null) p.setDuration(index, ticks);
    }

    /** Set the display label for a single button (max 16 chars). */
    public static void setButtonLabel(JavaOS os, int index, String label) {
        IButtonPanel p = local(os);
        if (p != null) p.setButtonLabel(index, label);
    }

    /** Set the display color for a single button (0xRRGGBB, or -1 for default). */
    public static void setButtonColor(JavaOS os, int index, int rgb) {
        IButtonPanel p = local(os);
        if (p != null) p.setButtonColor(index, rgb);
    }

    /** Rename the virtual panel itself (max 24 chars). */
    public static void setLabel(JavaOS os, String label) {
        IButtonPanel p = local(os);
        if (p != null) p.setLabel(label);
    }

    /** Change the virtual panel's Bluetooth channel (1..256). */
    public static void setChannel(JavaOS os, int channel) {
        IButtonPanel p = local(os);
        if (p != null) p.setChannel(channel);
    }
}

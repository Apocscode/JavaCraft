package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity.ButtonMode;

/**
 * Common interface for anything that behaves like a Button Panel — a 4×4 grid of
 * configurable momentary/toggle/timer/delay buttons that can drive redstone and
 * bundled-cable outputs.
 *
 * Implemented by the physical {@link ButtonPanelBlockEntity} and by
 * {@code ComputerBlockEntity}'s virtual panel so the Button App, RedstoneLib, and
 * the ButtonConfigPayload can treat them identically.
 */
public interface IButtonPanel {

    // ── State queries ─────────────────────────────────────────────────────
    int getButtonStates();
    boolean isButtonOn(int index);
    ButtonMode getMode(int index);
    int getDuration(int index);
    String getButtonLabel(int index);
    int getButtonColor(int index);
    String getLabel();
    int getChannel();

    // ── Mutators ──────────────────────────────────────────────────────────
    /** Apply a single button state directly (no mode logic). */
    void setButton(int index, boolean on);

    /** Apply all 16 button states from a bitmask (no mode logic). */
    void setAllButtons(int mask);

    /** Press a button through its configured mode (toggle / momentary / timer / delay). */
    void toggleButton(int index, net.minecraft.world.entity.player.Player player);

    void setMode(int index, ButtonMode mode);
    void setDuration(int index, int ticks);
    void setButtonLabel(int index, String label);
    void setButtonColor(int index, int rgb);
    void setLabel(String label);
    void setChannel(int channel);
}

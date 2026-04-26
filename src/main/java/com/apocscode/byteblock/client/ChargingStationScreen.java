package com.apocscode.byteblock.client;

import com.apocscode.byteblock.menu.ChargingStationMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen for the Charging Station. Shows current FE level (gradient bar matching the
 * robot/drone screens) and a scrolling list of compatible FE-providing neighbours
 * (covers any mod's cables, batteries, generators that expose the FE block cap).
 */
public class ChargingStationScreen extends AbstractContainerScreen<ChargingStationMenu> {

    private static final int MAX_FE = 100_000;

    public ChargingStationScreen(ChargingStationMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 224;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        // Vanilla-ish background.
        gui.fill(x, y, x + imageWidth, y + imageHeight, 0xFFC6C6C6);
        gui.fill(x, y, x + imageWidth, y + 1, 0xFFFFFFFF);
        gui.fill(x, y, x + 1, y + imageHeight, 0xFFFFFFFF);
        gui.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, 0xFF555555);
        gui.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, 0xFF555555);

        // Title pad.
        gui.drawString(this.font, this.title, x + 8, y + 6, 0x404040, false);

        // ---- Gradient FE bar (vertical, on the left) ----
        int barX = x + 14, barY = y + 22, barW = 14, barH = 50;
        gui.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF222222);
        gui.fill(barX, barY, barX + barW, barY + barH, 0xFF373737);
        int stored = menu.getStation().getEnergyStored();
        int max = Math.max(1, menu.getStation().getMaxEnergy());
        int pct = stored * barH / max;
        for (int i = 0; i < pct; i++) {
            float frac = i / (float) barH;
            int color = RobotScreen.chargeColor(frac);
            int yRow = barY + barH - 1 - i;
            gui.fill(barX + 1, yRow, barX + barW - 1, yRow + 1, color);
        }
        // Numeric readout.
        String pctText = (stored * 100 / max) + "%";
        String feText = formatFE(stored) + " / " + formatFE(max) + " FE";
        gui.drawString(this.font, pctText, x + 36, y + 24, 0x202020, false);
        gui.drawString(this.font, feText, x + 36, y + 36, 0x404040, false);

        // ---- Compatible neighbour list ----
        gui.drawString(this.font, Component.literal("Connected sources:"), x + 8, y + 78, 0x404040, false);
        List<NeighbourInfo> neighbours = scanNeighbours();
        int rowY = y + 90;
        if (neighbours.isEmpty()) {
            gui.drawString(this.font, Component.literal("  (none — place an FE source adjacent)"),
                    x + 8, rowY, 0x808080, false);
        } else {
            for (NeighbourInfo n : neighbours) {
                if (rowY > y + 130) break;
                int color = n.canExtract() ? 0xFF20A040 : 0xFF808080;
                gui.fill(x + 8, rowY + 1, x + 12, rowY + 9, color);
                String text = n.dirLabel() + ": " + n.kind() + (n.canExtract() ? " ✓" : " (no output)");
                gui.drawString(this.font, text, x + 16, rowY + 1, 0x202020, false);
                rowY += 11;
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        // Suppress default title at top-left of slots area.
        gui.drawString(this.font, this.playerInventoryTitle,
                this.inventoryLabelX, this.inventoryLabelY, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui, mouseX, mouseY, partialTick);
        super.render(gui, mouseX, mouseY, partialTick);

        // Tooltip on the FE bar.
        int barX = leftPos + 14, barY = topPos + 22, barW = 14, barH = 50;
        if (mouseX >= barX && mouseX < barX + barW && mouseY >= barY && mouseY < barY + barH) {
            int stored = menu.getStation().getEnergyStored();
            int max = menu.getStation().getMaxEnergy();
            gui.renderTooltip(this.font, Component.literal(stored + " / " + max + " FE"), mouseX, mouseY);
        }
        renderTooltip(gui, mouseX, mouseY);
    }

    private static String formatFE(int v) {
        if (v >= 1_000_000) return (v / 1_000_000) + "M";
        if (v >= 1_000) return (v / 1_000) + "k";
        return Integer.toString(v);
    }

    private List<NeighbourInfo> scanNeighbours() {
        List<NeighbourInfo> out = new ArrayList<>();
        Level lvl = menu.getStation().getLevel();
        if (lvl == null) return out;
        BlockPos pos = menu.getPos();
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockEntity be = lvl.getBlockEntity(n);
            if (be == null) continue;
            IEnergyStorage cap = lvl.getCapability(
                    Capabilities.EnergyStorage.BLOCK, n, d.getOpposite());
            if (cap == null) continue;
            String kind = be.getType().builtInRegistryHolder().key().location().toString();
            out.add(new NeighbourInfo(d, kind, cap.canExtract()));
        }
        return out;
    }

    private record NeighbourInfo(Direction dir, String kind, boolean canExtract) {
        String dirLabel() {
            return switch (dir) {
                case UP -> "Top";
                case DOWN -> "Btm";
                case NORTH -> "N";
                case SOUTH -> "S";
                case EAST -> "E";
                case WEST -> "W";
            };
        }
    }
}

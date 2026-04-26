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
 * Screen for the Charging Station. Self-contained — no player-inventory display.
 * Shows current FE level (gradient bar matching the robot/drone screens) and a
 * compact list of compatible FE-providing neighbours.
 */
public class ChargingStationScreen extends AbstractContainerScreen<ChargingStationMenu> {

    public ChargingStationScreen(ChargingStationMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 220;
        this.imageHeight = 160;
        // Hide default labels (we draw everything ourselves).
        this.titleLabelX = -9999;
        this.titleLabelY = -9999;
        this.inventoryLabelX = -9999;
        this.inventoryLabelY = -9999;
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos, w = imageWidth, h = imageHeight;

        // Window background + bevel.
        gui.fill(x, y, x + w, y + h, 0xFF1E1E22);
        gui.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF3A3A40);
        gui.fill(x + 1, y + 1, x + 2, y + h - 1, 0xFF3A3A40);
        gui.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF101012);
        gui.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, 0xFF101012);

        // Title bar.
        gui.fill(x + 2, y + 2, x + w - 2, y + 14, 0xFF2A2A30);
        gui.drawString(this.font, Component.literal("Charging Station"),
                x + 6, y + 4, 0xFFE0E0E0, false);

        int stored = menu.getStation().getEnergyStored();
        int max = Math.max(1, menu.getStation().getMaxEnergy());

        // Vertical gradient FE bar on left.
        int barX = x + 10, barY = y + 22, barW = 16, barH = 120;
        gui.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF000000);
        gui.fill(barX, barY, barX + barW, barY + barH, 0xFF222226);
        int fill = stored * barH / max;
        for (int i = 0; i < fill; i++) {
            float frac = i / (float) barH;
            int color = RobotScreen.chargeColor(frac);
            int yRow = barY + barH - 1 - i;
            gui.fill(barX + 1, yRow, barX + barW - 1, yRow + 1, color);
        }

        // Numeric readouts.
        int textX = barX + barW + 8;
        int pct = (int) (stored * 100L / max);
        gui.drawString(this.font, Component.literal(pct + "%"),
                textX, y + 22, 0xFFFFFFFF, false);
        gui.drawString(this.font, Component.literal(formatFE(stored) + " / " + formatFE(max) + " FE"),
                textX, y + 34, 0xFFB0B0B0, false);
        gui.drawString(this.font, Component.literal("Input:  1000 FE/t"),
                textX, y + 50, 0xFF80B0FF, false);
        gui.drawString(this.font, Component.literal("Output: 200 FE/t"),
                textX, y + 60, 0xFF80FFB0, false);

        // Compatible neighbour list.
        int listX = textX;
        int listY = y + 78;
        gui.drawString(this.font, Component.literal("Connected sources:"),
                listX, listY, 0xFFE0E0E0, false);
        listY += 11;

        List<NeighbourInfo> neighbours = scanNeighbours();
        if (neighbours.isEmpty()) {
            gui.drawString(this.font, Component.literal("(none — place FE"),
                    listX, listY, 0xFF808080, false);
            gui.drawString(this.font, Component.literal(" source adjacent)"),
                    listX, listY + 10, 0xFF808080, false);
        } else {
            int rows = Math.min(neighbours.size(), 5);
            for (int i = 0; i < rows; i++) {
                NeighbourInfo n = neighbours.get(i);
                int color = n.canExtract() ? 0xFF40D060 : 0xFF707070;
                gui.fill(listX, listY + 1, listX + 4, listY + 9, color);
                String name = n.shortName();
                int maxChars = 22;
                if (name.length() > maxChars) name = name.substring(0, maxChars - 1) + "…";
                gui.drawString(this.font, Component.literal(n.dirLabel() + " " + name),
                        listX + 8, listY + 1, 0xFFD0D0D0, false);
                listY += 10;
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        // Suppressed — drawn in renderBg.
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui, mouseX, mouseY, partialTick);
        super.render(gui, mouseX, mouseY, partialTick);

        int barX = leftPos + 10, barY = topPos + 22, barW = 16, barH = 120;
        if (mouseX >= barX && mouseX < barX + barW && mouseY >= barY && mouseY < barY + barH) {
            int stored = menu.getStation().getEnergyStored();
            int max = menu.getStation().getMaxEnergy();
            gui.renderTooltip(this.font,
                    Component.literal(stored + " / " + max + " FE"),
                    mouseX, mouseY);
        }
    }

    private static String formatFE(int v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1_000) return String.format("%.1fk", v / 1_000.0);
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
            String id = be.getType().builtInRegistryHolder().key().location().toString();
            out.add(new NeighbourInfo(d, id, cap.canExtract()));
        }
        return out;
    }

    private record NeighbourInfo(Direction dir, String kind, boolean canExtract) {
        String dirLabel() {
            return switch (dir) {
                case UP -> "U";
                case DOWN -> "D";
                case NORTH -> "N";
                case SOUTH -> "S";
                case EAST -> "E";
                case WEST -> "W";
            };
        }
        String shortName() {
            int colon = kind.indexOf(':');
            String name = colon >= 0 ? kind.substring(colon + 1) : kind;
            return name.replace('_', ' ');
        }
    }
}

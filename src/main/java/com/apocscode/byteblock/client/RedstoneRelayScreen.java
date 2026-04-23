package com.apocscode.byteblock.client;

import com.apocscode.byteblock.block.entity.RedstoneRelayBlockEntity;
import com.apocscode.byteblock.network.RelayConfigPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Configures per-face relay channels and bundled mode.
 */
public class RedstoneRelayScreen extends Screen {
    private final BlockPos relayPos;

    private final int[] faceChannels = new int[6];
    private final boolean[] bundledFaces = new boolean[6];

    private int selectedSide = Direction.NORTH.get3DDataValue();

    private EditBox channelField;
    private Button bundledButton;
    private Button[] faceButtons;

    private int guiLeft;
    private int guiTop;
    private static final int GUI_WIDTH = 320;
    private static final int GUI_HEIGHT = 260;

    private static final String[] FACE_NAMES = {
            "Bottom", "Top", "North", "South", "West", "East"
    };

    public RedstoneRelayScreen(BlockPos relayPos) {
        super(Component.literal("Redstone Relay Config"));
        this.relayPos = relayPos;

        // Defaults: Top=3, North=1, South=2, West=5, East=6, Bottom=4
        faceChannels[Direction.DOWN.get3DDataValue()] = 4;
        faceChannels[Direction.UP.get3DDataValue()] = 3;
        faceChannels[Direction.NORTH.get3DDataValue()] = 1;
        faceChannels[Direction.SOUTH.get3DDataValue()] = 2;
        faceChannels[Direction.WEST.get3DDataValue()] = 5;
        faceChannels[Direction.EAST.get3DDataValue()] = 6;

        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockEntity(relayPos) instanceof RedstoneRelayBlockEntity relay) {
            int[] savedChannels = relay.getFaceChannels();
            boolean[] savedBundled = relay.getBundledFaces();
            for (int i = 0; i < 6; i++) {
                faceChannels[i] = savedChannels[i];
                bundledFaces[i] = savedBundled[i];
            }
        }
    }

    @Override
    protected void init() {
        guiLeft = (width - GUI_WIDTH) / 2;
        guiTop = (height - GUI_HEIGHT) / 2;

        int contentX = guiLeft + 14;

        // Cube-cross face picker on the LEFT half (buttons 64 wide, 20 tall, no overlap with summary text)
        int cubeX = contentX;           // left column origin
        int cubeY = guiTop + 44;        // below title area
        int bw = 64;                    // button width
        int bh = 20;                    // button height
        int gap = 2;

        faceButtons = new Button[6];
        // Up (top of cross)
        faceButtons[Direction.UP.get3DDataValue()] = addRenderableWidget(
                makeFaceButton(Direction.UP, cubeX + (bw + gap), cubeY, bw, bh));
        // West / North / East (middle row). North sits in the center.
        faceButtons[Direction.WEST.get3DDataValue()] = addRenderableWidget(
                makeFaceButton(Direction.WEST, cubeX, cubeY + (bh + gap), bw, bh));
        faceButtons[Direction.NORTH.get3DDataValue()] = addRenderableWidget(
                makeFaceButton(Direction.NORTH, cubeX + (bw + gap), cubeY + (bh + gap), bw, bh));
        faceButtons[Direction.EAST.get3DDataValue()] = addRenderableWidget(
                makeFaceButton(Direction.EAST, cubeX + 2 * (bw + gap), cubeY + (bh + gap), bw, bh));
        // South (below center)
        faceButtons[Direction.SOUTH.get3DDataValue()] = addRenderableWidget(
                makeFaceButton(Direction.SOUTH, cubeX + (bw + gap), cubeY + 2 * (bh + gap), bw, bh));
        // Down (bottom of cross)
        faceButtons[Direction.DOWN.get3DDataValue()] = addRenderableWidget(
                makeFaceButton(Direction.DOWN, cubeX + (bw + gap), cubeY + 3 * (bh + gap), bw, bh));

        // Channel + bundled controls below the cross
        int controlY = cubeY + 4 * (bh + gap) + 14;
        channelField = new EditBox(font, contentX + 70, controlY, 60, 18, Component.literal("Channel"));
        channelField.setMaxLength(3);
        addRenderableWidget(channelField);

        bundledButton = addRenderableWidget(Button.builder(Component.literal("Bundled: OFF"), b -> toggleBundled())
                .bounds(contentX + 140, controlY, 90, 18)
                .build());

        // Apply / Done at the very bottom
        int bottomY = guiTop + GUI_HEIGHT - 30;
        int halfW = (GUI_WIDTH - 28 - 8) / 2;
        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> applyConfig())
                .bounds(contentX, bottomY, halfW, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(contentX + halfW + 8, bottomY, halfW, 20)
                .build());

        refreshSelectedFaceWidgets();
    }

    private Button makeFaceButton(Direction side, int x, int y, int w, int h) {
        return Button.builder(Component.literal(faceButtonText(side)), b -> selectFace(side))
                .bounds(x, y, w, h)
                .build();
    }

    private void selectFace(Direction side) {
        selectedSide = side.get3DDataValue();
        refreshSelectedFaceWidgets();
    }

    private void toggleBundled() {
        bundledFaces[selectedSide] = !bundledFaces[selectedSide];
        refreshSelectedFaceWidgets();
    }

    private void refreshSelectedFaceWidgets() {
        if (channelField != null) {
            channelField.setValue(String.valueOf(faceChannels[selectedSide]));
        }
        if (bundledButton != null) {
            bundledButton.setMessage(Component.literal(bundledFaces[selectedSide] ? "Bundled: ON" : "Bundled: OFF"));
        }
        if (faceButtons != null) {
            for (Direction side : Direction.values()) {
                int idx = side.get3DDataValue();
                if (faceButtons[idx] != null) {
                    faceButtons[idx].setMessage(Component.literal(faceButtonText(side)));
                }
            }
        }
    }

    private String faceButtonText(Direction side) {
        int idx = side.get3DDataValue();
        String prefix = (idx == selectedSide) ? "> " : "";
        String mode = bundledFaces[idx] ? "B" : "C";
        return prefix + shortFace(side) + " " + mode + ":" + faceChannels[idx];
    }

    private static String shortFace(Direction side) {
        return switch (side) {
            case UP -> "U";
            case DOWN -> "D";
            case NORTH -> "N";
            case SOUTH -> "S";
            case WEST -> "W";
            case EAST -> "E";
        };
    }

    private void applyConfig() {
        try {
            int selectedChannel = Integer.parseInt(channelField.getValue());
            faceChannels[selectedSide] = Math.max(0, Math.min(256, selectedChannel));
        } catch (NumberFormatException ignored) {}

        PacketDistributor.sendToServer(new RelayConfigPayload(relayPos, faceChannels.clone(), bundledFaces.clone()));
        refreshSelectedFaceWidgets();
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0xFF101018);

        int x = guiLeft;
        int y = guiTop;

        gui.fill(x, y, x + GUI_WIDTH, y + GUI_HEIGHT, 0xFF2A2A3C);
        gui.fill(x, y, x + GUI_WIDTH, y + 1, 0xFF8090B0);
        gui.fill(x, y, x + 1, y + GUI_HEIGHT, 0xFF8090B0);
        gui.fill(x + GUI_WIDTH - 1, y, x + GUI_WIDTH, y + GUI_HEIGHT, 0xFF101020);
        gui.fill(x, y + GUI_HEIGHT - 1, x + GUI_WIDTH, y + GUI_HEIGHT, 0xFF101020);

        // Header
        gui.drawString(font, "Redstone Relay", x + 14, y + 10, 0xFFE6ECFF, true);
        gui.drawString(font, "Per-face channel & bundled mode", x + 14, y + 22, 0xFF99AACC, false);

        // Right-side summary column (doesn't overlap cube-cross on the left).
        int sx = x + 214;
        int sy = y + 44;
        gui.drawString(font, "Face  Ch  Mode", sx, sy, 0xFFAABBCC, true);
        sy += 14;
        Direction[] order = { Direction.UP, Direction.NORTH, Direction.SOUTH,
                               Direction.WEST, Direction.EAST, Direction.DOWN };
        for (Direction d : order) {
            int idx = d.get3DDataValue();
            String mode = bundledFaces[idx] ? "BUN" : "sig";
            int color = (idx == selectedSide) ? 0xFFFFE680 : 0xFFCCD3E0;
            gui.drawString(font,
                    String.format("%-6s %-3d %s", FACE_NAMES[idx], faceChannels[idx], mode),
                    sx, sy, color, false);
            sy += 12;
        }

        // Selected face label above the channel field (at bottom of cube cross)
        int controlY = y + 44 + 4 * (20 + 2) + 14;
        gui.drawString(font, "Selected: " + FACE_NAMES[selectedSide],
                x + 14, controlY - 12, 0xFFFFE680, false);
        gui.drawString(font, "Channel:", x + 14 + 8, controlY + 5, 0xFFCCD3E0, false);

        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // intentionally empty
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public void onClose() {
        applyConfig();
        super.onClose();
    }
}

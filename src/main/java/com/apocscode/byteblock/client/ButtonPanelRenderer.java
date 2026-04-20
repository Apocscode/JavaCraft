package com.apocscode.byteblock.client;

import com.apocscode.byteblock.block.ButtonPanelBlock;
import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;

/**
 * Block Entity Renderer for the Button Panel.
 * Renders a 4×4 grid of colored buttons on the front face.
 * Active buttons glow brightly; inactive buttons are dim.
 */
public class ButtonPanelRenderer implements BlockEntityRenderer<ButtonPanelBlockEntity> {

    private static final int FULLBRIGHT = 15728880;
    private static final int DIM_LIGHT = 0x00500050; // ambient-ish

    // Use white texture atlas sprite — we'll color the vertices directly
    private static final ResourceLocation WHITE_TEX =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    /** Dye color RGB values (Minecraft 16 dye colors in order) */
    private static final int[] BUTTON_COLORS = {
        0xF9FFFE, // 0  White
        0xF9801D, // 1  Orange
        0xC74EBD, // 2  Magenta
        0x3AB3DA, // 3  Light Blue
        0xFED83D, // 4  Yellow
        0x80C71F, // 5  Lime
        0xF38BAA, // 6  Pink
        0x474F52, // 7  Gray
        0x9D9D97, // 8  Light Gray
        0x169C9C, // 9  Cyan
        0x8932B8, // 10 Purple
        0x3C44AA, // 11 Blue
        0x835432, // 12 Brown
        0x5E7C16, // 13 Green
        0xB02E26, // 14 Red
        0x1D1D21  // 15 Black
    };

    // Grid layout constants (in block units 0-1)
    private static final float MARGIN = 0.0625f;    // 1/16 block padding from edges
    private static final float BUTTON_GAP = 0.02f;  // gap between buttons
    private static final float CELL_SIZE = (1.0f - 2 * MARGIN) / 4.0f;
    private static final float BUTTON_SIZE = CELL_SIZE - BUTTON_GAP;

    public ButtonPanelRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ButtonPanelBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) return;

        Direction facing = be.getBlockState().getValue(ButtonPanelBlock.FACING);
        int states = be.getButtonStates();

        pose.pushPose();

        // Rotate to face direction
        switch (facing) {
            case SOUTH -> {
                pose.translate(1, 0, 1);
                pose.mulPose(Axis.YP.rotationDegrees(180));
            }
            case WEST -> {
                pose.translate(0, 0, 1);
                pose.mulPose(Axis.YP.rotationDegrees(90));
            }
            case EAST -> {
                pose.translate(1, 0, 0);
                pose.mulPose(Axis.YP.rotationDegrees(-90));
            }
            default -> { /* NORTH: no rotation */ }
        }

        float z = -0.005f; // slightly in front of the model surface
        Matrix4f mat = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE_TEX));

        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int index = row * 4 + col;
                boolean lit = (states & (1 << index)) != 0;
                int color = BUTTON_COLORS[index];

                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;

                if (!lit) {
                    // Dim unlit buttons to ~30% brightness
                    r = r * 77 / 256;
                    g = g * 77 / 256;
                    b = b * 77 / 256;
                }

                int light = lit ? FULLBRIGHT : DIM_LIGHT;

                // Calculate button quad position
                float x0 = MARGIN + col * CELL_SIZE + BUTTON_GAP / 2;
                float x1 = x0 + BUTTON_SIZE;
                // Rows go top-to-bottom (row 0 = top)
                float y1 = 1.0f - MARGIN - row * CELL_SIZE - BUTTON_GAP / 2;
                float y0 = y1 - BUTTON_SIZE;

                // Draw quad (CCW winding facing -Z)
                vc.addVertex(mat, x0, y1, z).setColor(r, g, b, 255).setUv(0, 0)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                        .setNormal(pose.last(), 0, 0, -1);
                vc.addVertex(mat, x0, y0, z).setColor(r, g, b, 255).setUv(0, 1)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                        .setNormal(pose.last(), 0, 0, -1);
                vc.addVertex(mat, x1, y0, z).setColor(r, g, b, 255).setUv(1, 1)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                        .setNormal(pose.last(), 0, 0, -1);
                vc.addVertex(mat, x1, y1, z).setColor(r, g, b, 255).setUv(1, 0)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                        .setNormal(pose.last(), 0, 0, -1);
            }
        }

        pose.popPose();
    }

    @Override
    public int getViewDistance() {
        return 48;
    }
}

package com.apocscode.byteblock.client;

import com.apocscode.byteblock.block.ButtonPanelBlock;
import com.apocscode.byteblock.block.entity.ButtonPanelBlockEntity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
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
 * Renders a 4Ã—4 grid of colored buttons on a recessed front face.
 *
 * Render passes (sequential â€” one VertexConsumer at a time to avoid the "Not building!"
 * crash that occurs if two are interleaved):
 *   1. SOLID (cutout)  â€” cuboid sides + back of every button (real geometry, world-shaded).
 *                        OFF buttons also get their front cap here.
 *   2. EMISSIVE CAP    â€” front face of LIT buttons via RenderType.entityTranslucentEmissive,
 *                        which uses the vanilla emissive shader (texture color additively
 *                        blended on the framebuffer, ignoring lightmap â†’ real glow).
 *   3. LENS DETAIL     â€” small inner concentric square on every button (darker for OFF, white-hot
 *                        highlight for ON) so caps don't look plain/flat.
 *   4. SOFT HALO       â€” outer translucent emissive quad around lit buttons for color bleed.
 */
public class ButtonPanelRenderer implements BlockEntityRenderer<ButtonPanelBlockEntity> {

    private static final int FULLBRIGHT = 15728880;

    private static final ResourceLocation WHITE_TEX =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    /** Dye color RGB values (Minecraft 16 dye colors in order) */
    private static final int[] BUTTON_COLORS = {
        0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA,
        0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
        0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA,
        0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21
    };

    // Slab is inset 1px (1/16) on each face edge (see ButtonPanelBlock shapes). Buttons get an
    // additional 1px inner margin â†’ MARGIN = 2/16, leaving a 12/16 grid area for 4Ã—4 buttons.
    private static final float MARGIN = 0.125f;
    private static final float BUTTON_GAP = 0.015f;
    private static final float CELL_SIZE = (1.0f - 2 * MARGIN) / 4.0f;
    private static final float BUTTON_SIZE = CELL_SIZE - BUTTON_GAP;
    private static final float LENS_INSET = BUTTON_SIZE * 0.20f;

    private static final float FRONT_Z   = 13.0f / 16.0f;
    private static final float Z_RAISED  = FRONT_Z - 0.05f;
    private static final float Z_PRESSED = FRONT_Z - 0.005f;
    private static final float Z_LENS_OFFSET = -0.0008f;
    private static final float Z_HALO_OFFSET = -0.0015f;

    public ButtonPanelRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** Returns the effective button color: BE override if set, otherwise default wool color. */
    private static int colorFor(ButtonPanelBlockEntity be, int index) {
        int override = be.getButtonColor(index);
        return (override >= 0) ? override : BUTTON_COLORS[index];
    }

    @Override
    public void render(ButtonPanelBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) return;

        // In Fabulous! graphics mode, entityTranslucentEmissive composites AFTER the GUI.
        // Skip emissive passes while any screen is open so they don't bleed over the GUI.
        boolean screenOpen = Minecraft.getInstance().screen != null;

        Direction facing = be.getBlockState().getValue(ButtonPanelBlock.FACING);
        int states = be.getButtonStates();

        pose.pushPose();
        switch (facing) {
            case SOUTH -> { pose.translate(1, 0, 1); pose.mulPose(Axis.YP.rotationDegrees(180)); }
            case WEST  -> { pose.translate(0, 0, 1); pose.mulPose(Axis.YP.rotationDegrees(90)); }
            case EAST  -> { pose.translate(1, 0, 0); pose.mulPose(Axis.YP.rotationDegrees(-90)); }
            case UP    -> { pose.translate(0, 1, 0); pose.mulPose(Axis.XP.rotationDegrees(90)); }
            case DOWN  -> { pose.translate(0, 0, 1); pose.mulPose(Axis.XP.rotationDegrees(-90)); }
            default    -> { /* NORTH */ }
        }
        Matrix4f mat = pose.last().pose();

        // ---------- PASS 1: Solid cuboid bodies ----------
        VertexConsumer vcSolid = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE_TEX));
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int index = row * 4 + col;
                boolean lit = (states & (1 << index)) != 0;
                int color = colorFor(be, index);
                int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;

                float x0 = MARGIN + col * CELL_SIZE + BUTTON_GAP / 2;
                float x1 = x0 + BUTTON_SIZE;
                float y1 = 1.0f - MARGIN - row * CELL_SIZE - BUTTON_GAP / 2;
                float y0 = y1 - BUTTON_SIZE;
                float zFront = lit ? Z_PRESSED : Z_RAISED;
                float zBack  = FRONT_Z;

                if (!lit) {
                    addQuad(vcSolid, mat, pose,
                            x0, y1, zFront, 0, 0,
                            x0, y0, zFront, 0, 1,
                            x1, y0, zFront, 1, 1,
                            x1, y1, zFront, 1, 0,
                            r, g, b, 255, packedLight, 0, 0, -1);
                }
                addQuad(vcSolid, mat, pose,
                        x0, y1, zBack,  0, 0,
                        x0, y1, zFront, 0, 1,
                        x1, y1, zFront, 1, 1,
                        x1, y1, zBack,  1, 0,
                        r, g, b, 255, packedLight, 0, 1, 0);
                addQuad(vcSolid, mat, pose,
                        x0, y0, zFront, 0, 0,
                        x0, y0, zBack,  0, 1,
                        x1, y0, zBack,  1, 1,
                        x1, y0, zFront, 1, 0,
                        r, g, b, 255, packedLight, 0, -1, 0);
                addQuad(vcSolid, mat, pose,
                        x0, y1, zBack,  0, 0,
                        x0, y0, zBack,  0, 1,
                        x0, y0, zFront, 1, 1,
                        x0, y1, zFront, 1, 0,
                        r, g, b, 255, packedLight, -1, 0, 0);
                addQuad(vcSolid, mat, pose,
                        x1, y1, zFront, 0, 0,
                        x1, y0, zFront, 0, 1,
                        x1, y0, zBack,  1, 1,
                        x1, y1, zBack,  1, 0,
                        r, g, b, 255, packedLight, 1, 0, 0);
            }
        }

        // ---------- PASS 2: Emissive caps for LIT buttons ----------
        if (states != 0 && !screenOpen) {
            VertexConsumer vcEmissive = buffers.getBuffer(RenderType.entityTranslucentEmissive(WHITE_TEX));
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
                    int index = row * 4 + col;
                    if ((states & (1 << index)) == 0) continue;
                    int color = colorFor(be, index);
                    int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
                    float x0 = MARGIN + col * CELL_SIZE + BUTTON_GAP / 2;
                    float x1 = x0 + BUTTON_SIZE;
                    float y1 = 1.0f - MARGIN - row * CELL_SIZE - BUTTON_GAP / 2;
                    float y0 = y1 - BUTTON_SIZE;
                    addQuad(vcEmissive, mat, pose,
                            x0, y1, Z_PRESSED, 0, 0,
                            x0, y0, Z_PRESSED, 0, 1,
                            x1, y0, Z_PRESSED, 1, 1,
                            x1, y1, Z_PRESSED, 1, 0,
                            r, g, b, 255, FULLBRIGHT, 0, 0, -1);
                }
            }
        }

        // ---------- PASS 3: Inner lens detail (every button) ----------
        VertexConsumer vcLens = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE_TEX));
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int index = row * 4 + col;
                boolean lit = (states & (1 << index)) != 0;
                int color = colorFor(be, index);

                float x0 = MARGIN + col * CELL_SIZE + BUTTON_GAP / 2 + LENS_INSET;
                float x1 = MARGIN + col * CELL_SIZE + BUTTON_GAP / 2 + BUTTON_SIZE - LENS_INSET;
                float y1 = 1.0f - MARGIN - row * CELL_SIZE - BUTTON_GAP / 2 - LENS_INSET;
                float y0 = 1.0f - MARGIN - row * CELL_SIZE - BUTTON_GAP / 2 - BUTTON_SIZE + LENS_INSET;
                float zLens = (lit ? Z_PRESSED : Z_RAISED) + Z_LENS_OFFSET;

                int lr, lg, lb, llight;
                if (lit) {
                    int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
                    lr = Math.min(255, r + 120);
                    lg = Math.min(255, g + 120);
                    lb = Math.min(255, b + 120);
                    llight = FULLBRIGHT;
                } else {
                    lr = (int) (((color >> 16) & 0xFF) * 0.35f);
                    lg = (int) (((color >> 8) & 0xFF) * 0.35f);
                    lb = (int) ((color & 0xFF) * 0.35f);
                    llight = packedLight;
                }
                addQuad(vcLens, mat, pose,
                        x0, y1, zLens, 0, 0,
                        x0, y0, zLens, 0, 1,
                        x1, y0, zLens, 1, 1,
                        x1, y1, zLens, 1, 0,
                        lr, lg, lb, 255, llight, 0, 0, -1);
            }
        }

        // ---------- PASS 4: Soft outer halo for LIT buttons ----------
        if (states != 0 && !screenOpen) {
            VertexConsumer vcHalo = buffers.getBuffer(RenderType.entityTranslucentEmissive(WHITE_TEX));
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
                    int index = row * 4 + col;
                    if ((states & (1 << index)) == 0) continue;
                    int color = colorFor(be, index);
                    int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;

                    float bx0 = MARGIN + col * CELL_SIZE + BUTTON_GAP / 2;
                    float bx1 = bx0 + BUTTON_SIZE;
                    float by1 = 1.0f - MARGIN - row * CELL_SIZE - BUTTON_GAP / 2;
                    float by0 = by1 - BUTTON_SIZE;
                    float pad = BUTTON_SIZE * 0.35f;
                    float hx0 = bx0 - pad, hx1 = bx1 + pad;
                    float hy0 = by0 - pad, hy1 = by1 + pad;
                    float zHalo = Z_PRESSED + Z_HALO_OFFSET;

                    addQuad(vcHalo, mat, pose,
                            hx0, hy1, zHalo, 0, 0,
                            hx0, hy0, zHalo, 0, 1,
                            hx1, hy0, zHalo, 1, 1,
                            hx1, hy1, zHalo, 1, 0,
                            r, g, b, 110, FULLBRIGHT, 0, 0, -1);
                }
            }
        }

        pose.popPose();
    }

    private static void addQuad(VertexConsumer vc, Matrix4f mat, PoseStack pose,
                                float x0, float y0, float z0, float u0, float v0,
                                float x1, float y1, float z1, float u1, float v1,
                                float x2, float y2, float z2, float u2, float v2,
                                float x3, float y3, float z3, float u3, float v3,
                                int r, int g, int b, int a, int light,
                                float nx, float ny, float nz) {
        vc.addVertex(mat, x0, y0, z0).setColor(r, g, b, a).setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose.last(), nx, ny, nz);
        vc.addVertex(mat, x1, y1, z1).setColor(r, g, b, a).setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose.last(), nx, ny, nz);
        vc.addVertex(mat, x2, y2, z2).setColor(r, g, b, a).setUv(u2, v2)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose.last(), nx, ny, nz);
        vc.addVertex(mat, x3, y3, z3).setColor(r, g, b, a).setUv(u3, v3)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose.last(), nx, ny, nz);
    }

    @Override
    public int getViewDistance() {
        return 48;
    }
}

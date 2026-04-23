package com.apocscode.byteblock.client;

import com.apocscode.byteblock.block.RedstoneRelayBlock;
import com.apocscode.byteblock.block.entity.RedstoneRelayBlockEntity;

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
 * Block Entity Renderer for the Redstone Relay.
 * Overlays emissive colored LEDs on each of the 6 port plates defined in
 * the block model, with color based on each face's channel and brightness
 * based on the face's current signal strength. Pulses the antenna tip when
 * the relay is connected to a network.
 */
public class RedstoneRelayRenderer implements BlockEntityRenderer<RedstoneRelayBlockEntity> {

    private static final int FULLBRIGHT = 15728880;
    private static final ResourceLocation WHITE_TEX =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    /** Channel color palette (8 distinct hues, cycled with offset for higher channels). */
    private static final int[] CHANNEL_COLORS = {
        0xFF3030, // 1  red
        0xFF8A00, // 2  orange
        0xFFD700, // 3  yellow
        0x37E045, // 4  green
        0x00D8E0, // 5  cyan
        0x2080FF, // 6  blue
        0xB040FF, // 7  purple
        0xFF50C8  // 8  pink
    };

    public RedstoneRelayRenderer(BlockEntityRendererProvider.Context context) {}

    private static int colorForChannel(int channel) {
        if (channel <= 0) return 0x404040;
        int idx = (channel - 1) % CHANNEL_COLORS.length;
        return CHANNEL_COLORS[idx];
    }

    @Override
    public void render(RedstoneRelayBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) return;

        boolean screenOpen = Minecraft.getInstance().screen != null;

        Direction facing = be.getBlockState().getValue(RedstoneRelayBlock.FACING);

        pose.pushPose();
        switch (facing) {
            case SOUTH -> { pose.translate(1, 0, 1); pose.mulPose(Axis.YP.rotationDegrees(180)); }
            case WEST  -> { pose.translate(0, 0, 1); pose.mulPose(Axis.YP.rotationDegrees(90)); }
            case EAST  -> { pose.translate(1, 0, 0); pose.mulPose(Axis.YP.rotationDegrees(-90)); }
            default    -> { /* NORTH */ }
        }
        Matrix4f mat = pose.last().pose();

        // Map model-local face to the world Direction used by the BE's per-side arrays.
        Direction worldForFront = facing;
        Direction worldForBack  = facing.getOpposite();
        Direction worldForRight = facing.getClockWise();
        Direction worldForLeft  = facing.getCounterClockWise();

        // Port definitions: (face, worldDirection, x0,y0,z0, x1,y1,z1)
        // x0/y0/z0 == x1/y1/z1 on the axis perpendicular to the face (defines its plane).
        PortDef[] ports = new PortDef[] {
            // FRONT (model-north)
            new PortDef(Face.NORTH, worldForFront,  0.375f, 0.219f, -0.006f, 0.625f, 0.4375f, -0.006f),
            // BACK (model-south)
            new PortDef(Face.SOUTH, worldForBack,   0.375f, 0.375f,  1.006f, 0.625f, 0.625f,   1.006f),
            // EAST
            new PortDef(Face.EAST,  worldForRight,  1.006f, 0.375f,  0.375f, 1.006f, 0.625f,   0.625f),
            // WEST
            new PortDef(Face.WEST,  worldForLeft,  -0.006f, 0.375f,  0.375f,-0.006f, 0.625f,   0.625f),
            // UP
            new PortDef(Face.UP,    Direction.UP,   0.25f,  1.006f,  0.375f, 0.4375f,1.006f,   0.625f),
            // DOWN
            new PortDef(Face.DOWN,  Direction.DOWN, 0.375f,-0.006f,  0.375f, 0.625f,-0.006f,   0.625f)
        };

        // -------- PASS 1: solid (dim) LED base on every port --------
        VertexConsumer vcSolid = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE_TEX));
        for (PortDef p : ports) {
            drawPortSolid(be, p, vcSolid, mat, pose, packedLight);
        }

        // -------- PASS 2: emissive glow for lit ports + antenna tip --------
        if (!screenOpen) {
            VertexConsumer vcEmissive = buffers.getBuffer(RenderType.entityTranslucentEmissive(WHITE_TEX));
            for (PortDef p : ports) {
                drawPortEmissive(be, p, vcEmissive, mat, pose);
            }
            if (be.isConnected()) {
                drawAntennaGlow(vcEmissive, mat, pose);
            }
        }

        pose.popPose();
    }

    private record PortDef(Face face, Direction worldSide,
                           float x0, float y0, float z0,
                           float x1, float y1, float z1) {}

    private enum Face { NORTH, SOUTH, EAST, WEST, UP, DOWN }

    private static void drawPortSolid(RedstoneRelayBlockEntity be, PortDef p,
                                      VertexConsumer vc, Matrix4f mat, PoseStack pose,
                                      int packedLight) {
        int sideIdx = p.worldSide.get3DDataValue();
        int channel = be.getFaceChannel(sideIdx);
        int level = Math.max(be.getOutput(sideIdx), be.getInput(sideIdx));

        int color = colorForChannel(channel);
        int baseR = (color >> 16) & 0xFF;
        int baseG = (color >> 8) & 0xFF;
        int baseB = color & 0xFF;

        float cx = (p.x0 + p.x1) * 0.5f;
        float cy = (p.y0 + p.y1) * 0.5f;
        float cz = (p.z0 + p.z1) * 0.5f;

        float sx0 = lerp(cx, p.x0, 0.6f), sx1 = lerp(cx, p.x1, 0.6f);
        float sy0 = lerp(cy, p.y0, 0.6f), sy1 = lerp(cy, p.y1, 0.6f);
        float sz0 = lerp(cz, p.z0, 0.6f), sz1 = lerp(cz, p.z1, 0.6f);

        float t = level / 15.0f;
        float dim = 0.25f + 0.35f * t;
        int dr = (int)(baseR * dim);
        int dg = (int)(baseG * dim);
        int db = (int)(baseB * dim);

        drawFaceQuad(vc, mat, pose, sx0, sy0, sz0, sx1, sy1, sz1, p.face,
                dr, dg, db, 255, packedLight);
    }

    private static void drawPortEmissive(RedstoneRelayBlockEntity be, PortDef p,
                                         VertexConsumer vc, Matrix4f mat, PoseStack pose) {
        int sideIdx = p.worldSide.get3DDataValue();
        int channel = be.getFaceChannel(sideIdx);
        int level = Math.max(be.getOutput(sideIdx), be.getInput(sideIdx));
        if (level <= 0) return;

        int color = colorForChannel(channel);
        int baseR = (color >> 16) & 0xFF;
        int baseG = (color >> 8) & 0xFF;
        int baseB = color & 0xFF;

        float cx = (p.x0 + p.x1) * 0.5f;
        float cy = (p.y0 + p.y1) * 0.5f;
        float cz = (p.z0 + p.z1) * 0.5f;
        float t = level / 15.0f;

        int er = Math.min(255, (int)(baseR * (0.6f + 0.5f * t)));
        int eg = Math.min(255, (int)(baseG * (0.6f + 0.5f * t)));
        int eb = Math.min(255, (int)(baseB * (0.6f + 0.5f * t)));
        int alpha = Math.min(255, 120 + (int)(120 * t));

        float hx0 = lerp(cx, p.x0, 0.9f), hx1 = lerp(cx, p.x1, 0.9f);
        float hy0 = lerp(cy, p.y0, 0.9f), hy1 = lerp(cy, p.y1, 0.9f);
        float hz0 = lerp(cz, p.z0, 0.9f), hz1 = lerp(cz, p.z1, 0.9f);

        drawFaceQuad(vc, mat, pose, hx0, hy0, hz0, hx1, hy1, hz1, p.face,
                er, eg, eb, alpha, FULLBRIGHT);
    }

    private static void drawAntennaGlow(VertexConsumer vc, Matrix4f mat, PoseStack pose) {
        long t = System.currentTimeMillis();
        float pulse = 0.8f + 0.2f * (float) Math.sin(t / 220.0);
        int r = (int) (255 * pulse);
        int g = (int) (60 * pulse);
        int b = (int) (60 * pulse);
        float tx0 = 10f / 16f, tx1 = 11f / 16f;
        float ty0 = 19.5f / 16f, ty1 = 20.5f / 16f;
        float tz0 = 8f / 16f, tz1 = 9f / 16f;
        float eps = 0.004f;
        // North face (-z)
        addQuad(vc, mat, pose,
                tx0 - eps, ty1 + eps, tz0 - eps, 0, 0,
                tx0 - eps, ty0 - eps, tz0 - eps, 0, 1,
                tx1 + eps, ty0 - eps, tz0 - eps, 1, 1,
                tx1 + eps, ty1 + eps, tz0 - eps, 1, 0,
                r, g, b, 220, FULLBRIGHT, 0, 0, -1);
        // South face (+z)
        addQuad(vc, mat, pose,
                tx1 + eps, ty1 + eps, tz1 + eps, 0, 0,
                tx1 + eps, ty0 - eps, tz1 + eps, 0, 1,
                tx0 - eps, ty0 - eps, tz1 + eps, 1, 1,
                tx0 - eps, ty1 + eps, tz1 + eps, 1, 0,
                r, g, b, 220, FULLBRIGHT, 0, 0, 1);
        // East face (+x)
        addQuad(vc, mat, pose,
                tx1 + eps, ty1 + eps, tz0 - eps, 0, 0,
                tx1 + eps, ty0 - eps, tz0 - eps, 0, 1,
                tx1 + eps, ty0 - eps, tz1 + eps, 1, 1,
                tx1 + eps, ty1 + eps, tz1 + eps, 1, 0,
                r, g, b, 220, FULLBRIGHT, 1, 0, 0);
        // West face (-x)
        addQuad(vc, mat, pose,
                tx0 - eps, ty1 + eps, tz1 + eps, 0, 0,
                tx0 - eps, ty0 - eps, tz1 + eps, 0, 1,
                tx0 - eps, ty0 - eps, tz0 - eps, 1, 1,
                tx0 - eps, ty1 + eps, tz0 - eps, 1, 0,
                r, g, b, 220, FULLBRIGHT, -1, 0, 0);
        // Top face (+y)
        addQuad(vc, mat, pose,
                tx0 - eps, ty1 + eps, tz0 - eps, 0, 0,
                tx0 - eps, ty1 + eps, tz1 + eps, 0, 1,
                tx1 + eps, ty1 + eps, tz1 + eps, 1, 1,
                tx1 + eps, ty1 + eps, tz0 - eps, 1, 0,
                r, g, b, 220, FULLBRIGHT, 0, 1, 0);
    }

    private static float lerp(float center, float edge, float scale) {
        return center + (edge - center) * scale;
    }

    /** Draws an axis-aligned quad on the given face, using outward-facing winding. */
    private static void drawFaceQuad(VertexConsumer vc, Matrix4f mat, PoseStack pose,
                                     float x0, float y0, float z0,
                                     float x1, float y1, float z1,
                                     Face face,
                                     int r, int g, int b, int a, int light) {
        if (vc == null) return;
        switch (face) {
            case NORTH -> addQuad(vc, mat, pose,
                    x0, y1, z0, 0, 0,
                    x0, y0, z0, 0, 1,
                    x1, y0, z0, 1, 1,
                    x1, y1, z0, 1, 0,
                    r, g, b, a, light, 0, 0, -1);
            case SOUTH -> addQuad(vc, mat, pose,
                    x1, y1, z1, 0, 0,
                    x1, y0, z1, 0, 1,
                    x0, y0, z1, 1, 1,
                    x0, y1, z1, 1, 0,
                    r, g, b, a, light, 0, 0, 1);
            case EAST -> addQuad(vc, mat, pose,
                    x1, y1, z0, 0, 0,
                    x1, y0, z0, 0, 1,
                    x1, y0, z1, 1, 1,
                    x1, y1, z1, 1, 0,
                    r, g, b, a, light, 1, 0, 0);
            case WEST -> addQuad(vc, mat, pose,
                    x0, y1, z1, 0, 0,
                    x0, y0, z1, 0, 1,
                    x0, y0, z0, 1, 1,
                    x0, y1, z0, 1, 0,
                    r, g, b, a, light, -1, 0, 0);
            case UP -> addQuad(vc, mat, pose,
                    x0, y1, z0, 0, 0,
                    x0, y1, z1, 0, 1,
                    x1, y1, z1, 1, 1,
                    x1, y1, z0, 1, 0,
                    r, g, b, a, light, 0, 1, 0);
            case DOWN -> addQuad(vc, mat, pose,
                    x0, y0, z1, 0, 0,
                    x0, y0, z0, 0, 1,
                    x1, y0, z0, 1, 1,
                    x1, y0, z1, 1, 0,
                    r, g, b, a, light, 0, -1, 0);
        }
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
    public boolean shouldRenderOffScreen(RedstoneRelayBlockEntity be) {
        return false;
    }

    @Override
    public int getViewDistance() {
        return 64;
    }
}

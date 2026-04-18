package com.apocscode.byteblock.client;

import com.apocscode.byteblock.entity.RobotEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;

/**
 * Block-sized robot renderer — tank tracks, boxy body, arms, head with face.
 * All geometry is hand-built quads using vanilla iron_block texture for shading.
 */
public class RobotRenderer extends EntityRenderer<RobotEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/iron_block.png");

    public RobotRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.5f;
    }

    @Override
    public void render(RobotEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        pose.pushPose();

        // Rotate to face robot direction
        float rotation = switch (entity.getRobotFacing()) {
            case SOUTH -> 180f;
            case WEST -> 90f;
            case EAST -> -90f;
            default -> 0f;
        };
        pose.mulPose(Axis.YP.rotationDegrees(rotation + 180f));

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        PoseStack.Pose last = pose.last();
        Matrix4f mat = last.pose();

        // === TANK TRACKS (bottom) ===
        // Left track pod
        drawBox(vc, mat, last, -0.45f, 0f, -0.4f, -0.25f, 0.2f, 0.4f,
                50, 50, 55, packedLight);
        // Right track pod
        drawBox(vc, mat, last, 0.25f, 0f, -0.4f, 0.45f, 0.2f, 0.4f,
                50, 50, 55, packedLight);
        // Tread strips (darker)
        drawBox(vc, mat, last, -0.46f, 0f, -0.4f, -0.24f, 0.03f, 0.4f,
                30, 30, 30, packedLight);
        drawBox(vc, mat, last, 0.24f, 0f, -0.4f, 0.46f, 0.03f, 0.4f,
                30, 30, 30, packedLight);
        // Axle between tracks
        drawBox(vc, mat, last, -0.25f, 0.05f, -0.1f, 0.25f, 0.15f, 0.1f,
                40, 40, 45, packedLight);

        // === BODY ===
        drawBox(vc, mat, last, -0.35f, 0.2f, -0.3f, 0.35f, 0.65f, 0.3f,
                100, 100, 110, packedLight);
        // Front chest plate
        drawBox(vc, mat, last, -0.25f, 0.3f, -0.31f, 0.25f, 0.58f, -0.30f,
                80, 85, 95, packedLight);
        // Green power LED
        drawBox(vc, mat, last, -0.05f, 0.48f, -0.32f, 0.05f, 0.55f, -0.31f,
                30, 200, 50, packedLight);
        // Rear vent
        drawBox(vc, mat, last, -0.2f, 0.35f, 0.30f, 0.2f, 0.55f, 0.31f,
                60, 60, 65, packedLight);

        // === LEFT ARM ===
        drawBox(vc, mat, last, -0.5f, 0.48f, -0.1f, -0.35f, 0.63f, 0.1f,
                85, 85, 90, packedLight); // shoulder
        drawBox(vc, mat, last, -0.5f, 0.25f, -0.08f, -0.38f, 0.48f, 0.08f,
                90, 90, 100, packedLight); // upper arm
        drawBox(vc, mat, last, -0.48f, 0.15f, -0.06f, -0.40f, 0.25f, 0.06f,
                110, 110, 120, packedLight); // forearm
        // Gripper fingers
        drawBox(vc, mat, last, -0.49f, 0.10f, -0.05f, -0.46f, 0.15f, 0.0f,
                70, 70, 75, packedLight);
        drawBox(vc, mat, last, -0.42f, 0.10f, 0.0f, -0.39f, 0.15f, 0.05f,
                70, 70, 75, packedLight);

        // === RIGHT ARM ===
        drawBox(vc, mat, last, 0.35f, 0.48f, -0.1f, 0.5f, 0.63f, 0.1f,
                85, 85, 90, packedLight);
        drawBox(vc, mat, last, 0.38f, 0.25f, -0.08f, 0.5f, 0.48f, 0.08f,
                90, 90, 100, packedLight);
        drawBox(vc, mat, last, 0.40f, 0.15f, -0.06f, 0.48f, 0.25f, 0.06f,
                110, 110, 120, packedLight);
        drawBox(vc, mat, last, 0.46f, 0.10f, -0.05f, 0.49f, 0.15f, 0.0f,
                70, 70, 75, packedLight);
        drawBox(vc, mat, last, 0.39f, 0.10f, 0.0f, 0.42f, 0.15f, 0.05f,
                70, 70, 75, packedLight);

        // === NECK ===
        drawBox(vc, mat, last, -0.08f, 0.65f, -0.08f, 0.08f, 0.72f, 0.08f,
                75, 75, 80, packedLight);

        // === HEAD ===
        drawBox(vc, mat, last, -0.22f, 0.72f, -0.2f, 0.22f, 1.0f, 0.2f,
                115, 115, 125, packedLight);

        // === FACE ===
        // Cyan eyes
        drawBox(vc, mat, last, -0.15f, 0.82f, -0.21f, -0.06f, 0.92f, -0.20f,
                40, 220, 255, packedLight);
        drawBox(vc, mat, last, 0.06f, 0.82f, -0.21f, 0.15f, 0.92f, -0.20f,
                40, 220, 255, packedLight);
        // Mouth grille
        drawBox(vc, mat, last, -0.12f, 0.75f, -0.21f, 0.12f, 0.79f, -0.20f,
                50, 50, 55, packedLight);

        // === ANTENNA ===
        drawBox(vc, mat, last, -0.02f, 1.0f, -0.02f, 0.02f, 1.12f, 0.02f,
                80, 80, 85, packedLight);
        // Red LED tip
        drawBox(vc, mat, last, -0.03f, 1.12f, -0.03f, 0.03f, 1.15f, 0.03f,
                220, 30, 30, packedLight);

        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    /** Draw an axis-aligned box with 6 shaded faces. */
    private static void drawBox(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 int r, int g, int b, int light) {
        int a = 255, ds = 20;
        // Bottom
        quad(vc, mat, last, x0,y0,z1, x1,y0,z1, x1,y0,z0, x0,y0,z0,
             0,-1,0, light, r-ds*2, g-ds*2, b-ds*2, a);
        // Top
        quad(vc, mat, last, x0,y1,z0, x1,y1,z0, x1,y1,z1, x0,y1,z1,
             0,1,0, light, r, g, b, a);
        // Front (z-)
        quad(vc, mat, last, x0,y1,z0, x0,y0,z0, x1,y0,z0, x1,y1,z0,
             0,0,-1, light, r-ds, g-ds, b-ds, a);
        // Back (z+)
        quad(vc, mat, last, x1,y1,z1, x1,y0,z1, x0,y0,z1, x0,y1,z1,
             0,0,1, light, r-ds, g-ds, b-ds, a);
        // Left (x-)
        quad(vc, mat, last, x0,y1,z1, x0,y0,z1, x0,y0,z0, x0,y1,z0,
             -1,0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
        // Right (x+)
        quad(vc, mat, last, x1,y1,z0, x1,y0,z0, x1,y0,z1, x1,y1,z1,
             1,0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
    }

    private static void quad(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              float x2, float y2, float z2,
                              float x3, float y3, float z3,
                              float nx, float ny, float nz,
                              int light, int r, int g, int b, int a) {
        r = Math.max(0, Math.min(255, r)); g = Math.max(0, Math.min(255, g)); b = Math.max(0, Math.min(255, b));
        vc.addVertex(mat, x0, y0, z0).setColor(r,g,b,a).setUv(0,0)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, nx, ny, nz);
        vc.addVertex(mat, x1, y1, z1).setColor(r,g,b,a).setUv(0,1)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, nx, ny, nz);
        vc.addVertex(mat, x2, y2, z2).setColor(r,g,b,a).setUv(1,1)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, nx, ny, nz);
        vc.addVertex(mat, x3, y3, z3).setColor(r,g,b,a).setUv(1,0)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, nx, ny, nz);
    }

    @Override
    public ResourceLocation getTextureLocation(RobotEntity entity) {
        return TEXTURE;
    }
}

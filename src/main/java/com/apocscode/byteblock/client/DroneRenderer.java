package com.apocscode.byteblock.client;

import com.apocscode.byteblock.entity.DroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;

/**
 * White quad-copter drone renderer — central body, 4 arms, 4 rotor discs, landing skids.
 */
public class DroneRenderer extends EntityRenderer<DroneEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("byteblock", "textures/block/terminal.png");

    public DroneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.3f;
    }

    @Override
    public void render(DroneEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        // Low-fuel visual alert — spawn smoke particles below the drone (~20 TPS rate-limited)
        if (entity.level() != null && entity.getFuel() > 0 && entity.getFuel() < 400
                && entity.tickCount % 4 == 0) {
            double dx = (entity.getRandom().nextDouble() - 0.5) * 0.3;
            double dz = (entity.getRandom().nextDouble() - 0.5) * 0.3;
            entity.level().addParticle(ParticleTypes.SMOKE,
                    entity.getX() + dx, entity.getY() - 0.1, entity.getZ() + dz,
                    0.0, -0.02, 0.0);
        }
        // Defender mode — angry red particles around the drone while armed
        if (entity.level() != null && entity.isDefender() && entity.tickCount % 10 == 0) {
            double dx = (entity.getRandom().nextDouble() - 0.5) * 0.8;
            double dy = entity.getRandom().nextDouble() * 0.4;
            double dz = (entity.getRandom().nextDouble() - 0.5) * 0.8;
            entity.level().addParticle(ParticleTypes.ANGRY_VILLAGER,
                    entity.getX() + dx, entity.getY() + 0.3 + dy, entity.getZ() + dz,
                    0.0, 0.0, 0.0);
        }

        pose.pushPose();

        // Gentle hover bob
        float bob = (float) Math.sin((entity.tickCount + partialTick) * 0.1) * 0.04f;
        pose.translate(0.0, bob, 0.0);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        PoseStack.Pose last = pose.last();
        Matrix4f mat = last.pose();

        // White body colors
        int bw = 230, bg = 230, bb = 235; // body white
        int dw = 180, dg = 180, db = 185; // accent gray
        int rw = 60, rg = 60, rb = 65;    // rotor dark

        // === CENTRAL BODY ===
        // Main hull — rounded-ish square
        drawBox(vc, mat, last, -0.12f, 0.08f, -0.12f, 0.12f, 0.18f, 0.12f,
                bw, bg, bb, packedLight);
        // Top dome / camera bump
        drawBox(vc, mat, last, -0.06f, 0.18f, -0.06f, 0.06f, 0.22f, 0.06f,
                dw, dg, db, packedLight);
        // Bottom sensor / camera
        drawBox(vc, mat, last, -0.04f, 0.05f, -0.04f, 0.04f, 0.08f, 0.04f,
                40, 40, 45, packedLight);
        // Camera lens (front, blue dot)
        drawBox(vc, mat, last, -0.02f, 0.06f, -0.05f, 0.02f, 0.08f, -0.04f,
                30, 120, 220, packedLight);

        // Front direction indicator — small green stripe
        drawBox(vc, mat, last, -0.08f, 0.17f, -0.13f, 0.08f, 0.19f, -0.12f,
                30, 200, 50, packedLight);
        // Rear indicator — red stripe
        drawBox(vc, mat, last, -0.08f, 0.17f, 0.12f, 0.08f, 0.19f, 0.13f,
                220, 40, 40, packedLight);

        // === FOUR ARMS extending diagonally ===
        // Front-Left arm
        drawArm(vc, mat, last, -0.12f, 0.12f, -0.12f, -0.35f, 0.12f, -0.35f,
                bw - 15, bg - 15, bb - 10, packedLight);
        // Front-Right arm
        drawArm(vc, mat, last, 0.12f, 0.12f, -0.12f, 0.35f, 0.12f, -0.35f,
                bw - 15, bg - 15, bb - 10, packedLight);
        // Back-Left arm
        drawArm(vc, mat, last, -0.12f, 0.12f, 0.12f, -0.35f, 0.12f, 0.35f,
                bw - 15, bg - 15, bb - 10, packedLight);
        // Back-Right arm
        drawArm(vc, mat, last, 0.12f, 0.12f, 0.12f, 0.35f, 0.12f, 0.35f,
                bw - 15, bg - 15, bb - 10, packedLight);

        // === FOUR ROTOR MOTORS (cylindrical hubs) ===
        drawBox(vc, mat, last, -0.39f, 0.12f, -0.39f, -0.31f, 0.17f, -0.31f,
                dw - 20, dg - 20, db - 15, packedLight); // FL
        drawBox(vc, mat, last, 0.31f, 0.12f, -0.39f, 0.39f, 0.17f, -0.31f,
                dw - 20, dg - 20, db - 15, packedLight); // FR
        drawBox(vc, mat, last, -0.39f, 0.12f, 0.31f, -0.31f, 0.17f, 0.39f,
                dw - 20, dg - 20, db - 15, packedLight); // BL
        drawBox(vc, mat, last, 0.31f, 0.12f, 0.31f, 0.39f, 0.17f, 0.39f,
                dw - 20, dg - 20, db - 15, packedLight); // BR

        // === SPINNING ROTOR DISCS (flat) ===
        float rotorSpin = ((entity.tickCount + partialTick) * 45f) % 360f;
        drawRotor(vc, mat, last, -0.35f, 0.175f, -0.35f, 0.12f, rotorSpin,
                rw, rg, rb, packedLight);
        drawRotor(vc, mat, last, 0.35f, 0.175f, -0.35f, 0.12f, -rotorSpin,
                rw, rg, rb, packedLight);
        drawRotor(vc, mat, last, -0.35f, 0.175f, 0.35f, 0.12f, -rotorSpin,
                rw, rg, rb, packedLight);
        drawRotor(vc, mat, last, 0.35f, 0.175f, 0.35f, 0.12f, rotorSpin,
                rw, rg, rb, packedLight);

        // === LANDING SKIDS ===
        // Two rails underneath
        drawBox(vc, mat, last, -0.2f, 0.0f, -0.15f, -0.16f, 0.05f, 0.15f,
                dw - 40, dg - 40, db - 35, packedLight);
        drawBox(vc, mat, last, 0.16f, 0.0f, -0.15f, 0.2f, 0.05f, 0.15f,
                dw - 40, dg - 40, db - 35, packedLight);
        // Vertical struts connecting skids to body
        drawBox(vc, mat, last, -0.18f, 0.05f, -0.08f, -0.16f, 0.10f, -0.04f,
                dw - 30, dg - 30, db - 25, packedLight);
        drawBox(vc, mat, last, -0.18f, 0.05f, 0.04f, -0.16f, 0.10f, 0.08f,
                dw - 30, dg - 30, db - 25, packedLight);
        drawBox(vc, mat, last, 0.16f, 0.05f, -0.08f, 0.18f, 0.10f, -0.04f,
                dw - 30, dg - 30, db - 25, packedLight);
        drawBox(vc, mat, last, 0.16f, 0.05f, 0.04f, 0.18f, 0.10f, 0.08f,
                dw - 30, dg - 30, db - 25, packedLight);

        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    /** Draw a thin arm connecting two points. */
    private static void drawArm(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1,
                                 int r, int g, int b, int light) {
        float hw = 0.025f, hh = 0.02f;
        // We draw a box from min to max along the diagonal
        float minX = Math.min(x0, x1) - hw, maxX = Math.max(x0, x1) + hw;
        float minZ = Math.min(z0, z1) - hw, maxZ = Math.max(z0, z1) + hw;
        drawBox(vc, mat, last, minX, y0 - hh, minZ, maxX, y0 + hh, maxZ,
                r, g, b, light);
    }

    /** Draw a flat rotor disc as a thin cross (two perpendicular blades). */
    private static void drawRotor(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                                   float cx, float cy, float cz, float radius, float angle,
                                   int r, int g, int b, int light) {
        float bw = 0.025f; // blade half-width
        float h = 0.005f;  // blade half-height
        // Convert angle to radians for blade tips
        double rad = Math.toRadians(angle);
        float cosA = (float) Math.cos(rad);
        float sinA = (float) Math.sin(rad);

        // Blade 1: along angle direction
        float bx0 = cx + cosA * radius, bz0 = cz + sinA * radius;
        float bx1 = cx - cosA * radius, bz1 = cz - sinA * radius;
        drawBox(vc, mat, last,
                Math.min(bx0, bx1) - bw, cy - h, Math.min(bz0, bz1) - bw,
                Math.max(bx0, bx1) + bw, cy + h, Math.max(bz0, bz1) + bw,
                r, g, b, light);

        // Blade 2: perpendicular
        float bx2 = cx + sinA * radius, bz2 = cz - cosA * radius;
        float bx3 = cx - sinA * radius, bz3 = cz + cosA * radius;
        drawBox(vc, mat, last,
                Math.min(bx2, bx3) - bw, cy - h, Math.min(bz2, bz3) - bw,
                Math.max(bx2, bx3) + bw, cy + h, Math.max(bz2, bz3) + bw,
                r, g, b, light);
    }

    /** Draw an axis-aligned box with 6 shaded faces. */
    private static void drawBox(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 int r, int g, int b, int light) {
        int a = 255, ds = 15;
        quad(vc, mat, last, x0,y0,z1, x1,y0,z1, x1,y0,z0, x0,y0,z0,
             0,-1,0, light, r-ds*2, g-ds*2, b-ds*2, a);
        quad(vc, mat, last, x0,y1,z0, x1,y1,z0, x1,y1,z1, x0,y1,z1,
             0,1,0, light, r, g, b, a);
        quad(vc, mat, last, x0,y1,z0, x0,y0,z0, x1,y0,z0, x1,y1,z0,
             0,0,-1, light, r-ds, g-ds, b-ds, a);
        quad(vc, mat, last, x1,y1,z1, x1,y0,z1, x0,y0,z1, x0,y1,z1,
             0,0,1, light, r-ds, g-ds, b-ds, a);
        quad(vc, mat, last, x0,y1,z1, x0,y0,z1, x0,y0,z0, x0,y1,z0,
             -1,0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
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
    public ResourceLocation getTextureLocation(DroneEntity entity) {
        return TEXTURE;
    }
}

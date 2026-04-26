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
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import java.util.WeakHashMap;

/**
 * Block-sized robot renderer — tank tracks, boxy body, arms, head with face.
 * All geometry is hand-built quads using vanilla iron_block texture for shading.
 *
 * Tank tracks animate when the robot moves: a row of dark "lugs" scrolls along each
 * tread, and small sprocket wheels at the front/back of each pod rotate. Phase is
 * accumulated per-entity (driven by horizontal speed × direction) so each robot
 * animates independently and the tracks freeze when the robot stops.
 */
public class RobotRenderer extends EntityRenderer<RobotEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/iron_block.png");

    /** Per-entity tread phase (in lug-spacings). Persists between frames so motion is smooth. */
    private static final WeakHashMap<RobotEntity, float[]> PHASE = new WeakHashMap<>();
    /** Number of lugs visible along each tread strip. */
    private static final int LUG_COUNT = 6;
    /** Spacing between lugs (world units), along the Z axis (robot's forward). */
    private static final float LUG_SPACING = 0.8f / LUG_COUNT;
    /** Half-length of one lug (Z extent / 2). */
    private static final float LUG_HALF = LUG_SPACING * 0.30f;
    /** Tread length scaling: how many lug-spacings of motion per block of horizontal travel. */
    private static final float TREAD_SCROLL_RATE = 1.0f / LUG_SPACING;

    public RobotRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.5f;
    }

    @Override
    public void render(RobotEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        // ---- Compute tread animation (per-entity phase) ----
        Vec3 vel = entity.getDeltaMovement();
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        // Forward unit vector in world space (matches entity facing).
        float facingYawRad = (float) Math.toRadians(switch (entity.getRobotFacing()) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case EAST -> -90f;
            default -> 180f; // NORTH
        });
        double fx = -Math.sin(facingYawRad);
        double fz =  Math.cos(facingYawRad);
        double dot = vel.x * fx + vel.z * fz;       // + forward, − backward
        float dir = (speed > 0.001) ? (float) Math.signum(dot) : 0f;

        float[] phaseHolder = PHASE.computeIfAbsent(entity, e -> new float[]{0f, -1f});
        float lastPhase = phaseHolder[0];
        // Advance phase by (speed * dir) every render frame, scaled so 1 block of travel = 1 spacing.
        // We use raw render-frame deltas via partialTick mismatch detection so motion looks smooth.
        float phaseDelta = (float) (speed * dir * TREAD_SCROLL_RATE);
        // Smooth: integrate as if 1 tick passes per render, capped to avoid huge jumps on lag spikes.
        if (Math.abs(phaseDelta) > 1.0f) phaseDelta = Math.signum(phaseDelta);
        float treadPhase = lastPhase + phaseDelta;
        phaseHolder[0] = treadPhase;
        // Lug offset in world units (positive = lugs shift toward +Z, i.e. backward when moving fwd).
        float lugOffset = ((treadPhase * LUG_SPACING) % LUG_SPACING + LUG_SPACING) % LUG_SPACING;
        // Sprocket rotation in degrees (one full revolution per LUG_COUNT spacings).
        float sprocketAngle = -treadPhase * (360f / LUG_COUNT);

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

        // === TANK TRACKS (bottom) — dark contrast against white chassis ===
        // Left track pod
        drawBox(vc, mat, last, -0.45f, 0f, -0.4f, -0.25f, 0.2f, 0.4f,
                70, 72, 78, packedLight);
        // Right track pod
        drawBox(vc, mat, last, 0.25f, 0f, -0.4f, 0.45f, 0.2f, 0.4f,
                70, 72, 78, packedLight);
        // Tread base strips (continuous dark belt)
        drawBox(vc, mat, last, -0.46f, 0f, -0.4f, -0.24f, 0.03f, 0.4f,
                40, 42, 46, packedLight);
        drawBox(vc, mat, last, 0.24f, 0f, -0.4f, 0.46f, 0.03f, 0.4f,
                40, 42, 46, packedLight);
        // ---- Animated lugs on top of each tread (left + right) ----
        // Lugs are small raised cleats spaced LUG_SPACING apart that scroll with motion.
        // Anchor: leftmost edge of tread is at z = -0.4. Draw LUG_COUNT+1 lugs and clip
        // those that fall outside [-0.4, +0.4].
        for (int i = -1; i <= LUG_COUNT; i++) {
            float lugZ = -0.4f + i * LUG_SPACING + lugOffset;
            float z0 = lugZ - LUG_HALF;
            float z1 = lugZ + LUG_HALF;
            if (z1 <= -0.4f || z0 >= 0.4f) continue;
            // Clip to tread length so lugs don't poke out of the pods.
            float cz0 = Math.max(z0, -0.4f);
            float cz1 = Math.min(z1,  0.4f);
            // Left tread
            drawBox(vc, mat, last, -0.47f, 0.025f, cz0, -0.23f, 0.06f, cz1,
                    25, 26, 30, packedLight);
            // Right tread
            drawBox(vc, mat, last,  0.23f, 0.025f, cz0,  0.47f, 0.06f, cz1,
                    25, 26, 30, packedLight);
        }
        // ---- Sprocket wheels at front and back of each pod (rotate with motion) ----
        renderSprocket(vc, mat, pose, packedLight, -0.35f, 0.10f, -0.38f, sprocketAngle);
        renderSprocket(vc, mat, pose, packedLight, -0.35f, 0.10f,  0.38f, sprocketAngle);
        renderSprocket(vc, mat, pose, packedLight,  0.35f, 0.10f, -0.38f, sprocketAngle);
        renderSprocket(vc, mat, pose, packedLight,  0.35f, 0.10f,  0.38f, sprocketAngle);
        // Re-fetch matrix in case sprockets changed pose state (they pushPose internally).
        last = pose.last();
        mat = last.pose();
        // Axle between tracks (light gray)
        drawBox(vc, mat, last, -0.25f, 0.05f, -0.1f, 0.25f, 0.15f, 0.1f,
                190, 192, 198, packedLight);

        // === BODY (clean white chassis, slight cool tint) ===
        drawBox(vc, mat, last, -0.35f, 0.2f, -0.3f, 0.35f, 0.65f, 0.3f,
                232, 234, 240, packedLight);
        // Front chest plate (cyan accent — matches ByteBlock theme)
        drawBox(vc, mat, last, -0.25f, 0.3f, -0.31f, 0.25f, 0.58f, -0.30f,
                210, 232, 240, packedLight);
        // Green power LED
        drawBox(vc, mat, last, -0.05f, 0.48f, -0.32f, 0.05f, 0.55f, -0.31f,
                30, 220, 80, packedLight);
        // Rear vent (mid-gray)
        drawBox(vc, mat, last, -0.2f, 0.35f, 0.30f, 0.2f, 0.55f, 0.31f,
                170, 172, 178, packedLight);

        // === LEFT ARM (white with cyan joint accents) ===
        drawBox(vc, mat, last, -0.5f, 0.48f, -0.1f, -0.35f, 0.63f, 0.1f,
                40, 200, 230, packedLight); // shoulder (cyan joint)
        drawBox(vc, mat, last, -0.5f, 0.25f, -0.08f, -0.38f, 0.48f, 0.08f,
                235, 237, 242, packedLight); // upper arm
        drawBox(vc, mat, last, -0.48f, 0.15f, -0.06f, -0.40f, 0.25f, 0.06f,
                225, 227, 232, packedLight); // forearm
        // Gripper fingers (mid gray)
        drawBox(vc, mat, last, -0.49f, 0.10f, -0.05f, -0.46f, 0.15f, 0.0f,
                160, 162, 168, packedLight);
        drawBox(vc, mat, last, -0.42f, 0.10f, 0.0f, -0.39f, 0.15f, 0.05f,
                160, 162, 168, packedLight);

        // === RIGHT ARM ===
        drawBox(vc, mat, last, 0.35f, 0.48f, -0.1f, 0.5f, 0.63f, 0.1f,
                40, 200, 230, packedLight);
        drawBox(vc, mat, last, 0.38f, 0.25f, -0.08f, 0.5f, 0.48f, 0.08f,
                235, 237, 242, packedLight);
        drawBox(vc, mat, last, 0.40f, 0.15f, -0.06f, 0.48f, 0.25f, 0.06f,
                225, 227, 232, packedLight);
        drawBox(vc, mat, last, 0.46f, 0.10f, -0.05f, 0.49f, 0.15f, 0.0f,
                160, 162, 168, packedLight);
        drawBox(vc, mat, last, 0.39f, 0.10f, 0.0f, 0.42f, 0.15f, 0.05f,
                160, 162, 168, packedLight);

        // === NECK (cyan accent) ===
        drawBox(vc, mat, last, -0.08f, 0.65f, -0.08f, 0.08f, 0.72f, 0.08f,
                40, 200, 230, packedLight);

        // === HEAD (bright white) ===
        drawBox(vc, mat, last, -0.22f, 0.72f, -0.2f, 0.22f, 1.0f, 0.2f,
                240, 242, 248, packedLight);

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

    /**
     * Draw a sprocket wheel (small spoked disc) at (cx, cy, cz) in renderer-local space,
     * rotated by {@code angleDeg} around the world X axis (so the wheel rolls along Z).
     * Built from 4 thin spoke boxes arranged as an asterisk for a clear "spinning" read.
     */
    private static void renderSprocket(VertexConsumer vc, Matrix4f mat0, PoseStack pose,
                                        int light, float cx, float cy, float cz, float angleDeg) {
        pose.pushPose();
        pose.translate(cx, cy, cz);
        pose.mulPose(Axis.XP.rotationDegrees(angleDeg));
        PoseStack.Pose last = pose.last();
        Matrix4f mat = last.pose();
        // Hub (small box) — slightly lighter so center is visible.
        drawBox(vc, mat, last, -0.06f, -0.025f, -0.025f, 0.06f, 0.025f, 0.025f,
                160, 162, 168, light);
        // Four spokes at 0/45/90/135 degrees (will visually rotate with pose).
        for (int i = 0; i < 4; i++) {
            pose.pushPose();
            pose.mulPose(Axis.XP.rotationDegrees(i * 45f));
            PoseStack.Pose sl = pose.last();
            Matrix4f sm = sl.pose();
            // Long thin spoke along the Y axis (post-rotation it sweeps Y/Z plane).
            drawBox(vc, sm, sl, -0.05f, -0.085f, -0.012f, 0.05f, 0.085f, 0.012f,
                    90, 92, 98, light);
            pose.popPose();
        }
        pose.popPose();
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

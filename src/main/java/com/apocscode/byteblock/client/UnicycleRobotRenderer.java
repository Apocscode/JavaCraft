package com.apocscode.byteblock.client;

import com.apocscode.byteblock.entity.UnicycleRobotEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import java.util.WeakHashMap;

/**
 * Player-sized unicycle robot renderer — single tire, long arms, no head, face on
 * a chest-mounted computer screen. Animations:
 *   • Tire rotates with horizontal speed (signed by movement direction along facing).
 *   • Arms swing fwd/back (sin wave) at a rate tied to speed.
 *   • Body leans forward/back proportional to speed-along-facing (Segway balance look),
 *     smoothed across frames so the lean settles when the bot stops.
 *   • Eyes blink + mouth animates via {@link RobotRenderer#renderFace} (shared helper).
 *
 * Geometry is hand-built quads using vanilla iron_block texture. Total height ≈ 1.8.
 */
public class UnicycleRobotRenderer extends EntityRenderer<UnicycleRobotEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    /** Per-entity smoothed state: {wheelAngleDeg, leanDeg}. */
    private static final WeakHashMap<UnicycleRobotEntity, float[]> STATE = new WeakHashMap<>();

    public UnicycleRobotRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.35f;
    }

    @Override
    public ResourceLocation getTextureLocation(UnicycleRobotEntity entity) {
        return TEXTURE;
    }

    @Override
    protected void renderNameTag(UnicycleRobotEntity entity, net.minecraft.network.chat.Component displayName,
                                  PoseStack poseStack, MultiBufferSource buffer,
                                  int packedLight, float partialTick) {
        super.renderNameTag(entity, displayName, poseStack, buffer, packedLight, partialTick);
        poseStack.pushPose();
        poseStack.translate(0.0, -0.27, 0.0);
        super.renderNameTag(entity, entity.getStatsLine(), poseStack, buffer, packedLight, partialTick);
        poseStack.popPose();
    }

    @Override
    public void render(UnicycleRobotEntity entity, float yaw, float partialTick,
                       PoseStack pose, MultiBufferSource buffers, int packedLight) {
        // ---- Compute movement-driven values ----
        Vec3 vel = entity.getDeltaMovement();
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        // Forward unit vector from facing.
        float facingYawDeg = switch (entity.getRobotFacing()) {
            case SOUTH -> 0f;
            case WEST  -> 90f;
            case EAST  -> -90f;
            default    -> 180f; // NORTH
        };
        float facingYawRad = (float) Math.toRadians(facingYawDeg);
        double fx = -Math.sin(facingYawRad);
        double fz =  Math.cos(facingYawRad);
        double dot = vel.x * fx + vel.z * fz;          // signed speed along facing
        float dir = (speed > 0.001) ? (float) Math.signum(dot) : 0f;

        float[] st = STATE.computeIfAbsent(entity, e -> new float[]{0f, 0f});
        // Wheel: 1 block of travel = ~360° (radius ~0.16).
        float wheelDelta = (float) (dot * 360f);
        if (Math.abs(wheelDelta) > 90f) wheelDelta = 90f * Math.signum(wheelDelta);
        st[0] = (st[0] + wheelDelta) % 360f;
        // Lean: target proportional to forward speed, smoothed exponentially.
        float targetLean = (float) Math.max(-25f, Math.min(25f, dot * 60f));
        st[1] += (targetLean - st[1]) * 0.18f;

        float wheelAngle = st[0];
        float leanAngle  = st[1];
        // Arm swing: sin of tickCount, amplitude scaled by speed.
        float t = entity.tickCount + partialTick;
        float armSwing = (float) Math.sin(t * 0.3) * 25f * (float) Math.min(1.0, speed * 8.0);

        pose.pushPose();

        // Face the robot in its travel direction.
        pose.mulPose(Axis.YP.rotationDegrees(facingYawDeg + 180f));

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));

        // ---- TIRE (single big wheel below body, rolls along forward axis) ----
        // The tire group sits at y≈0.0 → 0.56 (radius ~0.28 from y=0.28).
        // Push a sub-pose that rotates around the wheel center for spoke animation.
        pose.pushPose();
        pose.translate(0f, 0.28f, 0f);
        pose.mulPose(Axis.XP.rotationDegrees(wheelAngle));
        {
            PoseStack.Pose wp = pose.last();
            Matrix4f wm = wp.pose();
            // Black tire ring — 12 thicker boxes around a hub form a chunkier tread.
            for (int i = 0; i < 12; i++) {
                float a = (float) Math.toRadians(i * 30);
                float r = 0.28f, rin = 0.18f;
                float y0 =  (float) Math.sin(a) * rin;
                float y1 =  (float) Math.sin(a) * r;
                float z0 =  (float) Math.cos(a) * rin;
                float z1 =  (float) Math.cos(a) * r;
                float yMin = Math.min(y0, y1) - 0.03f;
                float yMax = Math.max(y0, y1) + 0.03f;
                float zMin = Math.min(z0, z1) - 0.03f;
                float zMax = Math.max(z0, z1) + 0.03f;
                RobotRenderer.drawBox(vc, wm, wp,
                        -0.13f, yMin, zMin,
                         0.13f, yMax, zMax,
                        20, 20, 24, packedLight);
            }
            // Hub (white center)
            RobotRenderer.drawBox(vc, wm, wp,
                    -0.15f, -0.07f, -0.07f, 0.15f, 0.07f, 0.07f,
                    230, 232, 238, packedLight);
            // Two cyan spokes (form an X with rotation)
            RobotRenderer.drawBox(vc, wm, wp,
                    -0.145f, -0.24f, -0.03f, 0.145f, 0.24f, 0.03f,
                    40, 200, 230, packedLight);
            RobotRenderer.drawBox(vc, wm, wp,
                    -0.145f, -0.03f, -0.24f, 0.145f, 0.03f, 0.24f,
                    40, 200, 230, packedLight);
        }
        pose.popPose();

        // ---- BALANCE LEAN — everything above the wheel pivots forward/back ----
        // Pivot around the wheel center (y=0.28) so feet stay grounded.
        pose.translate(0f, 0.28f, 0f);
        pose.mulPose(Axis.XP.rotationDegrees(leanAngle));
        pose.translate(0f, -0.28f, 0f);

        PoseStack.Pose last = pose.last();
        Matrix4f mat = last.pose();

        // ---- LEG STRUT (fork from body down to wheel hub) ----
        RobotRenderer.drawBox(vc, mat, last,
                -0.06f, 0.32f, -0.04f, 0.06f, 0.65f, 0.04f,
                210, 212, 220, packedLight);
        // Ankle joint (cyan accent)
        RobotRenderer.drawBox(vc, mat, last,
                -0.08f, 0.60f, -0.06f, 0.08f, 0.68f, 0.06f,
                40, 200, 230, packedLight);

        // ---- TORSO (white player-sized body, ~y 0.65..1.65) ----
        // Lower torso (slightly tapered)
        RobotRenderer.drawBox(vc, mat, last,
                -0.22f, 0.65f, -0.16f, 0.22f, 1.05f, 0.16f,
                232, 234, 240, packedLight);
        // Upper torso (chest housing)
        RobotRenderer.drawBox(vc, mat, last,
                -0.26f, 1.05f, -0.18f, 0.26f, 1.65f, 0.18f,
                240, 242, 248, packedLight);

        // ---- CHEST COMPUTER SCREEN (face goes here — no head) ----
        // Bezel
        RobotRenderer.drawBox(vc, mat, last,
                -0.22f, 1.15f, -0.19f, 0.22f, 1.55f, -0.181f,
                25, 28, 34, packedLight);
        // Inner screen surface
        RobotRenderer.drawBox(vc, mat, last,
                -0.19f, 1.18f, -0.193f, 0.19f, 1.52f, -0.184f,
                12, 16, 22, packedLight);
        // Animated face (shared helper from RobotRenderer)
        RobotRenderer.renderFace(vc, mat, last, packedLight, entity, partialTick,
                -0.19f, 1.18f, 0.19f, 1.52f, -0.194f);

        // Side cyan accent stripes
        RobotRenderer.drawBox(vc, mat, last,
                -0.265f, 1.20f, -0.05f, -0.255f, 1.50f, 0.05f,
                40, 200, 230, packedLight);
        RobotRenderer.drawBox(vc, mat, last,
                 0.255f, 1.20f, -0.05f,  0.265f, 1.50f, 0.05f,
                40, 200, 230, packedLight);
        // Top cap (small dome where a head would be — power LED)
        RobotRenderer.drawBox(vc, mat, last,
                -0.10f, 1.65f, -0.10f, 0.10f, 1.72f, 0.10f,
                220, 222, 230, packedLight);
        RobotRenderer.drawBox(vc, mat, last,
                -0.025f, 1.72f, -0.025f, 0.025f, 1.75f, 0.025f,
                40, 220, 80, packedLight);

        // ---- LONG ARMS (swing from shoulders, reach to ground for inventories) ----
        renderArm(vc, mat, last, packedLight,  0.30f, 1.52f,  armSwing); // right
        renderArm(vc, mat, last, packedLight, -0.30f, 1.52f, -armSwing); // left

        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    /**
     * Render one long articulated arm anchored at shoulder (sx, sy) on the body.
     * The arm pivots fwd/back by {@code swingDeg} around the shoulder X axis, with a
     * fixed elbow bend so the gripper hangs forward of the body — long enough to reach
     * an adjacent block from a player-sized chassis.
     */
    private static void renderArm(VertexConsumer vc, Matrix4f mat0, PoseStack.Pose last0,
                                   int light, float sx, float sy, float swingDeg) {
        // We need a sub-pose for the swing; reconstruct via a temporary PoseStack.
        // Since drawBox needs Matrix4f + Pose, and we're already mid-render, simulate the
        // shoulder rotation by computing each box's vertices in arm-local space and then
        // baking the rotation into the matrix via PoseStack.
        // Simpler path: use the outer PoseStack — but we don't have it here. So instead,
        // approximate the swing by offsetting the upper-arm Z based on swing angle (a
        // straight-arm swing reads clearly without true rotation, given long arms).
        float sin = (float) Math.sin(Math.toRadians(swingDeg));
        float cos = (float) Math.cos(Math.toRadians(swingDeg));

        // Shoulder ball joint (cyan)
        RobotRenderer.drawBox(vc, mat0, last0,
                sx - 0.06f, sy - 0.06f, -0.06f, sx + 0.06f, sy + 0.06f, 0.06f,
                40, 200, 230, light);

        // Upper arm: 0.05 wide × 0.45 long, hanging from shoulder. Bake swing as Z offset
        // of the bottom end relative to top (top stays at sy).
        float topY = sy - 0.06f;
        float upperLen = 0.45f;
        float bottomY = topY - upperLen * cos;
        float bottomZ = -upperLen * sin;
        // Render the upper arm as a tilted box approximated with several short segments
        // along the swing axis so the bend is visible.
        int segs = 4;
        for (int i = 0; i < segs; i++) {
            float t0 = i / (float) segs, t1 = (i + 1) / (float) segs;
            float y0 = topY + (bottomY - topY) * t0;
            float y1 = topY + (bottomY - topY) * t1;
            float z0 = 0f + (bottomZ - 0f) * t0;
            float z1 = 0f + (bottomZ - 0f) * t1;
            float yMin = Math.min(y0, y1) - 0.025f;
            float yMax = Math.max(y0, y1) + 0.025f;
            float zMin = Math.min(z0, z1) - 0.04f;
            float zMax = Math.max(z0, z1) + 0.04f;
            // Alternate two whites for a subtle segmented look.
            int v = (i % 2 == 0) ? 235 : 225;
            RobotRenderer.drawBox(vc, mat0, last0,
                    sx - 0.05f, yMin, zMin, sx + 0.05f, yMax, zMax,
                    v, v + 2, v + 8, light);
        }

        // Elbow joint (cyan)
        RobotRenderer.drawBox(vc, mat0, last0,
                sx - 0.06f, bottomY - 0.06f, bottomZ - 0.06f,
                sx + 0.06f, bottomY,         bottomZ + 0.06f,
                40, 200, 230, light);

        // Forearm: drops straight down 0.45 from elbow (no swing baked in — keeps gripper level).
        float forearmTop = bottomY - 0.06f;
        float forearmBot = forearmTop - 0.45f;
        RobotRenderer.drawBox(vc, mat0, last0,
                sx - 0.045f, forearmBot, bottomZ - 0.045f,
                sx + 0.045f, forearmTop, bottomZ + 0.045f,
                225, 227, 232, light);

        // Gripper (two small pincer fingers in mid-gray)
        RobotRenderer.drawBox(vc, mat0, last0,
                sx - 0.055f, forearmBot - 0.06f, bottomZ - 0.04f,
                sx - 0.020f, forearmBot,         bottomZ + 0.04f,
                160, 162, 168, light);
        RobotRenderer.drawBox(vc, mat0, last0,
                sx + 0.020f, forearmBot - 0.06f, bottomZ - 0.04f,
                sx + 0.055f, forearmBot,         bottomZ + 0.04f,
                160, 162, 168, light);
    }
}

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

    // Pure 1×1 white texture so the per-quad vertex colors come through cleanly
    // (the previous terminal.png caused visible UV artifacts across faces).
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

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
        // Resolve paint slots — defaults match the original hard-coded look.
        com.apocscode.byteblock.entity.EntityPaint paint = entity.getPaint();
        int[] cBody    = unpack(paint.get("body",    rgb(232, 234, 240)));
        int[] cTrim    = unpack(paint.get("trim",    rgb(40,  200, 230)));
        int[] cArms    = unpack(paint.get("arms",    rgb(235, 237, 242)));
        int[] cHead    = unpack(paint.get("head",    rgb(240, 242, 248)));
        int[] cEye     = unpack(paint.get("eye",     rgb(40,  220, 255)));
        int[] cAntenna = unpack(paint.get("antenna", rgb(80,  80,  85)));
        int[] cTracks  = unpack(paint.get("tracks",  rgb(70,  72,  78)));
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
                cTracks[0], cTracks[1], cTracks[2], packedLight);
        // Right track pod
        drawBox(vc, mat, last, 0.25f, 0f, -0.4f, 0.45f, 0.2f, 0.4f,
                cTracks[0], cTracks[1], cTracks[2], packedLight);
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
                cBody[0], cBody[1], cBody[2], packedLight);
        // Front chest plate (trim accent)
        drawBox(vc, mat, last, -0.25f, 0.3f, -0.31f, 0.25f, 0.58f, -0.30f,
                cTrim[0], cTrim[1], cTrim[2], packedLight);
        // Green power LED
        drawBox(vc, mat, last, -0.05f, 0.48f, -0.32f, 0.05f, 0.55f, -0.31f,
                30, 220, 80, packedLight);
        // Rear vent (mid-gray)
        drawBox(vc, mat, last, -0.2f, 0.35f, 0.30f, 0.2f, 0.55f, 0.31f,
                170, 172, 178, packedLight);

        // === LEFT ARM (white with cyan joint accents) ===
        drawBox(vc, mat, last, -0.5f, 0.48f, -0.1f, -0.35f, 0.63f, 0.1f,
                cTrim[0], cTrim[1], cTrim[2], packedLight); // shoulder (trim joint)
        drawBox(vc, mat, last, -0.5f, 0.25f, -0.08f, -0.38f, 0.48f, 0.08f,
                cArms[0], cArms[1], cArms[2], packedLight); // upper arm
        drawBox(vc, mat, last, -0.48f, 0.15f, -0.06f, -0.40f, 0.25f, 0.06f,
                cArms[0], cArms[1], cArms[2], packedLight); // forearm
        // Gripper fingers (mid gray)
        drawBox(vc, mat, last, -0.49f, 0.10f, -0.05f, -0.46f, 0.15f, 0.0f,
                160, 162, 168, packedLight);
        drawBox(vc, mat, last, -0.42f, 0.10f, 0.0f, -0.39f, 0.15f, 0.05f,
                160, 162, 168, packedLight);

        // === RIGHT ARM ===
        drawBox(vc, mat, last, 0.35f, 0.48f, -0.1f, 0.5f, 0.63f, 0.1f,
                cTrim[0], cTrim[1], cTrim[2], packedLight);
        drawBox(vc, mat, last, 0.38f, 0.25f, -0.08f, 0.5f, 0.48f, 0.08f,
                cArms[0], cArms[1], cArms[2], packedLight);
        drawBox(vc, mat, last, 0.40f, 0.15f, -0.06f, 0.48f, 0.25f, 0.06f,
                cArms[0], cArms[1], cArms[2], packedLight);
        drawBox(vc, mat, last, 0.46f, 0.10f, -0.05f, 0.49f, 0.15f, 0.0f,
                160, 162, 168, packedLight);
        drawBox(vc, mat, last, 0.39f, 0.10f, 0.0f, 0.42f, 0.15f, 0.05f,
                160, 162, 168, packedLight);

        // === NECK (trim accent) ===
        drawBox(vc, mat, last, -0.08f, 0.65f, -0.08f, 0.08f, 0.72f, 0.08f,
                cTrim[0], cTrim[1], cTrim[2], packedLight);

        // === HEAD ===
        drawBox(vc, mat, last, -0.22f, 0.72f, -0.2f, 0.22f, 1.0f, 0.2f,
                cHead[0], cHead[1], cHead[2], packedLight);

        // === FACE ===
        // Eyes: blue idle, green while a user program is running, lightning bolt while charging.
        boolean programRunning = entity.getOS() != null && entity.getOS().isProgramRunning();
        boolean charging = entity.isCharging();
        if (charging) {
            // Replace eyes/mouth with lightning bolt face on the head's front plane.
            float t = entity.tickCount + partialTick;
            renderLightningBoltFace(vc, mat, last, packedLight,
                    -0.20f, 0.74f, 0.20f, 0.98f, -0.205f, t);
        } else {
            int eyeR = programRunning ?  60 : cEye[0];
            int eyeG = programRunning ? 255 : cEye[1];
            int eyeB = programRunning ? 100 : cEye[2];
            // Pick face bitmap: custom if set, else preset by id (default "classic").
            String fid = paint.getFaceId();
            long bits = "custom".equals(fid) ? paint.getFaceBits() : FacePresets.get(fid);
            renderFaceBitmap(vc, mat, last, packedLight,
                    -0.20f, 0.74f, 0.20f, 0.98f, -0.205f,
                    bits, eyeR, eyeG, eyeB);
        }

        // === ANTENNA ===
        drawBox(vc, mat, last, -0.02f, 1.0f, -0.02f, 0.02f, 1.12f, 0.02f,
                cAntenna[0], cAntenna[1], cAntenna[2], packedLight);
        // Red LED tip
        drawBox(vc, mat, last, -0.03f, 1.12f, -0.03f, 0.03f, 1.15f, 0.03f,
                220, 30, 30, packedLight);

        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    /**
     * Draw an animated face on a flat screen surface in renderer-local coordinates.
     * The screen rectangle is (x0,y0)→(x1,y1) on the {@code screenZ} plane (lower Z = closer
     * to the player when the robot is facing them, after the body's facing rotation).
     *
     * Animation: eyes blink every ~3 seconds (closed for ~3 ticks), and the mouth toggles
     * between two phases driven by {@code entity.tickCount}. While the robot is moving, the
     * eyes also wobble slightly side-to-side; while stationary they sit centered.
     */
    static void renderFace(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                                    int light, com.apocscode.byteblock.entity.RobotEntity entity,
                                    float partialTick,
                                    float x0, float y0, float x1, float y1, float screenZ) {
        float t = entity.tickCount + partialTick;
        // Blink: every 60 ticks, eyes close for ~3 ticks.
        boolean blinking = (entity.tickCount % 60) < 3;
        // Mouth phase: toggles every 8 ticks while moving, frozen while idle.
        boolean moving = entity.getDeltaMovement().horizontalDistanceSqr() > 0.001;
        boolean mouthOpen = moving && ((entity.tickCount / 8) % 2 == 0);
        boolean programRunning = entity.getOS() != null && entity.getOS().isProgramRunning();
        boolean charging = entity.isCharging();
        boolean busy = moving || programRunning || charging;

        // Charging face: lightning bolt instead of normal eyes/mouth.
        if (charging) {
            renderLightningBoltFace(vc, mat, last, light, x0, y0, x1, y1, screenZ, t);
            return;
        }

        // Eye look-at: when idle and not running a program, eyes track the local player.
        // When busy, fall back to a small "scanning" jitter so the bot looks active.
        float lookX = 0f, lookY = 0f;
        if (!busy) {
            float[] off = computeLookOffset(entity);
            lookX = off[0];
            lookY = off[1];
        } else if (moving) {
            lookX = (float) Math.sin(t * 0.3) * 0.012f;
        }

        float w = x1 - x0;
        float h = y1 - y0;
        // Eye geometry as a fraction of the screen.
        float eyeW = w * 0.18f, eyeH = h * 0.28f;
        // Look offsets are unit-vector style; scale to a visible portion of one eye.
        float eyeDx = lookX * (eyeW * 0.6f);
        float eyeDy = lookY * (eyeH * 0.5f);
        float eyeY0 = y0 + h * 0.45f + eyeDy;
        float eyeY1 = eyeY0 + (blinking ? eyeH * 0.10f : eyeH);
        float lEyeCx = x0 + w * 0.30f + eyeDx;
        float rEyeCx = x0 + w * 0.70f + eyeDx;
        // Cyan eyes (bright when open, near-flat line when blinking).
        // Green tint while a user program is running.
        int er, eg, eb;
        if (programRunning) {
            er = blinking ?  40 :  60;
            eg = blinking ? 200 : 255;
            eb = blinking ?  90 : 100;
        } else {
            er = blinking ? 30 : 80;
            eg = blinking ? 200 : 240;
            eb = blinking ? 220 : 255;
        }
        drawBox(vc, mat, last, lEyeCx - eyeW * 0.5f, eyeY0, screenZ,
                lEyeCx + eyeW * 0.5f, eyeY1, screenZ + 0.001f,
                er, eg, eb, light);
        drawBox(vc, mat, last, rEyeCx - eyeW * 0.5f, eyeY0, screenZ,
                rEyeCx + eyeW * 0.5f, eyeY1, screenZ + 0.001f,
                er, eg, eb, light);
        // Mouth — closed = thin horizontal line, open = small rectangle.
        float mY0 = y0 + h * 0.18f;
        float mY1 = mY0 + h * (mouthOpen ? 0.18f : 0.05f);
        float mX0 = x0 + w * 0.32f;
        float mX1 = x0 + w * 0.68f;
        drawBox(vc, mat, last, mX0, mY0, screenZ,
                mX1, mY1, screenZ + 0.001f,
                40, 220, 255, light);
    }

    /**
     * Compute eye look-at offset (-1..+1 on each axis) targeting the local player.
     * Returns {x, y} in renderer-local face space (positive x = right, positive y = up).
     */
    private static float[] computeLookOffset(com.apocscode.byteblock.entity.RobotEntity entity) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return new float[]{0f, 0f};
        Vec3 toPlayer = mc.player.getEyePosition().subtract(entity.position().add(0, 1.4, 0));
        double dist = toPlayer.length();
        if (dist < 0.1 || dist > 24.0) return new float[]{0f, 0f};
        // Project toPlayer into entity's local face plane using its facing yaw.
        float yaw = switch (entity.getRobotFacing()) {
            case SOUTH -> 0f;
            case WEST  -> 90f;
            case EAST  -> -90f;
            default    -> 180f; // NORTH
        };
        float yawRad = (float) Math.toRadians(yaw);
        // Face is on the -Z side of body (after the +180° flip in render). Local right axis
        // in world is rotate(+X) by -yaw; horizontal look = dot(toPlayer, rightAxis).
        double rx = -Math.cos(yawRad);
        double rz = -Math.sin(yawRad);
        double horiz = toPlayer.x * rx + toPlayer.z * rz;
        double horizDist = Math.hypot(toPlayer.x, toPlayer.z);
        float lx = (float) Math.max(-1.0, Math.min(1.0, horiz / Math.max(0.5, horizDist)));
        float ly = (float) Math.max(-1.0, Math.min(1.0, toPlayer.y / Math.max(0.5, dist)));
        return new float[]{lx, ly};
    }

    /**
     * Draw an animated lightning-bolt face (Z-shape) instead of eyes/mouth.
     * Bolt flickers brightness with a sine wave so it reads as "actively charging".
     */
    private static void renderLightningBoltFace(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                                                 int light,
                                                 float x0, float y0, float x1, float y1,
                                                 float screenZ, float t) {
        float w = x1 - x0, h = y1 - y0;
        float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
        // Flicker 60 → 100% brightness.
        float flicker = 0.7f + 0.3f * (float) Math.sin(t * 0.6);
        int br = (int) (90 * flicker);
        int bg = (int) (255 * flicker);
        int bb = (int) (255 * flicker);
        int yr = (int) (255 * flicker);
        int yg = (int) (240 * flicker);
        int yb = (int) (60 * flicker);
        // Top diagonal stroke (top-right → middle).
        float tw = w * 0.18f;
        // Three stacked bars, offset horizontally to suggest a Z/lightning shape.
        float zPlane = screenZ;
        float zPlane2 = screenZ + 0.001f;
        // Top bar
        drawBox(vc, mat, last,
                cx + w * 0.05f, cy + h * 0.22f, zPlane,
                cx + w * 0.30f, cy + h * 0.35f, zPlane2,
                yr, yg, yb, light);
        // Top-mid bar (slightly down/left)
        drawBox(vc, mat, last,
                cx - w * 0.12f, cy + h * 0.05f, zPlane,
                cx + w * 0.22f, cy + h * 0.18f, zPlane2,
                yr, yg, yb, light);
        // Mid bar (center, longer — the kink)
        drawBox(vc, mat, last,
                cx - w * 0.22f, cy - h * 0.05f, zPlane,
                cx + w * 0.10f, cy + h * 0.07f, zPlane2,
                br, bg, bb, light);
        // Mid-bottom bar
        drawBox(vc, mat, last,
                cx - w * 0.30f, cy - h * 0.18f, zPlane,
                cx + w * 0.02f, cy - h * 0.05f, zPlane2,
                yr, yg, yb, light);
        // Bottom bar (left tip)
        drawBox(vc, mat, last,
                cx - w * 0.30f, cy - h * 0.35f, zPlane,
                cx - w * 0.05f, cy - h * 0.20f, zPlane2,
                yr, yg, yb, light);
        // Cyan glow dot at the kink
        drawBox(vc, mat, last,
                cx - w * 0.05f, cy - h * 0.02f, zPlane - 0.001f,
                cx + w * 0.05f, cy + h * 0.05f, zPlane,
                br, bg, bb, light);
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
    static void drawBox(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
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

    @Override
    protected void renderNameTag(RobotEntity entity, net.minecraft.network.chat.Component displayName,
                                  PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffer,
                                  int packedLight, float partialTick) {
        // Top line — vanilla custom name (only when player has named it via Name Tag).
        super.renderNameTag(entity, displayName, poseStack, buffer, packedLight, partialTick);
        // Second line — health + charge stats, drawn slightly below.
        poseStack.pushPose();
        poseStack.translate(0.0, -0.27, 0.0);
        super.renderNameTag(entity, entity.getStatsLine(), poseStack, buffer, packedLight, partialTick);
        poseStack.popPose();
    }

    private static int rgb(int r, int g, int b) { return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF); }
    private static int[] unpack(int rgb) { return new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF}; }

    /**
     * Render an 8x8 face bitmap onto the head's front plane. Bit (y*8 + x) of
     * {@code bits} = 1 means the pixel at column x, row y is lit (y=0 = bottom).
     * The plane is bounded in head-local space by [x0..x1] × [y0..y1] at z=zFront.
     */
    private static void renderFaceBitmap(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                                          int packedLight,
                                          float x0, float y0, float x1, float y1, float zFront,
                                          long bits, int r, int g, int b) {
        float pxW = (x1 - x0) / 8f;
        float pxH = (y1 - y0) / 8f;
        float depth = 0.005f;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (((bits >> (y * 8 + x)) & 1L) == 0L) continue;
                // x in head-local: head's RIGHT is +x; bitmap column 0 is leftmost
                // pixel viewed from the front, which is head-local +x.
                float px0 = x0 + x * pxW;
                float py0 = y0 + y * pxH;
                drawBox(vc, mat, last,
                        px0, py0, zFront,
                        px0 + pxW, py0 + pxH, zFront + depth,
                        r, g, b, packedLight);
            }
        }
    }
}

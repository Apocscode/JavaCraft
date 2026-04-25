package com.apocscode.byteblock.client;

import com.apocscode.byteblock.block.MonitorBlock;
import com.apocscode.byteblock.block.entity.ComputerBlockEntity;
import com.apocscode.byteblock.block.entity.MonitorBlockEntity;
import com.apocscode.byteblock.computer.PixelBuffer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Block Entity Renderer for the Monitor block.
 * Renders the linked computer's PixelBuffer on the front face of each monitor block,
 * with UV slicing for multi-block formations.
 */
public class MonitorRenderer implements BlockEntityRenderer<MonitorBlockEntity> {

    private static final int FB_W = PixelBuffer.SCREEN_W; // 640
    private static final int FB_H = PixelBuffer.SCREEN_H; // 400
    private static final int FULLBRIGHT = 15728880;

    /** Frame/bezel texture — solid gray block from vanilla resources. */
    private static final ResourceLocation FRAME_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/gray_concrete.png");
    /** Tiny inner-bezel border texture — black. */
    private static final ResourceLocation BEZEL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/black_concrete.png");

    // Texture cache keyed by formation origin position
    private static final Map<BlockPos, ScreenTexture> TEXTURE_CACHE = new HashMap<>();
    private static long lastCleanupTick = 0;

    private static class ScreenTexture {
        final NativeImage image;
        final DynamicTexture texture;
        final ResourceLocation location;
        final PixelBuffer stagingBuffer;
        long lastUsedTick;
        long lastUploadTick;
        long lastUploadedSourceVersion = -1;
        String lastUploadedSig = "";

        ScreenTexture(NativeImage image, DynamicTexture texture, ResourceLocation location) {
            this.image = image;
            this.texture = texture;
            this.location = location;
            this.stagingBuffer = new PixelBuffer();
        }
    }

    public MonitorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MonitorBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) return;

        MonitorBlockEntity origin = be.getOriginEntity();
        if (origin == null) return;

        BlockPos originPos = origin.getBlockPos();
        long currentTick = be.getLevel().getGameTime();

        // Determine source: linked-computer mirror, monitor's own text buffer, or test pattern.
        String displayMode = origin.getDisplayMode();
        BlockPos linkedPos = origin.getLinkedComputerPos();
        ComputerBlockEntity computer = null;
        if (linkedPos != null) {
            BlockEntity compBe = be.getLevel().getBlockEntity(linkedPos);
            if (compBe instanceof ComputerBlockEntity c) computer = c;
        }
        boolean isMirror = displayMode == null || "mirror".equals(displayMode);
        boolean isText   = "text".equals(displayMode);
        boolean isGfx    = "graphics".equals(displayMode);
        boolean isTest   = displayMode != null && displayMode.startsWith("test:");

        // Get/create texture
        ScreenTexture st = TEXTURE_CACHE.get(originPos);
        if (st == null) {
            st = createTexture(originPos);
            TEXTURE_CACHE.put(originPos, st);
        }
        st.lastUsedTick = currentTick;

        // Decide what to upload (with dirty-tracking to skip GPU work when nothing changed).
        if (st.lastUploadTick != currentTick) {
            st.lastUploadTick = currentTick;
            if (isMirror) {
                if (computer == null) {
                    String lbl = origin.getLastKnownComputerLabel();
                    String sig = "tomb:" + (linkedPos == null ? "nolink" : linkedPos.asLong())
                            + ":" + (lbl == null ? "" : lbl);
                    if (!sig.equals(st.lastUploadedSig)) {
                        renderTombstone(st.stagingBuffer, linkedPos, lbl);
                        uploadPixels(st, st.stagingBuffer);
                        st.lastUploadedSig = sig;
                        st.lastUploadedSourceVersion = -1;
                    }
                } else {
                    PixelBuffer pb = computer.getOS().getPixelBuffer();
                    if (pb == null) return;
                    long ver = pb.getVersion();
                    if (ver != st.lastUploadedSourceVersion || !"mirror".equals(st.lastUploadedSig)) {
                        uploadPixels(st, pb);
                        st.lastUploadedSourceVersion = ver;
                        st.lastUploadedSig = "mirror";
                    }
                }
            } else if (isText) {
                long ver = origin.getTextVersion();
                String sig = "text:" + ver + ":p" + origin.getPaletteVersion();
                if (!sig.equals(st.lastUploadedSig)) {
                    renderTextBuffer(st.stagingBuffer, origin);
                    uploadPixels(st, st.stagingBuffer);
                    st.lastUploadedSig = sig;
                    st.lastUploadedSourceVersion = -1;
                }
            } else if (isGfx) {
                long ver = origin.getGfxVersion();
                String sig = "gfx:" + ver + ":p" + origin.getPaletteVersion();
                if (!sig.equals(st.lastUploadedSig)) {
                    renderGfxBuffer(st.stagingBuffer, origin);
                    uploadPixels(st, st.stagingBuffer);
                    st.lastUploadedSig = sig;
                    st.lastUploadedSourceVersion = -1;
                }
            } else if (isTest) {
                String sig = displayMode;
                if (!sig.equals(st.lastUploadedSig)) {
                    renderDisplayMode(st.stagingBuffer, displayMode);
                    uploadPixels(st, st.stagingBuffer);
                    st.lastUploadedSig = sig;
                    st.lastUploadedSourceVersion = -1;
                }
            }
        }

        // Render the screen quad for this block
        renderQuad(be, origin, st, pose, buffers);

        // Periodic cleanup of stale textures
        if (currentTick - lastCleanupTick > 200) {
            lastCleanupTick = currentTick;
            cleanupTextures(currentTick);
        }
    }

    private static ScreenTexture createTexture(BlockPos origin) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, FB_W, FB_H, false);
        DynamicTexture texture = new DynamicTexture(image);
        ResourceLocation location = Minecraft.getInstance().getTextureManager()
                .register("byteblock_monitor_" + Long.toUnsignedString(origin.asLong()), texture);
        return new ScreenTexture(image, texture, location);
    }

    private static void uploadPixels(ScreenTexture st, PixelBuffer pb) {
        int[] pixels = pb.getPixels();
        NativeImage image = st.image;
        for (int y = 0; y < FB_H; y++) {
            int off = y * FB_W;
            for (int x = 0; x < FB_W; x++) {
                int argb = pixels[off + x];
                // Convert ARGB → ABGR for OpenGL
                int a = argb & 0xFF000000;
                int r = (argb >> 16) & 0xFF;
                int g = argb & 0x0000FF00;
                int b = (argb << 16) & 0x00FF0000;
                image.setPixelRGBA(x, y, a | b | g | r);
            }
        }
        st.texture.upload();
    }

    private static void renderDisplayMode(PixelBuffer pb, String displayMode) {
        int w = pb.getWidth();
        int h = pb.getHeight();
        String pattern = displayMode.startsWith("test:") ? displayMode.substring(5) : displayMode;

        switch (pattern) {
            case "bars" -> renderColorBars(pb, w, h);
            case "grid" -> renderGrid(pb, w, h);
            case "checker" -> renderCheckerboard(pb, w, h);
            case "gradient" -> renderGradient(pb, w, h);
            case "red" -> pb.fillRect(0, 0, w, h, 0xFFFF0000);
            case "green" -> pb.fillRect(0, 0, w, h, 0xFF00FF00);
            case "blue" -> pb.fillRect(0, 0, w, h, 0xFF0000FF);
            case "white" -> pb.fillRect(0, 0, w, h, 0xFFFFFFFF);
            default -> pb.fillRect(0, 0, w, h, 0xFF000000);
        }

        String label = "MONITOR TEST: " + pattern.toUpperCase();
        int textW = label.length() * PixelBuffer.CELL_W;
        pb.fillRect((w - textW) / 2 - 6, h - 24, textW + 12, 18, 0xCC000000);
        pb.drawStringCentered(0, w, h - 22, label, 0xFFFFFFFF);
    }

    /** Render the monitor's own terminal buffer (used in display_mode == "text"). */
    private static void renderTextBuffer(PixelBuffer pb, MonitorBlockEntity origin) {
        int w = pb.getWidth();
        int h = pb.getHeight();
        char[] chars = origin.getTextChars();
        byte[] fg    = origin.getTextFg();
        byte[] bg    = origin.getTextBg();
        int cols = MonitorBlockEntity.TEXT_COLS;
        int rows = MonitorBlockEntity.TEXT_ROWS;
        // Use the formation size to scale text up: bigger formation = larger characters.
        // textScale further multiplies size (scale 1 = normal CC monitor text).
        double scale = Math.max(0.5, Math.min(5.0, origin.getTextScale()));
        int multiW = origin.getMultiWidth();
        int multiH = origin.getMultiHeight();
        int effCols = Math.max(1, (int)Math.round(cols * multiW / scale));
        int effRows = Math.max(1, (int)Math.round(rows * multiH / scale));
        // Cap at our buffer
        effCols = Math.min(effCols, cols);
        effRows = Math.min(effRows, rows);
        int cellW = w / effCols;
        int cellH = h / effRows;
        // First fill all backgrounds
        for (int y = 0; y < effRows; y++) {
            int rowOff = y * cols;
            for (int x = 0; x < effCols; x++) {
                int idx = rowOff + x;
                int bgC = paletteColor(origin, bg[idx] & 0xF);
                pb.fillRect(x * cellW, y * cellH, cellW, cellH, bgC);
            }
        }
        // Then draw characters
        int fontW = PixelBuffer.CELL_W;
        int fontH = PixelBuffer.CELL_H;
        for (int y = 0; y < effRows; y++) {
            int rowOff = y * cols;
            int py = y * cellH + Math.max(0, (cellH - fontH) / 2);
            for (int x = 0; x < effCols; x++) {
                int idx = rowOff + x;
                char c = chars[idx];
                if (c == ' ' || c == 0) continue;
                int fgC = paletteColor(origin, fg[idx] & 0xF);
                int px = x * cellW + Math.max(0, (cellW - fontW) / 2);
                pb.drawChar(px, py, c, fgC);
            }
        }
    }

    /** "No signal" tombstone shown in mirror mode when the linked computer is missing. */
    private static void renderTombstone(PixelBuffer pb, BlockPos linkedPos, String label) {
        int w = pb.getWidth();
        int h = pb.getHeight();
        // Snow noise
        java.util.Random rng = new java.util.Random(0xCAFEBABEL);
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x < w; x += 4) {
                int v = rng.nextInt(64);
                int g = 0x33 + v;
                pb.fillRect(x, y, 4, 4, 0xFF000000 | (g << 16) | (g << 8) | g);
            }
        }
        // Banner
        boolean hasLabel = label != null && !label.isEmpty();
        int bw = 380, bh = hasLabel ? 100 : 80;
        pb.fillRoundRect((w - bw) / 2, (h - bh) / 2, bw, bh, 8, 0xCC000000);
        pb.drawRect((w - bw) / 2, (h - bh) / 2, bw, bh, 0xFFFF4444);
        int yLine = h / 2 - (hasLabel ? 30 : 20);
        pb.drawStringCentered(0, w, yLine, "NO SIGNAL", 0xFFFF4444);
        if (hasLabel) {
            pb.drawStringCentered(0, w, yLine + 18, "\"" + label + "\"", 0xFFFFCC66);
        }
        String detail = (linkedPos == null)
                ? "No computer linked. Place one adjacent."
                : ("Lost link to computer at "
                    + linkedPos.getX() + ", " + linkedPos.getY() + ", " + linkedPos.getZ());
        pb.drawStringCentered(0, w, yLine + (hasLabel ? 36 : 24), detail, 0xFFCCCCCC);
    }

    /** Render the monitor's 160×100 4-bit graphics buffer scaled to the monitor surface. */
    private static void renderGfxBuffer(PixelBuffer pb, MonitorBlockEntity origin) {
        int srcW = MonitorBlockEntity.GFX_W;
        int srcH = MonitorBlockEntity.GFX_H;
        int dstW = pb.getWidth(), dstH = pb.getHeight();
        int sx = Math.max(1, dstW / srcW);
        int sy = Math.max(1, dstH / srcH);
        for (int y = 0; y < srcH; y++) {
            int py = y * sy;
            for (int x = 0; x < srcW; x++) {
                int idx = origin.gfxGetPixel(x, y);
                pb.fillRect(x * sx, py, sx, sy, paletteColor(origin, idx));
            }
        }
    }

    private static int paletteColor(MonitorBlockEntity origin, int idx) {
        if (origin != null) return origin.getPaletteARGB(idx & 0xF);
        int[] p = com.apocscode.byteblock.computer.TerminalBuffer.PALETTE;
        if (idx < 0 || idx >= p.length) return 0xFF000000;
        return p[idx];
    }

    private static void renderColorBars(PixelBuffer pb, int w, int h) {
        int[] colors = {
                0xFFFFFFFF, 0xFFFFFF00, 0xFF00FFFF, 0xFF00FF00,
                0xFFFF00FF, 0xFFFF0000, 0xFF0000FF, 0xFF000000,
                0xFFFF8800, 0xFF8800FF, 0xFF0088FF, 0xFF88FF00
        };
        int barW = Math.max(1, w / colors.length);
        for (int i = 0; i < colors.length; i++) {
            int x = i * barW;
            int width = (i == colors.length - 1) ? (w - x) : barW;
            pb.fillRect(x, 0, width, h, colors[i]);
        }
    }

    private static void renderGrid(PixelBuffer pb, int w, int h) {
        pb.fillRect(0, 0, w, h, 0xFF000000);
        int spacing = 32;
        for (int x = 0; x < w; x += spacing) {
            pb.drawVLine(x, 0, h - 1, 0xFF444444);
        }
        for (int y = 0; y < h; y += spacing) {
            pb.drawHLine(0, w - 1, y, 0xFF444444);
        }
        pb.drawHLine(0, w - 1, h / 2, 0xFFFF0000);
        pb.drawVLine(w / 2, 0, h - 1, 0xFFFF0000);
        pb.drawRect(0, 0, w, h, 0xFFFFFFFF);
    }

    private static void renderCheckerboard(PixelBuffer pb, int w, int h) {
        int size = 16;
        for (int y = 0; y < h; y += size) {
            for (int x = 0; x < w; x += size) {
                boolean white = ((x / size) + (y / size)) % 2 == 0;
                pb.fillRect(x, y, Math.min(size, w - x), Math.min(size, h - y), white ? 0xFFFFFFFF : 0xFF000000);
            }
        }
    }

    private static void renderGradient(PixelBuffer pb, int w, int h) {
        int thirdH = Math.max(1, h / 3);
        for (int x = 0; x < w; x++) {
            float t = w <= 1 ? 0.0f : (float) x / (w - 1);
            int r1 = (int) ((1 - t) * 255);
            int g1 = (int) (t * 255);
            int color1 = 0xFF000000 | (r1 << 16) | (g1 << 8);
            for (int y = 0; y < thirdH && y < h; y++) pb.setPixel(x, y, color1);

            int g2 = (int) ((1 - t) * 255);
            int b2 = (int) (t * 255);
            int color2 = 0xFF000000 | (g2 << 8) | b2;
            for (int y = thirdH; y < thirdH * 2 && y < h; y++) pb.setPixel(x, y, color2);

            int b3 = (int) ((1 - t) * 255);
            int r3 = (int) (t * 255);
            int color3 = 0xFF000000 | (r3 << 16) | b3;
            for (int y = thirdH * 2; y < h; y++) pb.setPixel(x, y, color3);
        }
    }

    private static void renderQuad(MonitorBlockEntity be, MonitorBlockEntity origin,
                                   ScreenTexture st, PoseStack pose, MultiBufferSource buffers) {
        Direction facing = be.getBlockState().getValue(MonitorBlock.FACING);
        int multiW = origin.getMultiWidth();
        int multiH = origin.getMultiHeight();
        int offX = be.getOffsetX();
        int offY = be.getOffsetY();

        // UV coordinates for this block's slice of the full texture.
        // Note: a screen-facing player sees the block's local +X axis on their LEFT
        // (when facing NORTH-facing block, world +X = east = player left). We mirror
        // the per-block U range and swap the quad's U vertices so the texture reads
        // left-to-right naturally across multi-block formations.
        float u0 = (float) (multiW - 1 - offX) / multiW;
        float u1 = (float) (multiW - offX) / multiW;
        float v0 = (float) (multiH - 1 - offY) / multiH;
        float v1 = (float) (multiH - offY) / multiH;

        pose.pushPose();

        // Rotate so the "front face" is always at z=0 facing -Z
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
            default -> { /* NORTH: no transform */ }
        }

        // Slab thickness (per-BE) defines where the screen face sits. The slab back is at z=1,
        // front face is at z = 1 - (thickness/16). Place the screen quad just barely in front
        // of that surface (inset by 0.005 to avoid z-fighting with the bezel).
        int thickness = Math.max(1, Math.min(6, origin.getThicknessPx()));
        float screenZ = 1.0f - (thickness / 16.0f) - 0.005f;
        float frontZ  = 1.0f - (thickness / 16.0f);   // bezel front plane
        float m = 0.01f;    // tiny margin to prevent edge artifacts

        // Apply per-BE tilt (X axis) and yaw (Y axis) around the screen face center.
        // Edge blocks in a multi-block formation use the formation-wide center so the
        // surface stays coplanar across the whole panel.
        float tilt = origin.getTiltDegrees();
        float yaw  = origin.getYawDegrees();
        if (tilt != 0f || yaw != 0f) {
            // Center of this block within formation:
            float cx = 0.5f - offX;          // formation center x in this-block local coords
            float cy = 0.5f - offY;          // formation center y in this-block local coords (y-up)
            pose.translate(cx, cy, screenZ);
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            pose.mulPose(Axis.XP.rotationDegrees(tilt));
            pose.translate(-cx, -cy, -screenZ);
        }

        Matrix4f mat = pose.last().pose();

        // ---- FRAME GEOMETRY (Nuclear-Control style) -----------------------------
        // Draw the slab itself — back plane + 4 thin side strips + front bezel ring —
        // so the visible block reflects the configured thickness/tilt/yaw. The screen
        // quad is drawn last on top of the bezel.
        int multiWf = multiW;
        int multiHf = multiH;
        boolean hasLeft   = offX > 0;
        boolean hasRight  = offX < multiWf - 1;
        boolean hasBottom = offY > 0;
        boolean hasTop    = offY < multiHf - 1;
        VertexConsumer frame = buffers.getBuffer(RenderType.entityCutoutNoCull(FRAME_TEXTURE));

        // Back face (z=1) — facing +Z (away from screen)
        addQuad(frame, mat, pose,
                0, 0, 1f,   1, 0, 1f,   1, 1, 1f,   0, 1, 1f,
                0, 0, 1, 1, 0, 0, 1);

        // Top side (y=1) — only if this block is at the top edge
        if (!hasTop) {
            addQuad(frame, mat, pose,
                    0, 1, frontZ,   1, 1, frontZ,   1, 1, 1f,   0, 1, 1f,
                    0, 0, 1, 1, 0, 1, 0);
        }
        // Bottom side (y=0)
        if (!hasBottom) {
            addQuad(frame, mat, pose,
                    0, 0, 1f,   1, 0, 1f,   1, 0, frontZ,   0, 0, frontZ,
                    0, 0, 1, 1, 0, -1, 0);
        }
        // Left side (x=0)
        if (!hasLeft) {
            addQuad(frame, mat, pose,
                    0, 0, 1f,   0, 0, frontZ,   0, 1, frontZ,   0, 1, 1f,
                    0, 0, 1, 1, -1, 0, 0);
        }
        // Right side (x=1)
        if (!hasRight) {
            addQuad(frame, mat, pose,
                    1, 0, frontZ,   1, 0, 1f,   1, 1, 1f,   1, 1, frontZ,
                    0, 0, 1, 1, 1, 0, 0);
        }

        // Front bezel — thin black ring around the screen quad. Drawn slightly behind the
        // screen (frontZ vs screenZ which is frontZ - 0.005) using BEZEL_TEXTURE.
        VertexConsumer bezel = buffers.getBuffer(RenderType.entityCutoutNoCull(BEZEL_TEXTURE));
        // Draw the entire front face — the screen quad will cover the inner area, leaving
        // an `m`-wide black border (since screen quad is inset by `m`).
        addQuad(bezel, mat, pose,
                0, 0, frontZ,   1, 0, frontZ,   1, 1, frontZ,   0, 1, frontZ,
                0, 0, 1, 1, 0, 0, -1);
        // -----------------------------------------------------------.

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(st.location));

        // Quad: CCW winding when viewed from -Z (front of the screen).
        // Vertices use swapped U so texture reads correctly to the player (see u0/u1 above).
        // Top-left → Bottom-left → Bottom-right → Top-right (block-local; player sees mirrored).
        vc.addVertex(mat, m, 1 - m, screenZ).setColor(255, 255, 255, 255).setUv(u1, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT)
                .setNormal(pose.last(), 0, 0, -1);
        vc.addVertex(mat, m, m, screenZ).setColor(255, 255, 255, 255).setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT)
                .setNormal(pose.last(), 0, 0, -1);
        vc.addVertex(mat, 1 - m, m, screenZ).setColor(255, 255, 255, 255).setUv(u0, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT)
                .setNormal(pose.last(), 0, 0, -1);
        vc.addVertex(mat, 1 - m, 1 - m, screenZ).setColor(255, 255, 255, 255).setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT)
                .setNormal(pose.last(), 0, 0, -1);

        pose.popPose();
    }

    /**
     * Emit a textured quad with given 4 corner positions, UV rectangle (u0,v0,u1,v1)
     * applied as TL/TR/BR/BL, and a normal vector. Vertices are wound CCW from the
     * normal direction. Uses fullbright lighting.
     */
    private static void addQuad(VertexConsumer vc, Matrix4f mat, PoseStack pose,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3,
                                float x4, float y4, float z4,
                                float u0, float v0, float u1, float v1,
                                float nx, float ny, float nz) {
        vc.addVertex(mat, x1, y1, z1).setColor(255, 255, 255, 255).setUv(u0, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT)
                .setNormal(pose.last(), nx, ny, nz);
        vc.addVertex(mat, x2, y2, z2).setColor(255, 255, 255, 255).setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT)
                .setNormal(pose.last(), nx, ny, nz);
        vc.addVertex(mat, x3, y3, z3).setColor(255, 255, 255, 255).setUv(u1, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT)
                .setNormal(pose.last(), nx, ny, nz);
        vc.addVertex(mat, x4, y4, z4).setColor(255, 255, 255, 255).setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT)
                .setNormal(pose.last(), nx, ny, nz);
    }

    private static void cleanupTextures(long currentTick) {
        Iterator<Map.Entry<BlockPos, ScreenTexture>> it = TEXTURE_CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, ScreenTexture> entry = it.next();
            if (currentTick - entry.getValue().lastUsedTick > 200) {
                entry.getValue().texture.close();
                it.remove();
            }
        }
    }

    /** Called when a formation changes to release its cached texture. */
    public static void invalidateTexture(BlockPos originPos) {
        ScreenTexture st = TEXTURE_CACHE.remove(originPos);
        if (st != null) {
            st.texture.close();
        }
    }

    @Override
    public int getViewDistance() {
        return 64;
    }
}

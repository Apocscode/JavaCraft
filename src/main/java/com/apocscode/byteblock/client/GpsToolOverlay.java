package com.apocscode.byteblock.client;

import java.util.List;

import com.apocscode.byteblock.ByteBlock;
import com.apocscode.byteblock.init.ModItems;
import com.apocscode.byteblock.item.GpsToolItem;
import com.apocscode.byteblock.network.GpsModeCyclePayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side renderer for the GPS tool: draws colored highlight boxes,
 * wire-frame patrol areas, and connecting lines to visualise stored points.
 * Also handles Shift+Scroll cycling of the tool mode.
 */
@EventBusSubscriber(modid = ByteBlock.MODID, value = Dist.CLIENT)
public final class GpsToolOverlay {

    private GpsToolOverlay() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        ItemStack stack = findGpsTool(player);
        if (stack == null) return;

        GpsToolItem.Mode mode = GpsToolItem.getMode(stack);
        BlockPos a = GpsToolItem.getA(stack);
        BlockPos b = GpsToolItem.getB(stack);
        List<BlockPos> path = GpsToolItem.getPath(stack);

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        switch (mode) {
            case WAYPOINT -> {
                if (a != null) drawBox(pose, lines, a, 0.00f, 0.80f, 1.00f, 0.9f);
            }
            case ROUTE -> {
                // A = input  (green frame), B = output (blue frame), connector cyan.
                if (a != null) drawBox(pose, lines, a, 0.15f, 0.95f, 0.20f, 0.9f);
                if (b != null) drawBox(pose, lines, b, 0.15f, 0.55f, 1.00f, 0.9f);
                if (a != null && b != null) drawLine(pose, lines,
                        a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5,
                        b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5,
                        0.30f, 0.85f, 0.95f, 0.8f);
            }
            case AREA -> {
                if (a != null && b != null) drawAabb(pose, lines, a, b, 1.0f, 0.80f, 0.0f, 0.8f);
                else if (a != null) drawBox(pose, lines, a, 1.0f, 0.80f, 0.0f, 0.9f);
            }
            case PATH -> {
                BlockPos prev = null;
                for (int i = 0; i < path.size(); i++) {
                    BlockPos p = path.get(i);
                    drawBox(pose, lines, p, 0.80f, 0.20f, 1.00f, 0.9f);
                    if (prev != null) drawLine(pose, lines,
                            prev.getX() + 0.5, prev.getY() + 0.5, prev.getZ() + 0.5,
                            p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                            0.80f, 0.20f, 1.00f, 0.8f);
                    prev = p;
                }
            }
        }

        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || !player.isShiftKeyDown()) return;
        if (findGpsTool(player) == null) return;
        double delta = event.getScrollDeltaY();
        if (delta == 0) return;
        int dir = delta > 0 ? 1 : -1;
        PacketDistributor.sendToServer(new GpsModeCyclePayload(dir));
        event.setCanceled(true);
    }

    private static ItemStack findGpsTool(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() == ModItems.GPS_TOOL.get()) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() == ModItems.GPS_TOOL.get()) return off;
        return null;
    }

    private static void drawBox(PoseStack pose, VertexConsumer buf, BlockPos pos, float r, float g, float b, float a) {
        LevelRenderer.renderLineBox(pose, buf,
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0,
                r, g, b, a);
    }

    private static void drawAabb(PoseStack pose, VertexConsumer buf, BlockPos p1, BlockPos p2, float r, float g, float bl, float a) {
        int minX = Math.min(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ());
        int maxX = Math.max(p1.getX(), p2.getX());
        int maxY = Math.max(p1.getY(), p2.getY());
        int maxZ = Math.max(p1.getZ(), p2.getZ());
        LevelRenderer.renderLineBox(pose, buf,
                minX, minY, minZ,
                maxX + 1.0, maxY + 1.0, maxZ + 1.0,
                r, g, bl, a);
    }

    private static void drawLine(PoseStack pose, VertexConsumer buf,
                                  double x1, double y1, double z1,
                                  double x2, double y2, double z2,
                                  float r, float g, float b, float a) {
        var matrix = pose.last().pose();
        var last = pose.last();
        float dx = (float) (x2 - x1), dy = (float) (y2 - y1), dz = (float) (z2 - z1);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0) { dx /= len; dy /= len; dz /= len; }
        buf.addVertex(matrix, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a).setNormal(last, dx, dy, dz);
        buf.addVertex(matrix, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a).setNormal(last, dx, dy, dz);
    }
}

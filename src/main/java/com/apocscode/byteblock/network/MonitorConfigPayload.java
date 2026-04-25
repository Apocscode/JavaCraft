package com.apocscode.byteblock.network;

import com.apocscode.byteblock.block.entity.MonitorBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server packet sent from {@link com.apocscode.byteblock.client.MonitorConfigScreen}
 * to update a monitor's geometry (panel thickness, tilt, yaw).
 */
public record MonitorConfigPayload(BlockPos pos, int thicknessPx, float tiltDeg, float yawDeg)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MonitorConfigPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("byteblock", "monitor_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorConfigPayload> STREAM_CODEC =
            StreamCodec.of(MonitorConfigPayload::write, MonitorConfigPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, MonitorConfigPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeInt(p.thicknessPx);
        buf.writeFloat(p.tiltDeg);
        buf.writeFloat(p.yawDeg);
    }

    private static MonitorConfigPayload read(RegistryFriendlyByteBuf buf) {
        return new MonitorConfigPayload(buf.readBlockPos(), buf.readInt(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(MonitorConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64) return;
            Level level = player.level();
            BlockEntity be = level.getBlockEntity(payload.pos);
            if (be instanceof MonitorBlockEntity m) {
                m.applyConfigString(String.format(
                        java.util.Locale.ROOT,
                        "thickness=%d,tilt=%.2f,yaw=%.2f",
                        payload.thicknessPx, payload.tiltDeg, payload.yawDeg));
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

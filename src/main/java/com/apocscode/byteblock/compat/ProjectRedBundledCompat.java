package com.apocscode.byteblock.compat;

import com.apocscode.byteblock.ByteBlock;
import com.apocscode.byteblock.block.entity.RedstoneRelayBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Soft integration with ProjectRed bundled cable API via reflection.
 *
 * This class intentionally has no compile-time dependency on ProjectRed.
 */
public final class ProjectRedBundledCompat {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private ProjectRedBundledCompat() {}

    public static void tryRegister() {
        if (REGISTERED.get()) return;

        try {
            Class<?> projectRedApiClass = Class.forName("mrtjp.projectred.api.ProjectRedAPI");
            Field transmissionApiField = projectRedApiClass.getField("transmissionAPI");
            Object transmissionApi = transmissionApiField.get(null);
            if (transmissionApi == null) return;

            Class<?> interactionClass = Class.forName("mrtjp.projectred.api.IBundledTileInteraction");
            Object interactionProxy = Proxy.newProxyInstance(
                    interactionClass.getClassLoader(),
                    new Class<?>[]{interactionClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("canConnectBundled".equals(name)) {
                            return handleCanConnect(args);
                        }
                        if ("getBundledSignal".equals(name)) {
                            return handleGetBundledSignal(args);
                        }
                        return defaultValue(method.getReturnType());
                    }
            );

            Method register = transmissionApi.getClass()
                    .getMethod("registerBundledTileInteraction", interactionClass);
            register.invoke(transmissionApi, interactionProxy);

            REGISTERED.set(true);
            ByteBlock.LOGGER.info("[ByteBlock] ProjectRed bundled compat registered.");
        } catch (ClassNotFoundException ignored) {
            // ProjectRed is not installed; safe no-op.
        } catch (Throwable t) {
            ByteBlock.LOGGER.warn("[ByteBlock] Failed to register ProjectRed bundled compat: {}", t.toString());
        }
    }

    private static boolean handleCanConnect(Object[] args) {
        if (args == null || args.length < 3) return false;
        if (!(args[0] instanceof Level level)) return false;
        if (!(args[1] instanceof BlockPos pos)) return false;
        if (!(args[2] instanceof Direction side)) return false;
        if (!(level.getBlockEntity(pos) instanceof RedstoneRelayBlockEntity relay)) return false;
        return relay.isBundledFace(side.get3DDataValue());
    }

    private static Object handleGetBundledSignal(Object[] args) {
        if (args == null || args.length < 3) return null;
        if (!(args[0] instanceof Level level)) return null;
        if (!(args[1] instanceof BlockPos pos)) return null;
        if (!(args[2] instanceof Direction side)) return null;
        if (!(level.getBlockEntity(pos) instanceof RedstoneRelayBlockEntity relay)) return null;
        if (!relay.isBundledFace(side.get3DDataValue())) return null;
        return relay.getBundledSignalArray(side.get3DDataValue());
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        if (returnType == char.class) return '\0';
        return null;
    }
}

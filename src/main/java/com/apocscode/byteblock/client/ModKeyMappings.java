package com.apocscode.byteblock.client;

import com.apocscode.byteblock.ByteBlock;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Keybinds registered client-side (MOD bus) and polled (GAME bus).
 * H toggles the Smart Glasses HUD overlay.
 */
public final class ModKeyMappings {

    public static final KeyMapping GLASSES_TOGGLE = new KeyMapping(
        "key.byteblock.glasses_toggle",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_H,
        "key.categories.byteblock"
    );

    private ModKeyMappings() {}

    @EventBusSubscriber(modid = ByteBlock.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void onRegister(RegisterKeyMappingsEvent event) {
            event.register(GLASSES_TOGGLE);
        }
    }

    @EventBusSubscriber(modid = ByteBlock.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent
        public static void onPlayerTick(PlayerTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || event.getEntity() != mc.player) return;
            while (GLASSES_TOGGLE.consumeClick()) {
                GlassesHudState.toggleVisible();
                mc.player.displayClientMessage(
                    Component.literal("Glasses HUD: " + (GlassesHudState.isVisible() ? "ON" : "OFF")),
                    true);
            }
        }
    }
}

package com.apocscode.javacraft;

import org.slf4j.Logger;

import com.apocscode.javacraft.entity.DroneEntity;
import com.apocscode.javacraft.entity.RobotEntity;
import com.apocscode.javacraft.init.*;
import com.apocscode.javacraft.network.BluetoothNetwork;
import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(JavaCraft.MODID)
public class JavaCraft {
    public static final String MODID = "javacraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JavaCraft(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        // Register all deferred registers
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);

        // Entity attributes
        modEventBus.addListener(this::registerEntityAttributes);

        // Game events
        NeoForge.EVENT_BUS.register(this);

        // Config
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("JavaCraft initializing — in-game Java computer simulator");
    }

    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.DRONE.get(), DroneEntity.createAttributes().build());
        event.put(ModEntities.ROBOT.get(), RobotEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("JavaCraft server starting — Bluetooth network online");
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        // Cleanup stale Bluetooth registrations every 5 seconds
        if (event.getServer().getTickCount() % 100 == 0) {
            event.getServer().getAllLevels().forEach(BluetoothNetwork::cleanup);
        }
    }

    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("JavaCraft client setup");
        }
    }
}

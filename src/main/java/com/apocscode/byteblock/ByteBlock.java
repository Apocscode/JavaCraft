package com.apocscode.byteblock;

import org.slf4j.Logger;

import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.RobotEntity;
import com.apocscode.byteblock.init.*;
import com.apocscode.byteblock.network.BluetoothNetwork;
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
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(ByteBlock.MODID)
public class ByteBlock {
    public static final String MODID = "byteblock";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ByteBlock(IEventBus modEventBus, ModContainer modContainer) {
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
        LOGGER.info("ByteBlock initializing — in-game Java computer simulator");
    }

    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.DRONE.get(), DroneEntity.createAttributes().build());
        event.put(ModEntities.ROBOT.get(), RobotEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("ByteBlock server starting — Bluetooth network online");
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
            LOGGER.info("ByteBlock client setup");
        }

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ModEntities.ROBOT.get(),
                    com.apocscode.byteblock.client.RobotRenderer::new);
            event.registerEntityRenderer(ModEntities.DRONE.get(),
                    com.apocscode.byteblock.client.DroneRenderer::new);
        }
    }
}

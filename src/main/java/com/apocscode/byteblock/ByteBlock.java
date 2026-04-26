package com.apocscode.byteblock;

import org.slf4j.Logger;

import com.apocscode.byteblock.compat.ProjectRedBundledCompat;
import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.RobotEntity;
import com.apocscode.byteblock.init.*;
import com.apocscode.byteblock.network.BluetoothNetwork;
import com.apocscode.byteblock.network.RenameDiskPayload;
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
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

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
        ModMenuTypes.MENUS.register(modEventBus);
        com.apocscode.byteblock.init.ModSounds.SOUNDS.register(modEventBus);

        // Entity attributes
        modEventBus.addListener(this::registerEntityAttributes);

        // Entity capabilities (FE energy)
        modEventBus.addListener(this::registerCapabilities);

        // Network payloads
        modEventBus.addListener(this::onRegisterPayloads);

        // Game events
        NeoForge.EVENT_BUS.register(this);

        // Config
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("ByteBlock initializing — in-game Java computer simulator");
        com.apocscode.byteblock.computer.storage.ModLinkRegistry.registerDefaults();
        com.apocscode.byteblock.computer.peripheral.PeripheralRegistry.registerDefaults();
        event.enqueueWork(ProjectRedBundledCompat::tryRegister);
    }
    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(
            RenameDiskPayload.TYPE,
            RenameDiskPayload.STREAM_CODEC,
            RenameDiskPayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.WriteToDiskPayload.TYPE,
            com.apocscode.byteblock.network.WriteToDiskPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.WriteToDiskPayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.ButtonConfigPayload.TYPE,
            com.apocscode.byteblock.network.ButtonConfigPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.ButtonConfigPayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.MonitorConfigPayload.TYPE,
            com.apocscode.byteblock.network.MonitorConfigPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.MonitorConfigPayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.RelayConfigPayload.TYPE,
            com.apocscode.byteblock.network.RelayConfigPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.RelayConfigPayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.GpsModeCyclePayload.TYPE,
            com.apocscode.byteblock.network.GpsModeCyclePayload.STREAM_CODEC,
            com.apocscode.byteblock.network.GpsModeCyclePayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.GlassesPushRequestPayload.TYPE,
            com.apocscode.byteblock.network.GlassesPushRequestPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.GlassesPushRequestPayload::handle
        );
        registrar.playToClient(
            com.apocscode.byteblock.network.GlassesHudPayload.TYPE,
            com.apocscode.byteblock.network.GlassesHudPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.GlassesHudPayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.SetEntityLabelPayload.TYPE,
            com.apocscode.byteblock.network.SetEntityLabelPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.SetEntityLabelPayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.RenameByteChestPayload.TYPE,
            com.apocscode.byteblock.network.RenameByteChestPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.RenameByteChestPayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.UploadGpsToComputerPayload.TYPE,
            com.apocscode.byteblock.network.UploadGpsToComputerPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.UploadGpsToComputerPayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.SetEntityPaintPayload.TYPE,
            com.apocscode.byteblock.network.SetEntityPaintPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.SetEntityPaintPayload::handle
        );
        registrar.playToClient(
            com.apocscode.byteblock.network.EntityPaintSyncPayload.TYPE,
            com.apocscode.byteblock.network.EntityPaintSyncPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.EntityPaintSyncPayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.SetEntityMutePayload.TYPE,
            com.apocscode.byteblock.network.SetEntityMutePayload.STREAM_CODEC,
            com.apocscode.byteblock.network.SetEntityMutePayload::handle
        );
        registrar.playToServer(
            com.apocscode.byteblock.network.PaintByteChestPayload.TYPE,
            com.apocscode.byteblock.network.PaintByteChestPayload.STREAM_CODEC,
            com.apocscode.byteblock.network.PaintByteChestPayload::handle
        );
    }
    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.DRONE.get(), DroneEntity.createAttributes().build());
        event.put(ModEntities.ROBOT.get(), RobotEntity.createAttributes().build());
        event.put(ModEntities.UNICYCLE_ROBOT.get(),
                com.apocscode.byteblock.entity.UnicycleRobotEntity.createAttributes().build());
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerEntity(
                Capabilities.EnergyStorage.ENTITY,
                ModEntities.ROBOT.get(),
                (entity, direction) -> entity.getEnergyStorage()
        );
        event.registerEntity(
                Capabilities.EnergyStorage.ENTITY,
                ModEntities.UNICYCLE_ROBOT.get(),
                (entity, direction) -> entity.getEnergyStorage()
        );
        // Charging station accepts FE input from any side via pipes/cables/wires.
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                com.apocscode.byteblock.init.ModBlockEntities.CHARGING_STATION.get(),
                (be, direction) -> be.getEnergyStorage()
        );
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
            event.enqueueWork(() -> {
                net.minecraft.client.renderer.item.ItemProperties.register(
                    ModItems.GLASSES.get(),
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "bt_active"),
                    (stack, level, entity, seed) -> {
                        if (entity instanceof net.minecraft.world.entity.LivingEntity living
                                && living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD) == stack
                                && living.level() != null) {
                            var pos = living.blockPosition();
                            int devs = com.apocscode.byteblock.network.BluetoothNetwork
                                    .getDevicesInRange(living.level(), pos,
                                            com.apocscode.byteblock.network.BluetoothNetwork.BLOCK_RANGE).size();
                            return devs > 0 ? 1.0f : 0.0f;
                        }
                        return 0.0f;
                    });
            });
        }

        @SubscribeEvent
        public static void onRegisterMenuScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
            event.register(ModMenuTypes.PRINTER.get(),
                    com.apocscode.byteblock.client.PrinterScreen::new);
            event.register(ModMenuTypes.DRIVE.get(),
                    com.apocscode.byteblock.client.DriveScreen::new);
            event.register(ModMenuTypes.ROBOT.get(),
                    com.apocscode.byteblock.client.RobotScreen::new);
            event.register(ModMenuTypes.DRONE.get(),
                    com.apocscode.byteblock.client.DroneScreen::new);
            event.register(ModMenuTypes.CHARGING_STATION.get(),
                    com.apocscode.byteblock.client.ChargingStationScreen::new);
        }

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ModEntities.ROBOT.get(),
                    com.apocscode.byteblock.client.RobotRenderer::new);
            event.registerEntityRenderer(ModEntities.UNICYCLE_ROBOT.get(),
                    com.apocscode.byteblock.client.UnicycleRobotRenderer::new);
            event.registerEntityRenderer(ModEntities.DRONE.get(),
                    com.apocscode.byteblock.client.DroneRenderer::new);
            event.registerBlockEntityRenderer(ModBlockEntities.MONITOR.get(),
                    com.apocscode.byteblock.client.MonitorRenderer::new);
            event.registerBlockEntityRenderer(ModBlockEntities.BUTTON_PANEL.get(),
                    com.apocscode.byteblock.client.ButtonPanelRenderer::new);
            event.registerBlockEntityRenderer(ModBlockEntities.REDSTONE_RELAY.get(),
                    com.apocscode.byteblock.client.RedstoneRelayRenderer::new);
        }

        @SubscribeEvent
        public static void onRegisterBlockColors(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Block event) {
            event.register((state, level, pos, tintIndex) -> {
                if (tintIndex != 1 || level == null || pos == null) return 0xFFFFFF;
                var be = level.getBlockEntity(pos);
                if (be instanceof com.apocscode.byteblock.block.entity.ByteChestBlockEntity chest) {
                    return chest.getTint();
                }
                return 0xFFFFFF;
            }, com.apocscode.byteblock.init.ModBlocks.BYTE_CHEST.get());
        }
    }
}

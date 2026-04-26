package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.ChargingStationBlock;
import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.RobotEntity;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.energy.EnergyStorage;

import java.util.List;

/**
 * Charging Station block entity — stores FE energy internally and
 * transfers it to nearby Robots and Drones every tick.
 *
 * Robots receive FE directly into their EnergyStorage.
 * Drones receive fuel ticks (1 FE = 1 fuel tick conversion).
 */
public class ChargingStationBlockEntity extends BlockEntity implements net.minecraft.world.MenuProvider {
    private java.util.UUID deviceId = java.util.UUID.randomUUID();
    private static final int MAX_ENERGY = 100_000;
    private static final int MAX_RECEIVE = 1000;  // RF/t input from pipes/cables
    private static final int CHARGE_RATE = 200;   // FE/t output to each entity
    private static final double RANGE = 3.0;
    private static final int FUEL_PER_FE = 2;     // 1 FE = 2 fuel ticks for drones

    private EnergyStorage energyStorage = new EnergyStorage(MAX_ENERGY, MAX_RECEIVE, CHARGE_RATE, 0);

    public ChargingStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CHARGING_STATION.get(), pos, state);
    }

    public void serverTick() {
        BluetoothNetwork.register(level, deviceId, worldPosition, 1, BluetoothNetwork.DeviceType.CHARGING_STATION);
        if (level.getGameTime() % 20 == 0) {
            boolean connected = BluetoothNetwork.isComputerInRange(level, worldPosition);
            BlockState current = level.getBlockState(worldPosition);
            if (current.getValue(ChargingStationBlock.CONNECTED) != connected) {
                level.setBlockAndUpdate(worldPosition, current.setValue(ChargingStationBlock.CONNECTED, connected));
            }
        }
        if (energyStorage.getEnergyStored() <= 0) return;

        AABB area = new AABB(worldPosition).inflate(RANGE);

        // Charge nearby robots
        List<RobotEntity> robots = level.getEntitiesOfClass(RobotEntity.class, area);
        for (RobotEntity robot : robots) {
            if (energyStorage.getEnergyStored() <= 0) break;
            EnergyStorage robotEnergy = robot.getEnergyStorage();
            int space = robotEnergy.getMaxEnergyStored() - robotEnergy.getEnergyStored();
            if (space > 0) {
                int toTransfer = Math.min(CHARGE_RATE, Math.min(space, energyStorage.getEnergyStored()));
                robotEnergy.receiveEnergy(toTransfer, false);
                energyStorage.extractEnergy(toTransfer, false);
                robot.markCharging();
                setChanged();
            }
        }

        // Charge nearby drones (convert FE to fuel ticks)
        List<DroneEntity> drones = level.getEntitiesOfClass(DroneEntity.class, area);
        for (DroneEntity drone : drones) {
            if (energyStorage.getEnergyStored() <= 0) break;
            if (drone.getFuelTicks() < 72000) {
                int fuelSpace = 72000 - drone.getFuelTicks();
                int feNeeded = Math.max(1, fuelSpace / FUEL_PER_FE);
                int feToUse = Math.min(CHARGE_RATE, Math.min(feNeeded, energyStorage.getEnergyStored()));
                int fuelToAdd = feToUse * FUEL_PER_FE;
                drone.addFuel(fuelToAdd);
                energyStorage.extractEnergy(feToUse, false);
                drone.markCharging();
                setChanged();
            }
        }
    }

    public EnergyStorage getEnergyStorage() { return energyStorage; }
    public int getEnergyStored() { return energyStorage.getEnergyStored(); }
    public int getMaxEnergy() { return MAX_ENERGY; }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.literal("Charging Station");
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int containerId,
            net.minecraft.world.entity.player.Inventory inv,
            net.minecraft.world.entity.player.Player player) {
        return new com.apocscode.byteblock.menu.ChargingStationMenu(containerId, inv, this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("DeviceId", deviceId);
        tag.putInt("Energy", energyStorage.getEnergyStored());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("DeviceId")) deviceId = tag.getUUID("DeviceId");
        if (tag.contains("Energy")) {
            int stored = tag.getInt("Energy");
            energyStorage = new EnergyStorage(MAX_ENERGY, MAX_RECEIVE, CHARGE_RATE, stored);
        }
    }
}

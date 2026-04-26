package com.apocscode.byteblock.client;

import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.EntityPaint;

import net.minecraft.network.chat.Component;

public class DroneCustomizeScreen extends EntityPaintScreen {
    public DroneCustomizeScreen(DroneEntity entity) {
        super(entity, entity.getPaint(), EntityPaint.SLOTS_DRONE,
              Component.literal("Customize Drone"));
    }
}

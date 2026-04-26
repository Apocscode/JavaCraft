package com.apocscode.byteblock.client;

import com.apocscode.byteblock.entity.EntityPaint;
import com.apocscode.byteblock.entity.RobotEntity;

import net.minecraft.network.chat.Component;

public class RobotCustomizeScreen extends EntityPaintScreen {
    public RobotCustomizeScreen(RobotEntity entity) {
        super(entity, entity.getPaint(), EntityPaint.SLOTS_ROBOT, true,
              Component.literal("Customize Robot"));
    }
}

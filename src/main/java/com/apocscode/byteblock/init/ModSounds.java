package com.apocscode.byteblock.init;

import com.apocscode.byteblock.ByteBlock;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    private ModSounds() {}

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, ByteBlock.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> ROBOT_R1_TALK = register("robot.r1_talk");
    public static final DeferredHolder<SoundEvent, SoundEvent> ROBOT_R2_TALK = register("robot.r2_talk");
    public static final DeferredHolder<SoundEvent, SoundEvent> ROBOT_R2_HANDTOOL = register("robot.r2_handtool");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ByteBlock.MODID, name);
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }
}

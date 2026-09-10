package dev.westercraft.creatures.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// LivingEntity#jumping is protected, so a mounted rider's held-jump state (synced
// server-side every tick via vanilla's PlayerInputC2SPacket while riding a vehicle —
// confirmed via decompiled source, not guessed) isn't otherwise readable from
// ViserionEntity, which is an unrelated LivingEntity subclass.
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Accessor("jumping")
	boolean creaturesofwesteros$isJumping();
}

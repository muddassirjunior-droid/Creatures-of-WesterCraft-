package dev.westercraft.creatures.mixin;

import dev.westercraft.creatures.entity.ViserionEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Vanilla's sneak-to-dismount is entirely rider-side (PlayerEntity#shouldDismount just
// returns isSneaking(), consulted every tick from tickRiding() — the vehicle is never asked;
// confirmed via decompiled source, not guessed). Sneak is now Viserion's descend key, so
// without this, holding sneak to dive would also eject the rider mid-air. Only suppress the
// vanilla behavior while actually flying on Viserion — every other mount keeps normal
// sneak-dismount. Checks isFlying() now, not isOnGround() (which Viserion no longer overrides
// at all - the flying/landed state machine was rebuilt around explicit triggers only, see
// ViserionEntity#travel, so isFlying() is the correct and only signal to key off here too).
@Mixin(PlayerEntity.class)
public abstract class PlayerDismountMixin {

	@Inject(method = "shouldDismount", at = @At("HEAD"), cancellable = true)
	private void creaturesofwesteros$blockAirborneViserionDismount(CallbackInfoReturnable<Boolean> cir) {
		PlayerEntity self = (PlayerEntity) (Object) this;
		if (self.getVehicle() instanceof ViserionEntity viserion && viserion.isFlying()) {
			cir.setReturnValue(false);
		}
	}
}

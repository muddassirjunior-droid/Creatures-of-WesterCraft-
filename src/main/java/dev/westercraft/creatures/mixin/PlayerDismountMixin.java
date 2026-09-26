package dev.westercraft.creatures.mixin;

import dev.westercraft.creatures.entity.ViserionEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Vanilla's sneak-to-dismount is entirely rider-side (PlayerEntity#shouldDismount just
// returns isSneaking(), consulted every tick from tickRiding() — the vehicle is never asked;
// confirmed via decompiled source, not guessed). Sneak is now Viserion's descend/land key, so
// without this, holding sneak would also eject the rider mid-air. See
// ViserionEntity#isDismountAllowed for the two conditions that turned out not to work here
// (isFlying()-only, then isOnGround()) before landing on "our own tracked flying/landing state,
// not vanilla collision state" - this mixin just defers to that method rather than duplicating
// the reasoning.
@Mixin(PlayerEntity.class)
public abstract class PlayerDismountMixin {

	@Inject(method = "shouldDismount", at = @At("HEAD"), cancellable = true)
	private void creaturesofwesteros$blockAirborneViserionDismount(CallbackInfoReturnable<Boolean> cir) {
		PlayerEntity self = (PlayerEntity) (Object) this;
		if (self.getVehicle() instanceof ViserionEntity viserion && !viserion.isDismountAllowed()) {
			cir.setReturnValue(false);
		}
	}
}

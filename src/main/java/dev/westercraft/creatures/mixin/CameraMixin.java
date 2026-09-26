package dev.westercraft.creatures.mixin;

import dev.westercraft.creatures.entity.ViserionEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Vanilla hardcodes third-person camera distance as the literal 4.0 in Camera#update
// (confirmed via decompiled 1.20.1 source, not guessed) — there's no public API to change
// it, so this is the only way to zoom out further specifically while riding Viserion.
//
// Second pass at this. The original version used @ModifyConstant on that literal 4.0 directly -
// crashed the whole modpack (confirmed via crash report, not guessed) because the "smallships"
// mod's own CameraMixin also @ModifyConstant's that exact same single 4.0 occurrence (there's
// only one in the whole method, confirmed via javap - it's the argument to clipToSpace(4.0)) for
// its own ship-zoom feature. Two mods fighting over the same literal constant slot is a real
// conflict, not something fixable by editing our own require/priority alone. Redirecting the
// clipToSpace(double) CALL itself instead - a different instruction entirely - means we never
// touch that constant at all, so smallships' own modifier is free to claim it uncontested; we
// just override whatever value clipToSpace() would have returned, after it's already run with
// whatever input every other mod agreed on.
@Mixin(Camera.class)
public abstract class CameraMixin {

	// Was 17.44 (already scaled up from vanilla's 4.0 to match the 0.4x model size) - pulled out
	// much further again so riding reads as a full vehicle/mount view (see the whole dragon and
	// its surroundings, like the dragon itself is the player) rather than a tight over-the-
	// shoulder shot sized for a person.
	private static final double VISERION_CAMERA_DISTANCE = 32.0;

	// clipToSpace is private on Camera - shadowed instead of an access widener since this is
	// called from directly inside a mixin targeting Camera itself (the standard Mixin idiom for
	// this; an access widener is for reaching a private member from ordinary mod code outside a
	// mixin, not needed here).
	@Shadow
	private native double clipToSpace(double distance);

	@Redirect(method = "update",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;clipToSpace(D)D"))
	private double creaturesofwesteros$overrideThirdPersonDistance(CameraMixin self, double desiredDistance,
			BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta) {
		if (focusedEntity != null && focusedEntity.getVehicle() instanceof ViserionEntity) {
			return self.clipToSpace(VISERION_CAMERA_DISTANCE);
		}
		return self.clipToSpace(desiredDistance);
	}
}

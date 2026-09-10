package dev.westercraft.creatures.mixin;

import dev.westercraft.creatures.entity.ViserionEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

// Vanilla hardcodes third-person camera distance as the literal 4.0 in Camera#update
// (confirmed via decompiled 1.20.1 source, not guessed) — there's no public API to change
// it, so this is the only way to zoom out further specifically while riding Viserion.
@Mixin(Camera.class)
public class CameraMixin {

	// Was 17.44 (already scaled up from vanilla's 4.0 to match the 0.4x model size) - pulled out
	// much further again so riding reads as a full vehicle/mount view (see the whole dragon and
	// its surroundings, like the dragon itself is the player) rather than a tight over-the-
	// shoulder shot sized for a person.
	private static final double VISERION_CAMERA_DISTANCE = 32.0;

	@ModifyConstant(method = "update", constant = @Constant(doubleValue = 4.0))
	private double creaturesofwesteros$modifyThirdPersonDistance(double original, BlockView area,
			Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta) {
		if (focusedEntity != null && focusedEntity.getVehicle() instanceof ViserionEntity) {
			return VISERION_CAMERA_DISTANCE;
		}
		return original;
	}
}

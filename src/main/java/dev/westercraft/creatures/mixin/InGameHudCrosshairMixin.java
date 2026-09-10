package dev.westercraft.creatures.mixin;

import dev.westercraft.creatures.entity.ViserionEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.option.Perspective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Vanilla only ever renders the crosshair in first person (confirmed via decompiled InGameHud -
// #render's outer gate and #renderCrosshair's own internal re-check both call
// Perspective#isFirstPerson(), so both call sites need redirecting or the inner one still blocks
// it). While controlling Viserion in third person the player is aiming the DRAGON, not their own
// character - that needs a crosshair just as much as first person normally does.
@Mixin(InGameHud.class)
public class InGameHudCrosshairMixin {
	@Redirect(method = {"render", "renderCrosshair"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/Perspective;isFirstPerson()Z"))
	private boolean creaturesofwesteros$forceCrosshairWhileControllingViserion(Perspective perspective) {
		if (perspective.isFirstPerson()) {
			return true;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player != null
				&& client.player.getVehicle() instanceof ViserionEntity viserion
				&& viserion.getControllingPassenger() == client.player;
	}
}

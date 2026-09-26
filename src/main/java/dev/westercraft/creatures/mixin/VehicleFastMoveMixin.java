package dev.westercraft.creatures.mixin;

import dev.westercraft.creatures.entity.ViserionEntity;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Real bug, not guessed - confirmed via decompile of ServerPlayNetworkHandler#onVehicleMove:
// vanilla's own "vehicle moved too quickly" anti-cheat check computes
// (reportedDelta.lengthSquared() - vehicle.getVelocity().lengthSquared()) and, if that exceeds
// 100.0, logs a warning, snaps the vehicle straight back to its last known server position (a
// VehicleMoveS2CPacket built from the vehicle's OLD position) and drops the move entirely - no
// disconnect, just a hard position reset every time it fires. This is exactly the reported
// "server glitches out when I move too fast" - Viserion's dive/sprint throttle speeds routinely
// produce a reported per-tick delta whose square comfortably clears 100 units more than its own
// getVelocity() alone accounts for (network jitter/multiple ticks' worth of movement arriving
// in one packet only makes this worse), so the anti-cheat was firing and rubber-banding the
// dragon (and its rider) back constantly during fast flight.
//
// Redirecting just this one getVelocity() call (confirmed via javap to be the only call to
// Entity#getVelocity() anywhere in onVehicleMove, so this can't bleed into any other check) to
// report an inflated velocity for a ViserionEntity specifically - the returned Vec3d is a
// throwaway local used only for this one lengthSquared() comparison, never stored back onto the
// entity, so spoofing it here has zero effect on Viserion's actual physics. Every other vehicle
// (boats, horses, other mods' mounts) still gets its real velocity and the real check, so this
// doesn't loosen anti-cheat for anything else server-wide.
@Mixin(ServerPlayNetworkHandler.class)
public abstract class VehicleFastMoveMixin {

	@Redirect(method = "onVehicleMove",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getVelocity()Lnet/minecraft/util/math/Vec3d;"))
	private Vec3d creaturesofwesteros$reportInflatedVelocityForViserion(Entity vehicle) {
		if (vehicle instanceof ViserionEntity) {
			return new Vec3d(128.0, 128.0, 128.0);
		}
		return vehicle.getVelocity();
	}
}

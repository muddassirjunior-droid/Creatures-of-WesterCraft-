package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.CreaturesOfWesterosMod;
import dev.westercraft.creatures.entity.ViserionEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class ViserionGeoModel extends DefaultedEntityGeoModel<ViserionEntity> {
	// turnsHead=true - GeckoLib already has real head tracking built in (confirmed via decompile
	// of DefaultedEntityGeoModel#setCustomAnimations, not guessed): it looks up a bone named
	// "head" and rotates it from the entity's own netHeadYaw/headPitch, the same vanilla-standard
	// math LivingEntityRenderer uses (clamps to +/-85 degrees, pulls the body along past 50). The
	// lookup (AnimationProcessor#getBone) is a plain case-sensitive Map.get - also confirmed via
	// decompile, not assumed - so the model's bone had to be renamed from "Head" to lowercase
	// "head" in both viserion.geo.json and viserion.animation.json for this to actually resolve.
	// See tickControlled for the other half (headYaw needs to actually diverge from bodyYaw for
	// this to have anything to show).
	public ViserionGeoModel() {
		super(Identifier.of(CreaturesOfWesterosMod.MOD_ID, "viserion"), true);
	}
}

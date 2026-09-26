package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.CreaturesOfWesterosMod;
import dev.westercraft.creatures.entity.WyvernEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

// Same rig/head-tracking setup as ViserionGeoModel - see that class for the full reasoning on why
// the bone had to be renamed from "Head" to lowercase "head" (GeckoLib's turnsHead does a
// case-sensitive lookup for exactly that name).
public class WyvernGeoModel extends DefaultedEntityGeoModel<WyvernEntity> {
	public WyvernGeoModel() {
		super(Identifier.of(CreaturesOfWesterosMod.MOD_ID, "wyvern"), true);
	}
}

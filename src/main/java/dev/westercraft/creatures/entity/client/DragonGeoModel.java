package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.CreaturesOfWesterosMod;
import dev.westercraft.creatures.entity.DragonEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class DragonGeoModel extends DefaultedEntityGeoModel<DragonEntity> {
	public DragonGeoModel() {
		super(Identifier.of(CreaturesOfWesterosMod.MOD_ID, "dragon"));
	}
}

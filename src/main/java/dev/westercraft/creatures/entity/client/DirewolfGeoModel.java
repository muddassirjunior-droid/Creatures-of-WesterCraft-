package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.CreaturesOfWesterosMod;
import dev.westercraft.creatures.entity.DirewolfEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class DirewolfGeoModel extends DefaultedEntityGeoModel<DirewolfEntity> {
	// Coat variants beyond the default (black) texture the constructor already wires up.
	// Add new Identifiers here (and a matching PNG under textures/entity/) as more coats get
	// painted — DirewolfEntity.COAT_COUNT needs bumping to match.
	private static final Identifier SILVER_TEXTURE = Identifier.of(CreaturesOfWesterosMod.MOD_ID, "textures/entity/direwolf_silver.png");

	public DirewolfGeoModel() {
		super(Identifier.of(CreaturesOfWesterosMod.MOD_ID, "direwolf"));
	}

	@Override
	public Identifier getTextureResource(DirewolfEntity animatable) {
		return switch (animatable.getCoat()) {
			case DirewolfEntity.COAT_SILVER -> SILVER_TEXTURE;
			default -> super.getTextureResource(animatable);
		};
	}
}

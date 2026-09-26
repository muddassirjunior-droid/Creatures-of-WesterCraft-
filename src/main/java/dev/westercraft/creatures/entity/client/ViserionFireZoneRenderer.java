package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.entity.ViserionFireZoneEntity;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.util.Identifier;

// No model, no texture - this is a pure server-side tick/damage marker (see
// ViserionFireZoneEntity), every visual it has is particles spawned directly server-side, same
// pattern as ViserionPartRenderer.
public class ViserionFireZoneRenderer extends EntityRenderer<ViserionFireZoneEntity> {
	public ViserionFireZoneRenderer(EntityRendererFactory.Context context) {
		super(context);
	}

	@Override
	public Identifier getTexture(ViserionFireZoneEntity entity) {
		return null;
	}
}

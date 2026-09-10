package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.entity.ViserionEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ViserionRenderer extends GeoEntityRenderer<ViserionEntity> {
	public ViserionRenderer(EntityRendererFactory.Context context) {
		super(context, new ViserionGeoModel());
		// 0.4x the full authored model size. See ViserionPartId/ModEntities/ViserionEntity's
		// combat ranges/CameraMixin for the matching rescale — those numbers are all in blocks,
		// not model units, so they have to move with this whenever it changes.
		this.withScale(0.4f, 0.4f);
	}
}

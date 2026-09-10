package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.entity.DirewolfEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class DirewolfRenderer extends GeoEntityRenderer<DirewolfEntity> {
	public DirewolfRenderer(EntityRendererFactory.Context context) {
		super(context, new DirewolfGeoModel());
	}
}

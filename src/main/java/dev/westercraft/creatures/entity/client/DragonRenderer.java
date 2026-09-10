package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.entity.DragonEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class DragonRenderer extends GeoEntityRenderer<DragonEntity> {
	public DragonRenderer(EntityRendererFactory.Context context) {
		super(context, new DragonGeoModel());
	}
}

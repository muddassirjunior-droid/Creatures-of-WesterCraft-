package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.entity.WyvernEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class WyvernRenderer extends GeoEntityRenderer<WyvernEntity> {
	public WyvernRenderer(EntityRendererFactory.Context context) {
		super(context, new WyvernGeoModel());
		// Was 0.25, then 0.18 - shrunk again per explicit request ("also make it smaller"). The
		// hitbox is no longer derived from this scale at all - see ModEntities#WYVERN, kept
		// deliberately bigger than the visual model (per an earlier "make the hitbox bigger"
		// request), so the two stay mismatched: a smaller-looking creature that's still easy to
		// actually hit.
		this.withScale(0.13f, 0.13f);
	}
}

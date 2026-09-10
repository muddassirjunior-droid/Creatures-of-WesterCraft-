package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.entity.ViserionFireballEntity;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.util.Identifier;

// No model, no texture - this projectile is a pure particle-trail effect (see
// ViserionFireballEntity#getParticleType/tick), same pattern as ViserionPartRenderer.
public class ViserionFireballRenderer extends EntityRenderer<ViserionFireballEntity> {
	public ViserionFireballRenderer(EntityRendererFactory.Context context) {
		super(context);
	}

	@Override
	public Identifier getTexture(ViserionFireballEntity entity) {
		return null;
	}
}

package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.entity.ViserionPart;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.util.Identifier;

public class ViserionPartRenderer extends EntityRenderer<ViserionPart> {
	public ViserionPartRenderer(EntityRendererFactory.Context context) {
		super(context);
	}

	@Override
	public Identifier getTexture(ViserionPart entity) {
		return null;
	}
}

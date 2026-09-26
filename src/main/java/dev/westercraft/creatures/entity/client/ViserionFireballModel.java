package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.CreaturesOfWesterosMod;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;

// A single textured cube (from the "TestFireBall" Blockbench export - box UV, 16x16 texture,
// 4x4x4) instead of the pure-particle-trail look this projectile used to have. Centered on the
// entity's own origin (-2..2 on every axis) rather than Blockbench's authored 0..4 on Y - that
// offset is Blockbench's usual "feet at Y=0" convention for ground-standing creature models,
// which doesn't apply to a rigid flying projectile with no feet. No animation/setAngles needed
// either, so this skips the full EntityModel<T> wrapper (see DirewolfModel for that fuller
// pattern, unused/superseded by GeckoLib but a confirmed-compiling reference) - just the raw
// part, spun in the renderer instead of posed.
public final class ViserionFireballModel {
	public static final EntityModelLayer LAYER =
			new EntityModelLayer(Identifier.of(CreaturesOfWesterosMod.MOD_ID, "viserion_fireball"), "main");

	private ViserionFireballModel() {
	}

	public static TexturedModelData getTexturedModelData() {
		ModelData modelData = new ModelData();
		ModelPartData root = modelData.getRoot();
		// Was 4x4x4 - doubled per explicit "increase the size of the fireball" request, paired
		// with the entity hitbox bump in ModEntities and the "a few big ones instead of a
		// flamethrower spray" rework in ViserionEntity#DRACARYS_PROJECTILES_PER_TICK.
		root.addChild("cube", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-4.0F, -4.0F, -4.0F, 8.0F, 8.0F, 8.0F),
				ModelTransform.NONE);
		return TexturedModelData.of(modelData, 16, 16);
	}
}

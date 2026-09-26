package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.CreaturesOfWesterosMod;
import dev.westercraft.creatures.entity.ViserionFireballEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

// Real textured cube now (see ViserionFireballModel), not the pure-particle-trail look this used
// to have. Tumbles continuously off entity.age rather than facing travel direction - this is a
// roughly-symmetric fire-cube blob, not an arrow, so a precise velocity-facing rotation (which
// would need prevYaw/prevPitch interpolation this project has no prior confirmed pattern for)
// isn't worth the risk for a shape where orientation barely reads anyway; the tumble sells
// "chaotic fireball" fine on its own.
public class ViserionFireballRenderer extends EntityRenderer<ViserionFireballEntity> {
	private static final Identifier TEXTURE =
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "textures/entity/viserion_fireball.png");
	private static final float SPIN_DEGREES_PER_TICK = 20.0F;

	private final ModelPart cube;

	public ViserionFireballRenderer(EntityRendererFactory.Context context) {
		super(context);
		this.cube = context.getPart(ViserionFireballModel.LAYER).getChild("cube");
	}

	@Override
	public Identifier getTexture(ViserionFireballEntity entity) {
		return TEXTURE;
	}

	@Override
	public void render(ViserionFireballEntity entity, float yaw, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		matrices.push();
		float spin = (entity.age + tickDelta) * SPIN_DEGREES_PER_TICK;
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(spin));
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(spin * 0.7F));
		VertexConsumer vertexConsumer = vertexConsumers.getBuffer(RenderLayer.getEntityCutout(getTexture(entity)));
		this.cube.render(matrices, vertexConsumer, light, OverlayTexture.DEFAULT_UV);
		matrices.pop();
		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
	}
}

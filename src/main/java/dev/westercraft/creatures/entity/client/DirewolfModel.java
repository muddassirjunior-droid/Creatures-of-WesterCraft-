package dev.westercraft.creatures.entity.client;

import dev.westercraft.creatures.CreaturesOfWesterosMod;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

public class DirewolfModel<T extends Entity> extends EntityModel<T> {
	public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of(CreaturesOfWesterosMod.MOD_ID, "direwolf"), "main");

	private final ModelPart body;
	private final ModelPart legFrontLeft;
	private final ModelPart legFrontLeftLower;
	private final ModelPart legFrontRight;
	private final ModelPart legFrontRightLower;
	private final ModelPart legBackLeft;
	private final ModelPart legBackLeftLower;
	private final ModelPart legBackRight;
	private final ModelPart legBackRightLower;
	private final ModelPart head;
	private final ModelPart tail1;
	private final ModelPart tail2;

	public DirewolfModel(ModelPart root) {
		this.body = root.getChild("body");
		this.legFrontLeft = this.body.getChild("leg_front_left");
		this.legFrontLeftLower = this.legFrontLeft.getChild("leg_front_left_lower");
		this.legFrontRight = this.body.getChild("leg_front_right");
		this.legFrontRightLower = this.legFrontRight.getChild("leg_front_right_lower");
		this.legBackLeft = this.body.getChild("leg_back_left");
		this.legBackLeftLower = this.legBackLeft.getChild("leg_back_left_lower");
		this.legBackRight = this.body.getChild("leg_back_right");
		this.legBackRightLower = this.legBackRight.getChild("leg_back_right_lower");
		this.head = this.body.getChild("head");
		this.tail1 = this.body.getChild("tail_1");
		this.tail2 = this.tail1.getChild("tail_2");
	}

	public static TexturedModelData getTexturedModelData() {
		ModelData modelData = new ModelData();
		ModelPartData root = modelData.getRoot();

		ModelPartData body = root.addChild("body", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-5.0F, -1.0F, -14.0F, 10.0F, 6.0F, 26.0F)
				.uv(0, 0).cuboid(-6.0F, -2.0F, -16.0F, 12.0F, 5.0F, 6.0F),
				ModelTransform.pivot(0.0F, 4.0F, 0.0F));

		ModelPartData legFrontLeft = body.addChild("leg_front_left", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-1.5F, -1.0F, -2.0F, 3.0F, 9.0F, 4.0F),
				ModelTransform.of(4.0F, 4.0F, -10.0F, 0.1047F, 0.0F, 0.0F));

		legFrontLeft.addChild("leg_front_left_lower", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-1.3F, -1.0F, -1.5F, 2.6F, 9.0F, 3.0F)
				.uv(0, 0).cuboid(-1.6F, 7.5F, -1.0F, 3.2F, 1.5F, 3.5F),
				ModelTransform.of(0.0F, 7.0F, 1.0F, -0.2094F, 0.0F, 0.0F));

		ModelPartData legFrontRight = body.addChild("leg_front_right", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-1.5F, -1.0F, -2.0F, 3.0F, 9.0F, 4.0F),
				ModelTransform.of(-4.0F, 4.0F, -10.0F, 0.1047F, 0.0F, 0.0F));

		legFrontRight.addChild("leg_front_right_lower", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-1.3F, -1.0F, -1.5F, 2.6F, 9.0F, 3.0F)
				.uv(0, 0).cuboid(-1.6F, 7.5F, -1.0F, 3.2F, 1.5F, 3.5F),
				ModelTransform.of(0.0F, 7.0F, 1.0F, -0.2094F, 0.0F, 0.0F));

		ModelPartData legBackLeft = body.addChild("leg_back_left", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-2.0F, -1.0F, -3.0F, 4.0F, 9.0F, 5.5F),
				ModelTransform.of(4.0F, 4.0F, 9.0F, -0.1396F, 0.0F, 0.0F));

		legBackLeft.addChild("leg_back_left_lower", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-1.3F, -1.0F, -2.0F, 2.6F, 9.0F, 3.5F)
				.uv(0, 0).cuboid(-1.6F, 7.5F, -2.0F, 3.2F, 1.5F, 4.0F),
				ModelTransform.of(0.0F, 7.0F, 1.5F, 0.2793F, 0.0F, 0.0F));

		ModelPartData legBackRight = body.addChild("leg_back_right", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-2.0F, -1.0F, -3.0F, 4.0F, 9.0F, 5.5F),
				ModelTransform.of(-4.0F, 4.0F, 9.0F, -0.1396F, 0.0F, 0.0F));

		legBackRight.addChild("leg_back_right_lower", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-1.3F, -1.0F, -2.0F, 2.6F, 9.0F, 3.5F)
				.uv(0, 0).cuboid(-1.6F, 7.5F, -2.0F, 3.2F, 1.5F, 4.0F),
				ModelTransform.of(0.0F, 7.0F, 1.5F, 0.2793F, 0.0F, 0.0F));

		ModelPartData head = body.addChild("head", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-3.0F, -3.0F, -5.0F, 6.0F, 5.0F, 5.0F)
				.uv(0, 0).cuboid(-1.8F, -1.5F, -15.0F, 3.6F, 3.0F, 10.0F)
				.uv(0, 0).cuboid(-1.5F, 1.3F, -14.0F, 3.0F, 1.0F, 8.0F),
				ModelTransform.pivot(0.0F, 1.0F, -14.0F));

		head.addChild("ear_right", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-0.9F, -3.0F, -1.3F, 1.8F, 3.0F, 2.5F),
				ModelTransform.of(-1.9F, -2.5F, -2.2F, 0.0F, 0.0F, -0.2094F));

		head.addChild("ear_left", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-0.9F, -3.0F, -1.3F, 1.8F, 3.0F, 2.5F),
				ModelTransform.of(1.9F, -2.5F, -2.2F, 0.0F, 0.0F, 0.2094F));

		ModelPartData tail1 = body.addChild("tail_1", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-1.8F, -1.0F, -1.0F, 3.6F, 4.0F, 11.0F),
				ModelTransform.of(0.0F, 0.0F, 11.0F, 0.1745F, 0.0F, 0.0F));

		tail1.addChild("tail_2", ModelPartBuilder.create()
				.uv(0, 0).cuboid(-2.2F, -3.0F, -2.0F, 4.4F, 6.5F, 11.0F),
				ModelTransform.of(0.0F, 1.0F, 10.0F, 0.2793F, 0.0F, 0.0F));

		return TexturedModelData.of(modelData, 128, 128);
	}

	@Override
	public void setAngles(T entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
		head.yaw = headYaw * ((float) Math.PI / 180F);
		head.pitch = headPitch * ((float) Math.PI / 180F);

		legFrontLeft.pitch = MathHelper.cos(limbAngle * 0.6662F) * 1.4F * limbDistance;
		legFrontRight.pitch = MathHelper.cos(limbAngle * 0.6662F + (float) Math.PI) * 1.4F * limbDistance;
		legBackLeft.pitch = MathHelper.cos(limbAngle * 0.6662F + (float) Math.PI) * 1.4F * limbDistance;
		legBackRight.pitch = MathHelper.cos(limbAngle * 0.6662F) * 1.4F * limbDistance;
	}

	@Override
	public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, float red, float green, float blue, float alpha) {
		body.render(matrices, vertices, light, overlay, red, green, blue, alpha);
	}
}

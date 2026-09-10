package dev.westercraft.creatures.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

public class ViserionPart extends Entity {

	public ViserionEntity owner;
	public String partName = "part";
	private EntityDimensions partDimensions = EntityDimensions.fixed(1.0f, 1.0f);

	public ViserionPart(EntityType<ViserionPart> type, World world) {
		super(type, world);
	}

	public void setOwner(ViserionEntity owner, String name, float width, float height) {
		this.owner = owner;
		this.partName = name;
		this.partDimensions = EntityDimensions.fixed(width, height);
		this.calculateDimensions();
	}

	@Override
	protected void initDataTracker() {
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
	}

	@Override
	public boolean canHit() {
		return true;
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		if (this.isInvulnerableTo(source) || this.owner == null) {
			return false;
		}
		return this.owner.damageFromPart(this, source, amount);
	}

	@Override
	public boolean isPartOf(Entity entity) {
		return this == entity || (this.owner != null && this.owner == entity);
	}

	@Override
	public EntityDimensions getDimensions(EntityPose pose) {
		return this.partDimensions;
	}

	@Override
	public boolean shouldSave() {
		return false;
	}
}

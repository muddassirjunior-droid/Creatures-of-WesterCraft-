package dev.westercraft.creatures.entity;

import dev.westercraft.creatures.MeltedBlocksState;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

import java.util.UUID;

// A lingering burning patch left where a fireball actually lands - per explicit request, "a 3
// second dragons breath effect that burns everything in it, wherever the fireball lands." No
// particles at all any more (was FLAME/LAVA while burning, then a 7-second CAMPFIRE_COSY_SMOKE
// aftermath) - per explicit follow-up request ("no smoke please and no particles JUST FIRE LOTS
// OF FIRE EVERYWHERE"), the visual is now real fire blocks scattered around the landing point
// (see igniteArea, fired once on the zone's first tick) instead of any particle effect, and the
// smoke-only aftermath phase is gone entirely. Pure marker/tick entity like ViserionPart (no
// hitbox, no model/renderer), not a real AreaEffectCloudEntity - vanilla's cloud applies STATUS
// EFFECTS (Instant Damage), which would deal ordinary magic damage instead of this mod's
// established armor-bypassing dragon_breath damage type every other fire source here uses.
// Hand-rolled instead, same getEntitiesByClass+damage() pattern the old breath raycast used to
// use before that got replaced by fireballs entirely.
public class ViserionFireZoneEntity extends Entity {
	public static final int DURATION_TICKS = 60;
	private static final double RADIUS = 3.0;
	private static final float DAMAGE = 4.0f;
	private static final int DAMAGE_INTERVAL_TICKS = 10;
	private static final int IGNITE_TICKS = 60;
	private static final int IGNITE_RADIUS = 2;

	private UUID ownerUuid;
	private UUID excludedUuid;
	private boolean ignited;

	public ViserionFireZoneEntity(EntityType<ViserionFireZoneEntity> type, World world) {
		super(type, world);
	}

	// owner = the dragon (so it never burns itself), excluded = the rider (so it never burns
	// whoever's actually flying it) - same exclusions the old breath damage loop already used.
	public static ViserionFireZoneEntity create(ServerWorld world, Vec3d pos, Entity owner, Entity excluded) {
		ViserionFireZoneEntity zone = new ViserionFireZoneEntity(ModEntities.VISERION_FIRE_ZONE, world);
		zone.setPosition(pos.x, pos.y, pos.z);
		zone.ownerUuid = owner != null ? owner.getUuid() : null;
		zone.excludedUuid = excluded != null ? excluded.getUuid() : null;
		world.spawnEntity(zone);
		return zone;
	}

	@Override
	protected void initDataTracker() {
	}

	@Override
	public void tick() {
		super.tick();
		if (this.getWorld().isClient) {
			return;
		}
		if (this.age >= DURATION_TICKS) {
			this.discard();
			return;
		}
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		if (!this.ignited) {
			this.ignited = true;
			igniteArea(serverWorld);
		}
		if (this.age % DAMAGE_INTERVAL_TICKS != 0) {
			return;
		}
		Box searchBox = this.getBoundingBox().expand(RADIUS);
		for (LivingEntity target : this.getWorld().getEntitiesByClass(LivingEntity.class, searchBox,
				e -> e.isAlive() && !e.getUuid().equals(this.ownerUuid) && !e.getUuid().equals(this.excludedUuid))) {
			if (this.squaredDistanceTo(target) > RADIUS * RADIUS) {
				continue;
			}
			target.damage(this.getDamageSources().dragonBreath(), DAMAGE);
			target.setFireTicks(Math.max(target.getFireTicks(), IGNITE_TICKS));
		}
	}

	// Same scattered-real-fire approach as ViserionFireballEntity#igniteArea - tries every column
	// in a small square around the zone's landing point, one block above and below, igniting
	// wherever AbstractFireBlock actually allows it. Tracked into MeltedBlocksState like every
	// other dragon-placed fire, so /dragon revert cleans this up too.
	private void igniteArea(ServerWorld serverWorld) {
		if (!serverWorld.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
			return;
		}
		BlockPos center = BlockPos.ofFloored(this.getPos());
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		for (int dx = -IGNITE_RADIUS; dx <= IGNITE_RADIUS; dx++) {
			for (int dz = -IGNITE_RADIUS; dz <= IGNITE_RADIUS; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (AbstractFireBlock.canPlaceAt(serverWorld, mutable, Direction.UP)) {
						BlockState before = serverWorld.getBlockState(mutable);
						serverWorld.setBlockState(mutable, AbstractFireBlock.getState(serverWorld, mutable));
						MeltedBlocksState.get(serverWorld).addIgnitedFire(mutable.toImmutable(), before);
					}
				}
			}
		}
	}

	@Override
	public boolean shouldSave() {
		return false;
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
	}
}

package dev.westercraft.creatures.entity;

import net.minecraft.block.AbstractFireBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import org.joml.Vector3f;

// A custom fireball, not vanilla's SmallFireballEntity - that one self-accelerates from a
// near-crawl over ~3 seconds (ExplosiveProjectileEntity's own power/drag physics, confirmed via
// decompile) and renders as a small blaze fireball model, neither of which reads as Viserion's
// dragonfire. Extends ExplosiveProjectileEntity rather than ProjectileEntity directly - the
// latter's (EntityType, World) constructor is package-private in net.minecraft.entity.projectile,
// so a mod outside that package can't subclass it directly; ExplosiveProjectileEntity is vanilla's
// own public extension point for exactly this (SmallFireballEntity itself extends it). Reuses all
// of its battle-tested collision detection (ProjectileUtil.getCollision, onCollision dispatch)
// and just overrides the two things that were actually wrong: constant velocity instead of
// self-acceleration (getDrag() + zeroing power below), and a pure particle-trail look instead of
// a modeled entity (see ViserionFireballRenderer - renders nothing at all).
public class ViserionFireballEntity extends ExplosiveProjectileEntity {
	private static final double SPEED = 1.6;
	private static final float DAMAGE = 6.0f;
	private static final int IGNITE_TICKS = 100;
	// Safety despawn for a shot that never hits anything (fired into open sky) - vanilla
	// fireballs have no built-in max range/lifetime at all.
	private static final int MAX_AGE_TICKS = 200;

	private static final DustParticleEffect TRAIL_GOLD =
			new DustParticleEffect(new Vector3f(1.0f, 0.85f, 0.55f), 1.3f);
	private static final DustParticleEffect TRAIL_ORANGE =
			new DustParticleEffect(new Vector3f(1.0f, 0.55f, 0.15f), 1.2f);

	public ViserionFireballEntity(EntityType<? extends ViserionFireballEntity> type, World world) {
		super(type, world);
	}

	public ViserionFireballEntity(World world, LivingEntity owner, Vec3d origin, Vec3d direction) {
		super(ModEntities.VISERION_FIREBALL, owner, direction.x, direction.y, direction.z, world);
		this.setPosition(origin.x, origin.y, origin.z);
		// The super constructor above computed powerX/Y/Z from direction * 0.1 for vanilla's own
		// self-accelerating physics - zeroed out here since getDrag()'s 1.0 override makes that
		// term the only thing that would otherwise change velocity tick to tick.
		this.powerX = 0.0;
		this.powerY = 0.0;
		this.powerZ = 0.0;
		this.setVelocity(direction.normalize().multiply(SPEED));
		this.setNoGravity(true);
	}

	@Override
	protected void initDataTracker() {
	}

	// 1.0 = no drag - combined with zeroed power (see constructor), velocity never changes
	// magnitude or direction tick to tick: a real straight, constant-speed shot instead of the
	// slow-then-speeding-up crawl vanilla fireballs do.
	@Override
	protected float getDrag() {
		return 1.0f;
	}

	// Feeds the gold dust trail ExplosiveProjectileEntity's own tick() already spawns one of
	// per tick (vanilla defaults to plain SMOKE here) - supplemented with a denser multi-color
	// burst below for an actual visible trail, not a single sparse particle.
	@Override
	protected ParticleEffect getParticleType() {
		return TRAIL_GOLD;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.getWorld().isClient) {
			return;
		}
		if (this.getWorld() instanceof ServerWorld serverWorld) {
			Vec3d pos = this.getPos();
			serverWorld.spawnParticles(TRAIL_GOLD, pos.x, pos.y, pos.z, 2, 0.1, 0.1, 0.1, 0.01);
			serverWorld.spawnParticles(TRAIL_ORANGE, pos.x, pos.y, pos.z, 1, 0.1, 0.1, 0.1, 0.01);
		}
		if (this.age > MAX_AGE_TICKS) {
			this.discard();
		}
	}

	@Override
	protected void onEntityHit(EntityHitResult entityHitResult) {
		super.onEntityHit(entityHitResult);
		if (this.getWorld().isClient) {
			return;
		}
		Entity hit = entityHitResult.getEntity();
		if (hit instanceof LivingEntity living && hit != this.getOwner()) {
			// Same armor-bypassing damage type the breath itself now uses (minecraft:dragon_breath,
			// confirmed tagged bypasses_armor) - a direct fireball hit shouldn't be softer than
			// standing in the stream.
			living.damage(this.getDamageSources().dragonBreath(), DAMAGE);
			living.setFireTicks(Math.max(living.getFireTicks(), IGNITE_TICKS));
		}
		impactEffects(entityHitResult.getPos());
		this.discard();
	}

	@Override
	protected void onBlockHit(BlockHitResult blockHitResult) {
		super.onBlockHit(blockHitResult);
		if (this.getWorld().isClient) {
			return;
		}
		impactEffects(blockHitResult.getPos());
		this.discard();
	}

	// Simple ignite only, not the breath's full crater destruction (that's the breath's own
	// signature move now - this is a supplementary punch during it, not a second copy of it).
	private void impactEffects(Vec3d pos) {
		if (this.getWorld() instanceof ServerWorld serverWorld) {
			if (serverWorld.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
				BlockPos base = BlockPos.ofFloored(pos);
				if (AbstractFireBlock.canPlaceAt(serverWorld, base, Direction.UP)) {
					serverWorld.setBlockState(base, AbstractFireBlock.getState(serverWorld, base));
				} else if (AbstractFireBlock.canPlaceAt(serverWorld, base.down(), Direction.UP)) {
					serverWorld.setBlockState(base.down(), AbstractFireBlock.getState(serverWorld, base.down()));
				}
			}
			serverWorld.spawnParticles(TRAIL_ORANGE, pos.x, pos.y, pos.z, 20, 0.3, 0.3, 0.3, 0.05);
			serverWorld.spawnParticles(ParticleTypes.FLAME, pos.x, pos.y, pos.z, 10, 0.2, 0.2, 0.2, 0.02);
		}
	}
}

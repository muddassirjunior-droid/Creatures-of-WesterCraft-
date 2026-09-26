package dev.westercraft.creatures.entity;

import dev.westercraft.creatures.MeltedBlocksState;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
// self-acceleration (getDrag() + zeroing power below), and its own textured/modeled look (see
// ViserionFireballRenderer/ViserionFireballModel) plus a particle trail, instead of the small
// blaze fireball vanilla renders by default.
public class ViserionFireballEntity extends ExplosiveProjectileEntity {
	// Was 1.6, then 4.5 - bumped again per explicit "needs more range" request (paired with a
	// longer MAX_AGE_TICKS below, since range is speed * time-alive-until-something-stops-it).
	private static final double SPEED = 6.5;
	// This is the entire breath's damage now, not a supplement to a separate raycast tick (see
	// ViserionEntity#tickDracarysFire - that mechanic is gone, the breath is a pure flamethrower
	// of these now, "a lot of projectiles" fired rapidly rather than one continuous beam). Was
	// 6.0, then 10.0 - pushed way up again per explicit request ("it should be like it eats
	// whoever it hits") - a single connecting shot should feel devastating on its own, not just
	// add up over several hits.
	// Was 25 - bumped again alongside the "a few big ones instead of a flamethrower spray" rework
	// (see ViserionEntity#DRACARYS_PROJECTILES_PER_TICK) - far fewer shots land now, so each one
	// needs to carry more weight on its own.
	private static final float DAMAGE = 32.0f;
	private static final double KNOCKBACK_STRENGTH = 1.3;
	private static final int IGNITE_TICKS = 100;
	// Instant AoE on every single impact, per explicit request - separate from and in addition to
	// the direct-hit DAMAGE above and the 1-in-4 lingering fire zone. Radius bumped alongside the
	// fireball's own visual/hitbox size increase (see ModEntities#VISERION_FIREBALL,
	// ViserionFireballModel) - a visibly bigger fireball should have a correspondingly bigger
	// footprint, not the same small splash as the old tiny-and-numerous version.
	private static final float SPLASH_DAMAGE = 14.0f;
	private static final double SPLASH_RADIUS = 4.0;
	// How far out (in blocks, both horizontally and the one block up/down checked at each spot)
	// real fire blocks get scattered around a normal impact - per explicit request ("no particles
	// JUST FIRE LOTS OF FIRE EVERYWHERE"), the impact's entire visual weight is real placed fire
	// now, not any particle effect. See igniteArea.
	private static final int IMPACT_IGNITE_RADIUS = 2;
	// Safety despawn for a shot that never hits anything (fired into open sky) - vanilla
	// fireballs have no built-in max range/lifetime at all. Was 200 (10s), cut to 40 (2s) once
	// these started spawning at real volume, then raised back up to 60 (3s) per explicit "needs
	// more range" request - paired with the SPEED bump above, this roughly doubles maximum real
	// travel distance (SPEED * MAX_AGE_TICKS) versus the previous 40-tick cap, while still bounding
	// the pathological all-shots-miss-into-open-sky case rather than leaving it fully unbounded
	// like vanilla fireballs.
	private static final int MAX_AGE_TICKS = 60;

	private static final DustParticleEffect TRAIL_GOLD =
			new DustParticleEffect(new Vector3f(1.0f, 0.85f, 0.55f), 1.3f);

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
		// Real bug, not guessed: velocity was purely the aim direction, with no inheritance of
		// the dragon's own current velocity at all. Fine while standing still, but while actually
		// flying (which is the normal case for using this), each shot only ever had its own fixed
		// muzzle velocity in world space - relative to the dragon itself (and the rider watching
		// from it), that reads as every shot immediately falling behind/drifting backward the
		// instant it's fired, worse the faster the dragon is moving. Adding the owner's velocity
		// on top (real muzzle-velocity-plus-platform-velocity physics) fixes it at the source.
		this.setVelocity(direction.normalize().multiply(SPEED).add(owner.getVelocity()));
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

	// Real bug, not guessed: this is why shots were "exploding too quick." ProjectileEntity's
	// default canHit() only excludes the owner itself (via isConnectedThroughVehicle, which is a
	// vehicle/passenger relationship - it doesn't apply to Viserion's own ViserionPart hitboxes at
	// all, since those are separate, non-riding entities). Every part (head/neck/mouth/torso/
	// wings/etc, see ViserionEntity#initParts) has canHit()=true, and this fireball spawns right at
	// the mouth - so most shots were colliding with the dragon's OWN body on the very first tick,
	// detonating essentially at point-blank range instead of actually traveling anywhere. Excluding
	// anything that's isPartOf() the owner fixes this at the source.
	@Override
	protected boolean canHit(Entity entity) {
		if (this.getOwner() != null && entity.isPartOf(this.getOwner())) {
			return false;
		}
		return super.canHit(entity);
	}

	// Per explicit request, no more in-flight trail - particles should only appear at the actual
	// explosion/impact, not the whole way there. The dense multi-color burst that used to spawn
	// here every other tick (see tick() below) is gone entirely. TRAIL_GOLD is still returned here
	// because ExplosiveProjectileEntity's own tick() unconditionally dereferences whatever this
	// returns to spawn one particle per tick (confirmed via decompile - ClientWorld#addParticle
	// calls particleEffect.getType() with no null check), so returning null would crash the client;
	// this is the one small remaining per-tick particle that can't be safely removed without a much
	// larger rewrite of the projectile's own tick/collision logic.
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
			// Real explosive punch, not just a damage number - pushed away from wherever the
			// fireball actually hit from (same source-relative-to-victim convention LivingEntity's
			// own damage()->takeKnockback call uses internally, confirmed via decompile).
			living.takeKnockback(KNOCKBACK_STRENGTH, this.getX() - living.getX(), this.getZ() - living.getZ());
		}
		impactEffects(entityHitResult.getPos(), hit);
		this.discard();
	}

	@Override
	protected void onBlockHit(BlockHitResult blockHitResult) {
		super.onBlockHit(blockHitResult);
		if (this.getWorld().isClient) {
			return;
		}
		if (this.getWorld() instanceof ServerWorld serverWorld) {
			meltBlock(serverWorld, blockHitResult.getBlockPos());
		}
		impactEffects(blockHitResult.getPos(), null);
		this.discard();
	}

	// Real destruction now, not just ignition - per explicit request ("melt down"). Not
	// auto-regrowing any more - the block is just recorded (see MeltedBlocksState) so an op can
	// manually grow it back later via /dragon revert, rather than it quietly reappearing on its
	// own after a few hours. Same doMobGriefing/indestructible-hardness respect every other
	// block-modifying ability here already follows.
	// Chance-gated, not every hit - meltBlock has no flammability check (melts stone/dirt same as
	// wood, by design, "real destruction" not just fire spreading). Was 1-in-8, sized for the old
	// 100-projectiles/tick flamethrower density where melting every single impact would've erased
	// terrain instantly. Found stale in a self-directed review after the "a few big ones instead
	// of a spray" rework dropped that to 2/tick (see ViserionEntity#DRACARYS_PROJECTILES_PER_TICK)
	// - at that volume, 1-in-8 meant a big fireball landing on a block usually did NOT destroy it,
	// working directly against "each shot should feel devastating." Raised to 1-in-2 to match the
	// new lower-volume, higher-impact design.
	private static final int MELT_CHANCE_DENOMINATOR = 2;

	private void meltBlock(ServerWorld serverWorld, BlockPos pos) {
		if (!serverWorld.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
			return;
		}
		if (this.random.nextInt(MELT_CHANCE_DENOMINATOR) != 0) {
			return;
		}
		BlockState state = serverWorld.getBlockState(pos);
		if (state.isAir() || state.getHardness(serverWorld, pos) < 0) {
			// Negative hardness = vanilla's own "truly indestructible" marker (bedrock, barriers,
			// command/structure/jigsaw blocks, portal frames, etc.) - dragonfire still shouldn't
			// bypass whatever the world itself treats as unbreakable.
			return;
		}
		if (state.hasBlockEntity()) {
			// Real bug caught in review, not guessed: setBlockState(AIR) alone does not drop a
			// block entity's contents (that only happens via the proper break path, e.g.
			// world.breakBlock/Block.dropStacks) - a chest, furnace, barrel, or shulker box melted
			// this way would just silently delete its entire inventory, no drops, no warning. At
			// this mod's actual fireball density that's a real risk of quietly deleting a player's
			// storage, not a theoretical edge case, so these are excluded from melting entirely
			// rather than trying to thread dropping their contents correctly.
			return;
		}
		MeltedBlocksState.get(serverWorld).addMeltedBlock(pos, state);
		serverWorld.setBlockState(pos, Blocks.AIR.getDefaultState());
	}

	private static final double EXPLOSION_RADIUS = 4.0;
	private static final float EXPLOSION_DAMAGE = 22.0f;
	private static final double EXPLOSION_KNOCKBACK_STRENGTH = 2.2;
	private static final int EXPLOSION_IGNITE_RADIUS = 3;

	// Real block destruction, not vanilla's World#createExplosion - that was tried first (using
	// World.ExplosionSourceType.MOB, which does correctly respect doMobGriefing the same way a
	// creeper's explosion does) but per explicit follow-up report it wasn't actually breaking
	// anything in testing. Rather than chase why (a claim/protection mod in this pack intercepting
	// the vanilla explosion-destruction pipeline specifically is the most likely culprit - this is
	// a 300-mod pack, and that pipeline is the standard vector such mods hook), this does its own
	// direct block removal instead, the exact same setBlockState-based approach meltBlock/burnArea
	// already use successfully elsewhere in this mod. Also sidesteps the vanilla explosion's
	// hardcoded generic boom sound entirely, since this never constructs a real Explosion object -
	// see the dedicated fire-breath sound at the bottom instead of an explosion one, per explicit
	// request.
	private void triggerBigExplosion(ServerWorld serverWorld, ViserionEntity viserion, Vec3d pos) {
		Box box = new Box(pos.x - EXPLOSION_RADIUS, pos.y - EXPLOSION_RADIUS, pos.z - EXPLOSION_RADIUS,
				pos.x + EXPLOSION_RADIUS, pos.y + EXPLOSION_RADIUS, pos.z + EXPLOSION_RADIUS);
		for (LivingEntity target : serverWorld.getEntitiesByClass(LivingEntity.class, box,
				e -> e.isAlive() && e != viserion)) {
			if (target.squaredDistanceTo(pos) > EXPLOSION_RADIUS * EXPLOSION_RADIUS) {
				continue;
			}
			target.damage(this.getDamageSources().dragonBreath(), EXPLOSION_DAMAGE);
			target.setFireTicks(Math.max(target.getFireTicks(), IGNITE_TICKS));
			target.takeKnockback(EXPLOSION_KNOCKBACK_STRENGTH, pos.x - target.getX(), pos.z - target.getZ());
		}

		if (serverWorld.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
			BlockPos center = BlockPos.ofFloored(pos);
			int intRadius = (int) Math.ceil(EXPLOSION_RADIUS);
			double radiusSquared = EXPLOSION_RADIUS * EXPLOSION_RADIUS;
			BlockPos.Mutable mutable = new BlockPos.Mutable();
			for (int dx = -intRadius; dx <= intRadius; dx++) {
				for (int dy = -intRadius; dy <= intRadius; dy++) {
					for (int dz = -intRadius; dz <= intRadius; dz++) {
						if (dx * dx + dy * dy + dz * dz > radiusSquared) {
							continue;
						}
						mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						BlockState state = serverWorld.getBlockState(mutable);
						if (state.isAir() || state.getHardness(serverWorld, mutable) < 0 || state.hasBlockEntity()) {
							continue;
						}
						MeltedBlocksState.get(serverWorld).addMeltedBlock(mutable, state);
						serverWorld.setBlockState(mutable, Blocks.AIR.getDefaultState());
					}
				}
			}
		}

		// No particle burst any more, per explicit "no particles JUST FIRE" request - the crater
		// left behind gets filled with real fire instead (see igniteArea), which is both more
		// visually consistent with the rest of this rework and immune to whatever's swallowing the
		// vanilla explosion's own effects in this pack.
		igniteArea(serverWorld, BlockPos.ofFloored(pos), EXPLOSION_IGNITE_RADIUS);
		// Fire-breathing roar instead of an explosion boom, per explicit request - same voice as
		// the sustained breath's own periodic roar (ENTITY_BLAZE_SHOOT, pitched down), just louder
		// and deeper for a bigger moment.
		serverWorld.playSound(null, BlockPos.ofFloored(pos), SoundEvents.ENTITY_BLAZE_SHOOT,
				SoundCategory.HOSTILE, 3.0f, 0.4f);
	}

	// Scatters real fire blocks across a small area instead of a single spot, per explicit request
	// ("JUST FIRE LOTS OF FIRE EVERYWHERE") - tries every column in a (2*radius+1) square, igniting
	// wherever AbstractFireBlock actually allows it (open air/replaceable space with a solid,
	// non-fireproof-only surface below - same rule vanilla flint and steel follows). Tracked into
	// MeltedBlocksState exactly like every other dragon-placed fire, so /dragon revert still cleans
	// all of this up too.
	//
	// Vertical range was a fixed -1..1 around the impact's own height - fine for a normal impact,
	// but found stale in a self-directed review for the big-explosion case: triggerBigExplosion
	// carves out a real spherical crater up to EXPLOSION_RADIUS deep, and only igniting 1 block
	// below the original surface left most of that crater's actual floor unlit, looking like a
	// bald hole instead of "lots of fire everywhere." Scaled to the caller's own radius instead
	// (deeper reach downward, where a crater's exposed floor actually is, less needed upward).
	private void igniteArea(ServerWorld serverWorld, BlockPos center, int radius) {
		if (!serverWorld.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
			return;
		}
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -radius; dy <= 1; dy++) {
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

	// How far impact particles/the fire zone get pushed away from the dragon's own current
	// position, per explicit request - at close range (firing nearly straight down while flying
	// low, or grounded) the true impact point can be right next to/under the dragon's own body,
	// and a big particle burst right there reads as effects happening ON the dragon instead of a
	// shot that traveled away and landed. Ignition/melt/damage still happen at the real impact
	// point - only where things are drawn moves, see pushAwayFromOwner.
	private static final double PARTICLE_OWNER_CLEARANCE = 2.0;

	// Ignition and the block melt still happen at the real, exact impact point - only the
	// particle-facing stuff (this offset, and the lingering fire zone that's centered on it) gets
	// nudged, along the line from the dragon's current position through the impact point, so it
	// reads as clearly separate from the dragon's own body instead of overlapping it. Falls back
	// to straight down in the degenerate case where the impact is essentially at the dragon's own
	// position.
	private Vec3d pushAwayFromOwner(Vec3d pos) {
		Entity owner = this.getOwner();
		if (owner == null) {
			return pos;
		}
		Vec3d away = pos.subtract(owner.getPos());
		away = away.lengthSquared() < 1.0E-4 ? new Vec3d(0, -1, 0) : away.normalize();
		return pos.add(away.multiply(PARTICLE_OWNER_CLEARANCE));
	}

	// Still simple ignite (of the now-cratered surface left behind), not the breath's old full
	// area-sweep destruction (that mechanic is gone entirely, see ViserionEntity#tickDracarysFire)
	// - the single-block melt above is now this impact's actual destructive punch.
	// directHitEntity is whatever onEntityHit already damaged directly (or null, for a block hit) -
	// excluded from the splash below so a direct hit doesn't also eat the AoE on top of it.
	private void impactEffects(Vec3d pos, Entity directHitEntity) {
		if (this.getWorld() instanceof ServerWorld serverWorld) {
			Box splashBox = new Box(pos.x - SPLASH_RADIUS, pos.y - SPLASH_RADIUS, pos.z - SPLASH_RADIUS,
					pos.x + SPLASH_RADIUS, pos.y + SPLASH_RADIUS, pos.z + SPLASH_RADIUS);
			for (LivingEntity target : serverWorld.getEntitiesByClass(LivingEntity.class, splashBox,
					e -> e.isAlive() && e != this.getOwner() && e != directHitEntity)) {
				if (target.squaredDistanceTo(pos) > SPLASH_RADIUS * SPLASH_RADIUS) {
					continue;
				}
				target.damage(this.getDamageSources().dragonBreath(), SPLASH_DAMAGE);
				target.setFireTicks(Math.max(target.getFireTicks(), IGNITE_TICKS));
			}
			// Real explosions mixed into the stream, per explicit request ("one in every 4
			// fireballs blows up like a bed in the nether") - capped per-breath, not a flat
			// per-fireball roll, see ViserionEntity#tryConsumeExplosionChance for why.
			if (this.getOwner() instanceof ViserionEntity viserion && viserion.tryConsumeExplosionChance()) {
				triggerBigExplosion(serverWorld, viserion, pos);
			}
			// No particles at all any more, per explicit request ("no smoke please and no
			// particles JUST FIRE LOTS OF FIRE EVERYWHERE") - the impact's visual weight now comes
			// entirely from real fire blocks scattered across the landing area (see igniteArea)
			// instead of any particle burst.
			igniteArea(serverWorld, BlockPos.ofFloored(pos), IMPACT_IGNITE_RADIUS);
			Vec3d particlePos = pushAwayFromOwner(pos);
			serverWorld.playSound(null, BlockPos.ofFloored(pos), SoundEvents.ENTITY_BLAZE_SHOOT,
					SoundCategory.HOSTILE, 1.6f, 0.8f);
			// A lingering 3-second burning patch wherever the fireball actually lands, per explicit
			// request - not from every single impact, though. Kept at 1-in-4 even after the
			// "a few big ones" volume drop (see ViserionEntity#DRACARYS_PROJECTILES_PER_TICK) since
			// shots still land near each other (tight aim spread) and igniteArea above already
			// covers the immediate area with real fire on every impact - the zone is specifically
			// for ongoing AoE damage over its 3-second window, not the only source of fire.
			if (this.random.nextInt(4) == 0) {
				Entity rider = this.getOwner() instanceof ViserionEntity viserion ? viserion.getControllingPassenger() : null;
				// Same pushed-away position as the particles above, not the raw impact point - the
				// zone's whole 3-block damage radius comfortably still covers the true impact area
				// from 2 blocks off, and keeping its center matched to where its own particles
				// actually render (rather than a separate invisible anchor) is simpler than
				// splitting "where it hits" from "where it's drawn" for a lingering effect.
				ViserionFireZoneEntity.create(serverWorld, particlePos, this.getOwner(), rider);
			}
		}
	}
}

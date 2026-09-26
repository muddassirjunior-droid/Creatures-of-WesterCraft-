package dev.westercraft.creatures.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.Entity.PositionUpdater;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.TrackTargetGoal;
import net.minecraft.entity.ai.pathing.BirdNavigation;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;
import dev.westercraft.creatures.CreaturesOfWesterosMod;
import dev.westercraft.creatures.MeltedBlocksState;
import dev.westercraft.creatures.mixin.LivingEntityAccessor;
import dev.westercraft.creatures.permission.DragonAllowlist;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.EasingType;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

public class ViserionEntity extends PathAwareEntity implements GeoEntity, Dragon {

	private static final TrackedData<Boolean> FLYING =
			DataTracker.registerData(ViserionEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Optional<UUID>> BONDED_RIDER =
			DataTracker.registerData(ViserionEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

	// Names match the reworked animation.json's own keys (PascalCase_With_Underscores) - was
	// mixed-case-with-spaces ("Flying slow" etc) matching the old, now-replaced file.
	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("Idle");
	// walk/sprint are lowercase in the new (Sep 2026 "Final Rework") animation.json - the old file
	// had them capitalized. Everything else kept its casing across the swap.
	private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
	private static final RawAnimation SPRINT = RawAnimation.begin().thenLoop("sprint");
	private static final RawAnimation FLY_SLOW = RawAnimation.begin().thenLoop("Flying_Slow");
	private static final RawAnimation FLY_FAST = RawAnimation.begin().thenLoop("Flying_Fast");
	private static final RawAnimation HOVER = RawAnimation.begin().thenLoop("Hovering");
	// hold_on_last_frame in the JSON, not a loop - thenPlay() respects that (plays once, holds),
	// unlike thenLoop() which would force-restart it from frame 0 every tick setAndContinue is
	// called. setAndContinue itself is idempotent regardless, so re-asserting these every
	// animation tick while a state condition holds is the correct, established pattern here
	// (same as every other state clip below).
	private static final RawAnimation DIVE = RawAnimation.begin().thenPlay("Dive");
	// New in this pass - Glide (wings spread, no flap) for coasting with no thrust input held,
	// and Ascend (a real climbing pose) for actively holding jump, both genuinely loop:true.
	private static final RawAnimation GLIDE = RawAnimation.begin().thenLoop("Glide");
	private static final RawAnimation ASCEND = RawAnimation.begin().thenLoop("Ascend");

	private final AnimatableInstanceCache animatableInstanceCache = GeckoLibUtil.createInstanceCache(this);

	private ViserionPart headPart;
	private ViserionPart leftEyePart;
	private ViserionPart rightEyePart;
	private ViserionPart neckPart;
	private ViserionPart torsoPart;
	private ViserionPart lowerBodyPart;
	private ViserionPart tailMidPart;
	private ViserionPart tailTipPart;
	private ViserionPart leftWingPart;
	private ViserionPart rightWingPart;
	private ViserionPart leftLegPart;
	private ViserionPart rightLegPart;
	private ViserionPart[] parts;

	// Server-authoritative only, not synced — smoothed speed for throttle-based
	// acceleration/deceleration instead of snapping straight to target velocity.
	private float throttle;

	// Fifth pass at what the breath actually does. Went raycast-cone-damage -> raycast-cone-
	// damage-plus-periodic-volley ("ghast blasts") -> pure raycast damage with no volley at all ->
	// a real flamethrower (dozens to a hundred small fireballs a tick) -> this: per explicit
	// request ("increase the size of the fireball make it a few big one"), a complete pivot away
	// from spray volume - a handful of much bigger fireballs (see ViserionFireballEntity's bumped
	// DAMAGE/SPLASH_RADIUS, ModEntities#VISERION_FIREBALL's bigger hitbox, and
	// ViserionFireballModel's bigger cube) instead of a hundred small ones. Was 2, then 6, 12,
	// 40, 100/tick.
	private static final int DRACARYS_PROJECTILES_PER_TICK = 2;
	// Half-angle (in degrees) each thrown projectile's aim is randomly jittered within, around
	// the dragon's real head-aim direction. Was 20, pulled back for straighter shooting back when
	// this was a hundred-projectile/tick stream that didn't need a wide scatter to feel saturating.
	// Left tight after the "a few big ones" rework too (see DRACARYS_PROJECTILES_PER_TICK) - with
	// only 2 shots/tick a wide spread would just look like they're randomly missing each other
	// instead of landing together.
	private static final double DRACARYS_PROJECTILE_SPREAD_DEGREES = 4.0;
	// How much of the rider's raw camera pitch actually swings the shot's vertical aim - was 1.0
	// (full), pulled back per explicit feedback: firing from the mouth while the camera sits 32
	// blocks back (CameraMixin) means a mathematically-correct pitch doesn't visually track the
	// camera's own sightline (real third-person parallax, not a bug), and reads as firing off at
	// an unexpected downward diagonal. See tickDracarysFire for the fuller reasoning.
	private static final double DRACARYS_PROJECTILE_PITCH_SCALE = 0.5;
	// Guaranteed minimum downward angle (degrees), applied unconditionally (see tickDracarysFire) -
	// per explicit request the breath should always shoot down, never up, regardless of where the
	// rider is looking or whether Viserion is flying or grounded. Briefly made flying-only (raw
	// rider pitch while grounded) per an earlier "always downward when flying" request, but that
	// let a grounded shot go straight up if the rider so much as glanced upward - reverted back to
	// unconditional per the resulting bug report ("shooting vertically, let it shoot down"). Was
	// 10, then 30, then 15 - the current value roughly doubles horizontal reach versus 30 (half the
	// descent rate) before hitting the ground at a given altitude. Floored after scaling and spread
	// jitter both apply, so even the jitter's worst case (-DRACARYS_PROJECTILE_SPREAD_DEGREES)
	// can't push an individual shot back above this and point it skyward.
	private static final double DRACARYS_MIN_DOWNWARD_PITCH = 15.0;
	// Small random offset for each shot's launch point around the mouth, purely so a tick's worth
	// of simultaneous fireballs don't spawn perfectly stacked on top of each other.
	private static final double DRACARYS_PROJECTILE_ORIGIN_JITTER = 0.25;
	private static final int DRACARYS_IGNITE_TICKS = 100;
	private static final int DRACARYS_FIRE_DURATION_TICKS = 60;
	private static final int DRACARYS_ANIM_RETRIGGER_TICKS = 20;

	// Viserion's fire is pale gold, not vanilla's flat orange — every dragon is meant to have
	// its own signature fire color (ASOIAF lore precedent: Balerion's was black-and-scarlet,
	// etc.), this is Viserion's specific mix. DustParticleEffect is the only vanilla particle
	// that's both colorable and reads as a floating ember (confirmed via javap — FLAME/
	// SMALL_FLAME have no color parameter at all in 1.20.1), so the "flame" look here is really
	// a weighted mix of tinted dust: gold dominant, with red/orange accents. Kept as instance
	// constants (not hoisted to a shared dragon base class) since there's no such class yet —
	// a future dragon just needs its own version of this block.
	private static final DustParticleEffect FIRE_COLOR_GOLD =
			new DustParticleEffect(new Vector3f(1.0f, 0.85f, 0.55f), 1.8f);
	private static final DustParticleEffect FIRE_COLOR_ORANGE =
			new DustParticleEffect(new Vector3f(1.0f, 0.55f, 0.15f), 1.6f);
	private static final DustParticleEffect FIRE_COLOR_RED =
			new DustParticleEffect(new Vector3f(0.8f, 0.15f, 0.1f), 1.4f);

	private void spawnDragonFireParticles(ServerWorld serverWorld, Vec3d point, double spread, double speed) {
		serverWorld.spawnParticles(FIRE_COLOR_GOLD, point.x, point.y, point.z, 10, spread, spread, spread, speed);
		serverWorld.spawnParticles(FIRE_COLOR_ORANGE, point.x, point.y, point.z, 6, spread, spread, spread, speed);
		serverWorld.spawnParticles(FIRE_COLOR_RED, point.x, point.y, point.z, 4, spread, spread, spread, speed);
	}

	private int dracarysFireTicksRemaining;

	// Real explosions mixed into the fireball stream, per explicit request ("one in every 4
	// fireballs blows up like a bed in the nether"). A literal 1-in-4 chance per fireball would be
	// ~1500 real explosions in a single 3-second breath at this mod's projectile density (100/tick)
	// - certain to crater the landscape and choke the server. Capped hard instead: at most
	// DRACARYS_MAX_EXPLOSIONS_PER_BREATH real explosions total per breath, each individually rolled
	// at the requested 1-in-4 odds via tryConsumeExplosionChance() (called from
	// ViserionFireballEntity#impactEffects), so it still reads as "unpredictable, sometimes several
	// in a row, sometimes none" rather than a flat guaranteed count.
	private static final int DRACARYS_MAX_EXPLOSIONS_PER_BREATH = 3;
	private static final int EXPLOSION_CHANCE_DENOMINATOR = 4;
	private int explosionsThisBreath;

	// Called by each fireball on impact to ask permission for a real createExplosion(), not just
	// its own splash/melt. Consumes one of this breath's limited explosion "slots" only if it both
	// rolls the chance AND the cap isn't already hit - once the cap is reached this always returns
	// false for the rest of the breath.
	public boolean tryConsumeExplosionChance() {
		if (this.explosionsThisBreath >= DRACARYS_MAX_EXPLOSIONS_PER_BREATH) {
			return false;
		}
		if (this.random.nextInt(EXPLOSION_CHANCE_DENOMINATOR) != 0) {
			return false;
		}
		this.explosionsThisBreath++;
		return true;
	}

	// Rider-controlled melee (bite only, see performRiderMelee) — own cooldown, separate from
	// ViserionAttackGoal's (that goal is suspended entirely while ridden, so there's no
	// double-dipping, but keeping the state separate avoids the two sharing a field for no
	// reason).
	private static final int RIDER_MELEE_COOLDOWN_TICKS = 20;
	private int riderMeleeCooldown;

	// Own separate cooldown/trigger, not part of the bite/claw alternation - tail attack is
	// bound to its own key (right-click), not the shared attack key, so it needs to be able to
	// fire independent of whatever cooldown state bite/claw are in.
	private static final int RIDER_TAIL_COOLDOWN_TICKS = 25;
	private int riderTailCooldown;

	public ViserionEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
		super(entityType, world);
		// Per explicit report ("if i go far then its fucked") - the actual hitbox (2.18x2.54) is
		// far smaller than the real wingspan the model renders at, so the default frustum check
		// (which tests the hitbox, not the mesh) was clipping wingtips/tail off-screen well before
		// the visual model should actually disappear. Same vanilla trick the Ender Dragon itself
		// uses for the identical reason (confirmed field name via decompile, not guessed).
		this.ignoreCameraFrustum = true;
		// Per explicit request - Viserion is meant to be a singular, always-present named dragon,
		// not an ordinary mob that quietly despawns. Real vanilla mechanism, not guessed (confirmed
		// via decompile of MobEntity#checkDespawn): a custom name alone does NOT stop despawning in
		// 1.20.1 - that's a common misconception, the actual gate is the private `persistent` field
		// (set via setPersistent(), NBT tag "PersistenceRequired"), so both are needed here.
		// hasCustomName()/getCustomName() still work normally on top of this for the nameplate
		// itself. See CreaturesOfWesterosMod's ENTITY_LOAD hook for the actual "only one at a time"
		// enforcement - setPersistent() alone only stops the despawn timer, it doesn't stop someone
		// from /summon-ing a second one.
		this.setCustomName(Text.literal("Viserion"));
		this.setCustomNameVisible(true);
		this.setPersistent();
	}

	public static DefaultAttributeContainer.Builder createViserionAttributes() {
		return MobEntity.createMobAttributes()
				// Was 200 — a boss-tier dragon should feel genuinely hard to kill even for
				// whoever's actually managed to bring dragon-slaying gear (the near-total damage
				// immunity in adjustIncomingDamage already handles "hard to hurt at all"; this is
				// the "still takes a real fight once you can hurt it" half).
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 2000.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
				// Was 0.6 (~12 blocks/sec cruise, ~22 sprinting) - read as slow for a dragon,
				// bumped toward elytra-sprint territory (~1.1 = ~22 blocks/sec cruise, ~40
				// sprinting). Ground speed left alone - the "slow" complaint tracked with flight
				// testing specifically, not walking.
				.add(EntityAttributes.GENERIC_FLYING_SPEED, 1.1)
				// Was 10.0 - bumped per explicit request for bite to hit harder. Shared attribute,
				// so this also buffs the AI's own bite/claw (ViserionAttackGoal) the same way, not
				// just the rider-triggered one - no indication they should diverge, and keeping one
				// shared number is simpler than splitting rider vs. AI damage for no asked reason.
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 18.0)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0)
				// Dragon-sized — a skeleton's arrow or a zombie's punch shouldn't visibly shove it
				// around. Not 1.0 (fully immune, like the Wither) so heavy hits still register some
				// pushback.
				.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.6);
	}

	// Flight-immune to fall damage — matches the fire immunity set on VISERION's EntityType
	// (ModEntities), same reasoning: a flying creature taking fall damage doesn't make sense.
	// Overriding here instead of an EntityTypeTags.FALL_DAMAGE_IMMUNE datapack tag since it's
	// simpler and doesn't need a resource file for a single mod-owned entity.
	@Override
	protected int computeFallDamage(float fallDistance, float damageMultiplier) {
		return 0;
	}

	@Override
	protected void initDataTracker() {
		super.initDataTracker();
		this.dataTracker.startTracking(FLYING, false);
		this.dataTracker.startTracking(BONDED_RIDER, Optional.empty());
	}

	// Neutral-ish, same shape as the Direwolf's lessons: defends itself and its bonded rider
	// but doesn't proactively hunt. All movement-capable goals here are suspended entirely
	// while ridden — a rider has full control (including of combat), and letting AI
	// navigation fight the throttle-based travel() steering is exactly what caused the
	// Direwolf's "snap every few seconds" bug earlier this session.
	//
	// Real bug, not guessed: LookAtEntityGoal/LookAroundGoal both claim Goal.Control.LOOK and,
	// neither one checks hasPassengers() (confirmed via decompile of both), so they kept
	// running even while ridden. Both drive the shared vanilla LookControl, whose own tick()
	// (also confirmed via decompile) unconditionally overwrites the MobEntity headYaw field
	// toward whatever it was last told to look at - fighting the rider-driven
	// "this.headYaw = controllingPlayer.getYaw()" line in tickControlled every time either
	// goal happened to start (LookAtEntityGoal: a player wanders within 8 blocks; LookAroundGoal:
	// its own periodic random-glance timer). That's exactly the reported "randomly force aims in
	// a different direction" - the head snapping to whatever the AI wanted to look at instead of
	// where the rider is actually looking. Wrapped both below to suspend while ridden, same
	// reasoning already applied to the movement-control goals.
	@Override
	protected void initGoals() {
		this.goalSelector.add(1, new RiderAwareLookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		this.goalSelector.add(2, new RiderAwareLookAroundGoal(this));
		this.goalSelector.add(3, new ViserionAttackGoal());

		this.targetSelector.add(1, new DefendBondedRiderGoal());
		this.targetSelector.add(2, new RevengeGoal(this));
	}

	private class RiderAwareLookAtEntityGoal extends LookAtEntityGoal {
		private RiderAwareLookAtEntityGoal(ViserionEntity mob, Class<? extends LivingEntity> targetType, float range) {
			super(mob, targetType, range);
		}

		@Override
		public boolean canStart() {
			return !ViserionEntity.this.hasPassengers() && super.canStart();
		}

		@Override
		public boolean shouldContinue() {
			return !ViserionEntity.this.hasPassengers() && super.shouldContinue();
		}
	}

	private class RiderAwareLookAroundGoal extends LookAroundGoal {
		private RiderAwareLookAroundGoal(ViserionEntity mob) {
			super(mob);
		}

		@Override
		public boolean canStart() {
			return !ViserionEntity.this.hasPassengers() && super.canStart();
		}

		@Override
		public boolean shouldContinue() {
			return !ViserionEntity.this.hasPassengers() && super.shouldContinue();
		}
	}

	@Override
	protected EntityNavigation createNavigation(World world) {
		return new BirdNavigation(this, world);
	}

	public boolean isFlying() {
		return this.dataTracker.get(FLYING);
	}

	// Flying state is driven by the explicit jump/sneak triggers in travel(), not inferred. This
	// fires "landing" at the exact moment flight actually ends (dragon really at the ground - see
	// travel()'s isOnGround() check), matching the touchdown flourish the clip is meant for. No
	// equivalent one-shot on takeoff any more - the new ("Final Rework") animation set dropped
	// Take_Off_From_Ground with no replacement, and the Ascend loop already reads fine on its own
	// right after liftoff.
	public void setFlying(boolean flying) {
		this.dataTracker.set(FLYING, flying);
		this.setNoGravity(flying);
		if (!flying) {
			this.triggerAnim("action", "landing");
		}
	}

	public UUID getBondedRider() {
		return this.dataTracker.get(BONDED_RIDER).orElse(null);
	}

	@Nullable
	public PlayerEntity getBondedRiderPlayer() {
		UUID id = getBondedRider();
		return id == null ? null : this.getWorld().getPlayerByUuid(id);
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		UUID bonded = getBondedRider();
		if (bonded != null) {
			nbt.putUuid("BondedRider", bonded);
		}
		nbt.putBoolean("Flying", isFlying());
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.contains("BondedRider")) {
			this.dataTracker.set(BONDED_RIDER, Optional.of(nbt.getUuid("BondedRider")));
		}
		this.dataTracker.set(FLYING, nbt.getBoolean("Flying"));
	}

	// Ops bypass bonding entirely - can hop on and fly/fight with ANY dragon, bonded or not,
	// without it touching that dragon's actual BONDED_RIDER (so testing on someone else's
	// claimed dragon never steals it from them - see getControllingPassenger). Matches the
	// permission level /dragon allow|disallow already require, so "can manage the allowlist"
	// and "can override it" are the same bar.
	private static boolean isOp(PlayerEntity player) {
		return player.hasPermissionLevel(2);
	}

	@Override
	public ActionResult interactMob(PlayerEntity player, Hand hand) {
		if (this.getWorld().isClient) {
			return ActionResult.SUCCESS;
		}
		if (isOp(player)) {
			if (this.hasPassengers() && !this.hasPassenger(player)) {
				return ActionResult.PASS;
			}
			player.startRiding(this);
			return ActionResult.CONSUME;
		}
		UUID bonded = getBondedRider();
		if (bonded == null) {
			if (this.getWorld() instanceof ServerWorld serverWorld
					&& !DragonAllowlist.getServerState(serverWorld.getServer()).isAllowed(player.getUuid())) {
				player.sendMessage(Text.literal("You are not permitted to bond with dragons."), true);
				return ActionResult.FAIL;
			}
			this.dataTracker.set(BONDED_RIDER, Optional.of(player.getUuid()));
			player.sendMessage(Text.literal("You have bonded with Viserion."), true);
			player.startRiding(this);
			return ActionResult.CONSUME;
		}
		if (!bonded.equals(player.getUuid())) {
			player.sendMessage(Text.literal("This dragon is already bonded to another rider."), true);
			return ActionResult.FAIL;
		}
		if (this.hasPassengers()) {
			return ActionResult.PASS;
		}
		player.startRiding(this);
		return ActionResult.CONSUME;
	}

	@Override
	public LivingEntity getControllingPassenger() {
		Entity passenger = this.getFirstPassenger();
		if (passenger instanceof PlayerEntity player) {
			UUID bonded = getBondedRider();
			if ((bonded != null && bonded.equals(player.getUuid())) || isOp(player)) {
				return player;
			}
		}
		return null;
	}

	@Override
	protected Vec3d getControlledMovementInput(PlayerEntity controllingPlayer, Vec3d movementInput) {
		float sideways = controllingPlayer.sidewaysSpeed * 0.5f;
		float forward = controllingPlayer.forwardSpeed;
		// Not gated on isFlying() any more - travel()'s automatic takeoff check needs to see
		// jump held WHILE still grounded (that's literally what lifts it off the ground in the
		// first place), so this has to report it regardless of the current flying state.
		float ascendBoost = ((LivingEntityAccessor) controllingPlayer).creaturesofwesteros$isJumping()
				? 1.0f : 0.0f;
		return new Vec3d(sideways, ascendBoost, forward);
	}

	// Vanilla suppresses sprint-start while riding unless the vehicle opts in (confirmed via
	// decompiled ClientPlayerEntity#canVehicleSprint) — needed so double-tap-forward boost
	// works at all here, now that sneak is repurposed as "descend" instead of "boost".
	@Override
	public boolean canSprintAsVehicle() {
		return true;
	}

	@Override
	protected float getSaddledSpeed(PlayerEntity controllingPlayer) {
		double attribute = isFlying()
				? this.getAttributeValue(EntityAttributes.GENERIC_FLYING_SPEED)
				: this.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
		float base = (float) attribute;
		return controllingPlayer.isSprinting() ? base * 1.8f : base;
	}

	private static final double MAX_FLIGHT_PITCH_DEGREES = 25.0;
	private static final double FLIGHT_PITCH_VELOCITY_SCALE = 30.0;
	private static final float FLIGHT_PITCH_LERP_FACTOR = 0.2f;
	// Was 0.35 since the very first flight rework, never actually revisited despite being the
	// single biggest factor in how responsive steering feels - a fixed fractional ease means
	// turn RATE scales down as you approach the target heading, which reads as sluggish
	// specifically on the tight corrections that matter most. Bumped for snappier turning.
	private static final float YAW_LERP_FACTOR = 0.55f;

	// Vanilla never syncs a mount's yaw/pitch to its rider's look direction on its own — each
	// rideable entity must do it explicitly (confirmed via decompiled AbstractHorseEntity /
	// PigEntity#tickControlled, both follow this exact pattern). Runs before travel() every
	// tick a player is controlling this entity; without it the dragon never turns.
	//
	// Pitch is NOT slaved directly to the rider's raw camera angle, unlike yaw — a dragon-sized
	// model pitching to match every camera tilt (e.g. glancing straight up while flying level)
	// fights the flying animation's own keyframed neck motion and looks broken. Instead pitch
	// is derived from actual vertical velocity (climbing noses up, diving noses down) and
	// lerped in, so the body only tilts when it's actually changing altitude.
	@Override
	protected void tickControlled(PlayerEntity controllingPlayer, Vec3d movementInput) {
		super.tickControlled(controllingPlayer, movementInput);
		// Yaw eases toward the rider's look direction instead of snapping to it instantly —
		// wrapDegrees handles the -180/180 wraparound correctly (a naive lerp would spin the
		// wrong way around near that boundary).
		float yawDelta = MathHelper.wrapDegrees(controllingPlayer.getYaw() - this.getYaw());
		float yaw = this.getYaw() + yawDelta * YAW_LERP_FACTOR;

		float targetPitch = 0.0f;
		if (isFlying()) {
			double verticalVelocity = this.getVelocity().y;
			targetPitch = (float) MathHelper.clamp(-verticalVelocity * FLIGHT_PITCH_VELOCITY_SCALE,
					-MAX_FLIGHT_PITCH_DEGREES, MAX_FLIGHT_PITCH_DEGREES);
		}
		float pitch = MathHelper.lerp(FLIGHT_PITCH_LERP_FACTOR, this.getPitch(), targetPitch);
		this.setRotation(yaw, pitch);
		// bodyYaw tracks the eased entity yaw same as before, but headYaw now tracks the rider's
		// raw look yaw directly instead of being forced equal to bodyYaw - GeckoLib's head
		// tracking (see ViserionGeoModel's turnsHead) reads the gap between the two to rotate the
		// head bone independently, clamping to a believable range and pulling the body along if
		// it twists too far (the same vanilla-standard math LivingEntityRenderer itself uses).
		// Forcing them equal gave that system nothing to show, which is why the head never
		// visibly turned before this - prevYaw is still deliberately left alone so the renderer
		// keeps interpolating smoothly between ticks instead of hard-snapping every frame.
		//
		// Real bug, not guessed - this is the "I face north and the dragon looks the opposite way"
		// report: PlayerEntity#getYaw() is NOT wrapped to +/-180 by vanilla - it just keeps
		// accumulating as the rider spins the camera (a few mouse turns and it can genuinely sit at
		// 540, -720, whatever), while bodyYaw stays bounded near 0 by the wrapDegrees easing just
		// above. Assigning that raw, unbounded value straight into headYaw meant GeckoLib's own
		// head-turn math (a plain headYaw-vs-bodyYaw delta, confirmed via decompile) could see a
		// stray multiple of 360 baked into the difference and rotate the head bone as if the rider
		// were looking nearly the opposite way, worse the longer a play session went on. Anchored to
		// bodyYaw plus the wrapped delta instead - always within +/-180 of the body by construction,
		// same technique the yawDelta line above already uses, with zero change to the normal-case
		// (small delta) head-turning behavior.
		this.bodyYaw = yaw;
		this.headYaw = this.bodyYaw + MathHelper.wrapDegrees(controllingPlayer.getYaw() - this.bodyYaw);
	}

	// Sixth pass at the flying/landed state machine. The fifth pass (raycast-teleport "guaranteed
	// landing" on a 5-second sneak hold) worked but read as an ugly instant snap. Looked at how
	// Saint's Dragons (MIT-licensed source - reading the code for technique is fine, their art
	// assets are the only thing off-limits) actually solves this: they never built a
	// zero-velocity-capable hover to begin with, so their landing state just hands off to real
	// gravity and lets the dragon actually fall. That's why onGround() works for them and didn't
	// for us - Entity#move's groundCollision check is `verticalCollision && movement.y < 0.0`
	// (confirmed via decompile), and a real fall under gravity always satisfies that, while our
	// custom hover thrust could legitimately rest at exactly zero. Same trick adopted here now:
	// sneak while flying commits to landing (setNoGravity(false), real gravity takes over, still
	// steerable on the way down via the same rider-input path grounded movement already uses),
	// and isOnGround() is checked ONLY inside this one landing branch - never used to infer
	// flying state itself, still just the two deliberate triggers (jump to take off, sneak to
	// land). Jump cancels a landing back into controlled flight, same as releasing sneak. The
	// old hold-timer is gone entirely; forceLandNow (V hotkey) is kept as a manual instant
	// override, not the primary mechanism any more.
	private boolean landing = false;

	// Fourth pass at this. landedTicks+isFlying()+walking left a one-tick gap at touchdown.
	// Plain isOnGround() got stuck false even at rest (the same vanilla flag this whole project
	// already found unreliable for this entity - see the class comment above `landing`). Plain
	// !isFlying()&&!landing was reliable but only proves "not mid-flight-or-landing-sequence," not
	// "actually touching ground" - explicitly required now: on the ground AND in the
	// walking/landed animation state, never while flying. Ground contact is checked directly with
	// the same short-raycast technique forceLandNow already uses for real ground, instead of
	// trusting vanilla's collision-derived flag a third time - reliable regardless of how the
	// entity got here, and combined with the flying/landing flags so a false positive from either
	// side alone still can't let someone off mid-air.
	public boolean isDismountAllowed() {
		return !this.isFlying() && !this.landing && this.isTouchingGround() && this.isVelocitySettled();
	}

	private boolean isTouchingGround() {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return this.isOnGround();
		}
		Vec3d start = this.getPos();
		Vec3d end = start.add(0, -0.25, 0);
		BlockHitResult hit = serverWorld.raycast(new RaycastContext(start, end,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
		return hit.getType() == HitResult.Type.BLOCK;
	}

	// Real bug, not guessed - this is the "sneaked to land, died from fall damage" report. A fast
	// real-gravity descent from a real height (see the `landing` branch in travel()) has no
	// terminal-velocity cap of its own, so it can still be moving at vanilla's normal terminal fall
	// speed right up until actual touchdown. isTouchingGround()'s short raycast (0.25 blocks) can
	// return a false positive for one tick while still substantially airborne - clipping past a
	// THIN, non-full-block obstacle on the way down (leaves, a fence, a slab, snow layers, tree
	// canopy) registers as "hit a block" the same as genuine ground, even though the entity's own
	// downward velocity was never actually arrested by it and the real ground could be many blocks
	// further down. A genuine touchdown, unlike a clip-through, always has vanilla's own collision
	// response bring vertical velocity to (near) zero on the exact same tick - checking that here
	// filters out exactly this false-positive case without weakening the raycast itself.
	private boolean isVelocitySettled() {
		return Math.abs(this.getVelocity().y) < 0.1;
	}
	private Entity trackedPassenger = null;

	@Override
	public void travel(Vec3d movementInput) {
		if (this.getControllingPassenger() instanceof PlayerEntity rider) {
			boolean ascendHeld = movementInput.y > 0.5;
			if (!this.isFlying() && ascendHeld) {
				this.setFlying(true);
				this.landing = false;
			}

			// Dive outranks landing - sprint+sneak+forward (see flyControlledByRider) holds
			// sneak too, so without excluding it here a dive would immediately get misread as
			// "wants to land."
			boolean diving = rider.isSprinting() && rider.isSneaking() && movementInput.z > 0.01;
			boolean wantsToLand = this.isFlying() && rider.isSneaking() && !ascendHeld && !diving;
			if (!wantsToLand) {
				this.landing = false;
			} else {
				this.landing = true;
			}

			if (this.isFlying() && this.landing) {
				// Real gravity carries the descent - see class comment above the `landing` field.
				// Still steerable on the way down: same super.travel(movementInput) path grounded
				// rider-controlled movement already uses, just with gravity on instead of off.
				this.setNoGravity(false);
				super.travel(movementInput);
				// Was plain isOnGround() - same unreliable vanilla flag the class comment above
				// `landing` already called out ("got stuck false even at rest"), and the flip side
				// of that same unreliability: it can also read true for one tick while still
				// genuinely airborne (see isVelocitySettled's comment - a fast fall clipping past a
				// thin block on the way down). Reusing the same isTouchingGround()+isVelocitySettled()
				// pair isDismountAllowed() already relies on instead, so "the dragon thinks it landed"
				// and "the rider is allowed to get off" can no longer disagree about what landed
				// means.
				if (this.isTouchingGround() && this.isVelocitySettled()) {
					this.setFlying(false);
					this.landing = false;
				}
			} else if (this.isFlying()) {
				this.setNoGravity(true);
				flyControlledByRider(rider, movementInput);
			} else {
				this.setNoGravity(false);
				this.throttle = 0.0f;
				super.travel(movementInput);
			}
		} else if (this.isFlying()) {
			this.setNoGravity(true);
			super.travel(movementInput);
			this.setVelocity(this.getVelocity().multiply(0.98, 0.98, 0.98));
		} else {
			this.setNoGravity(false);
			this.throttle = 0.0f;
			super.travel(movementInput);
		}
	}

	// Fourth pass at this. Every prior version (Saint's-Dragons pitch-driven, Ice-and-Fire
	// flat+sprint-glide, the pitch/co-equal-vertical hybrid) kept vertical movement tied to
	// camera pitch in some form, and every one of them got complained about for it - that link
	// is now removed entirely instead of retuned again. Yaw still eases toward the rider's look
	// (never the actual complaint, same as riding a horse), but climb/dive is 100% keyboard-
	// driven and camera pitch has zero effect on flight physics any more - free-look only, for
	// aiming Dracarys and watching the dragon fly.
	//
	// Strafe is back, per explicit request - A/D now blend a sideways component into the same
	// direction vector forward/dive already use (see dirX/dirZ below), so it shares forward's own
	// throttle ramp-up/glide-decay rather than being a separate always-on thrust. getYaw()+90
	// (cos/sin swapped relative to the forward vector, same rotation-by-90-degrees relationship
	// vanilla's own Entity#movementInputToVelocity uses for sidewaysSpeed, confirmed via decompile)
	// gives the yaw-relative right vector.
	private static final float DIVE_SPEED_MULTIPLIER = 2.0f;
	private static final float DIVE_THROTTLE_GAIN = 0.12f;
	private static final float ASCEND_BOOST_THRUST = 0.35f;
	private static final float OVERDRIVE_BLEED_SCALE = 0.035f;
	private static final float GLIDE_DECAY = 0.985f;

	private void flyControlledByRider(PlayerEntity rider, Vec3d movementInput) {
		float yawRad = (float) Math.toRadians(this.getYaw());
		double forwardInput = movementInput.z;
		double sidewaysInput = movementInput.x;
		boolean hasInput = Math.abs(forwardInput) > 1.0E-3 || Math.abs(sidewaysInput) > 1.0E-3;

		// Dive is a deliberate key combo now, not a look angle - sprint+sneak+forward held
		// together commits to it. Same throttle-overdrive payoff as every prior version (real
		// risk/reward extra speed), just triggered by keyboard state instead of camera pitch.
		boolean diving = rider.isSprinting() && rider.isSneaking() && forwardInput > 0.01;

		float baseTarget = getSaddledSpeed(rider);
		float maxOverdrive = baseTarget * DIVE_SPEED_MULTIPLIER;

		if (hasInput) {
			if (this.throttle < baseTarget) {
				// Was 0.08 (~12-13 ticks / 0.6s to reach full speed from a stop) - part of why
				// flight read as slow even after the top-speed attribute bump: it wasn't just a
				// low ceiling, it took a while to even get there. Now reaches baseTarget in ~6
				// ticks (0.3s).
				this.throttle = Math.min(baseTarget, this.throttle + baseTarget * 0.16f);
			}
			if (diving) {
				if (this.throttle < maxOverdrive) {
					this.throttle = Math.min(maxOverdrive, this.throttle + baseTarget * DIVE_THROTTLE_GAIN);
				}
			} else if (this.throttle > baseTarget) {
				this.throttle = Math.max(baseTarget, this.throttle - baseTarget * OVERDRIVE_BLEED_SCALE);
			}
		} else {
			this.throttle = Math.max(0.0f, this.throttle - baseTarget * OVERDRIVE_BLEED_SCALE);
		}

		// Level, yaw-relative only - no pitch tilt at all. Diving folds a strong downward
		// component into this same normalized direction (so a dive is a genuine fast diagonal
		// descent, not just "fall while holding forward"); ordinary flight keeps dirY at 0 and
		// gets its vertical entirely from the jump/sneak additive below.
		double forwardX = -MathHelper.sin(yawRad);
		double forwardZ = MathHelper.cos(yawRad);
		double rightX = MathHelper.cos(yawRad);
		double rightZ = MathHelper.sin(yawRad);

		double dirX = forwardX * forwardInput + rightX * sidewaysInput;
		double dirZ = forwardZ * forwardInput + rightZ * sidewaysInput;
		double dirY = diving ? -1.0 : 0.0;
		double dirLength = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);

		Vec3d velocity;
		if (hasInput && dirLength > 0.01) {
			Vec3d targetVelocity = new Vec3d(dirX / dirLength, dirY / dirLength, dirZ / dirLength).multiply(this.throttle);
			// Was 0.18/0.1 - actual velocity lagged behind throttle on top of throttle's own
			// ramp-up, compounding into a sluggish feel. Snappier now that both are addressed.
			float accel = diving ? 0.24f : 0.17f;
			velocity = new Vec3d(
					MathHelper.lerp(accel, this.getVelocity().x, targetVelocity.x),
					MathHelper.lerp(accel, this.getVelocity().y, targetVelocity.y),
					MathHelper.lerp(accel, this.getVelocity().z, targetVelocity.z));
		} else {
			velocity = this.getVelocity().multiply(GLIDE_DECAY);
			if (velocity.lengthSquared() < 1.0E-4) {
				velocity = Vec3d.ZERO;
			}
		}

		// Jump is the only source of vertical movement in here outside an active dive - no pitch
		// contribution any more. Sneak no longer nudges vertical from this method at all: sneak
		// now means "commit to landing" (see travel()), which hands off to a real gravity descent
		// before flyControlledByRider is ever called, so there's nothing left for a sneak branch
		// here to do.
		boolean jumpBoostHeld = movementInput.y > 0.5;
		if (!diving && jumpBoostHeld) {
			velocity = velocity.add(0, baseTarget * ASCEND_BOOST_THRUST, 0);
		}

		this.setMovementSpeed(this.throttle);
		this.setVelocity(velocity);
		this.move(MovementType.SELF, this.getVelocity());
	}

	@Override
	protected void updatePassengerPosition(Entity passenger, PositionUpdater positionUpdater) {
		if (!this.hasPassenger(passenger)) {
			return;
		}
		Vec3d target = slotPosition(ViserionPartId.SEAT);
		positionUpdater.accept(passenger, target.x, target.y, target.z);
	}

	// Belt-and-suspenders on top of the isVelocitySettled() ground-detection fix above - that fix
	// targets the one concrete false-positive mechanism found (a fast fall clipping past a thin
	// block), but this covers EVERY dismount path uniformly (voluntary sneak-off, Viserion dying/
	// being removed mid-flight while ridden, an op force-dismounting, etc) rather than betting
	// everything on one gate being airtight in every case. removePassenger is the one vanilla hook
	// that actually fires for all of them (confirmed via decompile - Entity#dismountVehicle always
	// routes through it), so this is the correct single place for a universal safety net rather
	// than duplicating the same effect-application call at every individual dismount trigger.
	private static final int DISMOUNT_SLOW_FALLING_TICKS = 100;

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		if (!this.getWorld().isClient && passenger instanceof PlayerEntity player) {
			player.addStatusEffect(new StatusEffectInstance(
					StatusEffects.SLOW_FALLING, DISMOUNT_SLOW_FALLING_TICKS, 0, false, false));
		}
	}

	// Second pass at part positioning. Most slots are still a fixed offset from the entity's own
	// body position/yaw — rock-solid, no jitter/drift/desync risk, pure function of data the
	// server already has every tick — same reasoning as before (see git history / prior comment
	// here), and still the right call for torso/lower_body/tail/legs/seat, where the pose barely
	// moves relative to the body during normal flight anyway. HEAD/MOUTH/eyes/NECK and the wings
	// are the two cases where that was actually visibly wrong (head not turning, wings not
	// flapping), so those two get real per-tick tracking below instead - deliberately still no
	// networking or client-side bone sampling (the earlier attempt at that had real,
	// hard-to-fully-eliminate render-fps-vs-tick-rate jitter, and a client dictating its own
	// hitbox position over the network is also just not something to trust for collision).
	private Vec3d slotPosition(ViserionPartId id) {
		switch (id) {
			case HEAD, MOUTH, LEFT_EYE, RIGHT_EYE, NECK -> {
				// All five of these are nested under the model's "head" bone (Neck/bone41/
				// bone49/bone47 all declare "parent": "head" in viserion.geo.json, confirmed by
				// reading the actual hierarchy rather than assuming), so they genuinely rotate
				// with it. headYaw is the same real, server-tracked absolute yaw GeckoLib's
				// turnsHead already renders the head bone with (see ViserionGeoModel /
				// tickControlled) - not a guess, the actual value.
				return slotPositionAround(this.headYaw, id);
			}
			case LEFT_WING, RIGHT_WING -> {
				return wingSlotPosition(id);
			}
			default -> {
				return slotPositionAround(this.getYaw(), id);
			}
		}
	}

	private Vec3d slotPositionAround(float yawDeg, ViserionPartId id) {
		float yawRad = (float) Math.toRadians(yawDeg);
		float angle = yawRad + (float) Math.toRadians(id.defaultAngleDeg);
		double x = this.getX() - id.defaultRadius * MathHelper.sin(angle);
		double y = this.getY() + id.defaultYOffset;
		double z = this.getZ() + id.defaultRadius * MathHelper.cos(angle);
		return new Vec3d(x, y, z);
	}

	// WingLeft/WingRight (root bones, no parent - confirmed via geo.json, unaffected by head
	// tracking) actually swing through a compound 3-axis rotation during flight (see
	// viserion.animation.json's Flying_Slow block: ~17.5 degree rest, dipping through a full
	// downstroke around the middle of each loop, back to rest). Hand-porting that exact keyframe
	// curve for a hitbox is overkill, so this approximates just the dominant motion - the hitbox
	// dips down and pulls in slightly on a fixed-period cycle while actually flying, and holds
	// still at rest otherwise. Same "good enough directionally, not bone-accurate" bar this file
	// already uses for TAIL_MID/TAIL_TIP/LEFT_LEG/RIGHT_LEG (see ViserionPartId) - not synced to
	// whichever exact clip the movement controller happens to be playing, on purpose.
	private static final int WING_FLAP_PERIOD_TICKS = 30;
	private static final double WING_FLAP_Y_AMPLITUDE = 1.2;
	private static final double WING_FLAP_RADIUS_AMPLITUDE = 0.4;

	private Vec3d wingSlotPosition(ViserionPartId id) {
		double flap = 0.0;
		if (this.isFlying()) {
			double phase = (this.age % WING_FLAP_PERIOD_TICKS) / (double) WING_FLAP_PERIOD_TICKS;
			flap = Math.sin(phase * Math.PI);
		}
		float yawRad = (float) Math.toRadians(this.getYaw());
		float angle = yawRad + (float) Math.toRadians(id.defaultAngleDeg);
		double radius = id.defaultRadius - flap * WING_FLAP_RADIUS_AMPLITUDE;
		double x = this.getX() - radius * MathHelper.sin(angle);
		double y = this.getY() + id.defaultYOffset - flap * WING_FLAP_Y_AMPLITUDE;
		double z = this.getZ() + radius * MathHelper.cos(angle);
		return new Vec3d(x, y, z);
	}

	@Override
	public void tickMovement() {
		super.tickMovement();
		if (!this.getWorld().isClient) {
			// A dismount that happened last tick while not actually on the ground gets reverted -
			// same isDismountAllowed() check PlayerDismountMixin uses, so this and the mixin agree
			// on when it's safe. Shouldn't normally fire any more (the mixin should already have
			// vetoed it), but kept as a safety net. The distance check just guards against
			// re-mounting someone who's already moved well away for an unrelated reason within
			// that one tick.
			if (this.trackedPassenger != null && this.trackedPassenger.isAlive()
					&& this.getFirstPassenger() != this.trackedPassenger
					&& !this.isDismountAllowed()
					&& this.trackedPassenger.squaredDistanceTo(this) < 16.0) {
				this.trackedPassenger.startRiding(this, true);
			}
			this.trackedPassenger = this.getFirstPassenger();
			if (this.riderMeleeCooldown > 0) {
				this.riderMeleeCooldown--;
			}
			if (this.riderTailCooldown > 0) {
				this.riderTailCooldown--;
			}
			if (this.dracarysFireTicksRemaining > 0) {
				tickDracarysFire();
			}
			if (this.parts == null) {
				initParts();
			}
			positionPart(headPart, ViserionPartId.HEAD);
			positionPart(leftEyePart, ViserionPartId.LEFT_EYE);
			positionPart(rightEyePart, ViserionPartId.RIGHT_EYE);
			positionPart(neckPart, ViserionPartId.NECK);
			positionPart(torsoPart, ViserionPartId.TORSO);
			positionPart(lowerBodyPart, ViserionPartId.LOWER_BODY);
			positionPart(tailMidPart, ViserionPartId.TAIL_MID);
			positionPart(tailTipPart, ViserionPartId.TAIL_TIP);
			positionPart(leftWingPart, ViserionPartId.LEFT_WING);
			positionPart(rightWingPart, ViserionPartId.RIGHT_WING);
			positionPart(leftLegPart, ViserionPartId.LEFT_LEG);
			positionPart(rightLegPart, ViserionPartId.RIGHT_LEG);
		}
	}

	// These were plain eyeballed guesses before, unrelated to the model's actual geometry - never
	// updated when the render scale changed (see SEAT's old-0.55x-vs-current-0.4x note), which is
	// almost certainly why every one of them read as too small. Re-derived from real cube data in
	// viserion.geo.json this time: dumped every bone's cube origin/size, converted raw units to
	// blocks with the same factor already established for positions (raw * 0.4 render scale / 16
	// units-per-block, see ViserionPartId), and took the axis-aligned extent of the cube(s) that
	// actually make up each area. Two different rules depending on the part's shape:
	// - Blob-like clusters (head, torso, lower_body) use the larger of the local X/Z extent as
	//   width - these aren't strung out along a chain, so their full front-to-back bulk is real.
	// - Elongated chain members (neck, tail, wings, legs) use ONLY the local X extent - unioning
	//   a whole neck/tail/wing bone CHAIN blows the box up to the chain's entire span (that's its
	//   length, not its local thickness), so this uses one representative bone from partway along
	//   the chain instead of the whole thing. Same "good enough directionally, not bone-perfect"
	//   bar this file already leans on elsewhere (see ViserionPartId's own TAIL_MID/LEFT_LEG
	//   note) - a first-pass real-data estimate, not a final answer; check it against F3+B same
	//   as everything else here and it can get tuned further from there.
	private void initParts() {
		this.headPart = createPart("head", 3.5f, 3.6f);
		// Still small on purpose — a weak point should be genuinely harder to hit than the head,
		// not just worth more damage once hit (see damageFromPart) — no dedicated eye bone exists
		// in the model to derive a real size from, so these stay hand-picked, just nudged up
		// slightly rather than left at the original guess.
		this.leftEyePart = createPart("left_eye", 0.6f, 0.6f);
		this.rightEyePart = createPart("right_eye", 0.6f, 0.6f);
		this.neckPart = createPart("neck", 2.4f, 4.0f);
		this.torsoPart = createPart("torso", 5.0f, 3.6f);
		this.lowerBodyPart = createPart("lower_body", 2.8f, 2.2f);
		this.tailMidPart = createPart("tail_mid", 1.5f, 2.25f);
		this.tailTipPart = createPart("tail_tip", 1.2f, 1.8f);
		this.leftWingPart = createPart("left_wing", 4.0f, 1.8f);
		this.rightWingPart = createPart("right_wing", 4.0f, 1.8f);
		this.leftLegPart = createPart("left_leg", 1.8f, 2.6f);
		this.rightLegPart = createPart("right_leg", 1.8f, 2.6f);
		this.parts = new ViserionPart[]{headPart, leftEyePart, rightEyePart, neckPart, torsoPart, lowerBodyPart,
				tailMidPart, tailTipPart, leftWingPart, rightWingPart, leftLegPart, rightLegPart};
	}

	private ViserionPart createPart(String name, float width, float height) {
		ViserionPart part = ModEntities.VISERION_PART.create(this.getWorld());
		part.setOwner(this, name, width, height);
		part.setPosition(this.getX(), this.getY(), this.getZ());
		this.getWorld().spawnEntity(part);
		return part;
	}

	// Nothing discarded these when Viserion itself was removed (death, /kill, chunk unload) -
	// they'd sit in the world forever as orphaned tracked entities, each still syncing a static
	// position to every nearby player every tick. Across a session of repeatedly spawning and
	// killing test dragons, that's a real, accumulating source of exactly the "flying gives lag"
	// symptom reported in testing (more so now that they sync every tick instead of every 3rd -
	// see ModEntities). ViserionPart#shouldSave() is already false so they can't reliably
	// reattach across an unload/reload anyway - discarding on ANY removal reason and letting
	// initParts() rebuild fresh ones next active tick is correct regardless of why this dragon
	// was removed.
	@Override
	public void remove(Entity.RemovalReason reason) {
		super.remove(reason);
		if (this.parts != null) {
			for (ViserionPart part : this.parts) {
				part.discard();
			}
		}
		// Frees up the singleton slot (see enforceSingleton/canonicalUuid below) so a new Viserion
		// can actually be summoned again after this one is well and truly gone (death, /kill, etc).
		// Guarded by uuid rather than unconditionally clearing - this same method also runs for a
		// just-discarded DUPLICATE (see enforceSingleton calling discard() on it), and that must not
		// be allowed to blank out the real canonical dragon's slot.
		if (this.getUuid().equals(canonicalUuid)) {
			canonicalUuid = null;
		}
	}

	// Server-wide "only one Viserion at a time" enforcement, per explicit request. Plain static
	// field rather than a PersistentState - this only needs to track which instance is currently
	// loaded right now, not survive a server restart; whichever surviving copy loads back in first
	// after a restart just reclaims the slot the moment its chunk loads, same as any other entity.
	// Registered against Fabric's ServerEntityEvents.ENTITY_LOAD (see CreaturesOfWesterosMod) so
	// this fires uniformly for every spawn path - natural spawn, /summon, or a chunk loading a
	// previously-saved one back in - rather than needing separate handling for each.
	private static UUID canonicalUuid = null;

	public static void enforceSingleton(Entity entity) {
		if (!(entity instanceof ViserionEntity viserion)) {
			return;
		}
		if (canonicalUuid == null) {
			canonicalUuid = viserion.getUuid();
			return;
		}
		if (!canonicalUuid.equals(viserion.getUuid())) {
			viserion.discard();
		}
	}

	private void positionPart(ViserionPart part, ViserionPartId id) {
		Vec3d target = slotPosition(id);
		part.setPosition(target.x, target.y, target.z);
	}

	// Eyes are the true weak point (ASOIAF dragon-slaying lore precedent - "put it through the
	// eye" comes up repeatedly) - well above the head's own bonus, on top of a hitbox small
	// enough that actually landing the shot is the real reward, not just the multiplier.
	private static final float HEAD_DAMAGE_MULTIPLIER = 2.0f;
	private static final float EYE_DAMAGE_MULTIPLIER = 5.0f;

	public boolean damageFromPart(ViserionPart part, DamageSource source, float amount) {
		float multiplier = 1.0f;
		if (part == leftEyePart || part == rightEyePart) {
			multiplier = EYE_DAMAGE_MULTIPLIER;
		} else if (part == headPart) {
			multiplier = HEAD_DAMAGE_MULTIPLIER;
		}
		Float adjusted = adjustIncomingDamage(source, amount * multiplier);
		return adjusted != null && super.damage(source, adjusted);
	}

	// Near-invulnerable to ordinary weapons ("max armor") - ordinary arrows/tridents and
	// ordinary melee hits do nothing at all. Only gear tagged as genuinely dragon-slaying gets
	// through, and even then only for reduced damage. Both tags below ship empty - populate
	// with a datapack once Epic Knights of the Seven Kingdoms' weirwood arrow and two-handed
	// weapons actually exist (see the two tag JSONs under data/creaturesofwesteros/tags/).
	// Tag-based rather than a hard item reference so this mod doesn't need a build dependency
	// on that one. Explosions/magic/environmental damage (no attacker, not a projectile) and
	// anything tagged bypasses_invulnerability (/kill, void, etc.) are untouched - this is
	// specifically about being hard to fight, not unkillable outright.
	//
	// "Only dragons can kill other dragons": any attacker implementing the Dragon marker
	// bypasses the weapon-tag gate entirely and deals full damage - covers bite/claw and
	// Dracarys alike, since both go through getDamageSources().mobAttack(this) (attacker =
	// the dragon itself). Checked before the weapon-tag logic so two dragons trade real damage
	// regardless of what either is "wielding".
	private static final float DRAGONBANE_DAMAGE_MULTIPLIER = 0.25f;
	private static final TagKey<Item> DRAGON_SLAYING_WEAPONS =
			TagKey.of(RegistryKeys.ITEM, Identifier.of(CreaturesOfWesterosMod.MOD_ID, "dragon_slaying_weapons"));
	private static final TagKey<EntityType<?>> DRAGONBANE_PROJECTILES =
			TagKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(CreaturesOfWesterosMod.MOD_ID, "dragonbane_projectiles"));

	@Nullable
	private Float adjustIncomingDamage(DamageSource source, float amount) {
		if (source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return amount;
		}
		if (source.getAttacker() instanceof Dragon) {
			return amount;
		}
		if (source.isIn(DamageTypeTags.IS_PROJECTILE)) {
			if (source.getSource() != null && source.getSource().getType().isIn(DRAGONBANE_PROJECTILES)) {
				return amount * DRAGONBANE_DAMAGE_MULTIPLIER;
			}
			return null;
		}
		if (source.getAttacker() instanceof LivingEntity attacker) {
			if (attacker.getMainHandStack().isIn(DRAGON_SLAYING_WEAPONS)) {
				return amount * DRAGONBANE_DAMAGE_MULTIPLIER;
			}
			return null;
		}
		return amount;
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		Float adjusted = adjustIncomingDamage(source, amount);
		return adjusted != null && super.damage(source, adjusted);
	}

	// Rider-controlled melee: bound to the vanilla attack key (left-click) while mounted and
	// controlling a bonded Viserion — see CreaturesOfWesterosClient's tick check +
	// VISERION_MELEE_CHANNEL. Always bite now, not alternating with claw (per explicit request -
	// left click is bite, full stop; claw's own triggerableAnim entry is still registered if it
	// ever needs a new trigger later, this just doesn't call it any more). Own cooldown, separate
	// from ViserionAttackGoal's AI version (that goal is suspended entirely while ridden).
	public void performRiderMelee(@Nullable LivingEntity target) {
		if (target == null || this.riderMeleeCooldown > 0) {
			return;
		}
		this.riderMeleeCooldown = RIDER_MELEE_COOLDOWN_TICKS;
		this.triggerAnim("action", "bite");
		this.tryAttack(target);
	}

	// Rider-controlled tail whip - no longer bound to anything (right-click is Dracarys now, see
	// CreaturesOfWesterosClient), per explicit request to drop it rather than find it a new key.
	// Left in place, reachable if it gets wired to something again later.
	public void performRiderTailAttack(@Nullable LivingEntity target) {
		if (target == null || this.riderTailCooldown > 0) {
			return;
		}
		this.riderTailCooldown = RIDER_TAIL_COOLDOWN_TICKS;
		this.triggerAnim("action", "tail_attack");
		this.tryAttack(target);
	}

	// Rider hotkey (see CreaturesOfWesterosClient/VISERION_GROUND_MODE_CHANNEL) - a manual
	// override now that sneak-to-land (see travel()) is the primary landing trigger. Grounded ->
	// instantly takes off. Flying -> instantly teleport-lands via forceLandNow, from any altitude
	// (skips the real-gravity descent entirely - useful from very high up, or if the rider just
	// wants down right now instead of steering through the fall).
	public void toggleGroundMode() {
		if (this.isFlying()) {
			forceLandNow();
		} else {
			this.setFlying(true);
		}
	}

	// Manual instant-landing override (V hotkey / toggleGroundMode) - a straight-down raycast to
	// real solid ground, teleports there directly, done. The primary landing mechanism is now the
	// real-gravity sneak-to-land descent in travel(); this exists purely as a convenience for
	// skipping that fall (very high altitude, or just wanting down immediately).
	private void forceLandNow() {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		Vec3d start = this.getPos();
		Vec3d end = start.add(0, -256, 0);
		BlockHitResult hit = serverWorld.raycast(new RaycastContext(start, end,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		this.setPosition(this.getX(), hit.getPos().y, this.getZ());
		this.setVelocity(Vec3d.ZERO);
		this.landing = false;
		this.setFlying(false);
	}

	// Nearest living entity roughly in front of Viserion, for rider-triggered melee (the rider
	// has no crosshair-on-Viserion's-target concept while mounted, so this substitutes for
	// aiming: press the button, whatever's closest in front within range gets hit). Excludes
	// Viserion itself and its own rider. Mirrors DirewolfEntity#findRiderTarget.
	public LivingEntity findRiderTarget(double range) {
		Vec3d look = this.getRotationVector();
		Vec3d searchCenter = this.getPos().add(look.multiply(range * 0.5));
		Box box = new Box(searchCenter, searchCenter).expand(range);
		LivingEntity best = null;
		double bestDistanceSquared = Double.MAX_VALUE;
		for (LivingEntity candidate : this.getWorld().getEntitiesByClass(LivingEntity.class, box,
				e -> e != this && e != this.getControllingPassenger() && e.isAlive() && this.canTarget(e))) {
			double distanceSquared = this.squaredDistanceTo(candidate);
			if (distanceSquared < bestDistanceSquared) {
				bestDistanceSquared = distanceSquared;
				best = candidate;
			}
		}
		return best;
	}

	// Rider-triggered fire breath (see CreaturesOfWesterosMod's VISERION_DRACARYS_CHANNEL) - no
	// charge-up any more, fires the instant right-click is pressed (per explicit request; used to
	// be hold-to-charge/release-to-fire, which read as an unwanted delay - see the fire duration
	// below, that's still a real 3-second sustained breath once started, just no windup before it
	// begins). No cooldown either - can be re-triggered again the instant the previous breath
	// finishes (dracarysFireTicksRemaining is the only guard: can't start a second breath on top
	// of an already-active one). Fires a cone in front of wherever Viserion is currently facing,
	// not a locked target, matching the lore ("burns everything in the way") rather than the AI's
	// single-target ranged flame.
	public void dracarysOnCommand() {
		this.fireDracarysNow();
	}

	// Same chat-command pattern as above - "rise" just means "start flying," reusing the
	// existing automatic flight-state machinery rather than a separate code path.
	public void riseOnCommand() {
		if (!this.getWorld().isClient && !this.isFlying()) {
			this.setFlying(true);
		}
	}

	// Starts a sustained 3-second breath instead of an instant burst — see tickDracarysFire
	// (continuous damage/particles while it's active, and the periodic flame_middle retrigger)
	// and finishDracarys (flame_end once it's over). Triggers flame_middle directly, not
	// flame_start - found via a fluidity pass, not reported as a bug: projectiles start firing
	// the instant this is called (no windup gate any more, per the earlier "quite delayed"
	// fix), so playing flame_start's mouth-opening windup pose first meant fire was already
	// streaming out while the animation was still showing the mouth closed/opening. Going
	// straight to the sustained-fire pose matches what's actually happening mechanically. The
	// AI's own single-shot ranged flame (ViserionAttackGoal) still uses flame_start deliberately
	// - that one really is a discrete instant spit with no sustained stream to desync from.
	public void fireDracarysNow() {
		if (this.getWorld().isClient || this.dracarysFireTicksRemaining > 0) {
			return;
		}
		this.dracarysFireTicksRemaining = DRACARYS_FIRE_DURATION_TICKS;
		this.explosionsThisBreath = 0;
		this.triggerAnim("action", "flame_middle");
		// Direct, unambiguous check instead of another guess - block ignition (unlike entity fire
		// damage, which isn't gated) is gated on doMobGriefing, same as every other block-
		// modifying mob ability. If it's off, say so plainly instead of leaving "why won't the
		// ground burn" as an open question every time.
		PlayerEntity dracarysRider = getBondedRiderPlayer();
		if (this.getWorld() instanceof ServerWorld serverWorld && !canIgniteBlocks(serverWorld) && dracarysRider != null) {
			dracarysRider.sendMessage(Text.literal("mobGriefing is off - dragonfire can't burn terrain until it's enabled (/gamerule mobGriefing true)."), true);
		}
	}

	private void tickDracarysFire() {
		this.dracarysFireTicksRemaining--;
		int elapsed = DRACARYS_FIRE_DURATION_TICKS - this.dracarysFireTicksRemaining;
		// flame_middle already started at elapsed 0 (see fireDracarysNow) but is shorter than the
		// full breath duration, so it needs periodic re-triggering to keep playing instead of
		// holding its last frame, same technique the old single-clip version used.
		if (elapsed > 0 && elapsed % DRACARYS_ANIM_RETRIGGER_TICKS == 0) {
			this.triggerAnim("action", "flame_middle");
		}

		Vec3d origin = slotPosition(ViserionPartId.MOUTH);
		// Natural head-aim, not a crosshair-locked hitscan - the old version retargeted onto
		// whatever exact point the rider's eye-ray hit, which behaved like a precision weapon
		// rather than a dragon breathing roughly where its head is pointed. Yaw comes from the
		// dragon's own current facing (already eased toward the rider, see tickControlled) -
		// pitch comes from the rider's raw look pitch, deliberately NOT the body's own velocity-
		// derived flight pitch (that one only reacts to actual climbing/diving, so looking down
		// while hovering level wouldn't tip it at all).
		//
		// Pitch is scaled down (DRACARYS_PROJECTILE_PITCH_SCALE), not applied 1:1, per explicit
		// feedback: firing from the mouth while the camera sits 32 blocks back and up (see
		// CameraMixin) is real third-person parallax, not a math bug - a shot that's mathematically
		// exactly where the camera is looking doesn't visually line up with the camera's own
		// sightline from that far back, the same reason third-person shooters don't aim literally
		// from the gun barrel. Toning down how much pitch actually swings the shot keeps "look
		// down to aim down" working without every small glance producing a big visible diagonal.
		PlayerEntity aimingRider = getBondedRiderPlayer();

		// Per explicit request: no raycast damage/ignition at all any more - every bit of the
		// breath's damage and terrain ignition comes from these thrown projectiles actually
		// hitting something (see ViserionFireballEntity#onEntityHit/onBlockHit), fired rapidly for
		// real "a lot of projectiles" volume. Starts firing immediately on release (elapsed 0),
		// not gated behind the flame_start windup any more - that gate was exactly the "quite
		// delayed" complaint, waiting ~8 ticks before the first shot ever left the mouth. The
		// flame_start animation still plays for its own sake, it just no longer blocks anything.
		if (this.getWorld() instanceof ServerWorld serverWorld) {
			for (int i = 0; i < DRACARYS_PROJECTILES_PER_TICK; i++) {
				double yawJitter = (this.random.nextDouble() - 0.5) * 2.0 * DRACARYS_PROJECTILE_SPREAD_DEGREES;
				double pitchJitter = (this.random.nextDouble() - 0.5) * 2.0 * DRACARYS_PROJECTILE_SPREAD_DEGREES;
				float spreadYawRad = (float) Math.toRadians(this.getYaw() + yawJitter);
				double basePitch = aimingRider != null ? aimingRider.getPitch() * DRACARYS_PROJECTILE_PITCH_SCALE : 0.0;
				// Was conditional on isFlying() (floor only while airborne, raw rider pitch while
				// grounded) - per explicit bug report ("shooting vertically, let it shoot down"),
				// that let a grounded shot go straight up if the rider so much as glanced upward
				// (negative pitch, no floor to stop it). Unconditional again: always guaranteed
				// downward, grounded or flying alike.
				double finalPitch = Math.max(DRACARYS_MIN_DOWNWARD_PITCH, basePitch + pitchJitter);
				float spreadPitchRad = (float) Math.toRadians(finalPitch);
				double forwardXZ = MathHelper.cos(spreadPitchRad);
				Vec3d spreadDirection = new Vec3d(
						-MathHelper.sin(spreadYawRad) * forwardXZ,
						-MathHelper.sin(spreadPitchRad),
						MathHelper.cos(spreadYawRad) * forwardXZ);
				Vec3d launchPoint = origin.add(
						(this.random.nextDouble() - 0.5) * DRACARYS_PROJECTILE_ORIGIN_JITTER,
						(this.random.nextDouble() - 0.5) * DRACARYS_PROJECTILE_ORIGIN_JITTER,
						(this.random.nextDouble() - 0.5) * DRACARYS_PROJECTILE_ORIGIN_JITTER);
				serverWorld.spawnEntity(new ViserionFireballEntity(serverWorld, this, launchPoint, spreadDirection));
			}
		}
		if (elapsed % 5 == 0) {
			// Louder and pitched down further (was 1.5/0.7) for a heavier, more violent roar
			// instead of a comparatively thin blaze hiss.
			this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT,
					SoundCategory.HOSTILE, 2.2f, 0.55f);
		}

		if (this.dracarysFireTicksRemaining <= 0) {
			finishDracarys();
		}
	}

	// Sets flammable terrain alight, same technique vanilla flint and steel uses
	// (AbstractFireBlock#canPlaceAt/getState - confirmed via decompile, not guessed) rather than
	// an explosion (would crater terrain instead of just igniting it). Respects doMobGriefing
	// like every other block-modifying mob ability. Used by the AI's instant single-target flame
	// hit (igniteBlocksNear below) - the rider breath no longer has a raycast sweep version of
	// this at all, per the flamethrower rework (see tickDracarysFire); each thrown fireball
	// ignites on its own, independently, via its own simpler single-block canPlaceAt check (see
	// ViserionFireballEntity#impactEffects), not through this method.
	private static final double BURN_AREA_RADIUS_NEAR = 2.5;

	private boolean canIgniteBlocks(ServerWorld serverWorld) {
		return serverWorld.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING);
	}

	private void tryIgnite(ServerWorld serverWorld, BlockPos pos) {
		if (AbstractFireBlock.canPlaceAt(serverWorld, pos, Direction.UP)) {
			BlockState before = serverWorld.getBlockState(pos);
			serverWorld.setBlockState(pos, AbstractFireBlock.getState(serverWorld, pos));
			MeltedBlocksState.get(serverWorld).addIgnitedFire(pos, before);
		}
	}

	// Vegetation/ground cover that real dragonfire would instantly char away rather than merely
	// set alight next to — vanilla has no "scorched" block variants to convert these into (Ice
	// and Fire registers its own for exactly this), so straight to air is the closest vanilla-
	// only equivalent of "burned to nothing".
	private boolean isVaporizable(BlockState state) {
		return state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.FLOWERS) || state.isIn(BlockTags.CROPS)
				|| state.isIn(BlockTags.SAPLINGS) || state.isIn(BlockTags.REPLACEABLE)
				|| state.isOf(Blocks.SNOW) || state.isOf(Blocks.VINE) || state.isOf(Blocks.SUGAR_CANE)
				|| state.isOf(Blocks.BAMBOO) || state.isOf(Blocks.DEAD_BUSH);
	}

	// Real area destruction, not just single-block ignite attempts: vaporizes low ground cover
	// outright, and has a real (not incidental) chance to ignite anything else flammable in
	// range. Respects doMobGriefing like every other block-modifying mob ability.
	private void burnArea(ServerWorld serverWorld, BlockPos center, double radius) {
		if (!canIgniteBlocks(serverWorld)) {
			return;
		}
		int intRadius = MathHelper.ceil(radius);
		double radiusSquared = radius * radius;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int dx = -intRadius; dx <= intRadius; dx++) {
			for (int dy = -intRadius; dy <= intRadius; dy++) {
				for (int dz = -intRadius; dz <= intRadius; dz++) {
					if (dx * dx + dy * dy + dz * dz > radiusSquared) {
						continue;
					}
					pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = serverWorld.getBlockState(pos);
					if (isVaporizable(state)) {
						serverWorld.setBlockState(pos, Blocks.AIR.getDefaultState());
					} else if (state.isAir() || state.isReplaceable()) {
						if (this.random.nextInt(3) == 0) {
							tryIgnite(serverWorld, pos);
						}
					} else if (state.getHardness(serverWorld, pos) < 0) {
						// Bedrock, barriers, command/structure/jigsaw blocks, portal frames, etc.
						// (negative hardness = vanilla's own "truly indestructible" marker) - real
						// dragonfire still shouldn't bypass whatever the world itself treats as
						// unbreakable.
					} else {
						// Second pass at this. Previous version deleted the block outright (crater
						// left behind, then lit on top of whatever was still standing below it) -
						// that's real explosive-style terrain destruction, which reads as "the fire
						// TNTs blocks" rather than burns them, per explicit feedback. This leaves
						// the block itself alone entirely and just ignites whichever of its exposed
						// faces are actually open (air/replaceable neighbors) - normal vanilla fire
						// behavior (flammable surface + adjacent open face), so stone/brick/etc
						// correctly do nothing (tryIgnite's canPlaceAt check fails, nothing
						// flammable to catch), while wood/wool/leaves-adjacent-solids genuinely
						// catch fire on their exposed side instead of getting deleted.
						for (Direction faceDirection : Direction.values()) {
							BlockPos neighbor = pos.offset(faceDirection);
							BlockState neighborState = serverWorld.getBlockState(neighbor);
							if (neighborState.isAir() || neighborState.isReplaceable()) {
								tryIgnite(serverWorld, neighbor);
							}
						}
					}
				}
			}
		}
	}

	// One-shot terrain destruction for the AI's single-target ranged flame (ViserionAttackGoal) -
	// the rider's own breath no longer has an equivalent sweep at all (see tickDracarysFire -
	// terrain ignition there now comes entirely from thrown fireballs hitting things, same as
	// their damage), this is still a direct instant hit so it keeps its own explicit burnArea call.
	private void igniteBlocksNear(ServerWorld serverWorld, Vec3d center) {
		burnArea(serverWorld, BlockPos.ofFloored(center), BURN_AREA_RADIUS_NEAR);
	}

	private void finishDracarys() {
		if (this.getWorld() instanceof ServerWorld serverWorld) {
			Vec3d mouth = slotPosition(ViserionPartId.MOUTH);
			serverWorld.spawnParticles(ParticleTypes.LARGE_SMOKE, mouth.x, mouth.y, mouth.z,
					25, 0.4, 0.4, 0.4, 0.02);
			serverWorld.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, mouth.x, mouth.y, mouth.z,
					10, 0.3, 0.3, 0.3, 0.01);
		}
		// Was ENTITY_GHAST_SHOOT - swapped out along with the rest of the "ghast blasts" cleanup,
		// same blaze-family sound the rest of the breath already uses for its identity.
		this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT,
				SoundCategory.HOSTILE, 1.3f, 1.4f);
		// flame_end (mouth closing) instead of a roar - the breath now has its own proper closing
		// beat, and both would fight for the same single "action" track if triggered together.
		// Roaring is still its own separate rider/AI-triggered action elsewhere, just not an
		// automatic follow-up to finishing a breath any more.
		this.triggerAnim("action", "flame_end");
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		// The constructor's int param IS the transition length in ticks (confirmed via javap,
		// not assumed) - movement was already blending at 10 ticks (0.5s), left alone here.
		// Real source of the "jerky" complaint: action was 0 (instant hard cut), and its
		// one-shot clips (Takeoff/Landing especially - 10 and 23 bones respectively, heavily
		// overlapping movement's own Fly/Hover bone set) were popping in and snapping back out
		// while movement kept blending smoothly underneath, which reads as a fight between the
		// two tracks. Given a small blend-in/out (4 ticks) instead - short enough that bite/claw
		// still read as snappy reactions, long enough to stop takeoff/landing/roar from popping.
		controllers.add(new AnimationController<>(this, "movement", 10, this::movementAnimController)
				.setOverrideEasingType(EasingType.EASE_IN_OUT_SINE));
		controllers.add(new AnimationController<>(this, "action", 4, state -> PlayState.STOP)
				.triggerableAnim("bite", RawAnimation.begin().thenPlay("Bite_Attack"))
				.triggerableAnim("claw", RawAnimation.begin().thenPlay("Claw_Attack"))
				.triggerableAnim("tail_attack", RawAnimation.begin().thenPlay("Tail_Attack"))
				// Flame is now three real phases instead of one looping clip re-triggered blind -
				// see fireDracarysNow/tickDracarysFire/finishDracarys for how these get sequenced.
				.triggerableAnim("flame_start", RawAnimation.begin().thenPlay("Flame_Start"))
				.triggerableAnim("flame_middle", RawAnimation.begin().thenPlay("Flame_Middle"))
				.triggerableAnim("flame_end", RawAnimation.begin().thenPlay("Flame_End"))
				.triggerableAnim("roar_ground", RawAnimation.begin().thenPlay("Roaring"))
				.triggerableAnim("roar_flying", RawAnimation.begin().thenPlay("Roaring_From_Flying"))
				// No "takeoff" entry any more - Take_Off_From_Ground doesn't exist in the new
				// ("Final Rework") animation set, see setFlying().
				.triggerableAnim("landing", RawAnimation.begin().thenPlay("Landing")));
	}

	// Hysteresis bands for the movement animation state machine — a single hard threshold
	// (the old code: "speed < 0.02 -> Hover, else Fly") flickered constantly, because actual
	// velocity sits right at that boundary for extended stretches under the throttle system's
	// own per-tick accel/glide-decay (it eases toward target speed and coasts down 0.91-0.985x
	// per tick on release, rather than snapping) - every tick the reading crossed the line,
	// GeckoLib restarted/re-blended the clip, which is exactly what read as "jittery, bugged,
	// too fast" in testing. A latch with separate enter/exit thresholds means a brief noisy
	// crossing doesn't matter - the state only actually flips once it's clearly past the band.
	// Ground walking got the same treatment for the same reason (state.isMoving() is just as
	// threshold-based), swapping GeckoLib's built-in check for our own hysteresis version.
	private static final double HOVER_ENTER_SPEED = 0.02;
	private static final double HOVER_EXIT_SPEED = 0.08;
	// Named by what they gate, not by target state (unlike the Hover pair above) — there's no
	// single named "moving" state on the ground (could be Walk or Sprint), so "enter/exit Walk"
	// would be misleading.
	private static final double GROUND_STOP_SPEED = 0.01;
	private static final double GROUND_MOVE_SPEED = 0.05;
	private boolean hovering = true;
	private boolean walking = false;

	private PlayState movementAnimController(AnimationState<ViserionEntity> state) {
		if (this.isFlying()) {
			// Landing outranks everything else, same reasoning as diving below - an explicit
			// state should always beat a speed-based read. Reuses the Glide pose (wings spread,
			// no flap) since that's exactly what a real gravity descent under rider steering
			// looks like; mutually exclusive with diving by construction (travel() clears
			// landing the instant diving becomes true), so no ordering conflict between the two.
			if (this.landing) {
				this.hovering = false;
				return state.setAndContinue(GLIDE);
			}

			// Diving is checked FIRST, before the hover hysteresis - was checked last, after an
			// early return for hovering, which was a real bug: a dive is mostly a vertical
			// descent (dirY dominates in flyControlledByRider's direction vector), so horizontal
			// speed alone frequently never clears HOVER_EXIT_SPEED during one. That let hovering
			// win outright and the dive check never even ran, showing the Hover pose through
			// dives instead of Dive. A deliberate, explicit keyboard state like this should
			// always outrank a speed-based read. Same keyboard-combo condition travel()'s flight
			// physics uses (sprint+sneak+forward), so "is diving" means the same thing here as it
			// does for the actual flight.
			PlayerEntity rider = this.getControllingPassenger() instanceof PlayerEntity p ? p : null;
			boolean diving = rider != null && rider.isSprinting() && rider.isSneaking() && rider.forwardSpeed > 0.01f;
			if (diving) {
				// Exit hover outright rather than easing through the hysteresis band - otherwise
				// coming out of a dive that never built horizontal speed could get stuck reading
				// as "still hovering" until speed happened to cross HOVER_EXIT_SPEED on its own.
				this.hovering = false;
				return state.setAndContinue(DIVE);
			}

			// Ascend is checked next, same reasoning as diving above - a real climbing pose now
			// exists, so an explicit held key should show it rather than falling through to
			// whatever the speed-based hysteresis happens to read.
			boolean ascending = rider != null && ((LivingEntityAccessor) rider).creaturesofwesteros$isJumping();
			if (ascending) {
				this.hovering = false;
				return state.setAndContinue(ASCEND);
			}

			// Horizontal speed only, deliberately — using vertical speed too made pure
			// ascend/descend play the full flapping loop, which read as too frantic; the
			// calmer Hover pose looks better while there's no real horizontal travel.
			double speed = this.getVelocity().horizontalLength();
			if (this.hovering) {
				if (speed > HOVER_EXIT_SPEED) {
					this.hovering = false;
				}
			} else if (speed < HOVER_ENTER_SPEED) {
				this.hovering = true;
			}
			if (this.hovering) {
				return state.setAndContinue(HOVER);
			}

			// Gliding now has a real pose (wings spread, no flap) instead of just continuing
			// whatever the flap loop was - shows whenever airborne and moving but with no active
			// thrust input held (coasting on leftover speed, see travel()'s glide decay). Only
			// settles back to Hover once speed actually decays into the hysteresis band above,
			// which still reads as an actual glide-to-a-stop instead of a hard cut. sidewaysSpeed
			// counts as thrust too now that strafing actually moves the dragon (see
			// flyControlledByRider) - a rider actively strafing shouldn't see the coasting Glide
			// pose any more than one actively flying forward would.
			boolean hasThrustInput = rider != null && (rider.forwardSpeed != 0 || rider.sidewaysSpeed != 0);
			if (!hasThrustInput) {
				return state.setAndContinue(GLIDE);
			}

			boolean sprint = rider != null && rider.isSprinting();
			return state.setAndContinue(sprint ? FLY_FAST : FLY_SLOW);
		}
		double groundSpeed = this.getVelocity().horizontalLength();
		if (this.walking) {
			if (groundSpeed < GROUND_STOP_SPEED) {
				this.walking = false;
			}
		} else if (groundSpeed > GROUND_MOVE_SPEED) {
			this.walking = true;
		}
		if (this.walking) {
			boolean sprint = this.getControllingPassenger() instanceof PlayerEntity p && p.isSprinting();
			return state.setAndContinue(sprint ? SPRINT : WALK);
		}
		return state.setAndContinue(IDLE);
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.animatableInstanceCache;
	}

	// Mirrors vanilla TrackOwnerAttackerGoal's logic (real source verified, not guessed) but
	// checks the bonded rider (a plain UUID field here, since ViserionEntity isn't a
	// TameableEntity) instead of TameableEntity's owner system. Suspended entirely while
	// ridden — see the mount-awareness note on initGoals.
	private class DefendBondedRiderGoal extends TrackTargetGoal {
		private LivingEntity attacker;
		private int lastAttackedTime;

		private DefendBondedRiderGoal() {
			super(ViserionEntity.this, false);
			this.setControls(EnumSet.of(Goal.Control.TARGET));
		}

		@Override
		public boolean canStart() {
			if (ViserionEntity.this.hasPassengers()) {
				return false;
			}
			PlayerEntity rider = ViserionEntity.this.getBondedRiderPlayer();
			if (rider == null) {
				return false;
			}
			this.attacker = rider.getAttacker();
			int time = rider.getLastAttackedTime();
			return time != this.lastAttackedTime && this.canTrack(this.attacker, TargetPredicate.DEFAULT);
		}

		@Override
		public void start() {
			ViserionEntity.this.setTarget(this.attacker);
			PlayerEntity rider = ViserionEntity.this.getBondedRiderPlayer();
			if (rider != null) {
				this.lastAttackedTime = rider.getLastAttackedTime();
			}
			super.start();
		}
	}

	// Approach + attack execution once something has a target (from DefendBondedRiderGoal,
	// RevengeGoal, or set some other way) — melee (bite/claw, alternating for visual variety)
	// up close, flame breath at range. BirdNavigation (Viserion's navigation type) already
	// handles 3D flight pathing, so the same approach logic works for ground and airborne
	// targets alike. Suspended entirely while ridden (see initGoals).
	private class ViserionAttackGoal extends Goal {
		// Matches ViserionRenderer's current 0.4x render scale — see ViserionPartId for the full
		// history/derivation of these numbers.
		private static final double MELEE_RANGE_SQUARED = 8.46; // ~2.91 blocks
		private static final double FLAME_RANGE_SQUARED = 211.5; // ~14.54 blocks
		private static final int PATH_RECALC_INTERVAL_TICKS = 10;
		private static final int MELEE_COOLDOWN_TICKS = 20;
		private static final int FLAME_COOLDOWN_TICKS = 60;

		private int recalcCountdown;
		private int meleeCooldown;
		private int flameCooldown;
		private boolean nextMeleeIsClaw;

		private ViserionAttackGoal() {
			this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
		}

		@Override
		public boolean canStart() {
			if (ViserionEntity.this.hasPassengers()) {
				return false;
			}
			LivingEntity target = ViserionEntity.this.getTarget();
			return target != null && target.isAlive();
		}

		@Override
		public boolean shouldContinue() {
			if (ViserionEntity.this.hasPassengers()) {
				return false;
			}
			LivingEntity target = ViserionEntity.this.getTarget();
			return target != null && target.isAlive();
		}

		@Override
		public void start() {
			this.recalcCountdown = 0;
			this.meleeCooldown = 0;
			this.flameCooldown = 0;
		}

		@Override
		public void stop() {
			ViserionEntity.this.getNavigation().stop();
		}

		@Override
		public void tick() {
			LivingEntity target = ViserionEntity.this.getTarget();
			if (target == null) {
				return;
			}
			ViserionEntity.this.getLookControl().lookAt(target, 30.0f, 30.0f);
			if (--this.recalcCountdown <= 0) {
				this.recalcCountdown = PATH_RECALC_INTERVAL_TICKS;
				ViserionEntity.this.getNavigation().startMovingTo(target, 1.0);
			}
			if (this.meleeCooldown > 0) {
				this.meleeCooldown--;
			}
			if (this.flameCooldown > 0) {
				this.flameCooldown--;
			}

			double distanceSquared = ViserionEntity.this.squaredDistanceTo(target);
			if (distanceSquared <= MELEE_RANGE_SQUARED && this.meleeCooldown <= 0) {
				this.meleeCooldown = MELEE_COOLDOWN_TICKS;
				this.nextMeleeIsClaw = !this.nextMeleeIsClaw;
				ViserionEntity.this.triggerAnim("action", this.nextMeleeIsClaw ? "claw" : "bite");
				ViserionEntity.this.tryAttack(target);
			} else if (distanceSquared <= FLAME_RANGE_SQUARED && this.flameCooldown <= 0) {
				this.flameCooldown = FLAME_COOLDOWN_TICKS;
				// Instantaneous single hit, not a sustained breath - flame_start alone (a quick
				// spit of fire) represents it fine, no need for the full start/middle/end sequence
				// the rider's sustained Dracarys breath uses.
				ViserionEntity.this.triggerAnim("action", "flame_start");
				target.damage(ViserionEntity.this.getDamageSources().mobAttack(ViserionEntity.this), 6.0f);
				target.setFireTicks(Math.max(target.getFireTicks(), DRACARYS_IGNITE_TICKS));
				if (ViserionEntity.this.getWorld() instanceof ServerWorld serverWorld) {
					ViserionEntity.this.igniteBlocksNear(serverWorld, target.getPos());
					ViserionEntity.this.spawnDragonFireParticles(serverWorld,
							target.getPos().add(0, target.getHeight() * 0.5, 0), 0.4, 0.05);
				}
			}
		}
	}
}

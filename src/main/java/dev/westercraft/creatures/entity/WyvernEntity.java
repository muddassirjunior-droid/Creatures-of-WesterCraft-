package dev.westercraft.creatures.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.FlyGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.control.MoveControl;
import net.minecraft.entity.ai.pathing.BirdNavigation;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import dev.westercraft.creatures.permission.WyvernImmunity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.EnumSet;

// A rogue boss-type wyvern, horse-sized-or-slightly-bigger. Briefly made rideable/bondable (per a
// since-reversed request), but back to explicitly NOT rideable - "a wild boss you fight, not
// tame." Flight is deliberately erratic/unstable per explicit request ("SUPER UNSTABLE ITS LIKE
// WITHER") - see the jitter in travel()/WyvernMoveControl - not the smooth undulating cruise
// ViserionEntity uses, closer to how the vanilla Wither's own hover reads: twitchy, unpredictable,
// never quite level. No ranged fire breath at all despite the model having Flame_Start/Middle/End
// clips (left registered as triggerable anims in case a ranged attack gets added later, just
// never actually triggered). Combat is a real bull-rush loop, not a static melee slugfest: charge/
// dash in, land a hit (bite/claw/tail/bash rotation plus bonus damage and a real block-destructive
// explosion, see WyvernAttackGoal#tickCharge/triggerExplosion), immediately peel off into a short
// retreat, then charge again - and a landed hit from the player interrupts whatever it's doing to
// kick off that same retreat-then-charge cycle early (see damage()/wantsToRetreat). Both the
// charge and the retreat are obstacle-aware (raycastTo/hasClearPath) so it won't blindly ram or
// backpedal into a wall. No multi-part hitbox system like Viserion's (ViserionPart) - a single
// entity hitbox (deliberately bigger than the shrunk-down render scale, see ModEntities#WYVERN/
// WyvernRenderer) is standard for a horse-sized creature and far simpler for a first pass. Same
// rig/animation set as Viserion (see the shared "Head" bone rename requirement in
// wyvern.geo.json/wyvern.animation.json), plus one new clip this model has that Viserion's
// doesn't: Bash_attack.
public class WyvernEntity extends PathAwareEntity implements GeoEntity {
	private static final RawAnimation HOVER = RawAnimation.begin().thenLoop("Hovering");
	private static final RawAnimation FLY_SLOW = RawAnimation.begin().thenLoop("Flying_Slow");
	private static final RawAnimation FLY_FAST = RawAnimation.begin().thenLoop("Flying_Fast");
	private static final RawAnimation CHARGE = RawAnimation.begin().thenLoop("Dive");

	// Always true in practice right now - per explicit request ("it should usually fly") this
	// never lands or walks at all in this first pass, so FLYING only really distinguishes
	// "just spawned, hasn't ascended yet" from everything after. Still tracked data (not a plain
	// field) because registerControllers/movementAnimController read it from the CLIENT's own
	// mirrored entity instance, which only ever sees synced state.
	private static final TrackedData<Boolean> FLYING =
			DataTracker.registerData(WyvernEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

	private final AnimatableInstanceCache animatableInstanceCache = GeckoLibUtil.createInstanceCache(this);
	// Boss health bar - per explicit request, shown to nearby players the same way the Ender
	// Dragon/Wither are (onStartedTrackingBy/onStoppedTrackingBy below add/remove players as they
	// come in and out of tracking range; tick() keeps the percent in sync with actual health).
	private final ServerBossBar bossBar = new ServerBossBar(
			Text.literal("Wyvern").formatted(Formatting.BOLD, Formatting.DARK_RED),
			BossBar.Color.RED, BossBar.Style.NOTCHED_10);

	public WyvernEntity(EntityType<? extends WyvernEntity> type, World world) {
		super(type, world);
		// Same fix as ViserionEntity - the hitbox and the render scale were deliberately decoupled
		// (see ModEntities#WYVERN/WyvernRenderer, "make it smaller and make the hitbox bigger"),
		// so the default frustum check against the hitbox alone can no longer be trusted to match
		// what's actually on screen. Same vanilla trick the Ender Dragon uses.
		this.ignoreCameraFrustum = true;
		// Always flying per explicit request - see the FLYING field comment above (already
		// defaults to true via initDataTracker, this just applies the physics side of it).
		this.setNoGravity(true);
		// Real bug, not guessed: the default MoveControl (confirmed via decompile) only ever sets
		// yaw + forward speed - it has zero concept of vertical movement at all, it relies on
		// gravity/jumping for Y, which a NoGravity flier never triggers. That's why this had
		// "no AI" after the flight rework: it could target and try to navigate, but had no way to
		// actually move at all once airborne. Vanilla's own Vex (a true 3D-pathing flier, same
		// BirdNavigation-adjacent situation) fixes this with its own MoveControl that sets
		// velocity directly toward the target instead of yaw/forward-speed - WyvernMoveControl
		// below is that same pattern.
		this.moveControl = new WyvernMoveControl(this);
	}

	@Override
	protected void initDataTracker() {
		super.initDataTracker();
		this.dataTracker.startTracking(FLYING, true);
	}

	public boolean isFlying() {
		return this.dataTracker.get(FLYING);
	}

	public void setFlying(boolean flying) {
		this.dataTracker.set(FLYING, flying);
		this.setNoGravity(flying);
	}

	// A flying creature taking fall damage doesn't make sense - same reasoning as
	// ViserionEntity#computeFallDamage.
	@Override
	protected int computeFallDamage(float fallDistance, float damageMultiplier) {
		return 0;
	}

	// Set here, consumed by WyvernAttackGoal (which owns the actual retreat/charge state) - per
	// explicit request ("make him smart and actually fly away when hit to come back and strike"),
	// getting hit is now its own trigger for the hit-and-run loop, not just something that happens
	// to interrupt whatever it was already doing. A plain field, not a data tracker entry - only
	// the goal's own tick() (server-side) ever needs to see this.
	private boolean wantsToRetreat;

	@Override
	public boolean damage(DamageSource source, float amount) {
		boolean result = super.damage(source, amount);
		if (result && !this.getWorld().isClient && source.getAttacker() instanceof LivingEntity) {
			this.wantsToRetreat = true;
		}
		return result;
	}

	// Deliberately erratic, not smooth, per explicit request ("SUPER UNSTABLE ITS LIKE WITHER") -
	// a small random velocity kick every tick on top of whatever WyvernMoveControl/navigation is
	// already doing, so it never holds a clean line the way Viserion's own undulating-but-smooth
	// sine bob did (that approach is gone entirely - this replaces it, not supplements it). Off
	// while charging so a dash still reads as a real, deliberate straight-line lunge rather than
	// getting the same twitchy treatment as ordinary flight.
	private static final double JITTER_STRENGTH = 0.05;

	// No rider to steer this one any more (see class comment - back to not rideable) - gravity
	// off, let super.travel() apply whatever the active goal's movement input is (already driven
	// by BirdNavigation via getNavigation().startMovingTo()), then bleed off a little velocity
	// each tick so it doesn't coast forever between path updates, plus the jitter above.
	@Override
	public void travel(Vec3d movementInput) {
		if (this.isFlying()) {
			this.setNoGravity(true);
			super.travel(movementInput);
			this.setVelocity(this.getVelocity().multiply(0.98, 0.98, 0.98));
			if (!this.charging) {
				double jx = (this.random.nextDouble() - 0.5) * 2.0 * JITTER_STRENGTH;
				double jy = (this.random.nextDouble() - 0.5) * 2.0 * JITTER_STRENGTH;
				double jz = (this.random.nextDouble() - 0.5) * 2.0 * JITTER_STRENGTH;
				this.setVelocity(this.getVelocity().add(jx, jy, jz));
			}
			// Real bug, not guessed: mean-zero random jitter is still a random WALK in position,
			// not just velocity - with nothing actively holding altitude between wander/charge
			// targets, it could (and per report, did) drift down and settle right on the ground,
			// which just reads as "a normal grounded mob standing there," not floating. A NoGravity
			// creature that's allowed to come to rest on solid ground stops looking like it's
			// floating at all - bounced back up any time it actually touches down, so it can never
			// settle, the same way the vanilla Wither never actually lands.
			if (this.isOnGround()) {
				this.setVelocity(this.getVelocity().add(0, 0.35, 0));
			}
		} else {
			this.setNoGravity(false);
			super.travel(movementInput);
		}
	}

	public static DefaultAttributeContainer.Builder createWyvernAttributes() {
		return MobEntity.createMobAttributes()
				// Was 250 - bumped per explicit request. Still below Viserion's dragon-tier 2000,
				// but a real melee-only slugfest needs more health than a creature that could also
				// chip damage in from range.
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 500.0)
				// Was 0.3 - bumped per explicit "more aggressive" request, so it actually closes
				// distance fast between charges instead of drifting in slowly.
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.4)
				// Was 10 - bumped hard per explicit "high dps" request. Shared attribute, so this
				// is what bite/claw/tail_attack (via tryAttack) actually deal.
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 16.0)
				// Was 32, then 48 - bumped again to a literal 100 per explicit request ("everything
				// in a 100 radius"). ActiveTargetGoal's own search box is derived straight from
				// this attribute (confirmed via decompile - getFollowRange() feeds
				// getSearchBox()), so this single number is what actually controls how far out it
				// notices and commits to a target, not just flavor text.
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 100.0)
				.add(EntityAttributes.GENERIC_ARMOR, 4.0)
				// Enough that arrows/weak hits don't visibly shove it around, but not immune -
				// this isn't the near-untouchable dragon-tier immunity Viserion has.
				.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.4);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(1, new WyvernAttackGoal());
		// FlyGoal (vanilla's Phantom-style flight wander, picks airborne points via BirdNavigation
		// instead of WanderAroundFarGoal's ground-based LandRandomPos) - per explicit request
		// ("should usually fly"), swapped in alongside createNavigation()'s switch to
		// BirdNavigation below.
		FlyGoal flyGoal = new FlyGoal(this, 1.0);
		// Real bug found via decompile, not guessed: WanderAroundGoal (FlyGoal's own superclass)
		// only rolls a 1-in-DEFAULT_CHANCE (120) chance PER TICK to even start a new wander once
		// its current one finishes (shouldContinue() ends the goal outright the instant
		// getNavigation().isIdle() - there's no coasting, it just stops dead) - per explicit
		// report ("it should move more it does not move"), that's a real multi-second stand-still
		// between hops on average while it has no combat target, not just a slow flight feel.
		// setChance() is the vanilla-exposed way to override that roll frequency; 5 means it's
		// only a quarter-second average wait before picking its next destination instead of six.
		flyGoal.setChance(5);
		this.goalSelector.add(2, flyGoal);
		this.goalSelector.add(3, new LookAtEntityGoal(this, LivingEntity.class, 12.0f));
		this.goalSelector.add(4, new LookAroundGoal(this));
		this.targetSelector.add(1, new RevengeGoal(this));
		// Targets any living thing, not just players - per explicit request ("kills everything
		// when released"). Excludes itself and other Wyverns (no friendly fire between them);
		// the immune-player carve-out is handled centrally in canTarget() below, which this (and
		// RevengeGoal above) both consult automatically via TargetPredicate - confirmed via
		// decompile, not guessed, so one override covers both goals. reciprocalChance is 0 (was
		// 10) - per explicit request ("hostile to everything instantly"), confirmed via decompile
		// that ActiveTargetGoal#canStart() only even attempts a target search on a
		// 1-in-reciprocalChance random roll each tick when that value is positive; anything above
		// 0 means it can sit for a real stretch of ticks before it first notices something nearby.
		// 0 skips that roll entirely and searches every single tick instead. checkVisibility is
		// false now (was true) - real bug found via decompile of TargetPredicate#test, not
		// guessed: with it true, a candidate only ever counts if this mob's own
		// MobVisibilityCache.canSee(candidate) passes, a real raycast line-of-sight check. That's
		// exactly why "doesn't engage any mob" was plausible while players still worked fine -
		// flying well above the ground, most actual mobs sit under tree canopy/inside structures/
		// behind terrain from its vantage point and would routinely fail that raycast, while a
		// moving player in the open passes it far more often. False drops the requirement
		// entirely, targeting by proximity alone - matches "it just goes crazy" anyway.
		this.targetSelector.add(2, new ActiveTargetGoal<>(this, LivingEntity.class, 0, false, false,
				living -> living != this && !(living instanceof WyvernEntity)));
	}

	@Override
	protected EntityNavigation createNavigation(World world) {
		// Was MobNavigation (ground-only) - switched to BirdNavigation (3D, doesn't need a
		// walkable path underneath) per explicit request ("should usually fly").
		return new BirdNavigation(this, world);
	}

	// The one carve-out for "kills everything when released" - per explicit request, a player
	// granted immunity via /wyvern immune is completely ignored: never targeted proactively
	// (ActiveTargetGoal) and never retaliated against even if they attack it (RevengeGoal), since
	// both of those goals consult this same method through TargetPredicate (confirmed via
	// decompile - TargetPredicate.test calls LivingEntity#canTarget(LivingEntity)) rather than
	// needing a separate check wired into each goal individually.
	@Override
	public boolean canTarget(LivingEntity target) {
		if (target instanceof PlayerEntity player && this.getWorld() instanceof ServerWorld serverWorld
				&& WyvernImmunity.getServerState(serverWorld.getServer()).isImmune(player.getUuid())) {
			return false;
		}
		return super.canTarget(target);
	}

	// Sound identity per explicit request ("super scary audio") - Ender Dragon growl/death for the
	// idle ambience and death cry (deep, recognizably draconic, genuinely imposing rather than a
	// generic hostile-mob grunt) layered with Ravager hurt/step sounds (heavy, physical, sells the
	// "large grounded predator" weight a pure dragon sound wouldn't). getSoundPitch() below shifts
	// every one of these lower/deeper on top - same jitter formula LivingEntity's own default uses
	// (confirmed via decompile, not guessed), just centered on 0.7 instead of 1.0, so nothing here
	// needs its own one-off pitch tweak to read as bigger and more menacing than vanilla's default.
	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.ENTITY_ENDER_DRAGON_GROWL;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.ENTITY_RAVAGER_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.ENTITY_ENDER_DRAGON_DEATH;
	}

	@Override
	public float getSoundPitch() {
		return (this.random.nextFloat() - this.random.nextFloat()) * 0.2f + 0.7f;
	}

	@Override
	protected void playStepSound(BlockPos pos, BlockState state) {
		this.playSound(SoundEvents.ENTITY_RAVAGER_STEP, 0.5f, this.getSoundPitch());
	}

	// Real bug, not guessed: neither charging nor retreating checked for obstacles at all before
	// committing to a straight line - per explicit report ("it tries to save itself at the same
	// time fucks itself over trying to bash into shit"), that meant a charge could commit to
	// slamming straight into a wall between it and the target instead of actually reaching them,
	// and a retreat could back it directly into whatever terrain happened to be behind it. Used by
	// WyvernAttackGoal to check line-of-sight before committing to a charge, and to clamp a
	// retreat destination short of any actual obstacle - same raycast pattern
	// ViserionEntity's own ground checks (isTouchingGround/forceLandNow) already use in this
	// project (COLLIDER shape, no fluid handling, self excluded).
	private BlockHitResult raycastTo(Vec3d from, Vec3d to) {
		return this.getWorld().raycast(new RaycastContext(from, to,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
	}

	private boolean hasClearPath(Vec3d from, Vec3d to) {
		return raycastTo(from, to).getType() != HitResult.Type.BLOCK;
	}

	// Real hitbox at the mouth, per explicit request, and the actual fix for "dive down and
	// actually hit them" - the old charge-connect check was a single squaredDistanceTo() reading
	// taken at the START of the goal's tick(), before that tick's own movement gets applied, and
	// only re-evaluated against the coarse MELEE_RANGE_SQUARED radius from the wyvern's body
	// CENTER (not where the mouth visually is on the model). At real charge speed that stale,
	// center-based, single-point check could plainly whiff a target the mouth had actually already
	// reached, or reach a "hit" only once the wyvern had already flown past. A genuine Box anchored
	// at the mouth, tested for actual intersection against the target's own bounding box
	// (Box#intersects), fixes both at once: it's a real volume, not a point, and it's positioned
	// where the model's mouth actually is via getRotationVector() (accounts for pitch, so it
	// follows a real diving angle, not just horizontal facing).
	// Was a small cube floating at a single fixed offset - per explicit request ("longer hitbox"),
	// stretched into a real elongated box instead: runs from the eye position all the way out to
	// MOUTH_REACH along the current look direction (covers the whole snout/bite path, not just one
	// point past it), then padded out sideways/vertically by MOUTH_WIDTH so it isn't paper-thin.
	// More forgiving for a fast-moving dive by construction - a longer box has more room to
	// actually overlap a target's own hitbox as it passes through, not just at one exact instant.
	private static final double MOUTH_REACH = 3.0;
	private static final double MOUTH_WIDTH = 1.2;

	private Box getMouthHitbox() {
		Vec3d look = this.getRotationVector();
		Vec3d start = this.getEyePos();
		Vec3d end = start.add(look.multiply(MOUTH_REACH));
		return new Box(start, end).expand(MOUTH_WIDTH / 2.0);
	}

	private static final double BASH_DESTROY_RADIUS = 3.0;

	private static boolean isWooden(BlockState state) {
		return state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)
				|| state.isIn(BlockTags.WOODEN_DOORS) || state.isIn(BlockTags.WOODEN_TRAPDOORS)
				|| state.isIn(BlockTags.WOODEN_FENCES) || state.isIn(BlockTags.WOODEN_STAIRS)
				|| state.isIn(BlockTags.WOODEN_SLABS) || state.isIn(BlockTags.WOODEN_PRESSURE_PLATES)
				|| state.isIn(BlockTags.WOODEN_BUTTONS) || state.isIn(BlockTags.FENCE_GATES);
	}

	// Bash smashes through wood specifically (not stone/dirt/etc - a real "it barreled through the
	// wall" moment, not generic terrain destruction), per explicit request. Same
	// doMobGriefing/hasBlockEntity/indestructible-hardness respect every other block-modifying
	// ability in this mod already follows (see ViserionFireballEntity#meltBlock).
	private void destroyWoodenBlocksNear(BlockPos center) {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)
				|| !serverWorld.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
			return;
		}
		int intRadius = (int) Math.ceil(BASH_DESTROY_RADIUS);
		double radiusSquared = BASH_DESTROY_RADIUS * BASH_DESTROY_RADIUS;
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		for (int dx = -intRadius; dx <= intRadius; dx++) {
			for (int dy = -intRadius; dy <= intRadius; dy++) {
				for (int dz = -intRadius; dz <= intRadius; dz++) {
					if (dx * dx + dy * dy + dz * dz > radiusSquared) {
						continue;
					}
					mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = serverWorld.getBlockState(mutable);
					if (!isWooden(state) || state.hasBlockEntity() || state.getHardness(serverWorld, mutable) < 0) {
						continue;
					}
					serverWorld.breakBlock(mutable, true, this, 512);
				}
			}
		}
	}

	// Real explosion on every landed charge or bash, per explicit request ("just cause an
	// explosion everytime WE GO BOOM") - not vanilla's World#createExplosion (same reasoning as
	// ViserionFireballEntity#triggerBigExplosion: a claim/protection mod in a real pack is the
	// likely culprit for that silently not destroying anything, so this uses the same direct
	// setBlockState-based destruction that's already proven to work here), just entity
	// damage/knockback plus a real block-destructive sphere and a proper boom (particle + sound),
	// unlike the fire-flavored effects elsewhere in this mod - this one's allowed to just look and
	// sound like an explosion.
	private static final double EXPLOSION_RADIUS = 3.0;
	private static final float EXPLOSION_DAMAGE = 10.0f;
	private static final double EXPLOSION_KNOCKBACK_STRENGTH = 1.8;

	private void triggerExplosion(Vec3d pos) {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		Box box = new Box(pos.x - EXPLOSION_RADIUS, pos.y - EXPLOSION_RADIUS, pos.z - EXPLOSION_RADIUS,
				pos.x + EXPLOSION_RADIUS, pos.y + EXPLOSION_RADIUS, pos.z + EXPLOSION_RADIUS);
		// Real bug caught in review, not reported: this splash never checked canTarget() at all,
		// so a player on the /wyvern immune list standing near the actual target would still take
		// AoE damage - directly contradicting "completely ignored." The direct charge/bash hit was
		// always safe (its target comes from the immunity-filtered ActiveTargetGoal to begin
		// with), only this broader splash was missing the same check.
		for (LivingEntity target : serverWorld.getEntitiesByClass(LivingEntity.class, box,
				e -> e != this && e.isAlive() && this.canTarget(e))) {
			if (target.squaredDistanceTo(pos) > EXPLOSION_RADIUS * EXPLOSION_RADIUS) {
				continue;
			}
			target.damage(this.getDamageSources().mobAttack(this), EXPLOSION_DAMAGE);
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
						if (state.isAir() || state.hasBlockEntity() || state.getHardness(serverWorld, mutable) < 0) {
							continue;
						}
						serverWorld.setBlockState(mutable, Blocks.AIR.getDefaultState());
					}
				}
			}
		}

		serverWorld.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0.0, 0.0, 0.0, 0.0);
		serverWorld.playSound(null, BlockPos.ofFloored(pos), SoundEvents.ENTITY_GENERIC_EXPLODE,
				SoundCategory.HOSTILE, 3.0f, 0.9f);
	}

	// Constant ambient destruction, not just on landed attacks - per explicit request ("it's like
	// a wither but a wyvern... JUST DESTROY SHIT"). Same shape as vanilla's own
	// WitherEntity#mobTick block-breaking (confirmed via decompile: a small box centered on
	// itself, checked on a cooldown, doMobGriefing-gated, breakBlock not setBlockState so it still
	// drops items) and even reuses WITHER_IMMUNE for what NOT to touch - the same tag that keeps a
	// real Wither from chewing through bedrock/end portal frames/etc is exactly the "don't destroy
	// this" list a wither-flavored wyvern should respect too. Still keeps this mod's own
	// hasBlockEntity()/negative-hardness guards on top, unlike the real Wither (which doesn't
	// bother) - chests/furnaces silently losing their contents is a real risk at this creature's
	// actual flight density, not a theoretical one (see ViserionFireballEntity#meltBlock for the
	// same reasoning).
	private static final int CONTINUOUS_DESTROY_INTERVAL_TICKS = 10;
	private static final int CONTINUOUS_DESTROY_RADIUS = 2;

	private void tickContinuousDestruction() {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)
				|| !serverWorld.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
			return;
		}
		BlockPos center = this.getBlockPos();
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		for (int dx = -CONTINUOUS_DESTROY_RADIUS; dx <= CONTINUOUS_DESTROY_RADIUS; dx++) {
			for (int dy = -CONTINUOUS_DESTROY_RADIUS; dy <= CONTINUOUS_DESTROY_RADIUS; dy++) {
				for (int dz = -CONTINUOUS_DESTROY_RADIUS; dz <= CONTINUOUS_DESTROY_RADIUS; dz++) {
					mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = serverWorld.getBlockState(mutable);
					if (state.isAir() || state.isIn(BlockTags.WITHER_IMMUNE) || state.hasBlockEntity()
							|| state.getHardness(serverWorld, mutable) < 0) {
						continue;
					}
					serverWorld.breakBlock(mutable, true, this, 512);
				}
			}
		}
	}

	// Very slow passive regen, per explicit request - mainly to offset the bash's own 5 HP self-
	// damage recoil (see WyvernAttackGoal#performMelee) over time rather than a real sustain
	// mechanic; at 500 max health this is nowhere near fast enough to out-heal actual combat.
	private static final int HEAL_INTERVAL_TICKS = 200;

	@Override
	public void tick() {
		super.tick();
		if (!this.getWorld().isClient) {
			// Clamped - health/maxHealth can transiently read slightly outside 0..1 for a tick
			// around a killing blow, and ServerBossBar#setPercent throws on an out-of-range value.
			this.bossBar.setPercent(MathHelper.clamp(this.getHealth() / this.getMaxHealth(), 0.0f, 1.0f));
			if (this.isAlive() && this.getHealth() < this.getMaxHealth() && this.age % HEAL_INTERVAL_TICKS == 0) {
				this.heal(1.0f);
			}
			if (this.isAlive() && this.age % CONTINUOUS_DESTROY_INTERVAL_TICKS == 0) {
				tickContinuousDestruction();
			}
		}
	}

	@Override
	public void onStartedTrackingBy(ServerPlayerEntity player) {
		super.onStartedTrackingBy(player);
		this.bossBar.addPlayer(player);
	}

	@Override
	public void onStoppedTrackingBy(ServerPlayerEntity player) {
		super.onStoppedTrackingBy(player);
		this.bossBar.removePlayer(player);
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		this.bossBar.clearPlayers();
		super.remove(reason);
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.animatableInstanceCache;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "movement", 6, this::movementAnimController));
		controllers.add(new AnimationController<>(this, "action", 4, state -> PlayState.STOP)
				.triggerableAnim("bite", RawAnimation.begin().thenPlay("Bite_Attack"))
				.triggerableAnim("claw", RawAnimation.begin().thenPlay("Claw_Attack"))
				.triggerableAnim("tail_attack", RawAnimation.begin().thenPlay("Tail_Attack"))
				.triggerableAnim("bash_attack", RawAnimation.begin().thenPlay("Bash_attack"))
				.triggerableAnim("flame_start", RawAnimation.begin().thenPlay("Flame_Start"))
				.triggerableAnim("flame_middle", RawAnimation.begin().thenPlay("Flame_Middle"))
				.triggerableAnim("flame_end", RawAnimation.begin().thenPlay("Flame_End"))
				.triggerableAnim("roar", RawAnimation.begin().thenPlay("Roaring"))
				.triggerableAnim("roar_flying", RawAnimation.begin().thenPlay("Roaring_From_Flying")));
	}

	// Set/read by WyvernAttackGoal - not a data tracker entry, since flight animation state only
	// needs to be right on whichever side is actually running the goal's tick() (server), same as
	// how the geo model itself is driven by server-authoritative position/velocity either way.
	private boolean charging;

	// Real bug caught in review, not reported as "flicker" specifically but exactly the kind of
	// thing that reads as "the animations are broken": a single hard speed threshold flickers
	// constantly once there's real per-tick noise in the velocity reading, and travel() adds
	// exactly that (JITTER_STRENGTH, every tick, per the "SUPER UNSTABLE" request) - the speed
	// reading can cross a bare threshold multiple times a second, restarting/reblending the clip
	// each time. Same hysteresis-latch fix already proven on ViserionEntity#movementAnimController
	// for the identical symptom: separate enter/exit bands per state, so a brief noisy crossing
	// doesn't flip anything - the state only actually changes once speed is clearly past the band.
	private boolean hovering = true;
	private boolean flyingFast;
	private static final double HOVER_ENTER_SPEED = 0.03;
	private static final double HOVER_EXIT_SPEED = 0.12;
	private static final double FLY_FAST_ENTER_SPEED = 0.25;
	private static final double FLY_FAST_EXIT_SPEED = 0.15;

	// Flight-based now instead of ground walk/sprint, per explicit request ("should usually fly").
	// Charging (see WyvernAttackGoal) outranks a plain speed read - an explicit "it's mid-dash"
	// state should always win over guessing from velocity alone, same reasoning
	// ViserionEntity#movementAnimController uses for its own diving/landing overrides.
	private PlayState movementAnimController(AnimationState<WyvernEntity> state) {
		if (this.charging) {
			this.hovering = false;
			state.setAndContinue(CHARGE);
			return PlayState.CONTINUE;
		}
		double speed = this.getVelocity().length();
		if (this.hovering) {
			if (speed > HOVER_EXIT_SPEED) {
				this.hovering = false;
			}
		} else if (speed < HOVER_ENTER_SPEED) {
			this.hovering = true;
		}
		if (this.hovering) {
			state.setAndContinue(HOVER);
			return PlayState.CONTINUE;
		}
		if (this.flyingFast) {
			if (speed < FLY_FAST_EXIT_SPEED) {
				this.flyingFast = false;
			}
		} else if (speed > FLY_FAST_ENTER_SPEED) {
			this.flyingFast = true;
		}
		state.setAndContinue(this.flyingFast ? FLY_FAST : FLY_SLOW);
		return PlayState.CONTINUE;
	}

	// Approach + attack: a real bull-rush now, per explicit request ("high dps... make him go back
	// and charge again and again like he turns around and then charges in AGAIN AND AGAIN") -
	// charge/dash is the primary attack, not an occasional alternative to melee any more. After
	// every charge (hit or miss) it immediately backs off away from the target for a short window
	// (tickRetreat) instead of just standing there in melee range, then charges straight back in -
	// CHARGE_COOLDOWN_TICKS itself is now just a same-tick-retrigger guard, the retreat phase is
	// what actually paces the loop. Melee (bite/claw/tail/bash rotation) is still there as a
	// fallback for whenever it genuinely can't create separation (e.g. cornered), but charging is
	// what it does by default. No ranged fire breath at all per earlier explicit request. Modeled
	// closely on ViserionEntity#ViserionAttackGoal, minus the "suspended while ridden" checks that
	// don't apply here (Wyvern is never rideable at all).
	private class WyvernAttackGoal extends Goal {
		// Was 9 (3 blocks) - shrunk per explicit "more dive attacks" request, so more of a real
		// fight now falls into charging rather than the melee rotation; only genuinely
		// point-blank range still uses melee instead.
		private static final double MELEE_RANGE_SQUARED = 4.0; // 2 blocks
		private static final int PATH_RECALC_INTERVAL_TICKS = 10;
		// Was 20 - cut per explicit "more aggressive" request, so a cornered melee exchange
		// swings faster too, not just the charge loop.
		private static final int MELEE_COOLDOWN_TICKS = 14;
		// Just a same-tick-retrigger guard now - the real pacing between charges comes from the
		// retreat phase below, not this.
		private static final int CHARGE_COOLDOWN_TICKS = 10;
		// Gives up and resets on cooldown if a charge runs this long without connecting (target
		// dodged/flew away) - a charge shouldn't be able to lock on and chase forever.
		private static final int CHARGE_TIMEOUT_TICKS = 60;
		// Was 2.2 - bumped per explicit "more aggressive" request, for a faster, harder-hitting-
		// feeling run-up.
		private static final double CHARGE_SPEED_MULTIPLIER = 2.6;
		// Was 12 - bumped hard per explicit "high dps" request, alongside GENERIC_ATTACK_DAMAGE
		// (melee) and the bash's own direct-hit damage below.
		private static final float CHARGE_DAMAGE = 22.0f;
		private static final double CHARGE_KNOCKBACK_STRENGTH = 2.5;
		// How long it flies directly away from the target after a charge ends, and how fast - per
		// explicit request ("he turns around and then charges in again"), this is the literal
		// turn-around: without it, a charge that connects ends already inside MELEE_RANGE_SQUARED,
		// so the very next tick would just fall into the melee rotation instead of setting up
		// another pass. Was 20 ticks/12 blocks, then 10 ticks/7 blocks - cut again per the later
		// explicit "more aggressive" request: even less time spent backing off before it's lined
		// up for the next pass.
		private static final int RETREAT_TICKS = 6;
		private static final double RETREAT_SPEED = 1.8;
		private static final double RETREAT_DISTANCE = 5.0;

		private int recalcCountdown;
		private int meleeCooldown;
		private int chargeCooldown;
		private int chargeTicksElapsed;
		private int meleeMoveIndex;
		private boolean retreating;
		private int retreatTicksElapsed;

		private WyvernAttackGoal() {
			this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
		}

		@Override
		public boolean canStart() {
			LivingEntity target = WyvernEntity.this.getTarget();
			return target != null && target.isAlive();
		}

		@Override
		public boolean shouldContinue() {
			LivingEntity target = WyvernEntity.this.getTarget();
			return target != null && target.isAlive();
		}

		@Override
		public void start() {
			this.recalcCountdown = 0;
			this.meleeCooldown = 0;
			this.chargeCooldown = 0;
			this.retreating = false;
			WyvernEntity.this.wantsToRetreat = false;
			// Per explicit request ("use all the animations") - it's airborne essentially always
			// (see FLYING's own comment), so the flying-specific roar clip is the one that
			// actually matches its real state; "roar" (grounded) only fires on the rare tick it's
			// actually touching down.
			WyvernEntity.this.triggerAnim("action", WyvernEntity.this.isOnGround() ? "roar" : "roar_flying");
			// Louder and deeper than the idle ambient growl (getAmbientSound() plays that one on
			// its own, quieter, periodic timer) - a real "it's noticed you" moment.
			WyvernEntity.this.playSound(SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 3.0f, 0.5f);
		}

		@Override
		public void stop() {
			WyvernEntity.this.getNavigation().stop();
			WyvernEntity.this.charging = false;
			this.retreating = false;
		}

		@Override
		public void tick() {
			LivingEntity target = WyvernEntity.this.getTarget();
			if (target == null) {
				return;
			}
			WyvernEntity.this.getLookControl().lookAt(target, 30.0f, 30.0f);
			double distanceSquared = WyvernEntity.this.squaredDistanceTo(target);

			if (WyvernEntity.this.charging) {
				tickCharge(target, distanceSquared);
				return;
			}
			// Per explicit request ("actually fly away when hit to come back and strike") - a
			// real hit interrupts whatever it was doing (including just standing there mid-melee-
			// cooldown) and immediately kicks off the same retreat-then-charge loop a landed/missed
			// charge already uses, rather than getting hit only ever mattering once its current
			// action happened to finish on its own.
			if (WyvernEntity.this.wantsToRetreat && !this.retreating) {
				WyvernEntity.this.wantsToRetreat = false;
				beginRetreat();
			}
			if (this.retreating) {
				tickRetreat(target, distanceSquared);
				return;
			}

			if (this.chargeCooldown > 0) {
				this.chargeCooldown--;
			}
			if (this.meleeCooldown > 0) {
				this.meleeCooldown--;
			}
			if (--this.recalcCountdown <= 0) {
				this.recalcCountdown = PATH_RECALC_INTERVAL_TICKS;
				WyvernEntity.this.getNavigation().startMovingTo(target, 1.0);
			}

			if (distanceSquared <= MELEE_RANGE_SQUARED && this.meleeCooldown <= 0) {
				this.meleeCooldown = MELEE_COOLDOWN_TICKS;
				performMelee(target);
			} else if (distanceSquared > MELEE_RANGE_SQUARED && this.chargeCooldown <= 0
					// Smarter now, per explicit request ("the run up") - only actually commits to a
					// charge if there's a genuine clear line to the target first. Without this it
					// would happily line up a charge with a wall directly in the way and just slam
					// into that instead - with it, a blocked line just falls through to the normal
					// pathing-based approach above (which routes around obstacles) until it
					// actually has a clean shot.
					&& WyvernEntity.this.hasClearPath(WyvernEntity.this.getPos(), target.getPos())) {
				startCharge(target);
			}
		}

		private void startCharge(LivingEntity target) {
			WyvernEntity.this.charging = true;
			this.chargeTicksElapsed = 0;
			this.recalcCountdown = 0;
			WyvernEntity.this.playSound(SoundEvents.ENTITY_PHANTOM_SWOOP, 1.5f, WyvernEntity.this.getSoundPitch());
			WyvernEntity.this.getNavigation().startMovingTo(target, CHARGE_SPEED_MULTIPLIER);
		}

		// After connecting or missing, flies straight away from the target for RETREAT_TICKS
		// before allowing another charge - the literal "turns around" (WyvernEntity.this itself
		// already faces backward while retreating since getLookControl().lookAt keeps its head/
		// yaw on the target the whole time, so it visibly backpedals rather than flying blind).
		private void beginRetreat() {
			WyvernEntity.this.charging = false;
			this.retreating = true;
			this.retreatTicksElapsed = 0;
			this.recalcCountdown = 0;
		}

		private void tickRetreat(LivingEntity target, double distanceSquared) {
			this.retreatTicksElapsed++;
			if (--this.recalcCountdown <= 0) {
				this.recalcCountdown = PATH_RECALC_INTERVAL_TICKS;
				Vec3d self = WyvernEntity.this.getPos();
				Vec3d away = self.subtract(target.getPos());
				away = away.lengthSquared() < 1.0E-4 ? new Vec3d(1, 0, 0) : away.normalize();
				Vec3d dest = self.add(away.multiply(RETREAT_DISTANCE));
				// Real bug, not guessed - per explicit report, retreating backed it straight into
				// whatever terrain happened to be behind it with zero awareness. Clamps the
				// destination to just short of any actual obstacle along that line instead of the
				// full RETREAT_DISTANCE.
				BlockHitResult hit = WyvernEntity.this.raycastTo(self, dest);
				if (hit.getType() == HitResult.Type.BLOCK) {
					dest = hit.getPos().subtract(away.multiply(1.0));
				}
				WyvernEntity.this.getNavigation().startMovingTo(dest.x, dest.y, dest.z, RETREAT_SPEED);
			}
			// Cut the retreat short once there's already enough room for a real run-up, instead of
			// always waiting out the full window - keeps the loop snappy against a target that's
			// backing away on its own too.
			if (this.retreatTicksElapsed > RETREAT_TICKS || distanceSquared > RETREAT_DISTANCE * RETREAT_DISTANCE) {
				this.retreating = false;
				this.chargeCooldown = 0;
			}
		}

		private void tickCharge(LivingEntity target, double distanceSquared) {
			this.chargeTicksElapsed++;
			// Also checked mid-charge, not just before committing to one - per explicit report,
			// the target (or the wyvern's own slightly-curved pursuit path) can put a wall between
			// them after the charge already started. Bails into a retreat instead of continuing to
			// blindly ram whatever's actually in the way.
			if (!WyvernEntity.this.hasClearPath(WyvernEntity.this.getPos(), target.getPos())) {
				beginRetreat();
				return;
			}
			if (--this.recalcCountdown <= 0) {
				this.recalcCountdown = PATH_RECALC_INTERVAL_TICKS;
				WyvernEntity.this.getNavigation().startMovingTo(target, CHARGE_SPEED_MULTIPLIER);
			}
			// Mouth hitbox is the real, primary check now (see getMouthHitbox) - the plain
			// distance fallback stays only so a target that's somehow inside the wyvern's own
			// body without the mouth box quite reaching them (a big target, an odd approach
			// angle) still can't just stand there immune to a connecting charge.
			if (WyvernEntity.this.getMouthHitbox().intersects(target.getBoundingBox())
					|| distanceSquared <= MELEE_RANGE_SQUARED) {
				beginRetreat();
				// Per explicit request ("attack and bash and use all the animations to do it") -
				// a landed charge used to just apply damage with no animation at all (the "Dive"
				// clip cuts the instant charging flips false in beginRetreat() above); now it also
				// throws a real bite/claw/tail/bash swing into the same moment, cycling through
				// the full rotation same as a genuine melee exchange would, on top of the charge's
				// own bonus damage/knockback/explosion below.
				performMelee(target);
				target.damage(WyvernEntity.this.getDamageSources().mobAttack(WyvernEntity.this), CHARGE_DAMAGE);
				target.takeKnockback(CHARGE_KNOCKBACK_STRENGTH, WyvernEntity.this.getX() - target.getX(),
						WyvernEntity.this.getZ() - target.getZ());
				// Per explicit request - every landed charge is a real explosion now.
				WyvernEntity.this.triggerExplosion(target.getPos());
			} else if (this.chargeTicksElapsed > CHARGE_TIMEOUT_TICKS) {
				// Missed - shorter cooldown than a landed hit, so it isn't punished as hard for a
				// target that just dodged.
				beginRetreat();
			}
		}

		// bite/claw/tail_attack are even, roughly-equal-damage hits; bash_attack (every 4th swing)
		// is the heavy-knockback special per explicit request, dealing more damage and a real
		// shove instead of vanilla's default light knockback.
		private void performMelee(LivingEntity target) {
			int move = this.meleeMoveIndex;
			this.meleeMoveIndex = (this.meleeMoveIndex + 1) % 4;
			switch (move) {
				case 0 -> {
					WyvernEntity.this.triggerAnim("action", "bite");
					WyvernEntity.this.tryAttack(target);
				}
				case 1 -> {
					WyvernEntity.this.triggerAnim("action", "claw");
					WyvernEntity.this.tryAttack(target);
				}
				case 2 -> {
					WyvernEntity.this.triggerAnim("action", "tail_attack");
					WyvernEntity.this.tryAttack(target);
				}
				default -> {
					WyvernEntity.this.triggerAnim("action", "bash_attack");
					// Was 14 - bumped per explicit "high dps" request, same as CHARGE_DAMAGE/
					// GENERIC_ATTACK_DAMAGE above.
					target.damage(WyvernEntity.this.getDamageSources().mobAttack(WyvernEntity.this), 22.0f);
					target.takeKnockback(2.0, WyvernEntity.this.getX() - target.getX(),
							WyvernEntity.this.getZ() - target.getZ());
					// Per explicit request - bash smashes through wooden terrain/builds, not just
					// the target it hit.
					WyvernEntity.this.destroyWoodenBlocksNear(WyvernEntity.this.getBlockPos());
					// Every landed charge or bash is a real explosion now, per explicit request
					// ("just cause an explosion everytime WE GO BOOM") - covers the sound/particle
					// side too, so the standalone ENTITY_RAVAGER_ATTACK cue this used to play is
					// gone, replaced by the explosion's own boom.
					WyvernEntity.this.triggerExplosion(target.getPos());
					// Real recoil, per explicit request - slamming that hard costs it health too,
					// clawed back only very slowly afterward (see HEAL_INTERVAL_TICKS).
					WyvernEntity.this.damage(WyvernEntity.this.getDamageSources().generic(), 5.0f);
				}
			}
		}
	}

	// Real 3D flight propulsion - see the constructor comment for why the default MoveControl
	// can't do this at all. Same pattern vanilla's own VexEntity$VexMoveControl uses (confirmed
	// via decompile): while a MOVE_TO order is active, nudge velocity directly toward the target
	// point every tick (proportional to distance so it doesn't overshoot/oscillate), instead of
	// only ever setting yaw + horizontal forward speed. Facing (yaw/bodyYaw) is set from whichever
	// is more meaningful: the current attack target if there is one (so it visibly orients toward
	// what it's fighting even while maneuvering), otherwise its own direction of travel.
	private static class WyvernMoveControl extends MoveControl {
		private final WyvernEntity wyvern;

		private WyvernMoveControl(WyvernEntity wyvern) {
			super(wyvern);
			this.wyvern = wyvern;
		}

		@Override
		public void tick() {
			if (this.state != State.MOVE_TO) {
				return;
			}
			Vec3d diff = new Vec3d(this.targetX - this.wyvern.getX(), this.targetY - this.wyvern.getY(),
					this.targetZ - this.wyvern.getZ());
			double distance = diff.length();
			if (distance < this.wyvern.getBoundingBox().getAverageSideLength()) {
				this.state = State.WAIT;
				this.wyvern.setVelocity(this.wyvern.getVelocity().multiply(0.5));
				return;
			}
			this.wyvern.setVelocity(this.wyvern.getVelocity().add(diff.multiply(this.speed * 0.05 / distance)));
			LivingEntity target = this.wyvern.getTarget();
			double dx;
			double dz;
			if (target != null) {
				dx = target.getX() - this.wyvern.getX();
				dz = target.getZ() - this.wyvern.getZ();
			} else {
				Vec3d velocity = this.wyvern.getVelocity();
				dx = velocity.x;
				dz = velocity.z;
			}
			this.wyvern.setYaw((float) (-(MathHelper.atan2(dx, dz)) * (180.0 / Math.PI)));
			this.wyvern.bodyYaw = this.wyvern.getYaw();
		}
	}
}

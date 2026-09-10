package dev.westercraft.creatures.entity;

import dev.westercraft.creatures.item.ModItems;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.AttackWithOwnerGoal;
import net.minecraft.entity.ai.goal.EscapeDangerGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SitGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.TrackOwnerAttackerGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EntityView;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

// Bonds permanently to whoever feeds it a Direwolf Bone (ModItems.DIREWOLF_BONE) — no
// taming chance, one bone, one owner, matching "each Stark kid gets one direwolf" lore
// rather than vanilla wolf's repeatable bone-begging. Regular Items.BONE is just food/
// breeding fodder once bonded. Sized and statted up as a rideable mount, well above a
// vanilla wolf. Neutral: never picks a fight on its own, but always defends itself/its
// owner, and actively hunts hostiles in KILL mode. Sneak+right-click (while unmounted)
// cycles SIT -> FOLLOW -> KILL; plain right-click mounts.
public class DirewolfEntity extends TameableEntity implements GeoEntity {

	public static final int MODE_SIT = 0;
	public static final int MODE_FOLLOW = 1;
	public static final int MODE_KILL = 2;

	// Coat texture variants — see DirewolfGeoModel#getTextureResource for where these actually
	// pick a texture. COAT_COUNT drives the random spawn roll; bump it (and add the matching
	// texture + GeoModel case) whenever a new coat gets painted.
	public static final int COAT_BLACK = 0;
	public static final int COAT_SILVER = 1;
	public static final int COAT_COUNT = 2;

	private static final TrackedData<Integer> MODE =
			DataTracker.registerData(DirewolfEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Boolean> SLEEPING =
			DataTracker.registerData(DirewolfEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Integer> COAT =
			DataTracker.registerData(DirewolfEntity.class, TrackedDataHandlerRegistry.INTEGER);

	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("Idle");
	private static final RawAnimation WALK = RawAnimation.begin().thenLoop("Walk");
	private static final RawAnimation SPRINT = RawAnimation.begin().thenLoop("Sprint");
	// thenPlayAndHold, not thenLoop — "Sit" is a sit-DOWN motion, not an idle-while-sitting
	// loop, so looping it made it stand back up and sit down over and over. Play once, hold
	// the final pose.
	private static final RawAnimation SIT = RawAnimation.begin().thenPlayAndHold("Sit");
	private static final RawAnimation SLEEP = RawAnimation.begin().thenLoop("Sleep");

	private static final double LUNGE_MIN_RANGE_SQUARED = 9.0; // 3 blocks
	private static final double LUNGE_MAX_RANGE_SQUARED = 100.0; // 10 blocks
	private static final double LUNGE_STRIKE_RANGE_SQUARED = 9.0; // 3 blocks, was 2.45 — more forgiving hit window
	private static final double LUNGE_HOMING_STRENGTH = 0.15; // fraction corrected toward target per tick, mid-air
	private static final int LUNGE_COOLDOWN_TICKS = 160; // 8 seconds
	private static final int RIDER_BITE_COOLDOWN_TICKS = 10; // 0.5 seconds
	private static final double IDLE_MOVE_EPSILON_SQUARED = 0.0025; // ~0.05 blocks/tick
	private static final int IDLE_SLEEP_TICKS = 600; // 30 seconds of not moving

	// Ground movement speed multiplier applied on top of GENERIC_MOVEMENT_SPEED for both
	// chasing a melee target and following the owner — tune this to change how briskly it
	// moves on its own (AI-driven; getSaddledSpeed above is the separate ridden-speed knob).
	private static final double WALK_SPEED_MULTIPLIER = 1.0;
	// Raw velocity (blocks/tick) applied at lunge takeoff — horizontal is the push toward the
	// target, vertical is the hop height. Tune these directly to make the lunge feel
	// faster/higher; not tied to GENERIC_MOVEMENT_SPEED at all.
	private static final double LUNGE_HORIZONTAL_VELOCITY = 0.55;
	private static final double LUNGE_VERTICAL_VELOCITY = 0.55;
	// Rider-triggered movement jump (space bar while mounted, no target/damage — just a hop
	// in whichever direction it's facing, reusing the Lunge animation for the visual). These
	// numbers came from actually simulating vanilla's real per-tick physics (LivingEntity's
	// GRAVITY=0.08 and the 0.98 / 0.91 vertical/horizontal air-drag factors from travel()),
	// solved for a ~4 block high, ~5 block long arc — not guessed constants.
	private static final double JUMP_HORIZONTAL_VELOCITY = 0.522;
	private static final double JUMP_VERTICAL_VELOCITY = 0.803;

	private final AnimatableInstanceCache animatableInstanceCache = GeckoLibUtil.createInstanceCache(this);

	// Shared lunge state — set either by the AI LungeAttackGoal or by performRiderLunge, and
	// resolved centrally in tickMovement so both paths behave identically and share one
	// cooldown (see startLunge/canLunge).
	private boolean lunging;
	private boolean lungeStruck;
	private int lungeCooldown;
	private LivingEntity lungeTarget;
	private int riderBiteCooldown;

	// Server-authoritative; SLEEPING (synced) is derived from this each tick — see
	// updateSleepState.
	private int idleTicks;

	public DirewolfEntity(EntityType<? extends TameableEntity> entityType, World world) {
		super(entityType, world);
	}

	// 5x a tamed vanilla wolf's health (20) and attack damage (4) per request — 100 HP, 20
	// dmg. Movement speed matches AbstractHorseEntity.createBaseHorseAttributes()'s base value
	// (0.225) instead — "walk speed same as a horse" — with getSaddledSpeed's existing
	// sneak-to-boost acting as the "sprint" on top of that base.
	public static DefaultAttributeContainer.Builder createDirewolfAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 100.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.225)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 20.0)
				.add(EntityAttributes.GENERIC_ARMOR, 6.0)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
	}

	@Override
	protected void initDataTracker() {
		super.initDataTracker();
		this.dataTracker.startTracking(MODE, MODE_FOLLOW);
		this.dataTracker.startTracking(SLEEPING, false);
		this.dataTracker.startTracking(COAT, COAT_BLACK);
	}

	@Override
	public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason spawnReason,
			@Nullable EntityData entityData, @Nullable NbtCompound entityNbt) {
		this.dataTracker.set(COAT, world.getRandom().nextInt(COAT_COUNT));
		return super.initialize(world, difficulty, spawnReason, entityData, entityNbt);
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putInt("DirewolfMode", this.getMode());
		nbt.putInt("DirewolfCoat", this.getCoat());
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.contains("DirewolfMode")) {
			this.dataTracker.set(MODE, MathHelper.clamp(nbt.getInt("DirewolfMode"), MODE_SIT, MODE_KILL));
		}
		if (nbt.contains("DirewolfCoat")) {
			this.dataTracker.set(COAT, MathHelper.clamp(nbt.getInt("DirewolfCoat"), 0, COAT_COUNT - 1));
		}
	}

	public int getMode() {
		return this.dataTracker.get(MODE);
	}

	public int getCoat() {
		return this.dataTracker.get(COAT);
	}

	// SIT stops it dead (via vanilla SitGoal, driven off isSitting()); FOLLOW just stays near
	// the owner without fighting proactively; KILL adds active hostile-seeking on top of
	// FOLLOW's behavior. Always resets target/navigation on a mode change so leftover state
	// from the previous mode doesn't linger (e.g. still chasing after being told to sit).
	private void setMode(int mode) {
		this.dataTracker.set(MODE, mode);
		boolean sitting = mode == MODE_SIT;
		this.setSitting(sitting);
		this.setInSittingPose(sitting);
		this.navigation.stop();
		this.setTarget(null);
	}

	private void cycleMode(PlayerEntity player) {
		int next = (this.getMode() + 1) % 3;
		this.setMode(next);
		String messageKey = switch (next) {
			case MODE_SIT -> "Direwolf: Sit";
			case MODE_KILL -> "Direwolf: Kill (will hunt nearby hostiles)";
			default -> "Direwolf: Follow";
		};
		player.sendMessage(Text.literal(messageKey), true);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(1, new SwimGoal(this));
		this.goalSelector.add(1, new EscapeDangerGoal(this, 1.5));
		this.goalSelector.add(2, new SitGoal(this));
		this.goalSelector.add(3, new LungeAttackGoal());
		this.goalSelector.add(4, new DirewolfBiteGoal());
		this.goalSelector.add(5, new DirewolfFollowGoal());
		this.goalSelector.add(6, new WanderAroundFarGoal(this, 1.0));
		this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		this.goalSelector.add(7, new LookAroundGoal(this));

		// Neutral mob: never starts a fight on its own outside KILL mode, but always defends
		// its owner and itself, in every mode (including SIT — it can still bite something
		// that walks up and attacks it).
		this.targetSelector.add(1, new TrackOwnerAttackerGoal(this));
		this.targetSelector.add(2, new AttackWithOwnerGoal(this));
		this.targetSelector.add(3, new RevengeGoal(this));
		this.targetSelector.add(4, new ActiveTargetGoal<>(this, HostileEntity.class, true, target -> this.getMode() == MODE_KILL));
	}

	@Override
	public ActionResult interactMob(PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);
		if (this.getWorld().isClient) {
			boolean accepted = this.isOwner(player) || this.isTamed() || (stack.isOf(ModItems.DIREWOLF_BONE) && !this.isTamed());
			return accepted ? ActionResult.CONSUME : ActionResult.PASS;
		}

		if (!this.isTamed()) {
			if (stack.isOf(ModItems.DIREWOLF_BONE)) {
				if (!player.getAbilities().creativeMode) {
					stack.decrement(1);
				}
				this.setOwner(player);
				this.setMode(MODE_SIT);
				this.getWorld().sendEntityStatus(this, EntityStatuses.ADD_POSITIVE_PLAYER_REACTION_PARTICLES);
				return ActionResult.SUCCESS;
			}
			return super.interactMob(player, hand);
		}

		if (!this.isOwner(player)) {
			return super.interactMob(player, hand);
		}

		if (player.isSneaking()) {
			this.cycleMode(player);
			return ActionResult.SUCCESS;
		}

		if (!this.hasPassengers()) {
			this.setMode(MODE_FOLLOW);
			player.startRiding(this);
			return ActionResult.CONSUME;
		}

		return ActionResult.PASS;
	}

	// Unmapped in this Yarn build (Tameable#method_48926) — used by the interface's default
	// getOwner() to resolve the owner UUID to a player. Just needs to return the world.
	@Override
	public EntityView method_48926() {
		return this.getWorld();
	}

	@Override
	public LivingEntity getControllingPassenger() {
		Entity passenger = this.getFirstPassenger();
		if (passenger instanceof PlayerEntity player && this.isOwner(player)) {
			return player;
		}
		return null;
	}

	@Override
	protected Vec3d getControlledMovementInput(PlayerEntity controllingPlayer, Vec3d movementInput) {
		float sideways = controllingPlayer.sidewaysSpeed * 0.5f;
		float forward = controllingPlayer.forwardSpeed;
		return new Vec3d(sideways, 0.0, forward);
	}

	@Override
	protected float getSaddledSpeed(PlayerEntity controllingPlayer) {
		float base = (float) this.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
		return controllingPlayer.isSneaking() ? base * 1.6f : base;
	}

	@Override
	public void travel(Vec3d movementInput) {
		if (this.getControllingPassenger() instanceof PlayerEntity rider) {
			// Do NOT touch prevYaw here — vanilla's own tick pipeline captures it before
			// rotation updates each tick, and the renderer interpolates between prevYaw and
			// the current yaw for smooth turning. Forcing prevYaw = current yaw every tick
			// (an earlier version of this method did) collapses that interpolation range to
			// zero, so the client would see the wolf's facing SNAP instantly each tick instead
			// of turning smoothly — the root of the "not smooth" walk/sprint/turn report.
			this.setYaw(rider.getYaw());
			this.setPitch(rider.getPitch() * 0.5f);
			this.setRotation(this.getYaw(), this.getPitch());
			this.bodyYaw = this.getYaw();
			this.headYaw = this.bodyYaw;
			this.setMovementSpeed(getSaddledSpeed(rider));
			super.travel(movementInput);
		} else {
			super.travel(movementInput);
		}
	}

	@Override
	public boolean tryAttack(Entity target) {
		boolean hit = super.tryAttack(target);
		if (hit) {
			this.triggerAnim("action", "attack");
			this.playSound(SoundEvents.ENTITY_WOLF_GROWL, 1.0f, 0.85f);
		}
		return hit;
	}

	@Override
	public void tickMovement() {
		super.tickMovement();
		if (this.getWorld().isClient) {
			return;
		}
		if (this.lungeCooldown > 0) {
			this.lungeCooldown--;
		}
		if (this.riderBiteCooldown > 0) {
			this.riderBiteCooldown--;
		}
		if (this.lunging) {
			if (!this.lungeStruck && this.lungeTarget != null && this.lungeTarget.isAlive()) {
				this.applyLungeHoming(this.lungeTarget);
				if (this.squaredDistanceTo(this.lungeTarget) <= LUNGE_STRIKE_RANGE_SQUARED) {
					this.lungeStruck = true;
					this.performLungeStrike(this.lungeTarget);
				}
			}
			if (this.isOnGround() || this.lungeTarget == null || !this.lungeTarget.isAlive()) {
				this.lunging = false;
				this.lungeCooldown = LUNGE_COOLDOWN_TICKS;
				this.lungeTarget = null;
			}
		}
		this.updateSleepState();
	}

	// Sleep animation plays when either: sitting through a Minecraft night, or just standing
	// around not doing anything for 30+ seconds regardless of mode. Never while mounted,
	// mid-lunge, or actively pathing somewhere. damage() below wakes it immediately rather
	// than waiting for this to catch up next tick.
	private void updateSleepState() {
		if (this.hasPassengers() || this.lunging) {
			this.idleTicks = 0;
			this.dataTracker.set(SLEEPING, false);
			return;
		}
		double dx = this.getX() - this.prevX;
		double dy = this.getY() - this.prevY;
		double dz = this.getZ() - this.prevZ;
		boolean moved = (dx * dx + dy * dy + dz * dz) > IDLE_MOVE_EPSILON_SQUARED || !this.getNavigation().isIdle();
		this.idleTicks = moved ? 0 : this.idleTicks + 1;

		boolean sleeping = this.idleTicks >= IDLE_SLEEP_TICKS || (this.isSitting() && this.getWorld().isNight());
		this.dataTracker.set(SLEEPING, sleeping);
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		this.idleTicks = 0;
		this.dataTracker.set(SLEEPING, false);
		return super.damage(source, amount);
	}

	// Rider-controlled bite: bound to the vanilla attack key (left-click) while mounted on a
	// bonded Direwolf — see CreaturesOfWesterosClient's tick check + DIREWOLF_BITE_CHANNEL.
	// Own cooldown (not shared with the lunge's) so it can't be spam-clicked faster than a
	// normal attack.
	public void performRiderBite(@Nullable LivingEntity target) {
		if (target == null || this.riderBiteCooldown > 0) {
			return;
		}
		this.riderBiteCooldown = RIDER_BITE_COOLDOWN_TICKS;
		this.tryAttack(target);
	}

	// Rider-controlled lunge — bound to a dedicated "Ability" key (see
	// CreaturesOfWesterosClient + DIREWOLF_LUNGE_CHANNEL). Shares startLunge/canLunge with the
	// AI's LungeAttackGoal below, so triggering it manually still respects the same cooldown
	// the AI version uses (no double-dipping by mounting/dismounting to bypass it).
	public void performRiderLunge(@Nullable LivingEntity target) {
		if (target != null) {
			this.startLunge(target);
		}
	}

	// Space bar while mounted — a plain movement hop in whichever direction it's currently
	// facing, no target/damage involved. Reuses the Lunge animation for the visual since
	// there's no separate "jump" clip in the model. Grounded-only (can't double-jump), and
	// skipped mid-combat-lunge so the two don't stack their velocity.
	public void performRiderJump() {
		if (!this.isOnGround() || this.lunging) {
			return;
		}
		float yawRad = (float) Math.toRadians(this.getYaw());
		double vx = -MathHelper.sin(yawRad) * JUMP_HORIZONTAL_VELOCITY;
		double vz = MathHelper.cos(yawRad) * JUMP_HORIZONTAL_VELOCITY;
		this.setVelocity(vx, JUMP_VERTICAL_VELOCITY, vz);
		this.velocityDirty = true;
		this.triggerAnim("action", "lunge");
	}

	// Nearest living entity roughly in front of the wolf, for rider-triggered attacks (the
	// rider has no crosshair-on-the-wolf's-target concept, so this substitutes for aiming:
	// press the button, whatever's closest in front gets hit). Excludes the wolf itself and
	// its own rider/owner.
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

	private boolean canLunge() {
		return !this.lunging && this.lungeCooldown <= 0;
	}

	// Leap velocity math mirrors vanilla's PounceAtTargetGoal (real, tested numbers, not
	// guessed). Shared by both the AI LungeAttackGoal and performRiderLunge — the actual
	// strike-on-arrival check happens once, centrally, in tickMovement above.
	private void startLunge(LivingEntity target) {
		if (!this.canLunge()) {
			return;
		}
		this.lunging = true;
		this.lungeStruck = false;
		this.lungeTarget = target;
		Vec3d velocity = this.getVelocity();
		Vec3d toTarget = new Vec3d(target.getX() - this.getX(), 0.0, target.getZ() - this.getZ());
		if (toTarget.lengthSquared() > 1.0E-7) {
			toTarget = toTarget.normalize().multiply(LUNGE_HORIZONTAL_VELOCITY).add(velocity.multiply(0.2));
		}
		this.setVelocity(toTarget.x, LUNGE_VERTICAL_VELOCITY, toTarget.z);
		this.velocityDirty = true;
		this.triggerAnim("action", "lunge");
	}

	// Mid-air aim correction, ticked every frame of the leap — without this, the launch
	// direction is only ever aimed at where the target stood the instant it took off, so any
	// target that moves during the ~1 second of airtime is an easy dodge. Nudges horizontal
	// velocity toward the target's CURRENT position each tick instead of hard-locking the
	// original trajectory. Blended (LUNGE_HOMING_STRENGTH), not a hard snap, so it still reads
	// as a leap with some steering rather than a guided missile.
	private void applyLungeHoming(LivingEntity target) {
		Vec3d toTarget = new Vec3d(target.getX() - this.getX(), 0.0, target.getZ() - this.getZ());
		if (toTarget.lengthSquared() <= 1.0E-7) {
			return;
		}
		Vec3d desired = toTarget.normalize().multiply(LUNGE_HORIZONTAL_VELOCITY);
		Vec3d current = this.getVelocity();
		this.setVelocity(
				MathHelper.lerp(LUNGE_HOMING_STRENGTH, current.x, desired.x),
				current.y,
				MathHelper.lerp(LUNGE_HOMING_STRENGTH, current.z, desired.z));
		this.velocityDirty = true;
	}

	@Override
	public boolean isBreedingItem(ItemStack stack) {
		Item item = stack.getItem();
		return item.isFood() && item.getFoodComponent().isMeat();
	}

	@Nullable
	@Override
	public PassiveEntity createChild(ServerWorld world, PassiveEntity entity) {
		return null;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "movement", 5, this::movementAnimController));
		controllers.add(new AnimationController<>(this, "action", 0, state -> PlayState.STOP)
				.triggerableAnim("attack", RawAnimation.begin().thenPlay("Attack"))
				.triggerableAnim("lunge", RawAnimation.begin().thenPlay("Lunge")));
	}

	private PlayState movementAnimController(AnimationState<DirewolfEntity> state) {
		if (this.isSleeping()) {
			return state.setAndContinue(SLEEP);
		}
		if (this.isInSittingPose()) {
			return state.setAndContinue(SIT);
		}
		if (state.isMoving()) {
			boolean sprint = this.getControllingPassenger() instanceof PlayerEntity p && p.isSneaking();
			return state.setAndContinue(sprint ? SPRINT : WALK);
		}
		return state.setAndContinue(IDLE);
	}

	public boolean isSleeping() {
		return this.dataTracker.get(SLEEPING);
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.animatableInstanceCache;
	}

	// Custom bite goal instead of vanilla MeleeAttackGoal, for two reasons: (1) vanilla's
	// goalSelector/navigation/moveControl keep ticking every server tick regardless of whether
	// the mob has a rider (MobEntity#tickNewAi is final, can't be skipped), so plain
	// MeleeAttackGoal would periodically still path/steer the wolf toward its target via its
	// own moveControl, fighting the rider's actual steering in travel() every time it
	// recalculated a path — that was the "snap every few seconds" bug; (2) vanilla's own
	// attack cooldown lives in a private field with no subclass hook to shorten it, and a
	// quicker, more relentless cadence was asked for. Approach/attack logic mirrors vanilla's
	// MeleeAttackGoal.tick()/attack(), just with our own cooldown and the passenger gate.
	private class DirewolfBiteGoal extends Goal {
		private static final int ATTACK_COOLDOWN_TICKS = 12; // vanilla default is 20
		private static final int PATH_RECALC_INTERVAL_TICKS = 10;

		private int cooldown;
		private int recalcCountdown;

		private DirewolfBiteGoal() {
			this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
		}

		@Override
		public boolean canStart() {
			if (DirewolfEntity.this.hasPassengers()) {
				return false;
			}
			LivingEntity target = DirewolfEntity.this.getTarget();
			return target != null && target.isAlive();
		}

		@Override
		public boolean shouldContinue() {
			if (DirewolfEntity.this.hasPassengers()) {
				return false;
			}
			LivingEntity target = DirewolfEntity.this.getTarget();
			return target != null && target.isAlive();
		}

		@Override
		public void start() {
			this.cooldown = 0;
			this.recalcCountdown = 0;
		}

		@Override
		public void stop() {
			DirewolfEntity.this.getNavigation().stop();
		}

		@Override
		public void tick() {
			LivingEntity target = DirewolfEntity.this.getTarget();
			if (target == null) {
				return;
			}
			DirewolfEntity.this.getLookControl().lookAt(target, 30.0f, 30.0f);
			if (--this.recalcCountdown <= 0) {
				this.recalcCountdown = PATH_RECALC_INTERVAL_TICKS;
				DirewolfEntity.this.getNavigation().startMovingTo(target, WALK_SPEED_MULTIPLIER);
			}
			if (this.cooldown > 0) {
				this.cooldown--;
			}
			double reach = DirewolfEntity.this.getWidth() * 2.0 + target.getWidth();
			if (this.cooldown <= 0 && DirewolfEntity.this.squaredDistanceTo(target) <= reach * reach) {
				this.cooldown = ATTACK_COOLDOWN_TICKS;
				DirewolfEntity.this.swingHand(Hand.MAIN_HAND);
				DirewolfEntity.this.tryAttack(target);
			}
		}
	}

	// Custom replacement for vanilla's FollowOwnerGoal — same "walk to the owner" idea, but
	// deliberately does NOT include vanilla's tryTeleport() (FollowOwnerGoal snaps the pet to
	// within a few blocks of its owner once it falls more than 12 blocks behind). Explicitly
	// asked not to have the wolf ever teleport, so this only ever paths to the owner and
	// otherwise just... doesn't catch up if it truly can't get there. Only active in FOLLOW
	// and KILL modes (SIT is handled entirely by vanilla SitGoal).
	private class DirewolfFollowGoal extends Goal {
		private static final double START_FOLLOWING_DISTANCE_SQUARED = 36.0; // 6 blocks
		private static final double STOP_FOLLOWING_DISTANCE_SQUARED = 4.0; // 2 blocks
		private static final int PATH_RECALC_INTERVAL_TICKS = 10;

		private final EntityNavigation navigation;
		private LivingEntity owner;
		private int recalcCountdown;

		private DirewolfFollowGoal() {
			this.navigation = DirewolfEntity.this.getNavigation();
			this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
		}

		private boolean inFollowableMode() {
			int mode = DirewolfEntity.this.getMode();
			return mode == MODE_FOLLOW || mode == MODE_KILL;
		}

		@Override
		public boolean canStart() {
			if (!this.inFollowableMode() || DirewolfEntity.this.hasVehicle() || DirewolfEntity.this.hasPassengers()
					|| DirewolfEntity.this.isLeashed()) {
				return false;
			}
			LivingEntity candidate = DirewolfEntity.this.getOwner();
			if (candidate == null || candidate.isSpectator()) {
				return false;
			}
			if (DirewolfEntity.this.squaredDistanceTo(candidate) < START_FOLLOWING_DISTANCE_SQUARED) {
				return false;
			}
			this.owner = candidate;
			return true;
		}

		@Override
		public boolean shouldContinue() {
			return this.inFollowableMode() && this.owner != null && this.owner.isAlive()
					&& !DirewolfEntity.this.hasVehicle() && !DirewolfEntity.this.hasPassengers()
					&& !DirewolfEntity.this.isLeashed()
					&& DirewolfEntity.this.squaredDistanceTo(this.owner) > STOP_FOLLOWING_DISTANCE_SQUARED;
		}

		@Override
		public void start() {
			this.recalcCountdown = 0;
		}

		@Override
		public void stop() {
			this.owner = null;
			this.navigation.stop();
		}

		@Override
		public void tick() {
			DirewolfEntity.this.getLookControl().lookAt(this.owner, 10.0f, (float) DirewolfEntity.this.getMaxLookPitchChange());
			if (--this.recalcCountdown <= 0) {
				this.recalcCountdown = PATH_RECALC_INTERVAL_TICKS;
				this.navigation.startMovingTo(this.owner, WALK_SPEED_MULTIPLIER);
			}
		}
	}

	// Extra-damage strike applied when the lunge actually connects, plus the "stun" — vanilla
	// has no real stun status, so this leans on a heavy Slowness + Weakness stack instead
	// (near-total immobilization for a few seconds, and what little they can still do hits
	// softer). 1.5x normal bite damage so the lunge reads as a genuinely different, riskier
	// attack rather than just a repositioning move that happens to also bite.
	private void performLungeStrike(LivingEntity target) {
		float damage = (float) this.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE) * 1.5f;
		boolean hit = target.damage(this.getDamageSources().mobAttack(this), damage);
		if (hit) {
			target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 9));
			target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 60, 1));
			this.applyDamageEffects(this, target);
		}
	}

	// Leap-strike special attack, mirroring vanilla's PounceAtTargetGoal for the actual jump
	// velocity math (real, tested numbers, not guessed) but adding a mid-range gate, a real
	// cooldown instead of a random per-tick chance, and the damage/stun payload on landing
	// near the target. Runs at a higher priority than plain MeleeAttackGoal so it preempts a
	// close chase-bite whenever the target is far enough away to be worth leaping at. Thin
	// wrapper around the entity's shared lunging/startLunge state — the strike-on-arrival
	// check happens centrally in tickMovement, not here, so AI- and rider-triggered lunges
	// behave identically and share one cooldown.
	// Windup added on top of the AI-triggered version only — a beat where it plants, growls,
	// and glares at the target before actually committing to the leap, instead of instantly
	// snapping from a walk straight into the jump arc. Reads as a deliberate, aggressive
	// decision rather than a reflexive lunge, and the stationary beat also means it isn't
	// still carrying "walk toward target" velocity into a takeoff going a different direction
	// (part of what made the chase-to-lunge transition look janky). performRiderLunge (the
	// player-triggered ability key) intentionally bypasses this and calls startLunge directly
	// — a player-commanded attack should stay instant/responsive, not get an artificial delay.
	private class LungeAttackGoal extends Goal {
		private static final int WINDUP_TICKS = 10; // ~0.5s telegraph before committing

		private LivingEntity lungeGoalTarget;
		private int windupTicks;

		private LungeAttackGoal() {
			this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.JUMP, Goal.Control.LOOK));
		}

		@Override
		public boolean canStart() {
			if (!DirewolfEntity.this.canLunge()) {
				return false;
			}
			LivingEntity target = DirewolfEntity.this.getTarget();
			if (target == null || !target.isAlive() || DirewolfEntity.this.hasPassengers() || !DirewolfEntity.this.isOnGround()) {
				return false;
			}
			double distanceSquared = DirewolfEntity.this.squaredDistanceTo(target);
			return distanceSquared >= LUNGE_MIN_RANGE_SQUARED && distanceSquared <= LUNGE_MAX_RANGE_SQUARED;
		}

		@Override
		public boolean shouldContinue() {
			return this.windupTicks > 0 || DirewolfEntity.this.lunging;
		}

		@Override
		public void start() {
			this.lungeGoalTarget = DirewolfEntity.this.getTarget();
			this.windupTicks = WINDUP_TICKS;
			DirewolfEntity.this.getNavigation().stop();
			DirewolfEntity.this.playSound(SoundEvents.ENTITY_WOLF_GROWL, 1.0f, 0.7f);
		}

		@Override
		public void tick() {
			if (this.windupTicks <= 0) {
				return;
			}
			if (this.lungeGoalTarget != null) {
				DirewolfEntity.this.getLookControl().lookAt(this.lungeGoalTarget, 30.0f, 30.0f);
			}
			this.windupTicks--;
			if (this.windupTicks == 0 && this.lungeGoalTarget != null && this.lungeGoalTarget.isAlive()) {
				DirewolfEntity.this.startLunge(this.lungeGoalTarget);
			}
		}
	}
}

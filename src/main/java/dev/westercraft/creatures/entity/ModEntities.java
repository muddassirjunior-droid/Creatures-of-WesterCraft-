package dev.westercraft.creatures.entity;

import dev.westercraft.creatures.CreaturesOfWesterosMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModEntities {
	// Disabled for now, not deleted - per explicit request to focus on Viserion only. No natural
	// spawn placement was ever registered for this one (summon-only to begin with), so
	// disableSummon() alone fully takes it out of play; the class and its attributes/renderer
	// registration are untouched and this is a one-line revert if it's needed again.
	public static final EntityType<DragonEntity> DRAGON = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "dragon"),
			FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, DragonEntity::new)
					.dimensions(EntityDimensions.fixed(1.5f, 2.0f))
					.disableSummon()
					.build());

	public static final EntityType<ViserionEntity> VISERION = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "viserion"),
			FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, ViserionEntity::new)
					// Matches ViserionRenderer's current 0.4x render scale — see ViserionPartId for
					// the full history/derivation of these numbers.
					.dimensions(EntityDimensions.fixed(2.18f, 2.54f))
					// Was 80 - bumped per explicit report ("if i go far then its fucked"): this
					// controls how far away the SERVER keeps tracking/sending updates to a client at
					// all, separate from any client-side render distance. 80 blocks is close for a
					// boss-scale flying dragon a player might see/approach from much farther out.
					// 160 matches vanilla's own Ender Dragon tracking range.
					.trackRangeBlocks(160)
					// Was 3 (every 3rd tick) - too coarse for a large, fast, player-flown mount:
					// EntityTrackerEntry (confirmed via decompile) has no special-casing that
					// tightens sync just because an entity has a rider, so every other client
					// watching Viserion fly was only getting a position update a third as often as
					// it was actually moving, which reads as stutter/rubber-banding. 1 = sync every
					// tick, matching how vanilla treats other fast rider-carrying entities.
					.trackedUpdateRate(1)
					// A fire-breathing dragon getting hurt by its own element (or lava) would be
					// absurd — see also ViserionEntity#computeFallDamage for the flight-side
					// equivalent (a flying creature taking fall damage makes just as little sense).
					.fireImmune()
					.build());

	public static final EntityType<ViserionPart> VISERION_PART = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "viserion_part"),
			FabricEntityTypeBuilder.create(SpawnGroup.MISC, ViserionPart::new)
					.dimensions(EntityDimensions.fixed(1.0f, 1.0f))
					// Unlike VISERION itself, these don't need tight sync — they're invisible,
					// never rendered, and the server already sets their position directly every
					// tick in ViserionEntity#positionPart regardless of what any client is told, so
					// hit detection is fully server-authoritative and doesn't depend on this rate at
					// all. Syncing 10 of these to every nearby player every single tick (the earlier
					// rate(1) here) was pure unnecessary network traffic and a real contributor to
					// the "flying gives lag" symptom reported in testing — the only consumer of this
					// sync is the F3+B debug hitbox overlay, which doesn't need tick-perfect data.
					.trackRangeBlocks(80)
					.trackedUpdateRate(20)
					.disableSummon()
					.disableSaving()
					.build());

	public static final EntityType<ViserionFireballEntity> VISERION_FIREBALL = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "viserion_fireball"),
			FabricEntityTypeBuilder.<ViserionFireballEntity>create(SpawnGroup.MISC, ViserionFireballEntity::new)
					// Was 0.5x0.5 - bumped alongside the "a few big ones" rework (see
					// ViserionEntity#DRACARYS_PROJECTILES_PER_TICK and ViserionFireballModel's
					// doubled cube size) so the hitbox actually matches the now much bigger model.
					.dimensions(EntityDimensions.fixed(1.4f, 1.4f))
					.trackRangeBlocks(64)
					.trackedUpdateRate(2)
					.disableSummon()
					.disableSaving()
					.build());

	// Stationary once spawned - never moves, so it doesn't need real position sync at all, just
	// needs to exist long enough server-side to run its own tick logic (damage + particles are
	// both spawned directly via ServerWorld, not dependent on client-side entity tracking).
	public static final EntityType<ViserionFireZoneEntity> VISERION_FIRE_ZONE = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "viserion_fire_zone"),
			FabricEntityTypeBuilder.<ViserionFireZoneEntity>create(SpawnGroup.MISC, ViserionFireZoneEntity::new)
					.dimensions(EntityDimensions.fixed(0.1f, 0.1f))
					.trackRangeBlocks(64)
					.trackedUpdateRate(40)
					.disableSummon()
					.disableSaving()
					.build());

	// Disabled for now, not deleted - same as DRAGON above, per explicit request to focus on
	// Viserion only.
	public static final EntityType<DirewolfEntity> DIREWOLF = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "direwolf"),
			FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, DirewolfEntity::new)
					.dimensions(EntityDimensions.fixed(1.4f, 1.8f))
					.disableSummon()
					.build());

	// Disabled for now, not deleted - per explicit request to shift focus back to polishing
	// Viserion alone. Same one-line-revert pattern as DRAGON/DIREWOLF above - everything else
	// (attributes, AI, renderer, /wyvern immune) is untouched.
	public static final EntityType<WyvernEntity> WYVERN = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "wyvern"),
			FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, WyvernEntity::new)
					.dimensions(EntityDimensions.fixed(2.0f, 2.2f))
					// Was 64 - same "if i go far then its fucked" fix as VISERION above.
					.trackRangeBlocks(160)
					// Found in review, not reported - same reasoning as VISERION's own
					// trackedUpdateRate(1) above: this thing now does fast charge dashes and a
					// constant per-tick jitter (see WyvernEntity#travel), the exact kind of rapid,
					// erratic movement Fabric's coarser default sync rate reads as stutter/
					// rubber-banding for. Every tick keeps other clients seeing the same twitchy
					// motion the entity itself is actually doing.
					.trackedUpdateRate(1)
					.fireImmune()
					.disableSummon()
					.build());

	public static void registerModEntities() {
		FabricDefaultAttributeRegistry.register(DRAGON, DragonEntity.createDragonAttributes());
		FabricDefaultAttributeRegistry.register(VISERION, ViserionEntity.createViserionAttributes());
		FabricDefaultAttributeRegistry.register(DIREWOLF, DirewolfEntity.createDirewolfAttributes());
		FabricDefaultAttributeRegistry.register(WYVERN, WyvernEntity.createWyvernAttributes());
	}
}

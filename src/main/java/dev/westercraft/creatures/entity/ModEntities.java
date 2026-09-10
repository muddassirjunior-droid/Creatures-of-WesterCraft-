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
	public static final EntityType<DragonEntity> DRAGON = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "dragon"),
			FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, DragonEntity::new)
					.dimensions(EntityDimensions.fixed(1.5f, 2.0f))
					.build());

	public static final EntityType<ViserionEntity> VISERION = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "viserion"),
			FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, ViserionEntity::new)
					// Matches ViserionRenderer's current 0.4x render scale — see ViserionPartId for
					// the full history/derivation of these numbers.
					.dimensions(EntityDimensions.fixed(2.18f, 2.54f))
					.trackRangeBlocks(80)
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
					.dimensions(EntityDimensions.fixed(0.5f, 0.5f))
					.trackRangeBlocks(64)
					.trackedUpdateRate(2)
					.disableSummon()
					.disableSaving()
					.build());

	public static final EntityType<DirewolfEntity> DIREWOLF = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "direwolf"),
			FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, DirewolfEntity::new)
					.dimensions(EntityDimensions.fixed(1.4f, 1.8f))
					.build());

	public static void registerModEntities() {
		FabricDefaultAttributeRegistry.register(DRAGON, DragonEntity.createDragonAttributes());
		FabricDefaultAttributeRegistry.register(VISERION, ViserionEntity.createViserionAttributes());
		FabricDefaultAttributeRegistry.register(DIREWOLF, DirewolfEntity.createDirewolfAttributes());
	}
}

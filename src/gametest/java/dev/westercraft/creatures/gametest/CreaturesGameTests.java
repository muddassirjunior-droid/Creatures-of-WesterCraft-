package dev.westercraft.creatures.gametest;

import dev.westercraft.creatures.entity.DirewolfEntity;
import dev.westercraft.creatures.entity.ModEntities;
import dev.westercraft.creatures.entity.ViserionEntity;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.Entity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * Proof-of-concept + smoke-level Fabric GameTests for Creatures of Westeros.
 * Run via: gradlew.bat runGametest (see claude-testing/test_runner.py "gametest").
 *
 * Uses FabricGameTest.EMPTY_STRUCTURE since these tests only need a plain
 * platform, not a hand-built structure template.
 */
public class CreaturesGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void direwolfSpawnsAndHasAttributes(TestContext context) {
        Entity entity = context.spawnEntity(ModEntities.DIREWOLF, 1, 2, 1);
        context.assertTrue(entity instanceof DirewolfEntity, "Spawned entity should be a DirewolfEntity");

        DirewolfEntity wolf = (DirewolfEntity) entity;
        context.assertTrue(wolf.isAlive(), "Direwolf should be alive immediately after spawning");
        context.assertTrue(wolf.getMaxHealth() > 0, "Direwolf should have positive max health");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void viserionSpawnsAndHasAttributes(TestContext context) {
        Entity entity = context.spawnEntity(ModEntities.VISERION, 1, 2, 1);
        context.assertTrue(entity instanceof ViserionEntity, "Spawned entity should be a ViserionEntity");

        ViserionEntity viserion = (ViserionEntity) entity;
        context.assertTrue(viserion.isAlive(), "Viserion should be alive immediately after spawning");
        context.assertTrue(!viserion.isFlying(), "Freshly spawned Viserion should not be flying yet");

        context.complete();
    }
}

package dev.westercraft.creatures.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.westercraft.creatures.CreaturesOfWesterosMod;
import dev.westercraft.creatures.debug.EntityDebugDump;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * DEV-ONLY debug command for the Claude testing harness -- dumps an
 * entity's testable state (position, health, equipment, attributes, mod
 * specific fields) both to the calling player's chat AND to latest.log, so
 * claude-testing/log_monitor.py can read it without a screenshot. Only
 * registered when FabricLoader.isDevelopmentEnvironment() is true; see
 * CreaturesOfWesterosMod for the registration gate.
 */
public class DebugCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("creaturesofwesterosdebug")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("entity")
                        .then(argument("target", EntityArgumentType.entity())
                                .executes(DebugCommands::runEntity))));
    }

    private static int runEntity(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        Entity target = EntityArgumentType.getEntity(ctx, "target");
        JsonObject dump = EntityDebugDump.dump(target);
        String json = dump.toString();

        CreaturesOfWesterosMod.LOGGER.info("[DebugCommand] entity dump: {}", json);
        ctx.getSource().sendFeedback(() -> Text.literal(json), false);
        return 1;
    }
}

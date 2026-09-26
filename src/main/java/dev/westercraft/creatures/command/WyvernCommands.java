package dev.westercraft.creatures.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.westercraft.creatures.permission.WyvernImmunity;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Set;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * The /wyvern command tree. Op-only (permission level 2) - controls who every Wyvern completely
 * ignores (WyvernEntity#canTarget checks WyvernImmunity before ever targeting or retaliating
 * against a player). Same shape as DragonCommands' allowlist.
 */
public class WyvernCommands {
	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(literal("wyvern")
				.then(literal("immune")
						.requires(source -> source.hasPermissionLevel(2))
						.then(argument("player", EntityArgumentType.player())
								.executes(WyvernCommands::runImmune)))
				.then(literal("unimmune")
						.requires(source -> source.hasPermissionLevel(2))
						.then(argument("player", EntityArgumentType.player())
								.executes(WyvernCommands::runUnimmune)))
				.then(literal("list")
						.requires(source -> source.hasPermissionLevel(2))
						.executes(WyvernCommands::runList)));
	}

	private static int runImmune(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
		WyvernImmunity data = WyvernImmunity.getServerState(ctx.getSource().getServer());

		if (!data.grant(target.getUuid())) {
			ctx.getSource().sendError(Text.literal(target.getName().getString() + " is already immune to Wyverns."));
			return 0;
		}

		ctx.getSource().sendFeedback(
				() -> Text.literal("Wyverns will now ignore " + target.getName().getString() + " entirely."), true);
		return 1;
	}

	private static int runUnimmune(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
		WyvernImmunity data = WyvernImmunity.getServerState(ctx.getSource().getServer());

		if (!data.revoke(target.getUuid())) {
			ctx.getSource().sendError(Text.literal(target.getName().getString() + " wasn't immune to Wyverns."));
			return 0;
		}

		ctx.getSource().sendFeedback(
				() -> Text.literal(target.getName().getString() + " can be targeted by Wyverns again."), true);
		return 1;
	}

	private static int runList(CommandContext<ServerCommandSource> ctx) {
		WyvernImmunity data = WyvernImmunity.getServerState(ctx.getSource().getServer());
		Set<UUID> immune = data.all();

		if (immune.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("No players are immune to Wyverns."), false);
			return 0;
		}

		for (UUID id : immune) {
			ServerPlayerEntity player = ctx.getSource().getServer().getPlayerManager().getPlayer(id);
			String name = player != null ? player.getName().getString() : id.toString();
			ctx.getSource().sendFeedback(() -> Text.literal("- " + name), false);
		}

		return immune.size();
	}
}

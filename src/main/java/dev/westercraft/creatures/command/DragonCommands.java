package dev.westercraft.creatures.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.westercraft.creatures.permission.DragonAllowlist;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Set;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * The /dragon command tree. Op-only (permission level 2) - controls who is allowed
 * to bond with dragons (ViserionEntity#interactMob checks DragonAllowlist before
 * letting an unbonded dragon accept a new rider). Players cannot self-serve onto
 * the list.
 */
public class DragonCommands {
	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(literal("dragon")
				.then(literal("allow")
						.requires(source -> source.hasPermissionLevel(2))
						.then(argument("player", EntityArgumentType.player())
								.executes(DragonCommands::runAllow)))
				.then(literal("disallow")
						.requires(source -> source.hasPermissionLevel(2))
						.then(argument("player", EntityArgumentType.player())
								.executes(DragonCommands::runDisallow)))
				.then(literal("list")
						.requires(source -> source.hasPermissionLevel(2))
						.executes(DragonCommands::runList)));
	}

	private static int runAllow(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
		DragonAllowlist data = DragonAllowlist.getServerState(ctx.getSource().getServer());

		if (!data.allow(target.getUuid())) {
			ctx.getSource().sendError(Text.literal(target.getName().getString() + " is already allowed to bond with dragons."));
			return 0;
		}

		ctx.getSource().sendFeedback(
				() -> Text.literal(target.getName().getString() + " may now bond with dragons."), true);
		return 1;
	}

	private static int runDisallow(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
		DragonAllowlist data = DragonAllowlist.getServerState(ctx.getSource().getServer());

		if (!data.disallow(target.getUuid())) {
			ctx.getSource().sendError(Text.literal(target.getName().getString() + " wasn't on the dragon allowlist."));
			return 0;
		}

		ctx.getSource().sendFeedback(
				() -> Text.literal(target.getName().getString() + " may no longer bond with dragons."), true);
		return 1;
	}

	private static int runList(CommandContext<ServerCommandSource> ctx) {
		DragonAllowlist data = DragonAllowlist.getServerState(ctx.getSource().getServer());
		Set<UUID> allowed = data.all();

		if (allowed.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("No players are allowed to bond with dragons."), false);
			return 0;
		}

		for (UUID id : allowed) {
			ServerPlayerEntity player = ctx.getSource().getServer().getPlayerManager().getPlayer(id);
			String name = player != null ? player.getName().getString() : id.toString();
			ctx.getSource().sendFeedback(() -> Text.literal("- " + name), false);
		}

		return allowed.size();
	}
}

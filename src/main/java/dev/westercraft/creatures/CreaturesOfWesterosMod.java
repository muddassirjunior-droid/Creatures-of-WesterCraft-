package dev.westercraft.creatures;

import dev.westercraft.creatures.command.DragonCommands;
import dev.westercraft.creatures.entity.DirewolfEntity;
import dev.westercraft.creatures.entity.ModEntities;
import dev.westercraft.creatures.entity.ViserionEntity;
import dev.westercraft.creatures.item.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreaturesOfWesterosMod implements ModInitializer {
	public static final String MOD_ID = "creaturesofwesteros";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final Identifier DIREWOLF_BITE_CHANNEL = Identifier.of(MOD_ID, "direwolf_bite");
	public static final Identifier DIREWOLF_LUNGE_CHANNEL = Identifier.of(MOD_ID, "direwolf_lunge");
	public static final Identifier DIREWOLF_JUMP_CHANNEL = Identifier.of(MOD_ID, "direwolf_jump");
	public static final Identifier VISERION_DRACARYS_CHANNEL = Identifier.of(MOD_ID, "viserion_dracarys");
	public static final Identifier VISERION_MELEE_CHANNEL = Identifier.of(MOD_ID, "viserion_melee");
	public static final Identifier VISERION_TAIL_CHANNEL = Identifier.of(MOD_ID, "viserion_tail");
	public static final Identifier VISERION_GROUND_MODE_CHANNEL = Identifier.of(MOD_ID, "viserion_ground_mode");

	private static final double RIDER_BITE_RANGE = 4.0;
	private static final double RIDER_LUNGE_RANGE = 10.0;
	// Matches ViserionRenderer's current 0.4x render scale — see ViserionPartId for the full
	// history/derivation of these numbers.
	private static final double VISERION_RIDER_MELEE_RANGE = 3.64;
	// A tail whip reaches further behind/around than a bite/claw lunging forward - same
	// reasoning as RIDER_LUNGE_RANGE being wider than RIDER_BITE_RANGE for the direwolf.
	private static final double VISERION_TAIL_RANGE = 5.0;

	// Chat commands, alongside the existing keybinds (not a replacement) - typing these while
	// mounted triggers the action and eats the message so it doesn't also broadcast as chat.
	private static final String VISERION_RISE_COMMAND = "SOVES";
	private static final String VISERION_DRACARYS_COMMAND = "DRACARYS";

	@Override
	public void onInitialize() {
		LOGGER.info("Creatures of Westeros initializing");
		ModEntities.registerModEntities();
		ModItems.registerModItems();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> DragonCommands.register(dispatcher));

		ServerPlayNetworking.registerGlobalReceiver(DIREWOLF_BITE_CHANNEL, (server, player, handler, buf, sender) ->
				server.execute(() -> {
					if (player.getVehicle() instanceof DirewolfEntity wolf
							&& wolf.getControllingPassenger() == player) {
						LivingEntity target = wolf.findRiderTarget(RIDER_BITE_RANGE);
						wolf.performRiderBite(target);
					}
				}));

		ServerPlayNetworking.registerGlobalReceiver(DIREWOLF_LUNGE_CHANNEL, (server, player, handler, buf, sender) ->
				server.execute(() -> {
					if (player.getVehicle() instanceof DirewolfEntity wolf
							&& wolf.getControllingPassenger() == player) {
						LivingEntity target = wolf.findRiderTarget(RIDER_LUNGE_RANGE);
						wolf.performRiderLunge(target);
					}
				}));

		ServerPlayNetworking.registerGlobalReceiver(DIREWOLF_JUMP_CHANNEL, (server, player, handler, buf, sender) ->
				server.execute(() -> {
					if (player.getVehicle() instanceof DirewolfEntity wolf
							&& wolf.getControllingPassenger() == player) {
						wolf.performRiderJump();
					}
				}));

		ServerPlayNetworking.registerGlobalReceiver(VISERION_MELEE_CHANNEL, (server, player, handler, buf, sender) ->
				server.execute(() -> {
					if (player.getVehicle() instanceof ViserionEntity viserion
							&& viserion.getControllingPassenger() == player) {
						LivingEntity target = viserion.findRiderTarget(VISERION_RIDER_MELEE_RANGE);
						viserion.performRiderMelee(target);
					}
				}));

		ServerPlayNetworking.registerGlobalReceiver(VISERION_TAIL_CHANNEL, (server, player, handler, buf, sender) ->
				server.execute(() -> {
					if (player.getVehicle() instanceof ViserionEntity viserion
							&& viserion.getControllingPassenger() == player) {
						LivingEntity target = viserion.findRiderTarget(VISERION_TAIL_RANGE);
						viserion.performRiderTailAttack(target);
					}
				}));

		ServerPlayNetworking.registerGlobalReceiver(VISERION_GROUND_MODE_CHANNEL, (server, player, handler, buf, sender) ->
				server.execute(() -> {
					if (player.getVehicle() instanceof ViserionEntity viserion
							&& viserion.getControllingPassenger() == player) {
						viserion.toggleGroundMode();
					}
				}));

		// Hold-to-charge, release-to-fire — buf carries which edge this is (true = key pressed,
		// false = key released), see CreaturesOfWesterosClient's manual press/release tracking.
		ServerPlayNetworking.registerGlobalReceiver(VISERION_DRACARYS_CHANNEL, (server, player, handler, buf, sender) -> {
			boolean pressed = buf.readBoolean();
			server.execute(() -> {
				if (player.getVehicle() instanceof ViserionEntity viserion
						&& viserion.getControllingPassenger() == player) {
					if (pressed) {
						viserion.startDracarysCharge();
					} else {
						viserion.releaseDracarys();
					}
				}
			});
		});

		// Chat-command triggers - fires on the network/chat thread before the message would
		// otherwise broadcast, so returning false here both runs the action and suppresses the
		// literal text from appearing in chat for everyone else.
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			if (!(sender.getVehicle() instanceof ViserionEntity viserion) || viserion.getControllingPassenger() != sender) {
				return true;
			}
			String content = message.getSignedContent().trim();
			if (content.equalsIgnoreCase(VISERION_RISE_COMMAND)) {
				sender.getServer().execute(viserion::riseOnCommand);
				return false;
			}
			if (content.equalsIgnoreCase(VISERION_DRACARYS_COMMAND)) {
				sender.getServer().execute(viserion::dracarysOnCommand);
				return false;
			}
			return true;
		});
	}
}

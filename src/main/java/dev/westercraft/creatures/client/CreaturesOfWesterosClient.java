package dev.westercraft.creatures.client;

import dev.westercraft.creatures.CreaturesOfWesterosMod;
import dev.westercraft.creatures.entity.DirewolfEntity;
import dev.westercraft.creatures.entity.ModEntities;
import dev.westercraft.creatures.entity.ViserionEntity;
import dev.westercraft.creatures.entity.client.DirewolfRenderer;
import dev.westercraft.creatures.entity.client.DragonRenderer;
import dev.westercraft.creatures.entity.client.ViserionFireballRenderer;
import dev.westercraft.creatures.entity.client.ViserionPartRenderer;
import dev.westercraft.creatures.entity.client.ViserionRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;
import org.lwjgl.glfw.GLFW;

public class CreaturesOfWesterosClient implements ClientModInitializer {
	// Unbound by default (GLFW_KEY_UNKNOWN) — the player picks their own key in Controls,
	// same convention as the scrapped-but-still-valid pattern from the Viserion tuner attempt.
	private static final KeyBinding DIREWOLF_ABILITY_KEY = new KeyBinding(
			"key.creaturesofwesteros.direwolf_ability",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_UNKNOWN,
			"key.categories.creaturesofwesteros");
	// Dracarys moved off right-click (useKey) onto its own dedicated key so right-click could
	// become the tail attack trigger instead - bound to G by default, not left unbound, per
	// explicit request.
	private static final KeyBinding DRACARYS_KEY = new KeyBinding(
			"key.creaturesofwesteros.dracarys",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_G,
			"key.categories.creaturesofwesteros");
	// Manual override on top of the automatic flying/landed state (see ViserionEntity#travel) -
	// grounded instantly takes off, flying instantly commits to landing if actually close to the
	// ground. Not a custom entity key like the others above, so plain wasPressed() is safe here
	// (nothing in vanilla drains this one out from under us the way attackKey/useKey do).
	private static final KeyBinding GROUND_MODE_KEY = new KeyBinding(
			"key.creaturesofwesteros.ground_mode",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_V,
			"key.categories.creaturesofwesteros");
	// Tracked manually — KeyBinding has wasPressed() (edge on press) but nothing for release,
	// and Dracarys now needs both edges: press starts the charge windup, release fires it.
	private boolean dracarysHeldLastTick = false;
	// Same isPressed()+edge-tracking technique as attackKey below, not useKey.wasPressed() -
	// useKey drives vanilla's own item-use handling (eating, drawing bow, blocking) the same way
	// attackKey drives mining/attacking, so it's exposed to the identical wasPressed()-drained-
	// before-we-see-it race already confirmed for attackKey.
	private boolean useHeldLastTick = false;
	// attackKey specifically can't use wasPressed() here (confirmed via decompile, not a guess):
	// MinecraftClient.handleInputEvents() drains it itself every tick with a
	// "while (attackKey.wasPressed())" loop, as part of vanilla's own normal
	// attack/mine-block handling, and that runs before END_CLIENT_TICK - so by the time this
	// callback checks it, vanilla has already fully consumed the single-shot press counter down
	// to zero. This is exactly why "left click to bite" never actually worked. isPressed() (raw
	// held-state, not a consumable counter) plus our own last-tick edge tracking sidesteps the
	// race entirely - same technique already used for Dracarys above.
	private boolean attackHeldLastTick = false;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModEntities.DRAGON, DragonRenderer::new);
		EntityRendererRegistry.register(ModEntities.VISERION, ViserionRenderer::new);
		EntityRendererRegistry.register(ModEntities.VISERION_PART, ViserionPartRenderer::new);
		EntityRendererRegistry.register(ModEntities.VISERION_FIREBALL, ViserionFireballRenderer::new);
		EntityRendererRegistry.register(ModEntities.DIREWOLF, DirewolfRenderer::new);

		KeyBindingHelper.registerKeyBinding(DIREWOLF_ABILITY_KEY);
		KeyBindingHelper.registerKeyBinding(DRACARYS_KEY);
		KeyBindingHelper.registerKeyBinding(GROUND_MODE_KEY);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) {
				return;
			}
			boolean attackPressed = client.options.attackKey.isPressed();
			boolean attackJustPressed = attackPressed && !this.attackHeldLastTick;
			this.attackHeldLastTick = attackPressed;
			boolean usePressed = client.options.useKey.isPressed();
			boolean useJustPressed = usePressed && !this.useHeldLastTick;
			this.useHeldLastTick = usePressed;

			if (client.player.getVehicle() instanceof ViserionEntity viserion
					&& viserion.getControllingPassenger() == client.player) {
				boolean held = DRACARYS_KEY.isPressed();
				if (held != this.dracarysHeldLastTick) {
					PacketByteBuf buf = PacketByteBufs.create();
					buf.writeBoolean(held);
					ClientPlayNetworking.send(CreaturesOfWesterosMod.VISERION_DRACARYS_CHANNEL, buf);
					this.dracarysHeldLastTick = held;
				}
				if (attackJustPressed) {
					ClientPlayNetworking.send(CreaturesOfWesterosMod.VISERION_MELEE_CHANNEL, PacketByteBufs.empty());
				}
				if (useJustPressed) {
					ClientPlayNetworking.send(CreaturesOfWesterosMod.VISERION_TAIL_CHANNEL, PacketByteBufs.empty());
				}
				if (GROUND_MODE_KEY.wasPressed()) {
					ClientPlayNetworking.send(CreaturesOfWesterosMod.VISERION_GROUND_MODE_CHANNEL, PacketByteBufs.empty());
				}
			} else {
				// Not mounted (or dismounted mid-hold) — a release packet couldn't reach the
				// right entity anyway (getVehicle() would already be wrong/null), so just reset
				// local state; the server's own charge-timeout safety net covers this case.
				this.dracarysHeldLastTick = false;
			}
			if (client.player.getVehicle() instanceof DirewolfEntity wolf && wolf.getControllingPassenger() == client.player) {
				if (attackJustPressed) {
					ClientPlayNetworking.send(CreaturesOfWesterosMod.DIREWOLF_BITE_CHANNEL, PacketByteBufs.empty());
				}
				if (DIREWOLF_ABILITY_KEY.wasPressed()) {
					ClientPlayNetworking.send(CreaturesOfWesterosMod.DIREWOLF_LUNGE_CHANNEL, PacketByteBufs.empty());
				}
				if (client.options.jumpKey.wasPressed()) {
					ClientPlayNetworking.send(CreaturesOfWesterosMod.DIREWOLF_JUMP_CHANNEL, PacketByteBufs.empty());
				}
			}
		});
	}
}

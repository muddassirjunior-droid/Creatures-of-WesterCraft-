package dev.westercraft.creatures.client;

import dev.westercraft.creatures.CreaturesOfWesterosMod;
import dev.westercraft.creatures.entity.DirewolfEntity;
import dev.westercraft.creatures.entity.ModEntities;
import dev.westercraft.creatures.entity.ViserionEntity;
import dev.westercraft.creatures.entity.client.DirewolfRenderer;
import dev.westercraft.creatures.entity.client.DragonRenderer;
import dev.westercraft.creatures.entity.client.ViserionFireballModel;
import dev.westercraft.creatures.entity.client.ViserionFireballRenderer;
import dev.westercraft.creatures.entity.client.ViserionFireZoneRenderer;
import dev.westercraft.creatures.entity.client.ViserionPartRenderer;
import dev.westercraft.creatures.entity.client.ViserionRenderer;
import dev.westercraft.creatures.entity.client.WyvernRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class CreaturesOfWesterosClient implements ClientModInitializer {
	// Unbound by default (GLFW_KEY_UNKNOWN) — the player picks their own key in Controls,
	// same convention as the scrapped-but-still-valid pattern from the Viserion tuner attempt.
	private static final KeyBinding DIREWOLF_ABILITY_KEY = new KeyBinding(
			"key.creaturesofwesteros.direwolf_ability",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_UNKNOWN,
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
	// Dracarys is on right-click (useKey) now, not a dedicated key - fires instantly on press, no
	// charge-up (see ViserionEntity#fireDracarysNow). Still needs manual isPressed()+edge-tracking
	// rather than useKey.wasPressed(), for the same reason as attackKey below: useKey drives
	// vanilla's own item-use handling (eating, drawing bow, blocking) the same way attackKey
	// drives mining/attacking, so it's exposed to the identical wasPressed()-drained-before-we-
	// see-it race already confirmed for attackKey.
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
	// Tracks whether we were on Viserion LAST tick specifically, so the mount transition itself
	// can be told apart from an ordinary already-mounted tick - see the fix in the tick callback
	// below for why that distinction matters.
	private boolean wasOnViserionLastTick = false;

	@Override
	public void onInitializeClient() {
		EntityModelLayerRegistry.registerModelLayer(ViserionFireballModel.LAYER, ViserionFireballModel::getTexturedModelData);

		EntityRendererRegistry.register(ModEntities.DRAGON, DragonRenderer::new);
		EntityRendererRegistry.register(ModEntities.VISERION, ViserionRenderer::new);
		EntityRendererRegistry.register(ModEntities.VISERION_PART, ViserionPartRenderer::new);
		EntityRendererRegistry.register(ModEntities.VISERION_FIREBALL, ViserionFireballRenderer::new);
		EntityRendererRegistry.register(ModEntities.VISERION_FIRE_ZONE, ViserionFireZoneRenderer::new);
		EntityRendererRegistry.register(ModEntities.DIREWOLF, DirewolfRenderer::new);
		EntityRendererRegistry.register(ModEntities.WYVERN, WyvernRenderer::new);

		KeyBindingHelper.registerKeyBinding(DIREWOLF_ABILITY_KEY);
		KeyBindingHelper.registerKeyBinding(GROUND_MODE_KEY);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) {
				return;
			}
			boolean attackPressed = client.options.attackKey.isPressed();
			boolean attackJustPressed = attackPressed && !this.attackHeldLastTick;
			this.attackHeldLastTick = attackPressed;
			boolean usePressed = client.options.useKey.isPressed();

			// Dropped the client-side getControllingPassenger() re-check that used to gate this
			// block - that reads the BONDED_RIDER data tracker value, which is synced to the
			// client over its own separate packet from the vehicle-attachment (SetPassengers)
			// packet that populates getVehicle(). Under real network conditions those two don't
			// necessarily land the same tick, so a player could already be attached as a
			// passenger (getVehicle() true) for several seconds before their BONDED_RIDER sync
			// catches up - exactly matching the reported "can't breathe fire for the first 5-10s
			// after mounting" bug (right-click was silently not even sending a packet during that
			// window). getVehicle() alone is populated directly from the same attachment packet,
			// no extra synced state to wait on. Safe to drop the permission re-check here too -
			// every receiver below already re-validates viserion.getControllingPassenger() ==
			// player server-side (authoritative, no sync lag), so this was always a redundant,
			// lag-prone copy of a check that's already enforced correctly.
			boolean onViserionNow = client.player.getVehicle() instanceof ViserionEntity;
			if (onViserionNow) {
				// Real bug, not guessed: while unmounted, the else branch below forces
				// useHeldLastTick to false every single tick. The instant you mount, if you're
				// still physically holding right-click (the same click that just mounted you -
				// the overwhelmingly common case), the very next check sees
				// "usePressed && !useHeldLastTick" as true && true, reading the mount-click itself
				// as a fresh press and firing Dracarys immediately. On the transition tick only
				// (just mounted, wasn't last tick), absorb the currently-held state as the
				// baseline first instead of comparing against that stale forced-false value, so
				// the mounting click can't double as a fire command - an actual release+re-press
				// is still needed afterward, same as any other tick.
				if (!this.wasOnViserionLastTick) {
					this.useHeldLastTick = usePressed;
				}
				if (usePressed && !this.useHeldLastTick) {
					ClientPlayNetworking.send(CreaturesOfWesterosMod.VISERION_DRACARYS_CHANNEL, PacketByteBufs.empty());
				}
				this.useHeldLastTick = usePressed;
				if (attackJustPressed) {
					ClientPlayNetworking.send(CreaturesOfWesterosMod.VISERION_MELEE_CHANNEL, PacketByteBufs.empty());
				}
				if (GROUND_MODE_KEY.wasPressed()) {
					ClientPlayNetworking.send(CreaturesOfWesterosMod.VISERION_GROUND_MODE_CHANNEL, PacketByteBufs.empty());
				}
			} else {
				this.useHeldLastTick = false;
			}
			this.wasOnViserionLastTick = onViserionNow;
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

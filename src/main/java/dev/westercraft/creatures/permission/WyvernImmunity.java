package dev.westercraft.creatures.permission;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Server-wide list of players every Wyvern completely ignores - per explicit request ("kills
 * everything when released except the person who has /wyvern immune"), Wyvern's targeting is
 * broadened to attack any living thing, so this is the one carve-out. Same
 * PersistentState/survives-restart pattern as DragonAllowlist. Managed via
 * /wyvern immune|unimmune|list (see WyvernCommands) - players cannot add themselves.
 */
public class WyvernImmunity extends PersistentState {
	private static final String ID = "creaturesofwesteros_wyvern_immunity";

	private final Set<UUID> immune = new HashSet<>();

	public boolean isImmune(UUID player) {
		return immune.contains(player);
	}

	public boolean grant(UUID player) {
		boolean added = immune.add(player);
		if (added) {
			markDirty();
		}
		return added;
	}

	public boolean revoke(UUID player) {
		boolean removed = immune.remove(player);
		if (removed) {
			markDirty();
		}
		return removed;
	}

	public Set<UUID> all() {
		return Collections.unmodifiableSet(immune);
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList list = new NbtList();
		for (UUID player : immune) {
			NbtCompound entryNbt = new NbtCompound();
			entryNbt.putUuid("player", player);
			list.add(entryNbt);
		}
		nbt.put("immune", list);
		return nbt;
	}

	public static WyvernImmunity createFromNbt(NbtCompound nbt) {
		WyvernImmunity data = new WyvernImmunity();
		NbtList list = nbt.getList("immune", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			data.immune.add(list.getCompound(i).getUuid("player"));
		}
		return data;
	}

	public static WyvernImmunity getServerState(MinecraftServer server) {
		PersistentStateManager manager = server.getOverworld().getPersistentStateManager();
		return manager.getOrCreate(WyvernImmunity::createFromNbt, WyvernImmunity::new, ID);
	}
}

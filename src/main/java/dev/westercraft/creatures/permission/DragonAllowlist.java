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
 * Server-wide list of players ops have approved to bond with dragons (Viserion and
 * whatever OG-lore/custom dragons follow it). Saved in the overworld's persistent
 * state, survives restarts. Managed via /dragon allow|disallow|list (see
 * DragonCommands) - players cannot add themselves.
 */
public class DragonAllowlist extends PersistentState {
	private static final String ID = "creaturesofwesteros_dragon_allowlist";

	private final Set<UUID> allowed = new HashSet<>();

	public boolean isAllowed(UUID player) {
		return allowed.contains(player);
	}

	public boolean allow(UUID player) {
		boolean added = allowed.add(player);
		if (added) {
			markDirty();
		}
		return added;
	}

	public boolean disallow(UUID player) {
		boolean removed = allowed.remove(player);
		if (removed) {
			markDirty();
		}
		return removed;
	}

	public Set<UUID> all() {
		return Collections.unmodifiableSet(allowed);
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList list = new NbtList();
		for (UUID player : allowed) {
			NbtCompound entryNbt = new NbtCompound();
			entryNbt.putUuid("player", player);
			list.add(entryNbt);
		}
		nbt.put("allowed", list);
		return nbt;
	}

	public static DragonAllowlist createFromNbt(NbtCompound nbt) {
		DragonAllowlist data = new DragonAllowlist();
		NbtList list = nbt.getList("allowed", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			data.allowed.add(list.getCompound(i).getUuid("player"));
		}
		return data;
	}

	public static DragonAllowlist getServerState(MinecraftServer server) {
		PersistentStateManager manager = server.getOverworld().getPersistentStateManager();
		return manager.getOrCreate(DragonAllowlist::createFromNbt, DragonAllowlist::new, ID);
	}
}

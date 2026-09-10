package dev.westercraft.creatures.item;

import dev.westercraft.creatures.CreaturesOfWesterosMod;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModItems {
	public static final Item DIREWOLF_BONE = Registry.register(
			Registries.ITEM,
			Identifier.of(CreaturesOfWesterosMod.MOD_ID, "direwolf_bone"),
			new Item(new FabricItemSettings().maxCount(16)));

	public static void registerModItems() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS).register(entries -> entries.add(DIREWOLF_BONE));
	}
}

package dev.westercraft.creatures.entity;

/**
 * Marker for "this entity counts as a dragon" - checked by ViserionEntity's damage-immunity
 * system ("only dragons can kill other dragons" bypasses the dragon_slaying_weapons/
 * dragonbane_projectiles tag gate entirely). Implement this on every dragon-type entity added
 * later (OG-lore or custom) so they can all genuinely hurt each other without needing
 * ViserionEntity to know about each new class by name.
 */
public interface Dragon {
}

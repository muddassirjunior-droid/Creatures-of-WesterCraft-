package dev.westercraft.creatures.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.westercraft.creatures.entity.DirewolfEntity;
import dev.westercraft.creatures.entity.ViserionEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Builds a plain-JSON snapshot of an entity's testable state, for the dev
 * debug bridge (DevBridge) and the /<modid>debug entity command. Only
 * exposes real getters that already exist on these entity classes -- see
 * ViserionEntity#isFlying, DirewolfEntity#getMode/getCoat/isSleeping.
 */
public final class EntityDebugDump {
    private EntityDebugDump() {}

    private static final EntityAttribute[] COMMON_ATTRIBUTES = {
            EntityAttributes.GENERIC_MAX_HEALTH,
            EntityAttributes.GENERIC_MOVEMENT_SPEED,
            EntityAttributes.GENERIC_FLYING_SPEED,
            EntityAttributes.GENERIC_ATTACK_DAMAGE,
            EntityAttributes.GENERIC_ARMOR,
            EntityAttributes.GENERIC_ARMOR_TOUGHNESS,
            EntityAttributes.GENERIC_FOLLOW_RANGE,
    };

    public static JsonObject dump(Entity entity) {
        JsonObject out = new JsonObject();

        Identifier typeId = Registries.ENTITY_TYPE.getId(entity.getType());
        out.addProperty("entity_type", typeId.toString());
        out.addProperty("uuid", entity.getUuidAsString());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", entity.getX());
        pos.addProperty("y", entity.getY());
        pos.addProperty("z", entity.getZ());
        out.add("position", pos);

        out.addProperty("yaw", entity.getYaw());
        out.addProperty("pitch", entity.getPitch());

        JsonObject vel = new JsonObject();
        vel.addProperty("x", entity.getVelocity().x);
        vel.addProperty("y", entity.getVelocity().y);
        vel.addProperty("z", entity.getVelocity().z);
        out.add("velocity", vel);

        JsonArray passengers = new JsonArray();
        for (Entity p : entity.getPassengerList()) {
            JsonObject pj = new JsonObject();
            pj.addProperty("entity_type", Registries.ENTITY_TYPE.getId(p.getType()).toString());
            pj.addProperty("uuid", p.getUuidAsString());
            passengers.add(pj);
        }
        out.add("passengers", passengers);

        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            JsonObject vj = new JsonObject();
            vj.addProperty("entity_type", Registries.ENTITY_TYPE.getId(vehicle.getType()).toString());
            vj.addProperty("uuid", vehicle.getUuidAsString());
            out.add("vehicle", vj);
        }

        if (entity instanceof LivingEntity living) {
            out.addProperty("health", living.getHealth());
            out.addProperty("max_health", living.getMaxHealth());

            JsonObject equipment = new JsonObject();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getEquippedStack(slot);
                if (!stack.isEmpty()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
                    item.addProperty("count", stack.getCount());
                    equipment.add(slot.getName(), item);
                }
            }
            out.add("equipment", equipment);

            JsonObject attributes = new JsonObject();
            for (EntityAttribute attr : COMMON_ATTRIBUTES) {
                EntityAttributeInstance instance = living.getAttributeInstance(attr);
                if (instance != null) {
                    Identifier attrId = Registries.ATTRIBUTE.getId(attr);
                    attributes.addProperty(attrId != null ? attrId.getPath() : attr.toString(), instance.getValue());
                }
            }
            out.add("attributes", attributes);
        }

        // Mod-specific extras -- only fields that already exist as public getters.
        if (entity instanceof ViserionEntity viserion) {
            out.addProperty("is_flying", viserion.isFlying());
        }
        if (entity instanceof DirewolfEntity direwolf) {
            out.addProperty("mode", direwolf.getMode());
            out.addProperty("coat", direwolf.getCoat());
            out.addProperty("is_sleeping", direwolf.isSleeping());
        }

        return out;
    }
}

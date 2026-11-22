package net.tfminecraft.infestations.wildlife;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/**
 * One kind of sea creature: a MythicMobs mob or a vanilla entity, the biomes it lives in, the height
 * band of its spawn block, the water depth it needs, and how many may be near each other.
 */
public record WildlifeRule(String id, String mythicMob, EntityType entity, Set<String> biomes, int minY,
        int maxY, int minDepth, double chance, int cooldownTicks, int maxNearby, int nearbyRadius,
        int belowSurface, double level) {

    /** Reads a rule, or returns null when it names no creature (or two), no biome, or an empty height band. */
    static WildlifeRule parse(String id, ConfigurationSection section) {
        String mythicMob = section.getString("mythic-mob");
        String entityName = section.getString("entity");
        EntityType entity = null;
        if (entityName != null) {
            try {
                entity = EntityType.valueOf(entityName.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }
            // Players, projectiles and the like can't be spawned this way; reject them at load.
            if (!entity.isSpawnable() || !entity.isAlive()) {
                return null;
            }
        }
        if ((mythicMob == null) == (entity == null)) {
            return null;
        }
        Set<String> biomes = new HashSet<>();
        for (String biome : section.getStringList("biomes")) {
            biomes.add(biomeKey(biome));
        }
        int minY = section.getInt("min-y", 50);
        int maxY = section.getInt("max-y", 64);
        if (biomes.isEmpty() || maxY < minY) {
            return null;
        }
        double chance = Math.clamp(section.getDouble("chance", 1.0), 0.0, 1.0);
        return new WildlifeRule(id, mythicMob, entity, Set.copyOf(biomes), minY, maxY,
                Math.max(1, section.getInt("min-depth", 2)), chance,
                Math.max(0, section.getInt("cooldown-seconds", 30)) * 20,
                Math.max(1, section.getInt("max-nearby", 1)),
                Math.max(1, section.getInt("nearby-radius", 64)),
                Math.max(0, section.getInt("below-surface", 2)),
                Math.max(1.0, section.getDouble("level", 1.0)));
    }

    /** "DEEP_OCEAN" and "minecraft:deep_ocean" both become "minecraft:deep_ocean". */
    static String biomeKey(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        return key.contains(":") ? key : "minecraft:" + key;
    }

    /** The spawn block: {@link #belowSurface} under the water surface, but never into the floor. */
    int spawnY(int surface, int depth) {
        return surface - Math.min(belowSurface, depth - 1);
    }

    /** True when a spawn block in this biome, at this height and over this much water suits the rule. */
    boolean fits(String biome, int y, int depth) {
        return biomes.contains(biome) && y >= minY && y <= maxY && depth >= minDepth;
    }
}

package net.tfminecraft.infestations.loader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import me.Plugins.SimpleFactions.enums.Terrain;
import me.Plugins.TLibs.Interface.LoaderInterface;
import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.infestation.Severity;
import net.tfminecraft.infestations.utils.Provinces;

public final class GroupLoader implements LoaderInterface {

    private static final Map<String, MobGroup> GROUPS = new LinkedHashMap<>();

    public record WeightedMob(String id, int weight) {}

    public record GroupTune(
            int ambientCap,
            int ambientIntervalTicks,
            int ambientRingMin,
            int ambientRingMax,
            int lureCount,
            int lureDurationSeconds
    ) {
        public static GroupTune defaults(Severity s) {
            return switch (s) {
                case MILD -> new GroupTune(8, 40, 10, 22, 20, 120);
                case WORRYING -> new GroupTune(16, 40, 10, 20, 40, 120);
                case SEVERE -> new GroupTune(28, 30, 9, 19, 60, 120);
                case EXTREME -> new GroupTune(40, 20, 8, 18, 80, 120);
            };
        }
    }

    public record MobGroup(
            String id,
            String display,
            boolean nightOnly,
            Integer minY,
            Set<String> terrains,
            List<WeightedMob> mobs,
            Map<Severity, GroupTune> severity
    ) {}

    @Override
    public void load(File configFile) {
        loadSafe(configFile);
    }

    public boolean loadSafe(File configFile) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException ex) {
            Infestations.plugin.getLogger().severe("[Infestations] Failed to load groups.yml: " + ex.getMessage());
            return false;
        }

        GROUPS.clear();
        ConfigurationSection root = config.getConfigurationSection("groups");
        if (root == null) {
            Infestations.plugin.getLogger().warning("[Infestations] groups.yml has no groups.");
            return true;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            String display = sec.getString("display", id);
            boolean nightOnly = sec.getBoolean("night-only", false);
            Integer minY = sec.contains("min-y") ? sec.getInt("min-y") : null;
            Set<String> terrains = parseTerrains(id, sec.getStringList("terrains"));
            List<WeightedMob> mobs = new ArrayList<>();
            List<Map<?, ?>> list = sec.getMapList("mobs");
            for (Map<?, ?> row : list) {
                Object mobId = row.get("id");
                if (mobId == null) {
                    continue;
                }
                int weight = 1;
                Object w = row.get("weight");
                if (w instanceof Number n) {
                    weight = Math.max(1, n.intValue());
                }
                mobs.add(new WeightedMob(mobId.toString(), weight));
            }
            if (mobs.isEmpty()) {
                Infestations.plugin.getLogger().warning("[Infestations] Group " + id + " has no mobs.");
                continue;
            }
            EnumMap<Severity, GroupTune> tunes = new EnumMap<>(Severity.class);
            ConfigurationSection sevRoot = sec.getConfigurationSection("severity");
            for (Severity s : Severity.values()) {
                ConfigurationSection row = sevRoot != null ? sevRoot.getConfigurationSection(s.id()) : null;
                GroupTune d = GroupTune.defaults(s);
                if (row == null) {
                    tunes.put(s, d);
                    continue;
                }
                tunes.put(s, new GroupTune(
                        Math.max(1, row.getInt("ambient-cap", d.ambientCap())),
                        Math.max(10, row.getInt("ambient-interval-ticks", d.ambientIntervalTicks())),
                        Math.max(1, row.getInt("ambient-ring-min", d.ambientRingMin())),
                        Math.max(1, row.getInt("ambient-ring-max", d.ambientRingMax())),
                        Math.max(1, row.getInt("lure-count", d.lureCount())),
                        Math.max(10, row.getInt("lure-duration-seconds", d.lureDurationSeconds()))
                ));
            }
            GROUPS.put(id.toLowerCase(Locale.ROOT), new MobGroup(
                    id, display, nightOnly, minY, Set.copyOf(terrains), List.copyOf(mobs), Map.copyOf(tunes)));
        }
        return true;
    }

    private static Set<String> parseTerrains(String groupId, List<String> raw) {
        Set<String> terrains = new LinkedHashSet<>();
        if (raw == null) {
            return terrains;
        }
        for (String row : raw) {
            if (row == null || row.isBlank()) {
                continue;
            }
            String name = row.trim().toLowerCase(Locale.ROOT);
            if (!knownTerrain(name)) {
                Infestations.plugin.getLogger().warning(
                        "[Infestations] Group " + groupId + " has unknown terrain: " + name);
            }
            terrains.add(name);
        }
        return terrains;
    }

    private static boolean knownTerrain(String name) {
        for (Terrain terrain : Terrain.values()) {
            if (terrain.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public static MobGroup get(String id) {
        if (id == null) {
            return null;
        }
        return GROUPS.get(id.toLowerCase(Locale.ROOT));
    }

    public static String displayName(String groupId) {
        MobGroup group = get(groupId);
        return group != null ? group.display() : groupId;
    }

    public static GroupTune tune(String groupId, Severity severity) {
        MobGroup group = get(groupId);
        if (group == null || severity == null) {
            return GroupTune.defaults(severity != null ? severity : Severity.MILD);
        }
        GroupTune t = group.severity().get(severity);
        return t != null ? t : GroupTune.defaults(severity);
    }

    public static boolean nightOnly(String groupId) {
        MobGroup group = get(groupId);
        return group != null && group.nightOnly();
    }

    public static boolean allowsY(String groupId, int y) {
        MobGroup group = get(groupId);
        if (group == null || group.minY() == null) {
            return true;
        }
        return y >= group.minY();
    }

    public static boolean allowsTerrain(String groupId, int provinceId) {
        MobGroup group = get(groupId);
        if (group == null) {
            return false;
        }
        if (group.terrains().isEmpty()) {
            return true;
        }
        Terrain terrain = Provinces.terrain(provinceId);
        if (terrain == null) {
            return false;
        }
        return group.terrains().contains(terrain.name().toLowerCase(Locale.ROOT));
    }

    public static String terrainList(String groupId) {
        MobGroup group = get(groupId);
        if (group == null || group.terrains().isEmpty()) {
            return "any land";
        }
        return String.join(", ", group.terrains());
    }

    public static boolean isNight(World world) {
        if (world == null) {
            return false;
        }
        long t = world.getTime();
        return t >= 13000L && t < 23000L;
    }

    public static boolean blockedByNight(String groupId, World world) {
        return nightOnly(groupId) && !isNight(world);
    }

    public static Set<String> groupIds() {
        return Collections.unmodifiableSet(GROUPS.keySet());
    }

    public static String pickMobId(String groupId) {
        MobGroup group = get(groupId);
        if (group == null || group.mobs().isEmpty()) {
            return null;
        }
        int total = 0;
        for (WeightedMob m : group.mobs()) {
            total += m.weight();
        }
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        int acc = 0;
        for (WeightedMob m : group.mobs()) {
            acc += m.weight();
            if (roll < acc) {
                return m.id();
            }
        }
        return group.mobs().get(0).id();
    }
}

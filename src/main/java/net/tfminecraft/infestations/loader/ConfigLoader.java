package net.tfminecraft.infestations.loader;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.cache.Cache;

public final class ConfigLoader implements LoaderInterface {

    @Override
    public void load(File configFile) {
        loadSafe(configFile);
    }

    public boolean loadSafe(File configFile) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException ex) {
            Infestations.plugin.getLogger().severe("[Infestations] Failed to load config.yml: " + ex.getMessage());
            return false;
        }

        Cache.debug = config.getBoolean("debug", false);
        Cache.loggingEnabled = config.getBoolean("logging", true);
        Cache.wipeLog = config.getBoolean("wipe-log", true);
        Cache.spread = config.getBoolean("spread", false);
        Cache.spreadIntervalSeconds = Math.max(1, config.getInt("spread-interval-seconds", 600));
        Cache.worsenChance = clamp01(config.getDouble("worsen-chance", 0.0015));
        Cache.spreadChanceLand = loadSeverityChances(config, "spread-chance-land", Cache.DEFAULT_SPREAD_LAND);
        Cache.spreadChanceWater = loadSeverityChances(config, "spread-chance-water", Cache.DEFAULT_SPREAD_WATER);
        Cache.spreadChanceSea = loadSeverityChances(config, "spread-chance-sea", Cache.DEFAULT_SPREAD_SEA);
        Cache.lureItem = config.getString("lure-item", "ia.tfmc:lure");
        Cache.joinSeconds = Math.max(1, config.getInt("join-seconds", 20));
        Cache.lureSpawnRadius = Math.max(4, config.getInt("lure-spawn-radius", 48));
        Cache.minPlayerDistance = Math.max(0, config.getInt("min-player-distance", 16));
        Cache.logoutGraceSeconds = Math.max(1, config.getInt("logout-grace-seconds", 300));
        Cache.deserterDamage = Math.max(0.5, config.getDouble("deserter-damage", 2.0));
        Cache.hologramViewRange = Math.max(16, config.getDouble("hologram-view-range", 96));

        Set<String> skip = new HashSet<>();
        for (String t : config.getStringList("skip-terrains")) {
            if (t != null && !t.isBlank()) {
                skip.add(t.toLowerCase(Locale.ROOT));
            }
        }
        if (skip.isEmpty()) {
            skip.add("water");
            skip.add("sea");
        }
        Cache.skipTerrains = skip;
        return true;
    }

    private static double[] loadSeverityChances(FileConfiguration config, String path, double[] defaults) {
        double[] out = defaults.clone();
        if (!config.isConfigurationSection(path)) {
            return out;
        }
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return out;
        }
        out[0] = clamp01(section.getDouble("mild", defaults[0]));
        out[1] = clamp01(section.getDouble("worrying", defaults[1]));
        out[2] = clamp01(section.getDouble("severe", defaults[2]));
        out[3] = clamp01(section.getDouble("extreme", defaults[3]));
        return out;
    }

    private static double clamp01(double value) {
        if (value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }
}

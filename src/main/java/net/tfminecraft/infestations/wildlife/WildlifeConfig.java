package net.tfminecraft.infestations.wildlife;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.infestations.Infestations;

/** Settings from wildlife.yml. A file that fails to load turns wildlife off. */
public final class WildlifeConfig {

    public static boolean enabled = false;
    public static Set<String> worlds = Set.of();
    public static int intervalTicks = 200;
    public static int ringMin = 24;
    public static int ringMax = 64;
    public static int attempts = 12;
    public static int playerCap = 6;
    public static int playerCapRadius = 64;
    public static int despawnDistance = 96;
    public static int sweepTicks = 200;
    public static List<WildlifeRule> rules = List.of();

    private WildlifeConfig() {}

    public static boolean loadSafe(File file) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            Infestations.plugin.getLogger().severe("[Infestations] Failed to load wildlife.yml: " + ex.getMessage());
            enabled = false;
            rules = List.of();
            return false;
        }
        enabled = config.getBoolean("enabled", false);
        worlds = Set.copyOf(config.getStringList("worlds"));
        intervalTicks = Math.max(1, config.getInt("interval-seconds", 10)) * 20;
        ringMin = Math.max(0, config.getInt("ring-min", 24));
        ringMax = Math.max(ringMin + 1, config.getInt("ring-max", 64));
        attempts = Math.clamp(config.getInt("attempts", 12), 1, 64);
        playerCap = Math.max(0, config.getInt("player-cap", 6));
        playerCapRadius = Math.max(1, config.getInt("player-cap-radius", 64));
        despawnDistance = Math.max(ringMax, config.getInt("despawn-distance", 96));
        sweepTicks = Math.max(1, config.getInt("sweep-seconds", 10)) * 20;

        boolean ok = true;
        List<WildlifeRule> loaded = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("rules");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection ruleSection = section.getConfigurationSection(id);
                WildlifeRule rule = ruleSection == null ? null : WildlifeRule.parse(id, ruleSection);
                if (rule == null) {
                    Infestations.plugin.getLogger().warning("[Infestations] Skipping wildlife rule '" + id
                            + "': it needs exactly one of mythic-mob or entity, a biome list and min-y <= max-y.");
                    ok = false;
                    continue;
                }
                loaded.add(rule);
            }
        }
        rules = List.copyOf(loaded);
        Infestations.plugin.getLogger().info("[Infestations] Wildlife " + (enabled ? "on" : "off") + ": "
                + rules.size() + " rules, worlds " + worlds);
        return ok;
    }
}

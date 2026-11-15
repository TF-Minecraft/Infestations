package net.tfminecraft.infestations.reward;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.infestation.Severity;

/**
 * Settings from rewards.yml. A failed load keeps the previous settings.
 */
public final class RewardConfig {

    public static final String OUTSIDE = "outside";
    static final int MAX_LURE_MULTIPLIER = 100;

    public static boolean enabled;
    public static Set<String> groups = Set.of();
    public static List<RewardTable.Coin> coins = List.of();
    public static String keyItem = "m.loot.trial_key";
    public static int keyCapPerHour;
    public static Map<String, RewardTable> ambient = Map.of();
    public static int lureMinKills = 3;
    /** Ambient rolls each lure kill pays at victory. 0 turns the per-kill payout off. */
    public static int lureMultiplier;
    public static RewardRoller.Keep lureKeep = new RewardRoller.Keep(1, Map.of());
    public static Map<String, RewardTable> lure = Map.of();

    private RewardConfig() {}

    public static boolean loadSafe(File file) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            Infestations.plugin.getLogger().severe("[Infestations] Failed to load rewards.yml: " + ex.getMessage());
            return false;
        }
        enabled = config.getBoolean("enabled", true);
        Set<String> groupIds = new HashSet<>();
        for (String id : config.getStringList("groups")) {
            if (id != null && !id.isBlank()) {
                groupIds.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        groups = Set.copyOf(groupIds);

        List<RewardTable.Coin> coinList = new ArrayList<>();
        for (Map<?, ?> row : config.getMapList("coins")) {
            String item = string(row.get("item"));
            int value = integer(row.get("value"), 0);
            if (item != null && value > 0) {
                coinList.add(new RewardTable.Coin(item, value));
            }
        }
        coins = List.copyOf(coinList);

        keyItem = config.getString("ambient.key-item", "m.loot.trial_key");
        keyCapPerHour = Math.max(0, config.getInt("ambient.key-cap-per-hour", 0));
        Map<String, RewardTable> ambientTables = new HashMap<>();
        putTable(ambientTables, config, "ambient", OUTSIDE);
        for (Severity severity : Severity.values()) {
            putTable(ambientTables, config, "ambient", severity.id());
        }
        ambient = Map.copyOf(ambientTables);

        lureMinKills = Math.max(0, config.getInt("lure.min-kills", 3));
        lureMultiplier = Math.clamp(config.getInt("lure.multiplier", 0), 0, MAX_LURE_MULTIPLIER);
        Map<String, Double> keepItems = new HashMap<>();
        for (Map<?, ?> row : config.getMapList("lure.outside-zone.keep-items")) {
            String item = string(row.get("item"));
            if (item != null) {
                keepItems.put(item, percent(row.get("keep-percent"), 100));
            }
        }
        lureKeep = new RewardRoller.Keep(
                percent(config.get("lure.outside-zone.keep-percent"), 100), keepItems);
        Map<String, RewardTable> lureTables = new HashMap<>();
        for (Severity severity : Severity.values()) {
            putTable(lureTables, config, "lure", severity.id());
        }
        lure = Map.copyOf(lureTables);
        return true;
    }

    public static boolean appliesTo(String groupId) {
        if (!enabled) {
            return false;
        }
        return groups.isEmpty() || (groupId != null && groups.contains(groupId.toLowerCase(Locale.ROOT)));
    }

    public static RewardTable ambient(Severity severity, boolean inZone) {
        return ambient.get(inZone ? severity.id() : OUTSIDE);
    }

    public static RewardTable lure(Severity severity) {
        return lure.get(severity.id());
    }

    private static void putTable(Map<String, RewardTable> out, YamlConfiguration config, String root, String tier) {
        ConfigurationSection section = config.getConfigurationSection(root + "." + tier);
        if (section == null) {
            return;
        }
        List<RewardTable.Entry> drops = new ArrayList<>();
        for (Map<?, ?> row : section.getMapList("drops")) {
            String item = string(row.get("item"));
            if (item == null) {
                continue;
            }
            int[] range = range(row.get("amount"));
            drops.add(new RewardTable.Entry(item, percent(row.get("percent"), 100), range[0], range[1]));
        }
        List<RewardTable.Weighted> gems = new ArrayList<>();
        for (Map<?, ?> row : section.getMapList("gems")) {
            String item = string(row.get("item"));
            int weight = integer(row.get("weight"), 0);
            if (item != null && weight > 0) {
                gems.add(new RewardTable.Weighted(item, weight));
            }
        }
        out.put(tier, new RewardTable(drops, gems));
    }

    static String string(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    static int integer(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** Reads a 0-100 percentage and returns a 0-1 chance. */
    static double percent(Object raw, double fallback) {
        double value = fallback;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else if (raw != null) {
            try {
                value = Double.parseDouble(raw.toString().trim());
            } catch (NumberFormatException ex) {
                value = fallback;
            }
        }
        return Math.clamp(value / 100.0, 0.0, 1.0);
    }

    /** Reads {@code 3}, {@code "3"} or {@code "2-5"}. Bad values fall back to 1. */
    static int[] range(Object raw) {
        if (raw instanceof Number number) {
            int n = Math.max(0, number.intValue());
            return new int[] {n, n};
        }
        String text = string(raw);
        if (text == null) {
            return new int[] {1, 1};
        }
        int dash = text.indexOf('-', 1);
        try {
            if (dash < 0) {
                int n = Math.max(0, Integer.parseInt(text));
                return new int[] {n, n};
            }
            int a = Math.max(0, Integer.parseInt(text.substring(0, dash).trim()));
            int b = Math.max(0, Integer.parseInt(text.substring(dash + 1).trim()));
            return new int[] {Math.min(a, b), Math.max(a, b)};
        } catch (NumberFormatException ex) {
            return new int[] {1, 1};
        }
    }
}

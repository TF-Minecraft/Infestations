package net.tfminecraft.infestations;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import me.Plugins.TLibs.Objects.API.SubAPI.StringFormatter;

/**
 * Player-facing strings from messages.yml.
 */
public final class Messages {

    private static FileConfiguration config;

    private Messages() {}

    public static void load(File file) {
        FileConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
            config = loaded;
        } catch (IOException | InvalidConfigurationException ex) {
            Infestations.plugin.getLogger().severe("[Infestations] Failed to load messages.yml: " + ex.getMessage());
            config = new YamlConfiguration();
        }
    }

    public static String get(String path) {
        return format(applyPrefix(getRaw(path)));
    }

    public static String get(String path, String... keyValues) {
        String raw = applyPrefix(getRaw(path));
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String value = keyValues[i + 1] != null ? keyValues[i + 1] : "";
            raw = raw.replace("{" + keyValues[i] + "}", value);
        }
        return format(raw);
    }

    public static String getRaw(String path) {
        if (config == null) {
            return path;
        }
        return config.getString(path, path);
    }

    private static String applyPrefix(String raw) {
        if (raw == null) {
            return "";
        }
        String prefix = config != null ? config.getString("prefix", "") : "";
        return raw.replace("{prefix}", prefix != null ? prefix : "");
    }

    private static String format(String raw) {
        if (raw == null) {
            return "";
        }
        return StringFormatter.formatHex(raw.replace('&', '\u00A7'));
    }
}

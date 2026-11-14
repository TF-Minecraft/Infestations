package net.tfminecraft.infestations.reward;

import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.infestations.Infestations;

/**
 * Asks RPCharacters whether a location is inside a permadeath zone. Uses reflection so Infestations
 * never depends on RPCharacters. Without RPCharacters, or on any error, the location is outside a zone.
 */
public final class PermadeathZones {

    static final String LOOKUP = "net.tfminecraft.rpcharacters.permadeath.PermadeathAreaLookup";

    private static ClassLoader loader;
    private static Method lookup;
    private static boolean warned;

    private PermadeathZones() {}

    public static boolean inZone(Location location) {
        if (location == null) {
            return false;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin("RPCharacters");
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }
        try {
            return method(plugin.getClass().getClassLoader()).invoke(null, location) != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (!warned) {
                warned = true;
                Infestations.plugin.getLogger().warning(
                        "[Infestations] Permadeath zone lookup failed, treating locations as outside zones: " + ex);
            }
            return false;
        }
    }

    static Method method(ClassLoader classLoader) throws ReflectiveOperationException {
        if (lookup == null || loader != classLoader) {
            lookup = Class.forName(LOOKUP, true, classLoader).getMethod("getPermadeathZoneAt", Location.class);
            loader = classLoader;
        }
        return lookup;
    }
}

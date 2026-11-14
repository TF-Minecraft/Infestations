package net.tfminecraft.rpcharacters.permadeath;

import org.bukkit.Location;

/** Stand-in for the RPCharacters lookup that PermadeathZones finds by reflection. */
public final class PermadeathAreaLookup {
    public static Object zone;
    public static RuntimeException failure;

    private PermadeathAreaLookup() {}

    public static Object getPermadeathZoneAt(Location location) {
        if (failure != null) {
            throw failure;
        }
        return zone;
    }
}

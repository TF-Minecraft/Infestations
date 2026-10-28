package net.tfminecraft.infestations.utils;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.tfminecraft.simplefactions.SimpleFactions;
import net.tfminecraft.simplefactions.map.ProvinceGrid;
import net.tfminecraft.simplefactions.map.provinces.Province;
import net.tfminecraft.simplefactions.rest.RestServer;
import net.tfminecraft.simplefactions.enums.Terrain;
import net.tfminecraft.infestations.cache.Cache;

public final class Provinces {

    public static final int UNKNOWN = -2;

    private Provinces() {}

    public static int at(Player player) {
        if (player == null) {
            return UNKNOWN;
        }
        return RestServer.getProvince(player);
    }

    public static int at(Location location) {
        if (location == null) {
            return UNKNOWN;
        }
        ProvinceGrid grid = grid();
        if (grid == null) {
            return UNKNOWN;
        }
        return grid.getAt(location.getBlockX(), location.getBlockZ());
    }

    /**
     * Whether a player is inside a province for a lure: on its land, or in a river or lake
     * ({@code WATER} province) with its land within {@link Cache#riverReach} blocks. Rivers are
     * provinces of their own, so a river cutting through a province would otherwise count as
     * leaving it.
     */
    public static boolean within(Player player, int provinceId) {
        int at = at(player);
        if (at == provinceId) {
            return true;
        }
        return isWater(at) && near(player.getLocation(), provinceId, Cache.riverReach);
    }

    private static boolean near(Location location, int provinceId, int reach) {
        ProvinceGrid grid = grid();
        if (grid == null) {
            return false;
        }
        int x = location.getBlockX();
        int z = location.getBlockZ();
        int reachSquared = reach * reach;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx * dx + dz * dz <= reachSquared && grid.getAt(x + dx, z + dz) == provinceId) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ProvinceGrid grid() {
        SimpleFactions sf = SimpleFactions.getInstance();
        return sf == null ? null : sf.getProvinceGrid();
    }

    public static Terrain terrain(int provinceId) {
        if (provinceId <= 0) {
            return null;
        }
        SimpleFactions sf = SimpleFactions.getInstance();
        if (sf == null || sf.getProvinceManager() == null) {
            return null;
        }
        Province province = sf.getProvinceManager().get(provinceId);
        if (province == null) {
            return null;
        }
        return province.getTerrain();
    }

    public static boolean skipTerrain(int provinceId) {
        Terrain terrain = terrain(provinceId);
        if (terrain == null) {
            return true;
        }
        return Cache.skipTerrains.contains(terrain.name().toLowerCase(Locale.ROOT));
    }

    public static boolean isWater(int provinceId) {
        return terrain(provinceId) == Terrain.WATER;
    }

    public static boolean isSea(int provinceId) {
        return terrain(provinceId) == Terrain.SEA;
    }

    public static boolean validLand(int provinceId) {
        return provinceId > 0 && !skipTerrain(provinceId);
    }

    public static Set<Integer> neighbours(int provinceId) {
        if (provinceId <= 0) {
            return Collections.emptySet();
        }
        SimpleFactions sf = SimpleFactions.getInstance();
        if (sf == null || sf.getProvinceManager() == null) {
            return Collections.emptySet();
        }
        Province province = sf.getProvinceManager().get(provinceId);
        if (province == null) {
            return Collections.emptySet();
        }
        return province.getNeighbours();
    }
}

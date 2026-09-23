package net.tfminecraft.infestations.utils;

import java.util.Collections;
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
        SimpleFactions sf = SimpleFactions.getInstance();
        if (sf == null) {
            return UNKNOWN;
        }
        ProvinceGrid grid = sf.getProvinceGrid();
        if (grid == null) {
            return UNKNOWN;
        }
        return grid.getAt(location.getBlockX(), location.getBlockZ());
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
        return Cache.skipTerrains.contains(terrain.name().toLowerCase());
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

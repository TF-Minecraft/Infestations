package net.tfminecraft.infestations.lure;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.InteractibleFurniture;
import net.tfminecraft.furniture.Furniture;
import net.tfminecraft.furniture.FurnitureType;
import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.infestation.Infestation;

public final class LureFurniture {

    private LureFurniture() {}

    public static boolean available() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("InteractibleFurniture");
        return plugin != null && plugin.isEnabled();
    }

    public static boolean isLure(Furniture furniture) {
        if (furniture == null) {
            return false;
        }
        FurnitureType type = furniture.getType();
        if (type == null || type.getItemPath() == null || type.getItemPath().isBlank()) {
            return false;
        }
        return type.getItemPath().equalsIgnoreCase(Cache.lureItem);
    }

    public static boolean matches(Infestation infestation, Furniture furniture) {
        if (infestation == null || !infestation.hasLure() || furniture == null || furniture.getLoc() == null) {
            return false;
        }
        Location loc = furniture.getLoc();
        if (infestation.getWorldName() == null || loc.getWorld() == null) {
            return false;
        }
        if (!infestation.getWorldName().equals(loc.getWorld().getName())) {
            return false;
        }
        return infestation.getLureX() != null
                && infestation.getLureX() == loc.getBlockX()
                && infestation.getLureY() != null
                && infestation.getLureY() == loc.getBlockY()
                && infestation.getLureZ() != null
                && infestation.getLureZ() == loc.getBlockZ();
    }

    public static void remove(Furniture furniture) {
        if (!available() || furniture == null) {
            return;
        }
        InteractibleFurniture.getInstance().getFurnitureManager().removePlugin(furniture);
    }

    public static void removeFor(Infestation infestation) {
        if (!available() || infestation == null) {
            return;
        }
        Location loc = infestation.lureLocation();
        if (loc == null || loc.getWorld() == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return;
        }
        Chunk chunk = loc.getChunk();
        for (Furniture furniture : InteractibleFurniture.getInstance().getFurnitureManager().getFurnitureInChunk(chunk)) {
            if (isLure(furniture) && matches(infestation, furniture)) {
                remove(furniture);
            }
        }
    }

    public static void sweepChunk(Chunk chunk, java.util.function.Function<Integer, Infestation> byProvince) {
        if (!available() || chunk == null) {
            return;
        }
        for (Furniture furniture : java.util.List.copyOf(
                InteractibleFurniture.getInstance().getFurnitureManager().getFurnitureInChunk(chunk))) {
            if (!isLure(furniture)) {
                continue;
            }
            Infestation infestation = byProvince.apply(
                    net.tfminecraft.infestations.utils.Provinces.at(furniture.getLoc()));
            if (infestation != null && matches(infestation, furniture)) {
                continue;
            }
            remove(furniture);
        }
    }
}

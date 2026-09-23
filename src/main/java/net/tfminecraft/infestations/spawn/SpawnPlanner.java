package net.tfminecraft.infestations.spawn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * TrialRooms SpawnPlanner: shuffled disk, 3x3x3 air, 3x3 solid floor.
 */
public final class SpawnPlanner {

    private SpawnPlanner() {}

    public static List<Location> find(Location origin, int ringMin, int ringMax, int max,
            Predicate<Location> extra) {
        List<Location> results = new ArrayList<>(max);
        if (origin == null) {
            return results;
        }
        World world = origin.getWorld();
        if (world == null) {
            return results;
        }

        int min = Math.max(0, Math.min(ringMin, ringMax));
        int maxR = Math.max(min, Math.max(ringMin, ringMax));
        int minSq = min * min;
        int maxSq = maxR * maxR;
        int baseY = origin.getBlockY();

        List<int[]> candidates = new ArrayList<>();
        for (int dx = -maxR; dx <= maxR; dx++) {
            for (int dz = -maxR; dz <= maxR; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 >= minSq && d2 <= maxSq) {
                    candidates.add(new int[] { dx, dz });
                }
            }
        }
        Collections.shuffle(candidates, ThreadLocalRandom.current());

        for (int[] off : candidates) {
            int x = origin.getBlockX() + off[0];
            int z = origin.getBlockZ() + off[1];
            for (int y = baseY - 2; y <= baseY + 2; y++) {
                if (!is3x3x3ClearAir(world, x, y, z) || !is3x3FloorSolid(world, x, y - 1, z)) {
                    continue;
                }
                Location loc = new Location(world, x + 0.5, y, z + 0.5);
                if (extra != null && !extra.test(loc)) {
                    continue;
                }
                results.add(loc);
                break;
            }
            if (results.size() >= max) {
                break;
            }
        }
        return results;
    }

    private static boolean is3x3x3ClearAir(World world, int x, int y, int z) {
        for (int oy = 0; oy < 3; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    Block b = world.getBlockAt(x + ox, y + oy, z + oz);
                    if (!b.getType().isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean is3x3FloorSolid(World world, int x, int yFloor, int z) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Block b = world.getBlockAt(x + ox, yFloor, z + oz);
                if (!b.getType().isSolid()) {
                    return false;
                }
            }
        }
        return true;
    }
}

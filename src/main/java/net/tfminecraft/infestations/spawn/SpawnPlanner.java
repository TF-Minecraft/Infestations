package net.tfminecraft.infestations.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Random spots in a ring: 3x3x3 air over a 3x3 solid floor. Sampling is capped
 * per spot and never touches unloaded chunks, so a failed search stays cheap.
 */
public final class SpawnPlanner {

    private static final int ATTEMPTS_PER_SPOT = 24;
    private static final int MIN_ATTEMPTS = 48;
    private static final double MIN_SPOT_GAP_SQ = 4.0;

    private SpawnPlanner() {}

    public static List<Location> find(Location origin, int ringMin, int ringMax, int max,
            Predicate<Location> extra) {
        List<Location> results = new ArrayList<>(Math.max(0, max));
        if (origin == null || max <= 0) {
            return results;
        }
        World world = origin.getWorld();
        if (world == null) {
            return results;
        }

        int min = Math.max(0, Math.min(ringMin, ringMax));
        int maxR = Math.max(min, Math.max(ringMin, ringMax));
        double minSq = (double) min * min;
        double maxSq = (double) maxR * maxR;
        int baseX = origin.getBlockX();
        int baseY = origin.getBlockY();
        int baseZ = origin.getBlockZ();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        int attempts = Math.max(MIN_ATTEMPTS, max * ATTEMPTS_PER_SPOT);
        for (int i = 0; i < attempts && results.size() < max; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = Math.sqrt(minSq + random.nextDouble() * (maxSq - minSq));
            int x = baseX + (int) Math.round(Math.cos(angle) * radius);
            int z = baseZ + (int) Math.round(Math.sin(angle) * radius);
            if (!areaLoaded(world, x, z)) {
                continue;
            }
            for (int y = baseY - 2; y <= baseY + 2; y++) {
                if (!is3x3x3ClearAir(world, x, y, z) || !is3x3FloorSolid(world, x, y - 1, z)) {
                    continue;
                }
                Location loc = new Location(world, x + 0.5, y, z + 0.5);
                if (tooCloseToChosen(results, loc) || (extra != null && !extra.test(loc))) {
                    continue;
                }
                results.add(loc);
                break;
            }
        }
        return results;
    }

    /**
     * True when no player who can see the world (anything but spectator) is within {@code minDistance}.
     */
    public static boolean clearOfPlayers(Location loc, double minDistance) {
        if (minDistance <= 0 || loc == null || loc.getWorld() == null) {
            return true;
        }
        double minSq = minDistance * minDistance;
        for (Player player : loc.getWorld().getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (player.getLocation().distanceSquared(loc) < minSq) {
                return false;
            }
        }
        return true;
    }

    private static boolean areaLoaded(World world, int x, int z) {
        return world.isChunkLoaded((x - 1) >> 4, (z - 1) >> 4)
                && world.isChunkLoaded((x + 1) >> 4, (z - 1) >> 4)
                && world.isChunkLoaded((x - 1) >> 4, (z + 1) >> 4)
                && world.isChunkLoaded((x + 1) >> 4, (z + 1) >> 4);
    }

    private static boolean tooCloseToChosen(List<Location> chosen, Location loc) {
        for (Location other : chosen) {
            if (other.distanceSquared(loc) < MIN_SPOT_GAP_SQ) {
                return true;
            }
        }
        return false;
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

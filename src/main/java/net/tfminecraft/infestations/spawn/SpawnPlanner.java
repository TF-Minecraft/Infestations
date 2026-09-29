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
 * Random spots in a ring. The strict search wants 3x3x3 air over a 3x3 solid floor; the loose search
 * wants two air blocks over one solid block and a taller column. Sampling is capped per spot and never
 * touches unloaded chunks, so a failed search stays cheap. {@link #anchor} always returns a spot at the
 * origin when a lure still owes mobs and the ring has nowhere to put them.
 */
public final class SpawnPlanner {

    private static final int ATTEMPTS_PER_SPOT = 24;
    private static final int MIN_ATTEMPTS = 48;
    private static final double MIN_SPOT_GAP_SQ = 4.0;
    private static final int STRICT_VERTICAL = 2;
    private static final int LOOSE_VERTICAL = 12;

    private SpawnPlanner() {}

    public static List<Location> find(Location origin, int ringMin, int ringMax, int max,
            Predicate<Location> extra) {
        return sample(origin, ringMin, ringMax, max, extra, STRICT_VERTICAL, true);
    }

    /**
     * Same ring, but a single column of air over a solid block and a wider height band.
     */
    public static List<Location> findLoose(Location origin, int ringMin, int ringMax, int max,
            Predicate<Location> extra) {
        return sample(origin, ringMin, ringMax, max, extra, LOOSE_VERTICAL, false);
    }

    /**
     * A place to stand at the origin. Used when the ring cannot take the mobs a lure still owes.
     * Prefers two air blocks over a solid floor near the origin, and still returns the block above
     * the origin when nothing around it is clear.
     */
    public static Location anchor(Location origin) {
        if (origin == null || origin.getWorld() == null) {
            return null;
        }
        World world = origin.getWorld();
        int x = origin.getBlockX();
        int y = origin.getBlockY();
        int z = origin.getBlockZ();
        if (world.isChunkLoaded(x >> 4, z >> 4)) {
            for (int radius = 0; radius <= 3; radius++) {
                for (int dy = 1; dy <= 6; dy++) {
                    Location found = standInSquare(world, x, y + dy, z, radius);
                    if (found != null) {
                        return found;
                    }
                }
                for (int dy = 0; dy >= -4; dy--) {
                    Location found = standInSquare(world, x, y + dy, z, radius);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return origin.clone().add(0, 1, 0);
    }

    private static List<Location> sample(Location origin, int ringMin, int ringMax, int max,
            Predicate<Location> extra, int vertical, boolean roomy) {
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
            for (int y = baseY - vertical; y <= baseY + vertical; y++) {
                if (!clear(world, x, y, z, roomy)) {
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

    private static Location standInSquare(World world, int x, int y, int z, int radius) {
        for (int ox = -radius; ox <= radius; ox++) {
            for (int oz = -radius; oz <= radius; oz++) {
                if (Math.max(Math.abs(ox), Math.abs(oz)) != radius) {
                    continue;
                }
                if (!areaLoaded(world, x + ox, z + oz) || !isStandable(world, x + ox, y, z + oz)) {
                    continue;
                }
                return new Location(world, x + ox + 0.5, y, z + oz + 0.5);
            }
        }
        return null;
    }

    private static boolean clear(World world, int x, int y, int z, boolean roomy) {
        if (!columnInWorld(world, y, roomy)) {
            return false;
        }
        if (roomy) {
            return is3x3x3ClearAir(world, x, y, z) && is3x3FloorSolid(world, x, y - 1, z);
        }
        return isStandable(world, x, y, z);
    }

    private static boolean columnInWorld(World world, int y, boolean roomy) {
        int top = y + (roomy ? 2 : 1);
        return y - 1 >= world.getMinHeight() && top < world.getMaxHeight();
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

    private static boolean isStandable(World world, int x, int y, int z) {
        if (!columnInWorld(world, y, false)) {
            return false;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block floor = world.getBlockAt(x, y - 1, z);
        return feet.getType().isAir() && head.getType().isAir() && floor.getType().isSolid();
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

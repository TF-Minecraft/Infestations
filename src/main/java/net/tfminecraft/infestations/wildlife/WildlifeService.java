package net.tfminecraft.infestations.wildlife;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.cache.Cache;

/**
 * Ambient sea creatures around players. Every interval each player in survival or adventure gets one
 * try: a few random columns in a ring around them, using only the height maps and two block reads of
 * loaded chunks. A column whose top block is water picks a fitting rule (biome, height, depth), checks
 * the rule's cooldown and how many of its kind are near, and spawns one creature. Spawned creatures are
 * not saved with their chunk and are removed once no player is within the despawn distance.
 */
public final class WildlifeService {

    static final int TICK_PERIOD = 20;
    static final int SWEEP_TICKS = 200;

    private final Map<UUID, String> spawned = new LinkedHashMap<>();
    private final Map<UUID, Integer> lastTry = new HashMap<>();
    private final Map<String, Integer> lastSpawn = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    private List<WildlifeRule> knownRules;
    private BukkitTask task;
    private int tick;

    public void start(Plugin plugin) {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
    }

    /** Stops the timer and removes the creatures this service spawned. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : spawned.keySet()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        spawned.clear();
    }

    /** Wildlife spawned by this service and still tracked. */
    public int tracked() {
        return spawned.size();
    }

    void tick() {
        tick += TICK_PERIOD;
        if (tick % SWEEP_TICKS == 0) {
            sweep();
        }
        if (!WildlifeConfig.enabled || WildlifeConfig.rules.isEmpty()) {
            lastTry.clear();
            return;
        }
        if (WildlifeConfig.rules != knownRules) {
            // A reload may have fixed a Mythic mob name or the mob packs, so try unknown mobs again.
            knownRules = WildlifeConfig.rules;
            warned.clear();
        }
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            if (!eligible(player)) {
                continue;
            }
            Integer last = lastTry.get(player.getUniqueId());
            if (last != null && tick - last < WildlifeConfig.intervalTicks) {
                continue;
            }
            lastTry.put(player.getUniqueId(), tick);
            trySpawn(player);
        }
        lastTry.keySet().retainAll(online);
    }

    private static boolean eligible(Player player) {
        GameMode mode = player.getGameMode();
        return (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) && !player.isDead()
                && WildlifeConfig.worlds.contains(player.getWorld().getName());
    }

    private void trySpawn(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation();
        if (countNear(at, WildlifeConfig.playerCapRadius, null) >= WildlifeConfig.playerCap) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double minSq = (double) WildlifeConfig.ringMin * WildlifeConfig.ringMin;
        double maxSq = (double) WildlifeConfig.ringMax * WildlifeConfig.ringMax;
        double reachSq = (double) WildlifeConfig.despawnDistance * WildlifeConfig.despawnDistance;
        for (int i = 0; i < WildlifeConfig.attempts; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = Math.sqrt(minSq + random.nextDouble() * (maxSq - minSq));
            int x = at.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = at.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            int surface = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
            if (world.getBlockAt(x, surface, z).getType() != Material.WATER) {
                continue;
            }
            int depth = surface - world.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR);
            if (depth < 1) {
                continue;
            }
            int y = surface - Math.min(2, depth - 1);
            Location spot = new Location(world, x + 0.5, y, z + 0.5);
            // A player far above or below the water (flying, deep mines) would see it swept at once.
            if (spot.distanceSquared(at) > reachSq) {
                continue;
            }
            String biome = world.getBiome(x, y, z).getKey().toString();
            List<WildlifeRule> fitting = new ArrayList<>();
            for (WildlifeRule rule : WildlifeConfig.rules) {
                if (rule.fits(biome, y, depth) && ready(rule)
                        && (rule.mythicMob() == null || !warned.contains(rule.mythicMob()))) {
                    fitting.add(rule);
                }
            }
            if (fitting.isEmpty()) {
                continue;
            }
            WildlifeRule rule = fitting.get(random.nextInt(fitting.size()));
            if (countNear(spot, rule.nearbyRadius(), rule.id()) >= rule.maxNearby()) {
                continue;
            }
            // One roll per try, so a lower chance means fewer spawns rather than more sampling.
            if (random.nextDouble() < rule.chance() && spawn(rule, spot)) {
                lastSpawn.put(rule.id(), tick);
            }
            return;
        }
    }

    private boolean ready(WildlifeRule rule) {
        Integer last = lastSpawn.get(rule.id());
        return last == null || tick - last >= rule.cooldownTicks();
    }

    private boolean spawn(WildlifeRule rule, Location spot) {
        Entity entity;
        if (rule.mythicMob() != null) {
            MythicMob mob = MythicBukkit.inst().getMobManager().getMythicMob(rule.mythicMob()).orElse(null);
            if (mob == null) {
                if (warned.add(rule.mythicMob())) {
                    Infestations.plugin.getLogger().warning("[Infestations] Unknown Mythic mob in wildlife.yml: "
                            + rule.mythicMob());
                }
                return false;
            }
            ActiveMob active = mob.spawn(BukkitAdapter.adapt(spot), 1);
            entity = active == null || active.getEntity() == null ? null : active.getEntity().getBukkitEntity();
        } else {
            entity = spot.getWorld().spawnEntity(spot, rule.entity());
        }
        // A spawn another plugin cancelled still returns an entity, but not one in the world.
        if (entity == null || !entity.isValid()) {
            return false;
        }
        entity.getPersistentDataContainer().set(key(), PersistentDataType.STRING, rule.id());
        entity.setPersistent(false);
        spawned.put(entity.getUniqueId(), rule.id());
        if (Cache.debug) {
            Infestations.plugin.getLogger().info("[Infestations] Wildlife " + rule.id() + " at "
                    + spot.getBlockX() + "," + spot.getBlockY() + "," + spot.getBlockZ());
        }
        return true;
    }

    /** Tracked creatures within {@code radius} of {@code at}; only those of {@code ruleId} when it is set. */
    private int countNear(Location at, double radius, String ruleId) {
        double radiusSq = radius * radius;
        int count = 0;
        for (Map.Entry<UUID, String> entry : spawned.entrySet()) {
            if (ruleId != null && !ruleId.equals(entry.getValue())) {
                continue;
            }
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity != null && entity.isValid() && entity.getWorld() == at.getWorld()
                    && entity.getLocation().distanceSquared(at) <= radiusSq) {
                count++;
            }
        }
        return count;
    }

    /** Forgets creatures that are gone and removes those with no player within the despawn distance. */
    void sweep() {
        double maxSq = (double) WildlifeConfig.despawnDistance * WildlifeConfig.despawnDistance;
        Iterator<UUID> it = spawned.keySet().iterator();
        while (it.hasNext()) {
            Entity entity = Bukkit.getEntity(it.next());
            if (entity == null || !entity.isValid()) {
                it.remove();
                continue;
            }
            if (!nearPlayer(entity.getLocation(), maxSq)) {
                entity.remove();
                it.remove();
            }
        }
    }

    private static boolean nearPlayer(Location at, double maxSq) {
        for (Player player : at.getWorld().getPlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR && player.getLocation().distanceSquared(at) <= maxSq) {
                return true;
            }
        }
        return false;
    }

    static NamespacedKey key() {
        return new NamespacedKey(Infestations.plugin, "wildlife");
    }
}

package net.tfminecraft.infestations.spawn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.infestation.Infestation;
import net.tfminecraft.infestations.infestation.LurePhase;
import net.tfminecraft.infestations.utils.Provinces;

/**
 * Deals with infestation mobs no player can get to, such as a mob in a sealed cave. A lure mob that
 * no committed player has had in sight for {@link Cache#lureStragglerSeconds} is moved next to the
 * lure, so the lure can still be won; it stays the same mob, so the kill count is unchanged. An
 * ambient mob that no player has had in sight for {@link Cache#ambientStragglerSeconds} is removed
 * without loot, which frees its place under the ambient cap.
 */
public final class StragglerService {

    static final int SCAN_TICKS = 40;
    /** Players farther than this never count as seeing a mob. */
    static final double SIGHT_RANGE = 64.0;
    private static final int PULL_RING_MIN = 4;
    private static final int PULL_RING_MAX = 12;

    private final Map<UUID, Integer> lastSeen = new HashMap<>();
    private final BiPredicate<Player, LivingEntity> sight;

    public StragglerService() {
        this(Player::hasLineOfSight);
    }

    StragglerService(BiPredicate<Player, LivingEntity> sight) {
        this.sight = sight;
    }

    /**
     * Checks tagged mobs every {@link #SCAN_TICKS} ticks. Returns true when an ambient mob was
     * removed, so the caller can recount the ambient cap.
     */
    public boolean tick(AmbientSpawnService.CollectionInfestations infestations, int tick) {
        if (tick % SCAN_TICKS != 0) {
            return false;
        }
        boolean removed = false;
        Set<UUID> present = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity living : world.getLivingEntities()) {
                if (living instanceof Player || living.isDead()) {
                    continue;
                }
                Integer id = Keys.infestationId(living.getPersistentDataContainer());
                Infestation infestation = id == null ? null : infestations.get(id);
                if (infestation == null) {
                    continue;
                }
                String kind = Keys.kind(living.getPersistentDataContainer());
                boolean lure = Keys.KIND_LURE.equals(kind) && infestation.getPhase() == LurePhase.ACTIVE;
                boolean ambient = Keys.KIND_AMBIENT.equals(kind) && infestation.getPhase() == LurePhase.NONE;
                int limitTicks = (lure ? Cache.lureStragglerSeconds
                        : ambient ? Cache.ambientStragglerSeconds : 0) * 20;
                if (limitTicks <= 0) {
                    continue;
                }
                UUID mobId = living.getUniqueId();
                present.add(mobId);
                Integer last = lastSeen.get(mobId);
                if (last == null || seen(living, lure ? committedPlayers(infestation) : world.getPlayers())) {
                    lastSeen.put(mobId, tick);
                    continue;
                }
                if (tick - last < limitTicks) {
                    continue;
                }
                lastSeen.put(mobId, tick);
                if (lure) {
                    pullToLure(living, infestation);
                } else {
                    living.remove();
                    SpawnLog.line(infestation, "-", "straggler-removed");
                    removed = true;
                }
            }
        }
        lastSeen.keySet().retainAll(present);
        return removed;
    }

    private boolean seen(LivingEntity mob, List<Player> watchers) {
        Location at = mob.getLocation();
        double rangeSq = SIGHT_RANGE * SIGHT_RANGE;
        for (Player player : watchers) {
            if (player.getGameMode() == GameMode.SPECTATOR || player.getWorld() != at.getWorld()
                    || player.getLocation().distanceSquared(at) > rangeSq) {
                continue;
            }
            if (sight.test(player, mob)) {
                return true;
            }
        }
        return false;
    }

    private static List<Player> committedPlayers(Infestation infestation) {
        List<Player> players = new ArrayList<>();
        for (UUID id : infestation.getCommitted()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private static void pullToLure(LivingEntity mob, Infestation infestation) {
        Location origin = infestation.lureLocation();
        if (origin == null) {
            return;
        }
        int provinceId = infestation.getProvinceId();
        List<Location> spots = SpawnPlanner.findLoose(origin, PULL_RING_MIN, PULL_RING_MAX, 1,
                loc -> Provinces.at(loc) == provinceId && SpawnPlanner.reachable(loc));
        Location spot = spots.isEmpty() ? SpawnPlanner.anchor(origin) : spots.get(0);
        mob.teleport(spot);
        mob.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0, false, false));
        SpawnLog.line(infestation, "-", "straggler-pulled");
    }
}

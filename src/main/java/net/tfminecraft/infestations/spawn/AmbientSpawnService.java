package net.tfminecraft.infestations.spawn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.infestation.Infestation;
import net.tfminecraft.infestations.infestation.LurePhase;
import net.tfminecraft.infestations.loader.GroupLoader;
import net.tfminecraft.infestations.loader.GroupLoader.GroupTune;
import net.tfminecraft.infestations.utils.Provinces;

public final class AmbientSpawnService {

    private final Map<UUID, Integer> lastSpawnTick = new HashMap<>();

    public void tick(CollectionInfestations infestations, int tick) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            int provinceId = Provinces.at(player);
            Infestation infestation = infestations.get(provinceId);
            if (infestation == null || infestation.getPhase() != LurePhase.NONE) {
                continue;
            }
            GroupTune tune = GroupLoader.tune(infestation.getGroupId(), infestation.getSeverity());
            int last = lastSpawnTick.getOrDefault(player.getUniqueId(), 0);
            if (tick - last < tune.ambientIntervalTicks()) {
                continue;
            }
            lastSpawnTick.put(player.getUniqueId(), tick);
            if (GroupLoader.blockedByNight(infestation.getGroupId(), player.getWorld())) {
                SpawnLog.line(infestation, player.getName(), "night");
                continue;
            }
            if (infestation.getAmbientAlive() >= tune.ambientCap()) {
                SpawnLog.line(infestation, player.getName(), "cap alive="
                        + infestation.getAmbientAlive() + "/" + tune.ambientCap());
                continue;
            }
            spawnAround(player, infestation, 1);
        }
    }

    public void spawnAround(Player player, Infestation infestation, int amount) {
        GroupTune tune = GroupLoader.tune(infestation.getGroupId(), infestation.getSeverity());
        int room = tune.ambientCap() - infestation.getAmbientAlive();
        int want = Math.min(amount, Math.max(0, room));
        if (want <= 0) {
            return;
        }
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        int provinceId = infestation.getProvinceId();
        List<Location> spots = SpawnPlanner.find(
                player.getLocation(),
                tune.ambientRingMin(),
                tune.ambientRingMax(),
                want,
                loc -> Provinces.at(loc) == provinceId
                        && GroupLoader.allowsY(infestation.getGroupId(), loc.getBlockY()));
        if (spots.isEmpty()) {
            SpawnLog.line(infestation, playerName, "no-spot");
            return;
        }
        for (Location spot : spots) {
            int delay = ThreadLocalRandom.current().nextInt(20, 61);
            Bukkit.getScheduler().runTaskLater(Infestations.plugin, () -> {
                if (Infestations.plugin.getInfestationManager() == null) {
                    return;
                }
                Infestation current = Infestations.plugin.getInfestationManager().get(provinceId);
                if (current != infestation || infestation.getPhase() != LurePhase.NONE) {
                    return;
                }
                Player origin = Bukkit.getPlayer(playerId);
                if (origin == null || !origin.isOnline() || Provinces.at(origin) != provinceId) {
                    SpawnLog.line(infestation, playerName, "left-province");
                    return;
                }
                if (GroupLoader.blockedByNight(infestation.getGroupId(), origin.getWorld())) {
                    SpawnLog.line(infestation, playerName, "night");
                    return;
                }
                if (infestation.getAmbientAlive() >= tune.ambientCap()) {
                    SpawnLog.line(infestation, playerName, "cap alive="
                            + infestation.getAmbientAlive() + "/" + tune.ambientCap());
                    return;
                }
                if (Provinces.at(spot) != provinceId) {
                    SpawnLog.line(infestation, playerName, "no-spot");
                    return;
                }
                LivingEntity spawned = MythicSpawner.spawn(
                        spot, infestation.getGroupId(), infestation.getProvinceId(), Keys.KIND_AMBIENT);
                if (spawned != null) {
                    infestation.setAmbientAlive(infestation.getAmbientAlive() + 1);
                    SpawnLog.spawned(infestation, playerName, spot, infestation.getAmbientAlive(), tune.ambientCap());
                } else {
                    SpawnLog.line(infestation, playerName, "unknown-mythic");
                }
            }, delay);
        }
    }

    /**
     * Set ambientAlive from loaded chunks. Does not despawn anything.
     */
    public void reconcileLoaded(CollectionInfestations infestations) {
        recountAmbient(infestations);
    }

    public void onChunkLoaded(Chunk chunk, CollectionInfestations infestations) {
        if (hasTagged(chunk)) {
            recountAmbient(infestations);
        }
    }

    private boolean hasTagged(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living instanceof Player) {
                continue;
            }
            if (Keys.infestationId(living.getPersistentDataContainer()) != null) {
                return true;
            }
        }
        return false;
    }

    private void recountAmbient(CollectionInfestations infestations) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    if (!(entity instanceof LivingEntity living) || living instanceof Player) {
                        continue;
                    }
                    Integer id = Keys.infestationId(living.getPersistentDataContainer());
                    String kind = Keys.kind(living.getPersistentDataContainer());
                    if (id == null || !Keys.KIND_AMBIENT.equals(kind)) {
                        continue;
                    }
                    Infestation infestation = infestations.get(id);
                    if (infestation == null || infestation.getPhase() != LurePhase.NONE) {
                        continue;
                    }
                    counts.merge(id, 1, Integer::sum);
                }
            }
        }
        for (Infestation infestation : infestations.all()) {
            infestation.setAmbientAlive(counts.getOrDefault(infestation.getProvinceId(), 0));
        }
    }

    public interface CollectionInfestations {
        Infestation get(int provinceId);

        Iterable<Infestation> all();
    }
}

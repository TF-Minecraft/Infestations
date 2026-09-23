package net.tfminecraft.infestations.infestation;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import net.tfminecraft.simplefactions.events.PlayerProvinceEnterEvent;
import net.tfminecraft.simplefactions.events.PlayerProvinceLeaveEvent;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.Messages;
import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.database.InfestationDatabase;
import net.tfminecraft.interactiblefurniture.events.FurnitureBreakEvent;
import net.tfminecraft.interactiblefurniture.events.FurnitureInteractEvent;
import net.tfminecraft.interactiblefurniture.events.FurniturePlaceEvent;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.infestations.loader.GroupLoader;
import net.tfminecraft.infestations.loader.GroupLoader.GroupTune;
import net.tfminecraft.infestations.lure.LureFurniture;
import net.tfminecraft.infestations.lure.LureHologram;
import net.tfminecraft.infestations.map.InfestationMapExport;
import net.tfminecraft.infestations.spawn.AmbientSpawnService;
import net.tfminecraft.infestations.spawn.Keys;
import net.tfminecraft.infestations.spawn.MythicSpawner;
import net.tfminecraft.infestations.spawn.SpawnLog;
import net.tfminecraft.infestations.spawn.SpawnPlanner;
import net.tfminecraft.infestations.utils.Provinces;

public final class InfestationManager implements Listener, AmbientSpawnService.CollectionInfestations {

    private final Infestations plugin;
    private final Map<Integer, Infestation> byProvince = new HashMap<>();
    private final AmbientSpawnService ambient = new AmbientSpawnService();
    private boolean pluginFurnitureRemove;
    private BukkitTask task;
    private int tick;
    private int lastLureHighlightTick = -1;
    private int lastLureHighlightProvince = -1;

    public InfestationManager(Infestations plugin) {
        this.plugin = plugin;
    }

    public void start() {
        for (Infestation infestation : InfestationDatabase.load()) {
            byProvince.put(infestation.getProvinceId(), infestation);
        }
        InfestationMapExport.exportAsync(byProvince.values());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            sweepLoadedChunks();
            ambient.reconcileLoaded(this);
        }, 20L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Infestation infestation : byProvince.values()) {
            LureHologram.remove(infestation);
        }
        save();
    }

    public Infestation get(int provinceId) {
        return byProvince.get(provinceId);
    }

    public Collection<Infestation> all() {
        return byProvince.values();
    }

    public void set(int provinceId, String groupId, Severity severity) {
        byProvince.put(provinceId, new Infestation(provinceId, groupId, severity));
        save();
        InfestationMapExport.exportAsync(byProvince.values());
    }

    public void clear(int provinceId, boolean victory) {
        Infestation infestation = byProvince.get(provinceId);
        if (infestation == null) {
            return;
        }
        if (infestation.hasLure()) {
            destroyLure(infestation, victory);
        }
        byProvince.remove(provinceId);
        save();
        InfestationMapExport.exportAsync(byProvince.values());
    }

    public void reloadFromDisk() {
        Map<Integer, Infestation> previous = new HashMap<>(byProvince);
        byProvince.clear();
        for (Infestation infestation : InfestationDatabase.load()) {
            byProvince.put(infestation.getProvinceId(), infestation);
        }
        pluginFurnitureRemove = true;
        try {
            for (Infestation old : previous.values()) {
                if (!old.hasLure()) {
                    continue;
                }
                Infestation current = byProvince.get(old.getProvinceId());
                if (current != null && current.hasLure() && sameLureBlock(old, current)) {
                    continue;
                }
                LureHologram.remove(old);
                LureFurniture.removeFor(old);
            }
        } finally {
            pluginFurnitureRemove = false;
        }
        sweepLoadedChunks();
        ambient.reconcileLoaded(this);
        save();
        InfestationMapExport.exportAsync(byProvince.values());
    }

    private static boolean sameLureBlock(Infestation a, Infestation b) {
        return a.getWorldName() != null
                && a.getWorldName().equals(b.getWorldName())
                && java.util.Objects.equals(a.getLureX(), b.getLureX())
                && java.util.Objects.equals(a.getLureY(), b.getLureY())
                && java.util.Objects.equals(a.getLureZ(), b.getLureZ());
    }

    public void save() {
        InfestationDatabase.save(byProvince.values());
    }

    private void tick() {
        tick++;
        long now = System.currentTimeMillis();
        ambient.tick(this, tick);

        for (Infestation infestation : List.copyOf(byProvince.values())) {
            expireGrace(infestation, now);
            if (infestation.getPhase() == LurePhase.JOINING) {
                warnJoining(infestation);
                if (now >= infestation.getJoinEndsAt()) {
                    activate(infestation);
                }
            }
            if (infestation.getPhase() == LurePhase.ACTIVE) {
                warnNonJoiners(infestation);
                spawnLureWave(infestation);
                checkWipe(infestation);
            }
            if (infestation.hasLure()) {
                LureHologram.tick(infestation);
            }
            tickDeserters(infestation);
        }

        tickSpread();

        if (tick % 100 == 0) {
            save();
        }
    }

    private void tickSpread() {
        if (!Cache.spread) {
            return;
        }
        int intervalTicks = Cache.spreadIntervalSeconds * 20;
        if (intervalTicks <= 0 || tick % intervalTicks != 0) {
            return;
        }
        boolean changed = false;
        for (Infestation source : List.copyOf(byProvince.values())) {
            if (source.getPhase() != LurePhase.NONE) {
                continue;
            }
            if (source.getSeverity().canWorsen()
                    && ThreadLocalRandom.current().nextDouble() < Cache.worsenChance) {
                source.setSeverity(source.getSeverity().worse());
                changed = true;
                if (Cache.debug) {
                    plugin.getLogger().info("[Infestations] Spread worsened "
                            + source.getProvinceId() + " to " + source.getSeverity().id());
                }
            }
            SpreadPick pick = pickSpreadTarget(source.getProvinceId(), source.getSeverity(), source.getGroupId());
            if (pick == null) {
                continue;
            }
            byProvince.put(pick.provinceId, new Infestation(pick.provinceId, source.getGroupId(), Severity.MILD));
            changed = true;
            if (Cache.debug) {
                plugin.getLogger().info("[Infestations] Spread " + source.getGroupId()
                        + " from " + source.getProvinceId() + " to " + pick.provinceId
                        + " via " + pick.via.id());
            }
        }
        if (changed) {
            save();
            InfestationMapExport.exportAsync(byProvince.values());
        }
    }

    private SpreadPick pickSpreadTarget(int fromId, Severity severity, String groupId) {
        Map<Integer, SpreadVia> best = new HashMap<>();
        for (int neighborId : Provinces.neighbours(fromId)) {
            if (canInfect(neighborId, groupId)) {
                offerSpread(best, neighborId, SpreadVia.LAND);
                continue;
            }
            if (Provinces.isWater(neighborId)) {
                collectWaterOrSeaHop(fromId, neighborId, groupId, SpreadVia.WATER, best);
            } else if (Provinces.isSea(neighborId)) {
                collectWaterOrSeaHop(fromId, neighborId, groupId, SpreadVia.SEA, best);
            }
        }
        if (best.isEmpty()) {
            return null;
        }
        int provinceId = weightedSpreadPick(best, severity);
        SpreadVia via = best.get(provinceId);
        if (ThreadLocalRandom.current().nextDouble() >= via.chance(severity)) {
            if (Cache.debug) {
                plugin.getLogger().info("[Infestations] Spread hop failed via " + via.id()
                        + " from " + fromId + " toward " + provinceId);
            }
            return null;
        }
        return new SpreadPick(provinceId, via);
    }

    private void collectWaterOrSeaHop(
            int fromId, int mediumId, String groupId, SpreadVia via, Map<Integer, SpreadVia> best) {
        for (int farId : Provinces.neighbours(mediumId)) {
            if (farId == fromId || !canInfect(farId, groupId)) {
                continue;
            }
            offerSpread(best, farId, via);
        }
    }

    private boolean canInfect(int provinceId, String groupId) {
        return Provinces.validLand(provinceId)
                && !byProvince.containsKey(provinceId)
                && GroupLoader.allowsTerrain(groupId, provinceId);
    }

    private static void offerSpread(Map<Integer, SpreadVia> best, int provinceId, SpreadVia via) {
        SpreadVia current = best.get(provinceId);
        if (current == null || via.easierThan(current)) {
            best.put(provinceId, via);
        }
    }

    private static int weightedSpreadPick(Map<Integer, SpreadVia> best, Severity severity) {
        double total = 0;
        for (SpreadVia via : best.values()) {
            total += Math.max(0.0001, via.chance(severity));
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        int last = -1;
        for (Map.Entry<Integer, SpreadVia> entry : best.entrySet()) {
            last = entry.getKey();
            roll -= Math.max(0.0001, entry.getValue().chance(severity));
            if (roll <= 0) {
                return entry.getKey();
            }
        }
        return last;
    }

    private enum SpreadVia {
        LAND,
        WATER,
        SEA;

        boolean easierThan(SpreadVia other) {
            return ordinal() < other.ordinal();
        }

        double chance(Severity severity) {
            return Cache.hopChance(severity, ordinal());
        }

        String id() {
            return name().toLowerCase();
        }
    }

    private record SpreadPick(int provinceId, SpreadVia via) {}

    private void sweepLoadedChunks() {
        pluginFurnitureRemove = true;
        try {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                    LureFurniture.sweepChunk(chunk, this::get);
                }
            }
        } finally {
            pluginFurnitureRemove = false;
        }
    }

    private void activate(Infestation infestation) {
        infestation.setPhase(LurePhase.ACTIVE);
        infestation.setLureActivatedAt(System.currentTimeMillis());
        spawnLureWave(infestation);
        save();
    }

    private void spawnLureWave(Infestation infestation) {
        if (tick < infestation.getWaveRetryAtTick()) {
            return;
        }
        Location origin = infestation.lureLocation();
        if (origin == null) {
            return;
        }
        if (GroupLoader.blockedByNight(infestation.getGroupId(), origin.getWorld())) {
            if (tick % 40 == 0) {
                SpawnLog.line(infestation, "-", "night");
            }
            return;
        }
        GroupTune tune = GroupLoader.tune(infestation.getGroupId(), infestation.getSeverity());
        long started = infestation.getLureActivatedAt();
        if (started <= 0) {
            started = System.currentTimeMillis();
            infestation.setLureActivatedAt(started);
        }
        long elapsed = Math.max(0, System.currentTimeMillis() - started);
        long durationMs = tune.lureDurationSeconds() * 1000L;
        int lureCount = tune.lureCount();
        int allowed = durationMs <= 0
                ? lureCount
                : (int) Math.min(lureCount, elapsed * lureCount / durationMs);
        int killsDone = Math.max(0, lureCount - infestation.getLureRemaining());
        int need = allowed - infestation.getPendingSpawns() - infestation.getEnemiesAlive() - killsDone;
        if (need <= 0) {
            return;
        }
        List<Location> spots = SpawnPlanner.find(
                origin,
                4,
                Cache.lureSpawnRadius,
                need,
                loc -> Provinces.at(loc) == infestation.getProvinceId()
                        && GroupLoader.allowsY(infestation.getGroupId(), loc.getBlockY()));
        if (spots.isEmpty()) {
            infestation.setWaveRetryAtTick(tick + 40);
            SpawnLog.line(infestation, "-", "no-spot");
            return;
        }
        infestation.setWaveRetryAtTick(0);
        for (Location spot : spots) {
            infestation.setPendingSpawns(infestation.getPendingSpawns() + 1);
            int delay = ThreadLocalRandom.current().nextInt(20, 61);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (infestation.getPhase() != LurePhase.ACTIVE) {
                    infestation.setPendingSpawns(Math.max(0, infestation.getPendingSpawns() - 1));
                    return;
                }
                if (GroupLoader.blockedByNight(infestation.getGroupId(), spot.getWorld())) {
                    infestation.setPendingSpawns(Math.max(0, infestation.getPendingSpawns() - 1));
                    SpawnLog.line(infestation, "-", "night");
                    return;
                }
                LivingEntity spawned = MythicSpawner.spawn(
                        spot, infestation.getGroupId(), infestation.getProvinceId(), Keys.KIND_LURE);
                infestation.setPendingSpawns(Math.max(0, infestation.getPendingSpawns() - 1));
                if (spawned != null) {
                    infestation.setEnemiesAlive(infestation.getEnemiesAlive() + 1);
                    SpawnLog.spawned(
                            infestation,
                            "-",
                            spot,
                            infestation.getEnemiesAlive(),
                            lureCount);
                } else {
                    SpawnLog.line(infestation, "-", "unknown-mythic");
                }
            }, delay);
        }
    }

    private void warnJoining(Infestation infestation) {
        if (tick % 20 != 0) {
            return;
        }
        String bar = Messages.get("lure-activating-bar", "seconds", String.valueOf(infestation.joinSecondsLeft()));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (Provinces.at(player) != infestation.getProvinceId()) {
                continue;
            }
            actionBar(player, bar);
            if (tick % 100 == 0) {
                player.sendMessage(Messages.get("lure-leave-or-join"));
            }
        }
    }

    private void warnNonJoiners(Infestation infestation) {
        if (tick % 20 != 0) {
            return;
        }
        String bar = Messages.get("lure-nonjoiner-bar");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (Provinces.at(player) != infestation.getProvinceId()) {
                continue;
            }
            if (infestation.isCommitted(player.getUniqueId())) {
                continue;
            }
            actionBar(player, bar);
            player.damage(Cache.deserterDamage);
            if (tick % 100 == 0) {
                player.sendMessage(Messages.get("lure-nonjoiner"));
            }
        }
    }

    private void tickDeserters(Infestation infestation) {
        if (tick % 20 != 0 || infestation.getPhase() == LurePhase.NONE) {
            return;
        }
        for (UUID id : List.copyOf(infestation.getCommitted())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            if (Provinces.at(player) == infestation.getProvinceId()) {
                continue;
            }
            failLure(infestation);
            return;
        }
    }

    private void expireGrace(Infestation infestation, long now) {
        Iterator<Map.Entry<UUID, Long>> it = infestation.getLogoutGraceUntil().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (now >= e.getValue()) {
                infestation.getDeathOnLogin().add(e.getKey());
                it.remove();
            }
        }
    }

    private void checkWipe(Infestation infestation) {
        if (infestation.getPhase() != LurePhase.ACTIVE) {
            return;
        }
        if (hasLivingOrGrace(infestation)) {
            return;
        }
        failLure(infestation);
    }

    private boolean hasLivingOrGrace(Infestation infestation) {
        if (!infestation.getLogoutGraceUntil().isEmpty()) {
            return true;
        }
        if (!infestation.getDeathOnLogin().isEmpty()) {
            return true;
        }
        for (UUID id : infestation.getCommitted()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline() && !player.isDead()) {
                return true;
            }
        }
        return false;
    }

    private void failLure(Infestation infestation) {
        if (infestation.getPhase() == LurePhase.NONE) {
            return;
        }
        Set<UUID> committed = new HashSet<>(infestation.getCommitted());
        int provinceId = infestation.getProvinceId();
        Location loc = infestation.lureLocation();
        destroyLure(infestation, false);
        infestation.clearLure();
        ambient.reconcileLoaded(this);
        save();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_WITHER_SPAWN, 0.4f, 1.4f);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (Provinces.at(player) == provinceId || committed.contains(player.getUniqueId())) {
                player.sendMessage(Messages.get("lure-fail"));
            }
        }
    }

    private void victory(Infestation infestation) {
        Location loc = infestation.lureLocation();
        int provinceId = infestation.getProvinceId();
        destroyLure(infestation, true);
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, loc.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.2);
            loc.getWorld().spawnParticle(Particle.FIREWORK, loc.clone().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.05);
            loc.getWorld().playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.1f);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (Provinces.at(player) == provinceId) {
                player.sendMessage(Messages.get("lure-victory"));
            }
        }
        byProvince.remove(provinceId);
        save();
        InfestationMapExport.exportAsync(byProvince.values());
    }

    private void destroyLure(Infestation infestation, boolean victory) {
        LureHologram.remove(infestation);
        pluginFurnitureRemove = true;
        try {
            LureFurniture.removeFor(infestation);
        } finally {
            pluginFurnitureRemove = false;
        }
    }

    private void handleLureClick(Player player, Infestation infestation) {
        if (player == null) {
            return;
        }
        if (infestation.getPhase() == LurePhase.ACTIVE) {
            int count = highlightLureMobs(infestation);
            if (tick != lastLureHighlightTick || lastLureHighlightProvince != infestation.getProvinceId()) {
                lastLureHighlightTick = tick;
                lastLureHighlightProvince = infestation.getProvinceId();
                player.sendMessage(Messages.get("lure-highlight", "count", String.valueOf(count)));
            }
            if (!infestation.isCommitted(player.getUniqueId())) {
                tryJoin(player, infestation);
            }
            return;
        }
        tryJoin(player, infestation);
    }

    private int highlightLureMobs(Infestation infestation) {
        Location origin = infestation.lureLocation();
        if (origin == null) {
            return 0;
        }
        World world = origin.getWorld();
        if (world == null) {
            return 0;
        }
        int count = 0;
        PotionEffect glow = new PotionEffect(PotionEffectType.GLOWING, 200, 0, false, false);
        int provinceId = infestation.getProvinceId();
        for (LivingEntity entity : world.getLivingEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            Integer tagged = Keys.infestationId(entity.getPersistentDataContainer());
            if (tagged == null || tagged != provinceId) {
                continue;
            }
            if (!Keys.KIND_LURE.equals(Keys.kind(entity.getPersistentDataContainer()))) {
                continue;
            }
            entity.addPotionEffect(glow);
            count++;
        }
        return count;
    }

    private void tryJoin(Player player, Infestation infestation) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (infestation.isCommitted(player.getUniqueId())) {
            player.sendMessage(Messages.get("lure-already-joined"));
            return;
        }
        infestation.getCommitted().add(player.getUniqueId());
        infestation.getLogoutGraceUntil().remove(player.getUniqueId());
        infestation.getDeathOnLogin().remove(player.getUniqueId());
        player.sendMessage(Messages.get("lure-join"));
        save();
    }

    private boolean isLureItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return TLibs.getItemAPI().getChecker().checkItemWithPath(stack, Cache.lureItem);
    }

    private boolean isProtectedLure(Block block) {
        if (block == null) {
            return false;
        }
        for (Infestation infestation : byProvince.values()) {
            if (infestation.isLureBlock(block)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActiveLure(Furniture furniture) {
        if (furniture == null || furniture.getLoc() == null) {
            return false;
        }
        Infestation infestation = get(Provinces.at(furniture.getLoc()));
        return infestation != null && LureFurniture.matches(infestation, furniture);
    }

    private void removeLureFurniture(Furniture furniture) {
        pluginFurnitureRemove = true;
        try {
            LureFurniture.remove(furniture);
        } finally {
            pluginFurnitureRemove = false;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        Furniture furniture = event.getFurniture();
        if (!LureFurniture.isLure(furniture)) {
            return;
        }
        Player player = event.getPlayer();
        Location loc = furniture.getLoc();
        int provinceId = Provinces.at(loc);
        Infestation infestation = get(provinceId);
        if (infestation == null || !Provinces.validLand(provinceId)) {
            event.setCancelled(true);
            if (player != null) {
                player.sendMessage(Messages.get("no-infestation-here"));
            }
            return;
        }
        if (infestation.hasLure()) {
            event.setCancelled(true);
            if (player != null) {
                player.sendMessage(Messages.get("lure-already"));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurniturePlaceDone(FurniturePlaceEvent event) {
        Furniture furniture = event.getFurniture();
        if (!LureFurniture.isLure(furniture) || furniture.getLoc() == null) {
            return;
        }
        Player player = event.getPlayer();
        int provinceId = Provinces.at(furniture.getLoc());
        Infestation infestation = get(provinceId);
        if (infestation == null || infestation.hasLure()) {
            return;
        }
        infestation.placeLure(
                furniture.getLoc().getBlock(),
                System.currentTimeMillis() + Cache.joinSeconds * 1000L,
                infestation.lureBudget());
        if (player != null) {
            tryJoin(player, infestation);
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player)) {
                continue;
            }
            if (Provinces.at(other) == provinceId) {
                other.sendMessage(Messages.get("lure-placed"));
            }
        }
        save();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        if (pluginFurnitureRemove) {
            return;
        }
        Furniture furniture = event.getFurniture();
        if (!LureFurniture.isLure(furniture)) {
            return;
        }
        event.setCancelled(true);
        if (!isActiveLure(furniture)) {
            removeLureFurniture(furniture);
        }
    }

    @EventHandler
    public void onFurnitureInteract(FurnitureInteractEvent event) {
        Furniture furniture = event.getFurniture();
        if (!LureFurniture.isLure(furniture)) {
            return;
        }
        event.setCancelled(true);
        if (!isActiveLure(furniture)) {
            removeLureFurniture(furniture);
            return;
        }
        Infestation infestation = get(Provinces.at(furniture.getLoc()));
        if (infestation == null || infestation.getPhase() == LurePhase.NONE) {
            return;
        }
        handleLureClick(event.getPlayer(), infestation);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isLureItem(event.getItemInHand())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isProtectedLure(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedLure);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedLure);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isProtectedLure)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isProtectedLure)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        Infestation infestation = lureAt(block);
        if (infestation == null) {
            infestation = lureAt(block.getRelative(BlockFace.UP));
        }
        if (infestation == null || infestation.getPhase() == LurePhase.NONE) {
            return;
        }
        event.setCancelled(true);
        handleLureClick(event.getPlayer(), infestation);
    }

    private Infestation lureAt(Block block) {
        for (Infestation infestation : byProvince.values()) {
            if (infestation.isLureBlock(block)) {
                return infestation;
            }
        }
        return null;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Integer provinceId = Keys.infestationId(entity.getPersistentDataContainer());
        String kind = Keys.kind(entity.getPersistentDataContainer());
        if (provinceId == null || kind == null) {
            if (entity instanceof Player player) {
                onPlayerDeath(player);
            }
            return;
        }
        Infestation infestation = get(provinceId);
        if (infestation == null) {
            return;
        }
        if (Keys.KIND_AMBIENT.equals(kind)) {
            if (infestation.getAmbientAlive() > 0) {
                infestation.setAmbientAlive(infestation.getAmbientAlive() - 1);
            }
            return;
        }
        if (Keys.KIND_LURE.equals(kind) && infestation.getPhase() == LurePhase.ACTIVE) {
            if (infestation.getEnemiesAlive() > 0) {
                infestation.setEnemiesAlive(infestation.getEnemiesAlive() - 1);
            }
            infestation.setLureRemaining(infestation.getLureRemaining() - 1);
            if (infestation.getLureRemaining() <= 0) {
                victory(infestation);
            }
        }
    }

    private void onPlayerDeath(Player player) {
        for (Infestation infestation : List.copyOf(byProvince.values())) {
            if (!infestation.isCommitted(player.getUniqueId())) {
                continue;
            }
            infestation.getCommitted().remove(player.getUniqueId());
            infestation.getLogoutGraceUntil().remove(player.getUniqueId());
            infestation.getDeathOnLogin().remove(player.getUniqueId());
            if (infestation.getPhase() != LurePhase.NONE) {
                checkWipe(infestation);
            }
        }
        save();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        long until = System.currentTimeMillis() + Cache.logoutGraceSeconds * 1000L;
        for (Infestation infestation : byProvince.values()) {
            if (!infestation.isCommitted(player.getUniqueId())) {
                continue;
            }
            infestation.getLogoutGraceUntil().put(player.getUniqueId(), until);
        }
        save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            for (Infestation infestation : byProvince.values()) {
                infestation.getLogoutGraceUntil().remove(player.getUniqueId());
                if (infestation.getDeathOnLogin().remove(player.getUniqueId())
                        && infestation.isCommitted(player.getUniqueId())
                        && !player.isDead()) {
                    player.damage(Math.max(40.0, player.getHealth() + 10.0));
                }
            }
        }, 5L);
    }

    @EventHandler
    public void onLeaveProvince(PlayerProvinceLeaveEvent event) {
        Infestation infestation = get(event.getProvinceId());
        if (infestation == null) {
            return;
        }
        if (infestation.getPhase() == LurePhase.NONE) {
            return;
        }
        if (infestation.isCommitted(event.getPlayer().getUniqueId())) {
            failLure(infestation);
        }
    }

    @EventHandler
    public void onEnterProvince(PlayerProvinceEnterEvent event) {
        Infestation infestation = get(event.getProvinceId());
        if (infestation != null && infestation.getPhase() == LurePhase.JOINING) {
            event.getPlayer().sendMessage(Messages.get("lure-leave-or-join"));
        }
        if (infestation != null && infestation.getPhase() == LurePhase.ACTIVE
                && !infestation.isCommitted(event.getPlayer().getUniqueId())) {
            event.getPlayer().sendMessage(Messages.get("lure-nonjoiner"));
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        LureHologram.removeOrphans(event.getChunk());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        org.bukkit.Chunk chunk = event.getChunk();
        Bukkit.getScheduler().runTask(plugin, () -> {
            pluginFurnitureRemove = true;
            try {
                LureFurniture.sweepChunk(chunk, this::get);
            } finally {
                pluginFurnitureRemove = false;
            }
            LureHologram.removeOrphans(chunk);
            ambient.onChunkLoaded(chunk, this);
            for (Infestation infestation : byProvince.values()) {
                if (!infestation.hasLure()) {
                    continue;
                }
                Block block = infestation.lureBlock();
                if (block != null && block.getChunk().equals(chunk)) {
                    LureHologram.tick(infestation);
                }
            }
        });
    }

    private static void actionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }
}

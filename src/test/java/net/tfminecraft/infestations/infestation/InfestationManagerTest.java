package net.tfminecraft.infestations.infestation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import io.lumine.mythic.bukkit.utils.serialize.Optl;
import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.Messages;
import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.database.InfestationDatabase;
import net.tfminecraft.infestations.loader.GroupLoader;
import net.tfminecraft.infestations.loader.GroupLoader.GroupTune;
import net.tfminecraft.infestations.lure.LureFurniture;
import net.tfminecraft.infestations.lure.LureHologram;
import net.tfminecraft.infestations.map.InfestationMapExport;
import net.tfminecraft.infestations.spawn.*;
import net.tfminecraft.infestations.utils.Provinces;
import net.tfminecraft.interactiblefurniture.events.*;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.simplefactions.events.PlayerProvinceEnterEvent;
import net.tfminecraft.simplefactions.events.PlayerProvinceLeaveEvent;
import net.tfminecraft.tlibs.TLibs;

@SuppressWarnings("deprecation")
class InfestationManagerTest {
    private final List<AutoCloseable> mocks = new ArrayList<>();
    private final List<Player> players = new ArrayList<>();
    private final List<LivingEntity> entities = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>();
    private final Map<PersistentDataContainer, Integer> tags = new IdentityHashMap<>();
    private final Map<PersistentDataContainer, String> kinds = new IdentityHashMap<>();
    private final Map<Object, Integer> provinces = new IdentityHashMap<>();
    private final Map<UUID, Player> online = new HashMap<>();
    private final Map<String, Object> savedCache = new HashMap<>();
    private Infestations previousPlugin;
    private Infestations plugin;
    private InfestationManager manager;
    private AmbientSpawnService ambient;
    private World world;
    private Chunk chunk;
    private Block lureBlock;
    private BukkitScheduler scheduler;
    private BukkitTask timer;
    private Runnable tick;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<InfestationDatabase> database;
    private MockedStatic<InfestationMapExport> export;
    private MockedStatic<GroupLoader> groups;
    private MockedStatic<Provinces> provinceApi;
    private MockedStatic<SpawnPlanner> planner;
    private MockedStatic<MythicSpawner> spawner;
    private MockedStatic<LureFurniture> furniture;
    private MockedStatic<LureHologram> hologram;
    private MockedStatic<SpawnLog> log;
    private ThreadLocalRandom random;

    @BeforeAll static void initializeRegistries() { MockBukkit.mock(); }
    @AfterAll static void releaseRegistries() { MockBukkit.unmock(); }

    @BeforeEach void setup() throws Exception {
        for (Field f : Cache.class.getFields()) {
            if (!java.lang.reflect.Modifier.isFinal(f.getModifiers())) savedCache.put(f.getName(), f.get(null));
        }
        Cache.spread = false;
        Cache.debug = false;
        Cache.minPlayerDistance = 16;
        Cache.deserterDamage = 2;
        previousPlugin = Infestations.plugin;
        plugin = mock(Infestations.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        Infestations.plugin = plugin;
        bukkit = scoped(mockStatic(Bukkit.class));
        database = scoped(mockStatic(InfestationDatabase.class));
        export = scoped(mockStatic(InfestationMapExport.class));
        groups = scoped(mockStatic(GroupLoader.class));
        provinceApi = scoped(mockStatic(Provinces.class));
        planner = scoped(mockStatic(SpawnPlanner.class));
        spawner = scoped(mockStatic(MythicSpawner.class));
        furniture = scoped(mockStatic(LureFurniture.class));
        hologram = scoped(mockStatic(LureHologram.class));
        log = scoped(mockStatic(SpawnLog.class));
        scoped(mockStatic(Messages.class, invocation -> invocation.getArgument(0)));
        MockedStatic<Keys> keys = scoped(mockStatic(Keys.class));
        keys.when(() -> Keys.infestationId(any(PersistentDataContainer.class)))
                .thenAnswer(i -> tags.get(i.getArgument(0)));
        keys.when(() -> Keys.kind(any(PersistentDataContainer.class)))
                .thenAnswer(i -> kinds.get(i.getArgument(0)));
        keys.when(() -> Keys.tagMob(any(), anyInt(), anyString())).thenAnswer(i -> {
            tags.put(i.getArgument(0), i.getArgument(1));
            kinds.put(i.getArgument(0), i.getArgument(2));
            return null;
        });
        MockedConstruction<AmbientSpawnService> ambientConstruction = scoped(mockConstruction(AmbientSpawnService.class));
        MockedStatic<ThreadLocalRandom> randomApi = scoped(mockStatic(ThreadLocalRandom.class));
        random = mock(ThreadLocalRandom.class);
        randomApi.when(ThreadLocalRandom::current).thenReturn(random);
        when(random.nextInt(20, 61)).thenReturn(20);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getLivingEntities()).thenReturn(entities);
        chunk = mock(Chunk.class);
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(players);
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(i -> online.get(i.getArgument(0)));
        lureBlock = block(5, 64, 5);
        when(world.getBlockAt(5, 64, 5)).thenReturn(lureBlock);
        when(world.getBlockAt(any(Location.class))).thenAnswer(i -> {
            Location loc = i.getArgument(0);
            return world.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        });
        scheduler = mock(BukkitScheduler.class);
        timer = mock(BukkitTask.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(i -> { tick = i.getArgument(1); return timer; });
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
                .thenAnswer(i -> { later.add(i.getArgument(1)); return mock(BukkitTask.class); });
        when(scheduler.runTask(eq(plugin), any(Runnable.class)))
                .thenAnswer(i -> { later.add(i.getArgument(1)); return mock(BukkitTask.class); });
        groups.when(() -> GroupLoader.tune(anyString(), any())).thenReturn(GroupTune.defaults(Severity.MILD));
        groups.when(() -> GroupLoader.allowsY(anyString(), anyInt())).thenReturn(true);
        groups.when(() -> GroupLoader.allowsTerrain(anyString(), anyInt())).thenReturn(true);
        provinceApi.when(() -> Provinces.at(any(Player.class))).thenAnswer(i -> provinces.getOrDefault(i.getArgument(0), -2));
        provinceApi.when(() -> Provinces.at(any(Location.class))).thenReturn(1);
        provinceApi.when(() -> Provinces.validLand(anyInt())).thenAnswer(i -> (int) i.getArgument(0) > 0);
        planner.when(() -> SpawnPlanner.clearOfPlayers(any(), anyDouble())).thenReturn(true);
        planner.when(() -> SpawnPlanner.anchor(any())).thenReturn(new Location(world, 5.5, 65, 5.5));
        manager = new InfestationManager(plugin);
        ambient = ambientConstruction.constructed().getFirst();
        when(plugin.getInfestationManager()).thenReturn(manager);
        manager.start();
        later.clear();
    }

    @AfterEach void cleanup() throws Exception {
        Collections.reverse(mocks);
        for (AutoCloseable mock : mocks) mock.close();
        Infestations.plugin = previousPlugin;
        for (var entry : savedCache.entrySet()) Cache.class.getField(entry.getKey()).set(null, entry.getValue());
    }

    private <T extends AutoCloseable> T scoped(T resource) { mocks.add(resource); return resource; }
    private Infestation infestation(int id) {
        manager.set(id, "zombies", Severity.MILD);
        return manager.get(id);
    }
    private Infestation lure(int id, LurePhase phase) {
        Infestation result = infestation(id);
        result.placeLure(lureBlock, Long.MAX_VALUE, 20);
        result.setPhase(phase);
        result.setLureActivatedAt(System.currentTimeMillis() - 150_000);
        return result;
    }
    private Player player(int province) {
        Player result = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(result.getUniqueId()).thenReturn(id);
        when(result.isOnline()).thenReturn(true);
        when(result.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(result.getWorld()).thenReturn(world);
        when(result.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        when(result.spigot()).thenReturn(mock(Player.Spigot.class));
        when(result.getHealth()).thenReturn(20.0);
        players.add(result); online.put(id, result); provinces.put(result, province);
        return result;
    }
    private Block block(int x, int y, int z) {
        Block b = mock(Block.class);
        when(b.getWorld()).thenReturn(world); when(b.getX()).thenReturn(x);
        when(b.getY()).thenReturn(y); when(b.getZ()).thenReturn(z);
        when(b.getLocation()).thenReturn(new Location(world, x, y, z));
        when(b.getChunk()).thenReturn(chunk);
        return b;
    }
    private LivingEntity mob(Integer province, String kind) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(entity.getPersistentDataContainer()).thenReturn(pdc);
        if (province != null) tags.put(pdc, province);
        if (kind != null) kinds.put(pdc, kind);
        entities.add(entity);
        return entity;
    }
    private Furniture furniture() {
        Furniture f = mock(Furniture.class);
        when(f.getLoc()).thenReturn(new Location(world, 5, 64, 5));
        furniture.when(() -> LureFurniture.isLure(f)).thenReturn(true);
        return f;
    }
    private Object call(String name, Object... args) {
        try {
            Method method = Arrays.stream(InfestationManager.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length)
                    .findFirst().orElseThrow();
            method.setAccessible(true);
            return method.invoke(manager, args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
    private void atTick(int value) throws Exception {
        Field field = InfestationManager.class.getDeclaredField("tick");
        field.setAccessible(true); field.setInt(manager, value);
    }
    private void runLater() {
        List<Runnable> callbacks = List.copyOf(later); later.clear(); callbacks.forEach(Runnable::run);
    }

    @Test void lifecycleLoadsSweepsSavesAndCancelsOnlyOnce() {
        Infestation saved = new Infestation(7, "zombies", Severity.SEVERE);
        database.when(InfestationDatabase::load).thenReturn(List.of(saved));
        manager.start();
        assertSame(saved, manager.get(7));
        assertEquals(List.of(saved), List.copyOf(manager.all()));
        verify(scheduler, times(2)).runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(1L));
        runLater();
        furniture.verify(() -> LureFurniture.sweepChunk(eq(chunk), any()));
        verify(ambient).reconcileLoaded(manager);
        manager.shutdown(); manager.shutdown();
        verify(timer, times(1)).cancel();
        hologram.verify(() -> LureHologram.remove(saved), times(2));
        database.verify(() -> InfestationDatabase.save(anyCollection()), times(2));
    }

    @Test void clearHandlesAbsentIdleAndActiveInfestations() {
        manager.clear(4, false);
        Infestation idle = infestation(1);
        manager.clear(1, false);
        assertNull(manager.get(1));
        hologram.verify(() -> LureHologram.remove(idle), never());
        Infestation active = lure(2, LurePhase.ACTIVE);
        manager.clear(2, true);
        assertTrue(manager.all().isEmpty());
        hologram.verify(() -> LureHologram.remove(active));
        furniture.verify(() -> LureFurniture.removeFor(active));
    }

    @Test void reloadKeepsMatchingLureAndRemovesChangedOrDeletedLures() {
        Infestation idle = infestation(9);
        Infestation retained = lure(1, LurePhase.JOINING);
        Infestation changed = lure(2, LurePhase.ACTIVE);
        Infestation deleted = lure(3, LurePhase.ACTIVE);
        Infestation replacement = new Infestation(1, "zombies", Severity.MILD);
        replacement.placeLure(lureBlock, Long.MAX_VALUE, 20);
        Infestation moved = new Infestation(2, "zombies", Severity.MILD);
        moved.placeLure(block(6, 65, 7), Long.MAX_VALUE, 20);
        database.when(InfestationDatabase::load).thenReturn(List.of(replacement, moved));
        manager.reloadFromDisk();
        assertSame(replacement, manager.get(1));
        assertSame(moved, manager.get(2));
        assertNull(manager.get(3));
        furniture.verify(() -> LureFurniture.removeFor(changed));
        furniture.verify(() -> LureFurniture.removeFor(deleted));
        furniture.verify(() -> LureFurniture.removeFor(retained), never());
        furniture.verify(() -> LureFurniture.removeFor(idle), never());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void delayedLureSpawnsDoNotSurviveClearOrReload(boolean reload) {
        Infestation active = lure(1, LurePhase.ACTIVE);
        call("spawnLureWave", active);
        assertFalse(later.isEmpty());
        if (reload) {
            Infestation replacement = new Infestation(1, "zombies", Severity.MILD);
            replacement.placeLure(lureBlock, Long.MAX_VALUE, 20);
            replacement.setPhase(LurePhase.ACTIVE);
            database.when(InfestationDatabase::load).thenReturn(List.of(replacement));
            manager.reloadFromDisk();
        } else {
            manager.clear(1, false);
        }
        runLater();
        spawner.verifyNoInteractions();
        assertEquals(0, active.getPendingSpawns());
    }

    @Test void delayedSpawnsFromFailedLureCannotMutateItsReplacementOnTheSameObject() {
        Infestation active = lure(1, LurePhase.ACTIVE);
        active.setLureRemaining(1);
        call("spawnLureWave", active);
        assertEquals(1, later.size());
        Runnable previousLureSpawn = later.removeFirst();

        call("failLure", active);
        assertSame(active, manager.get(1));
        Block replacementBlock = block(9, 64, 9);
        active.placeLure(replacementBlock, 0, 20);
        Location replacementSpot = new Location(world, 9.5, 65, 9.5);
        planner.when(() -> SpawnPlanner.anchor(any())).thenReturn(replacementSpot);
        call("activate", active);
        assertEquals(1, active.getPendingSpawns());
        assertEquals(1, active.getLureReleased());

        previousLureSpawn.run();

        spawner.verifyNoInteractions();
        assertEquals(1, active.getPendingSpawns(), "An old callback must not consume the new lure's pending slot");
        assertEquals(1, active.getLureReleased());
        assertEquals(0, active.getEnemiesAlive());
        assertEquals(20, active.getLureRemaining());
        assertEquals(LurePhase.ACTIVE, active.getPhase());

        LivingEntity spawned = mob(1, Keys.KIND_LURE);
        spawner.when(() -> MythicSpawner.spawn(replacementSpot, "zombies", 1, Keys.KIND_LURE)).thenReturn(spawned);
        runLater();
        spawner.verify(() -> MythicSpawner.spawn(replacementSpot, "zombies", 1, Keys.KIND_LURE));
        assertEquals(0, active.getPendingSpawns());
        assertEquals(1, active.getLureReleased());
        assertEquals(1, active.getEnemiesAlive());
    }

    @Test void tickActivatesJoiningLureAndPersistsPeriodicState() throws Exception {
        Infestation active = lure(1, LurePhase.JOINING);
        Player joined = player(1); active.getCommitted().add(joined.getUniqueId());
        active.setJoinEndsAt(0);
        atTick(99);
        tick.run();
        assertEquals(LurePhase.ACTIVE, active.getPhase());
        assertTrue(active.getLureActivatedAt() > 0);
        assertFalse(later.isEmpty());
        verify(ambient).tick(manager, 100);
        hologram.verify(() -> LureHologram.tick(active));
        verify(joined).sendMessage("lure-leave-or-join");
    }

    @Test void recountAdoptsOnlyMatchingLivingAmbientMobs() {
        Infestation active = lure(1, LurePhase.ACTIVE);
        active.setLureRemaining(1); active.setPendingSpawns(2);
        LivingEntity adopted = mob(1, Keys.KIND_AMBIENT);
        mob(1, Keys.KIND_LURE); mob(2, Keys.KIND_LURE); mob(null, null); mob(1, "other");
        LivingEntity dead = mob(1, Keys.KIND_LURE); when(dead.isDead()).thenReturn(true);
        entities.add(player(1));
        call("recountLure", active);
        assertEquals(2, active.getEnemiesAlive());
        assertEquals(4, active.getLureRemaining());
        assertEquals(1, active.getLureReleased());
        assertEquals(Keys.KIND_LURE, kinds.get(adopted.getPersistentDataContainer()));
        log.verify(() -> SpawnLog.line(eq(active), eq("-"), contains("adopted 1")));
        Infestation absentWorld = lure(2, LurePhase.ACTIVE); absentWorld.setWorldName("missing");
        call("recountLure", absentWorld);
        assertEquals(0, absentWorld.getEnemiesAlive());
    }

    @Test void victoryWaitsForSummonsThenClearsInfestation() throws Exception {
        Infestation active = lure(1, LurePhase.ACTIVE);
        Player nearby = player(1); Player distant = player(9);
        active.setLureRemaining(0);
        assertEquals(false, call("tickVictory", active));
        assertEquals(40, active.getVictoryAtTick());
        atTick(20); assertEquals(false, call("tickVictory", active));
        active.setLureRemaining(1);
        assertEquals(false, call("tickVictory", active)); assertEquals(0, active.getVictoryAtTick());
        active.setLureRemaining(0); call("tickVictory", active);
        atTick(60); assertEquals(true, call("tickVictory", active));
        assertNull(manager.get(1));
        verify(nearby).sendMessage("lure-victory"); verify(distant, never()).sendMessage("lure-victory");
        verify(world).playSound(any(Location.class), eq(Sound.UI_TOAST_CHALLENGE_COMPLETE), eq(1f), eq(1.1f));
    }

    @Test void waveHonorsRetryMissingWorldNightAndQuota() throws Exception {
        Infestation active = lure(1, LurePhase.ACTIVE);
        active.setWaveRetryAtTick(10); call("spawnLureWave", active); assertTrue(later.isEmpty());
        active.setWaveRetryAtTick(0); active.setWorldName("missing");
        call("spawnLureWave", active); assertTrue(later.isEmpty()); active.setWorldName("world");
        groups.when(() -> GroupLoader.blockedByNight("zombies", world)).thenReturn(true);
        call("spawnLureWave", active);
        log.verify(() -> SpawnLog.line(active, "-", "night"));
        atTick(1); call("spawnLureWave", active); assertTrue(later.isEmpty());
        groups.when(() -> GroupLoader.blockedByNight("zombies", world)).thenReturn(false);
        active.setEnemiesAlive(20); call("spawnLureWave", active); assertTrue(later.isEmpty());
    }

    @Test void missingSpawnSpotsBackOffAndSuccessfulSpawnsUpdateTallies() {
        Infestation active = lure(1, LurePhase.ACTIVE);
        active.setLureRemaining(1);
        planner.when(() -> SpawnPlanner.anchor(any())).thenReturn(null);
        call("spawnLureWave", active);
        assertEquals(40, active.getWaveRetryAtTick());
        log.verify(() -> SpawnLog.line(active, "-", "no-spot"));
        active.setWaveRetryAtTick(0);
        Location spot = new Location(world, 20, 64, 20);
        planner.when(() -> SpawnPlanner.find(any(), anyInt(), anyInt(), anyInt(), any())).thenReturn(List.of(spot));
        LivingEntity spawned = mob(1, Keys.KIND_LURE);
        spawner.when(() -> MythicSpawner.spawn(spot, "zombies", 1, Keys.KIND_LURE)).thenReturn(spawned);
        call("spawnLureWave", active);
        assertEquals(1, active.getPendingSpawns()); assertEquals(1, active.getLureReleased());
        runLater();
        assertEquals(0, active.getPendingSpawns()); assertEquals(1, active.getEnemiesAlive());
        log.verify(() -> SpawnLog.spawned(active, "-", spot, 1, 20));
    }

    @Test void delayedWaveRechecksPhaseNightAndFailedMythicSpawn() {
        Infestation active = lure(1, LurePhase.ACTIVE); active.setLureRemaining(1);
        call("spawnLureWave", active); active.setPhase(LurePhase.NONE); runLater();
        assertEquals(0, active.getPendingSpawns()); spawner.verifyNoInteractions();
        active.setPhase(LurePhase.ACTIVE); active.setLureReleased(0);
        call("spawnLureWave", active);
        groups.when(() -> GroupLoader.blockedByNight("zombies", world)).thenReturn(true);
        runLater(); assertEquals(0, active.getPendingSpawns()); assertEquals(0, active.getLureReleased());
        groups.when(() -> GroupLoader.blockedByNight("zombies", world)).thenReturn(false);
        call("spawnLureWave", active); runLater();
        assertEquals(0, active.getPendingSpawns()); assertEquals(0, active.getLureReleased());
        log.verify(() -> SpawnLog.line(active, "-", "unknown-mythic"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void playerApproachingStrictSpotUsesAnchorOrRefundsQuota(boolean anchorAvailable) {
        Infestation active = lure(1, LurePhase.ACTIVE); active.setLureRemaining(1);
        Location strict = new Location(world, 20, 64, 20);
        planner.when(() -> SpawnPlanner.find(any(), anyInt(), anyInt(), anyInt(), any())).thenReturn(List.of(strict));
        call("spawnLureWave", active);
        planner.when(() -> SpawnPlanner.clearOfPlayers(strict, Cache.minPlayerDistance)).thenReturn(false);
        Location anchor = anchorAvailable ? new Location(world, 5, 65, 5) : null;
        planner.when(() -> SpawnPlanner.anchor(any())).thenReturn(anchor);
        runLater();
        assertEquals(0, active.getPendingSpawns());
        if (anchorAvailable) {
            spawner.verify(() -> MythicSpawner.spawn(anchor, "zombies", 1, Keys.KIND_LURE));
            log.verify(() -> SpawnLog.line(active, "-", "too-close-anchor"));
        } else {
            spawner.verifyNoInteractions();
            assertEquals(0, active.getLureReleased());
            log.verify(() -> SpawnLog.line(active, "-", "too-close"));
        }
    }

    @Test void planningCombinesStrictLooseAndAnchorSpotsAndEvaluatesFilters() {
        Infestation active = lure(1, LurePhase.ACTIVE); active.setLureRemaining(3);
        Location strict = new Location(world, 20, 64, 20), loose = new Location(world, 10, 64, 10);
        planner.when(() -> SpawnPlanner.find(any(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(i -> {
            Predicate<Location> filter = i.getArgument(4);
            assertTrue(filter.test(strict));
            groups.when(() -> GroupLoader.allowsY("zombies", 64)).thenReturn(false);
            assertFalse(filter.test(strict));
            groups.when(() -> GroupLoader.allowsY("zombies", 64)).thenReturn(true);
            planner.when(() -> SpawnPlanner.clearOfPlayers(strict, Cache.minPlayerDistance)).thenReturn(false);
            assertFalse(filter.test(strict));
            planner.when(() -> SpawnPlanner.clearOfPlayers(strict, Cache.minPlayerDistance)).thenReturn(true);
            provinceApi.when(() -> Provinces.at(strict)).thenReturn(2);
            assertFalse(filter.test(strict));
            provinceApi.when(() -> Provinces.at(strict)).thenReturn(1);
            return List.of(strict);
        });
        planner.when(() -> SpawnPlanner.findLoose(any(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(i -> {
            Predicate<Location> filter = i.getArgument(4); assertTrue(filter.test(loose));
            groups.when(() -> GroupLoader.allowsY("zombies", 64)).thenReturn(false);
            assertFalse(filter.test(loose));
            groups.when(() -> GroupLoader.allowsY("zombies", 64)).thenReturn(true);
            provinceApi.when(() -> Provinces.at(loose)).thenReturn(2); assertFalse(filter.test(loose));
            provinceApi.when(() -> Provinces.at(loose)).thenReturn(1);
            return List.of(loose);
        });
        call("spawnLureWave", active);
        assertEquals(3, active.getPendingSpawns());
        log.verify(() -> SpawnLog.line(active, "-", "anchor 1"));
        runLater(); assertEquals(0, active.getPendingSpawns());
    }

    @Test void looseOnlyWaveLogsFallbackAndStartsMissingClock() {
        Infestation active = lure(1, LurePhase.ACTIVE); active.setLureRemaining(1); active.setLureActivatedAt(0);
        Location spot = new Location(world, 10, 64, 10);
        planner.when(() -> SpawnPlanner.findLoose(any(), anyInt(), anyInt(), anyInt(), any())).thenReturn(List.of(spot));
        call("spawnLureWave", active);
        assertTrue(active.getLureActivatedAt() > 0); assertEquals(1, active.getPendingSpawns());
        log.verify(() -> SpawnLog.line(active, "-", "loose 1"));
    }

    @Test void joiningAndActiveWarningsRespectProvinceMembershipAndModes() throws Exception {
        Infestation active = lure(1, LurePhase.JOINING);
        Player joined = player(1), stranger = player(1), elsewhere = player(2);
        Player creative = player(1), spectator = player(1);
        when(creative.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(spectator.getGameMode()).thenReturn(GameMode.SPECTATOR);
        active.getCommitted().add(joined.getUniqueId());
        atTick(1); call("warnJoining", active); call("warnNonJoiners", active);
        verifyNoInteractions(joined.spigot());
        atTick(100); call("warnJoining", active);
        verify(joined).sendMessage("lure-leave-or-join");
        verify(elsewhere, never()).sendMessage("lure-leave-or-join");
        active.setPhase(LurePhase.ACTIVE); call("warnNonJoiners", active);
        verify(stranger).damage(2.0); verify(stranger).sendMessage("lure-nonjoiner");
        verify(joined, never()).damage(anyDouble()); verify(creative, never()).damage(anyDouble());
        verify(spectator, never()).damage(anyDouble()); verify(elsewhere, never()).damage(anyDouble());
        atTick(20); call("warnJoining", active); call("warnNonJoiners", active);
        verify(stranger, times(2)).damage(2.0);
    }

    @Test void outsideCommittedDamageSkipsAbsentOfflineDeadExemptAndInsidePlayers() throws Exception {
        Infestation active = lure(1, LurePhase.ACTIVE);
        Player outside = player(2), inside = player(1), creative = player(2), spectator = player(2);
        Player dead = player(2), offline = player(2);
        when(creative.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(spectator.getGameMode()).thenReturn(GameMode.SPECTATOR);
        when(dead.isDead()).thenReturn(true); when(offline.isOnline()).thenReturn(false);
        for (Player p : players) active.getCommitted().add(p.getUniqueId());
        active.getCommitted().add(UUID.randomUUID());
        atTick(1); call("tickOutsideCommitted", active); verify(outside, never()).damage(anyDouble());
        atTick(100); call("tickOutsideCommitted", active);
        verify(outside).damage(2.0); verify(outside).sendMessage("lure-outside");
        for (Player p : List.of(inside, creative, spectator, dead, offline)) verify(p, never()).damage(anyDouble());
        atTick(20); call("tickOutsideCommitted", active); verify(outside, times(2)).damage(2.0);
        active.setPhase(LurePhase.NONE); call("tickOutsideCommitted", active);
        verify(outside, times(2)).damage(2.0);
    }

    @Test void leaveRemovesCommitmentFromEveryLureAndFailsOnlyUnoccupiedOnes() {
        Player leaving = player(2), remaining = player(1);
        assertFalse(manager.leaveLure(leaving));
        Infestation shared = lure(1, LurePhase.ACTIVE), abandoned = lure(2, LurePhase.JOINING);
        Infestation remote = lure(3, LurePhase.ACTIVE); infestation(4);
        for (Infestation infestation : List.of(shared, abandoned, remote)) {
            infestation.getCommitted().add(leaving.getUniqueId());
            infestation.getLogoutGraceUntil().put(leaving.getUniqueId(), Long.MAX_VALUE);
            infestation.getDeathOnLogin().add(leaving.getUniqueId());
        }
        shared.getCommitted().add(remaining.getUniqueId());
        assertTrue(manager.leaveLure(leaving));
        verify(leaving).sendMessage("lure-left");
        assertEquals(LurePhase.ACTIVE, shared.getPhase());
        assertEquals(LurePhase.NONE, abandoned.getPhase());
        assertEquals(LurePhase.NONE, remote.getPhase());
        assertFalse(shared.isCommitted(leaving.getUniqueId()));
        assertTrue(shared.getLogoutGraceUntil().isEmpty()); assertTrue(shared.getDeathOnLogin().isEmpty());
        verify(ambient, times(2)).reconcileLoaded(manager);
    }

    @Test void graceExpiresIntoDeathOnLoginAndWipeNeedsNoSurvivorsOrGrace() {
        Infestation active = lure(1, LurePhase.ACTIVE);
        UUID expired = UUID.randomUUID(), grace = UUID.randomUUID();
        active.getLogoutGraceUntil().put(expired, 10L); active.getLogoutGraceUntil().put(grace, 30L);
        call("expireGrace", active, 20L);
        assertEquals(Set.of(expired), active.getDeathOnLogin());
        assertEquals(Map.of(grace, 30L), active.getLogoutGraceUntil());
        call("checkWipe", active); assertEquals(LurePhase.ACTIVE, active.getPhase());
        active.getLogoutGraceUntil().clear(); call("checkWipe", active);
        assertEquals(LurePhase.ACTIVE, active.getPhase());
        active.getDeathOnLogin().clear();
        Player dead = player(1), offline = player(1), witness = player(1), elsewhere = player(2);
        when(dead.isDead()).thenReturn(true); when(offline.isOnline()).thenReturn(false);
        active.getCommitted().addAll(List.of(dead.getUniqueId(), offline.getUniqueId(), UUID.randomUUID()));
        call("checkWipe", active); assertEquals(LurePhase.NONE, active.getPhase());
        verify(witness).sendMessage("lure-fail"); verify(elsewhere, never()).sendMessage("lure-fail");
        call("checkWipe", active); call("failLure", active);
        verify(ambient).reconcileLoaded(manager);
    }

    @Test void spreadDisabledWrongIntervalAndActiveSourcesDoNothing() throws Exception {
        Infestation source = lure(1, LurePhase.ACTIVE);
        call("tickSpread"); assertEquals(1, manager.all().size());
        Cache.spread = true; Cache.spreadIntervalSeconds = 1;
        atTick(1); call("tickSpread"); assertEquals(1, manager.all().size());
        atTick(20); call("tickSpread"); assertEquals(Severity.MILD, source.getSeverity());
        Cache.spreadIntervalSeconds = Integer.MAX_VALUE; call("tickSpread");
        assertEquals(1, manager.all().size());
    }

    @Test void spreadWorsensAndCrossesLandWaterAndSeaUsingEasiestAvailableHop() throws Exception {
        Infestation source = infestation(1);
        Cache.spread = true; Cache.debug = true; Cache.spreadIntervalSeconds = 1; Cache.worsenChance = 1;
        Cache.spreadChanceLand = new double[]{1, 1, 1, 1};
        Cache.spreadChanceWater = new double[]{1, 1, 1, 1};
        Cache.spreadChanceSea = new double[]{1, 1, 1, 1};
        provinceApi.when(() -> Provinces.neighbours(1)).thenReturn(new LinkedHashSet<>(List.of(10, 11, 2, 12)));
        provinceApi.when(() -> Provinces.validLand(10)).thenReturn(false);
        provinceApi.when(() -> Provinces.validLand(11)).thenReturn(false);
        provinceApi.when(() -> Provinces.validLand(12)).thenReturn(false);
        provinceApi.when(() -> Provinces.isWater(10)).thenReturn(true);
        provinceApi.when(() -> Provinces.isSea(11)).thenReturn(true);
        provinceApi.when(() -> Provinces.neighbours(10)).thenReturn(new LinkedHashSet<>(List.of(1, 2, 3, 12)));
        provinceApi.when(() -> Provinces.neighbours(11)).thenReturn(Set.of(2, 4));
        groups.when(() -> GroupLoader.allowsTerrain("zombies", 3)).thenReturn(false);
        atTick(20); call("tickSpread");
        assertEquals(Severity.WORRYING, source.getSeverity());
        assertEquals(2, manager.all().size());
        assertNotNull(manager.get(2)); assertEquals(Severity.MILD, manager.get(2).getSeverity());
        verify(plugin.getLogger()).info(contains("Spread worsened"));
        verify(plugin.getLogger()).info(contains("via land"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void waterAndSeaSpreadLogTheirRoutes(boolean sea) throws Exception {
        infestation(1); Cache.spread = true; Cache.debug = true;
        Cache.spreadIntervalSeconds = 1; Cache.worsenChance = 0;
        Cache.spreadChanceWater = new double[]{1, 1, 1, 1};
        Cache.spreadChanceSea = new double[]{1, 1, 1, 1};
        provinceApi.when(() -> Provinces.neighbours(1)).thenReturn(Set.of(10));
        provinceApi.when(() -> Provinces.validLand(10)).thenReturn(false);
        provinceApi.when(() -> Provinces.isWater(10)).thenReturn(!sea);
        provinceApi.when(() -> Provinces.isSea(10)).thenReturn(sea);
        provinceApi.when(() -> Provinces.neighbours(10)).thenReturn(Set.of(2));
        atTick(20); call("tickSpread");
        assertNotNull(manager.get(2));
        verify(plugin.getLogger()).info(contains("via " + (sea ? "sea" : "water")));
    }

    @Test void spreadCanFailItsChanceOrHaveNoEligibleTarget() throws Exception {
        Infestation source = infestation(1); source.setSeverity(Severity.EXTREME);
        Cache.spread = true; Cache.debug = true; Cache.spreadIntervalSeconds = 1;
        Cache.spreadChanceLand = new double[]{0, 0, 0, 0};
        provinceApi.when(() -> Provinces.neighbours(1)).thenReturn(Set.of(2));
        atTick(20); call("tickSpread");
        assertNull(manager.get(2)); verify(plugin.getLogger()).info(contains("Spread hop failed"));
        Cache.debug = false; call("tickSpread");
        provinceApi.when(() -> Provinces.neighbours(1)).thenReturn(Set.of(1));
        call("tickSpread"); assertEquals(1, manager.all().size());
    }

    @Test void furniturePlacementRejectsUnknownProvinceAndExistingLureThenJoinsOwner() {
        Player owner = player(1), witness = player(1), elsewhere = player(2);
        Furniture f = furniture(); FurniturePlaceEvent event = mock(FurniturePlaceEvent.class);
        when(event.getFurniture()).thenReturn(f); when(event.getPlayer()).thenReturn(owner);
        manager.onFurniturePlace(event); verify(event).setCancelled(true);
        verify(owner).sendMessage("no-infestation-here");
        clearInvocations(event);
        Infestation state = infestation(1);
        provinceApi.when(() -> Provinces.validLand(1)).thenReturn(false);
        manager.onFurniturePlace(event); verify(event).setCancelled(true);
        provinceApi.when(() -> Provinces.validLand(1)).thenReturn(true);
        clearInvocations(event); manager.onFurniturePlace(event); verify(event, never()).setCancelled(true);
        manager.onFurniturePlaceDone(event);
        assertEquals(LurePhase.JOINING, state.getPhase()); assertTrue(state.isCommitted(owner.getUniqueId()));
        assertEquals(20, state.getLureRemaining());
        verify(owner).sendMessage("lure-join"); verify(witness).sendMessage("lure-placed");
        verify(elsewhere, never()).sendMessage("lure-placed");
        manager.onFurniturePlace(event); verify(owner).sendMessage("lure-already");
        manager.onFurniturePlaceDone(event); verify(owner, times(1)).sendMessage("lure-join");
    }

    @Test void furniturePlacementWithoutPlayerAndIrrelevantFurnitureAreSafe() {
        FurniturePlaceEvent event = mock(FurniturePlaceEvent.class);
        manager.onFurniturePlace(event); manager.onFurniturePlaceDone(event);
        Furniture f = furniture(); when(event.getFurniture()).thenReturn(f);
        manager.onFurniturePlace(event); verify(event).setCancelled(true);
        manager.onFurniturePlaceDone(event);
        Infestation state = infestation(1); manager.onFurniturePlaceDone(event);
        assertEquals(LurePhase.JOINING, state.getPhase()); assertTrue(state.getCommitted().isEmpty());
        manager.onFurniturePlace(event); verify(event, times(2)).setCancelled(true);
        when(f.getLoc()).thenReturn(null); manager.onFurniturePlaceDone(event);
    }

    @Test void furnitureBreakAndInteractionRemoveOrphansButProtectActiveLures() {
        Furniture f = furniture(); FurnitureBreakEvent broken = mock(FurnitureBreakEvent.class);
        when(broken.getFurniture()).thenReturn(f);
        manager.onFurnitureBreak(broken); verify(broken).setCancelled(true);
        furniture.verify(() -> LureFurniture.remove(f));
        Infestation active = lure(1, LurePhase.JOINING);
        furniture.when(() -> LureFurniture.matches(active, f)).thenReturn(true);
        manager.onFurnitureBreak(broken); furniture.verify(() -> LureFurniture.remove(f), times(1));
        FurnitureInteractEvent interact = mock(FurnitureInteractEvent.class);
        when(interact.getFurniture()).thenReturn(f); Player joiner = player(1);
        when(interact.getPlayer()).thenReturn(joiner);
        manager.onFurnitureInteract(interact); verify(interact).setCancelled(true);
        assertTrue(active.isCommitted(joiner.getUniqueId()));
        manager.onFurnitureInteract(interact); verify(joiner).sendMessage("lure-already-joined");
        furniture.when(() -> LureFurniture.matches(active, f)).thenReturn(false);
        manager.onFurnitureInteract(interact); furniture.verify(() -> LureFurniture.remove(f), times(2));
        when(interact.getFurniture()).thenReturn(null); manager.onFurnitureInteract(interact);
        when(broken.getFurniture()).thenReturn(null); manager.onFurnitureBreak(broken);
    }

    @Test void pluginFurnitureCleanupDoesNotRecursivelyCancelBreakEvents() {
        Infestation active = lure(1, LurePhase.ACTIVE);
        Furniture f = furniture(); FurnitureBreakEvent broken = mock(FurnitureBreakEvent.class);
        when(broken.getFurniture()).thenReturn(f);
        furniture.when(() -> LureFurniture.removeFor(active)).thenAnswer(i -> { manager.onFurnitureBreak(broken); return null; });
        manager.clear(1, false);
        verify(broken, never()).setCancelled(true);
        manager.onFurnitureBreak(broken); verify(broken).setCancelled(true);
    }

    @Test void activeLureClickHighlightsTaggedMobsAndDebouncesMessageWhileJoiningPlayer() {
        Infestation active = lure(1, LurePhase.ACTIVE); Player joiner = player(1);
        LivingEntity tagged = mob(1, Keys.KIND_LURE);
        mob(2, Keys.KIND_LURE); mob(null, null); mob(1, Keys.KIND_AMBIENT); entities.add(joiner);
        call("handleLureClick", joiner, active);
        assertTrue(active.isCommitted(joiner.getUniqueId()));
        verify(tagged).addPotionEffect(any(PotionEffect.class));
        verify(joiner).sendMessage("lure-highlight");
        call("handleLureClick", joiner, active); verify(joiner, times(1)).sendMessage("lure-highlight");
        call("handleLureClick", null, active);
        Infestation absent = lure(2, LurePhase.ACTIVE); absent.setWorldName("missing");
        assertEquals(0, call("highlightLureMobs", absent));
        Player spectator = player(1); when(spectator.getGameMode()).thenReturn(GameMode.SPECTATOR);
        call("tryJoin", spectator, active); assertFalse(active.isCommitted(spectator.getUniqueId()));
    }

    @Test void vanillaBlockProtectionCoversBreakExplosionsAndBothPistons() {
        lure(1, LurePhase.ACTIVE); Block ordinary = block(9, 64, 9);
        BlockBreakEvent breaking = mock(BlockBreakEvent.class);
        when(breaking.getBlock()).thenReturn(lureBlock); manager.onBreak(breaking); verify(breaking).setCancelled(true);
        when(breaking.getBlock()).thenReturn(ordinary); clearInvocations(breaking); manager.onBreak(breaking);
        verify(breaking, never()).setCancelled(true);
        BlockExplodeEvent blocks = mock(BlockExplodeEvent.class); EntityExplodeEvent entity = mock(EntityExplodeEvent.class);
        List<Block> first = new ArrayList<>(Arrays.asList(lureBlock, ordinary, null));
        List<Block> second = new ArrayList<>(List.of(ordinary, lureBlock));
        when(blocks.blockList()).thenReturn(first); when(entity.blockList()).thenReturn(second);
        manager.onExplode(blocks); manager.onExplode(entity);
        assertEquals(Arrays.asList(ordinary, null), first); assertEquals(List.of(ordinary), second);
        BlockPistonExtendEvent extend = mock(BlockPistonExtendEvent.class);
        BlockPistonRetractEvent retract = mock(BlockPistonRetractEvent.class);
        when(extend.getBlocks()).thenReturn(List.of(lureBlock)); when(retract.getBlocks()).thenReturn(List.of(lureBlock));
        manager.onPiston(extend); manager.onPiston(retract);
        verify(extend).setCancelled(true); verify(retract).setCancelled(true);
        when(extend.getBlocks()).thenReturn(List.of(ordinary)); when(retract.getBlocks()).thenReturn(List.of(ordinary));
        clearInvocations(extend, retract); manager.onPiston(extend); manager.onPiston(retract);
        verify(extend, never()).setCancelled(true); verify(retract, never()).setCancelled(true);
    }

    @Test void vanillaPlacementRecognizesLureItemThroughItemApi() {
        scoped(mockStatic(TLibs.class, RETURNS_DEEP_STUBS));
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        manager.onPlace(event); verify(event, never()).setCancelled(true);
        ItemStack air = mock(ItemStack.class); when(air.getType()).thenReturn(Material.AIR);
        when(event.getItemInHand()).thenReturn(air); manager.onPlace(event); verify(event, never()).setCancelled(true);
        ItemStack item = mock(ItemStack.class); when(item.getType()).thenReturn(Material.STONE);
        when(event.getItemInHand()).thenReturn(item); manager.onPlace(event); verify(event, never()).setCancelled(true);
        when(TLibs.getItemAPI().getChecker().checkItemWithPath(item, Cache.lureItem)).thenReturn(true);
        manager.onPlace(event); verify(event).setCancelled(true);
    }

    @Test void vanillaInteractionRequiresMainHandAndFindsLureAboveClickedBlock() {
        Infestation joining = lure(1, LurePhase.JOINING); Player p = player(1);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(p); when(event.getAction()).thenReturn(Action.LEFT_CLICK_BLOCK);
        manager.onInteract(event); verify(event, never()).setCancelled(true);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK); manager.onInteract(event);
        when(event.getClickedBlock()).thenReturn(lureBlock); when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        manager.onInteract(event); verify(event, never()).setCancelled(true);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND); manager.onInteract(event);
        assertTrue(joining.isCommitted(p.getUniqueId())); verify(event).setCancelled(true);
        Block below = block(5, 63, 5); when(below.getRelative(BlockFace.UP)).thenReturn(lureBlock);
        when(event.getClickedBlock()).thenReturn(below); manager.onInteract(event);
        verify(p).sendMessage("lure-already-joined");
        doReturn(block(9, 64, 9)).when(event).getClickedBlock(); manager.onInteract(event);
        joining.setPhase(LurePhase.NONE); when(event.getClickedBlock()).thenReturn(lureBlock);
        manager.onInteract(event); verify(event, times(2)).setCancelled(true);
    }

    @Test void mobDeathsAdjustOnlyMatchingAmbientAndActiveLureTallies() {
        Infestation state = lure(1, LurePhase.ACTIVE); state.setAmbientAlive(1); state.setEnemiesAlive(1);
        EntityDeathEvent death = mock(EntityDeathEvent.class);
        LivingEntity ambientMob = mob(1, Keys.KIND_AMBIENT), lureMob = mob(1, Keys.KIND_LURE);
        when(death.getEntity()).thenReturn(ambientMob); manager.onDeath(death); manager.onDeath(death);
        assertEquals(0, state.getAmbientAlive());
        when(death.getEntity()).thenReturn(lureMob); manager.onDeath(death); manager.onDeath(death);
        assertEquals(0, state.getEnemiesAlive()); assertEquals(18, state.getLureRemaining());
        state.setPhase(LurePhase.JOINING); manager.onDeath(death); assertEquals(18, state.getLureRemaining());
        doReturn(mob(1, "other")).when(death).getEntity(); manager.onDeath(death);
        doReturn(mob(99, Keys.KIND_LURE)).when(death).getEntity(); manager.onDeath(death);
        doReturn(mob(null, null)).when(death).getEntity(); manager.onDeath(death);
        doReturn(mob(1, null)).when(death).getEntity(); manager.onDeath(death);
        assertEquals(18, state.getLureRemaining());
    }

    @Test void playerDeathRemovesCommitmentAndFailsActiveParty() {
        Infestation active = lure(1, LurePhase.ACTIVE); Infestation idle = infestation(2); infestation(3);
        Player p = player(1); active.getCommitted().add(p.getUniqueId()); idle.getCommitted().add(p.getUniqueId());
        active.getLogoutGraceUntil().put(p.getUniqueId(), Long.MAX_VALUE); active.getDeathOnLogin().add(p.getUniqueId());
        EntityDeathEvent death = mock(EntityDeathEvent.class); when(death.getEntity()).thenReturn(p);
        manager.onDeath(death);
        assertFalse(active.isCommitted(p.getUniqueId())); assertFalse(idle.isCommitted(p.getUniqueId()));
        assertEquals(LurePhase.NONE, active.getPhase());
    }

    @Test void logoutPersistsGraceAndLoginOnlyPunishesCommittedLivingExpiredPlayers() {
        Infestation state = lure(1, LurePhase.ACTIVE); infestation(2);
        Player p = player(1); state.getCommitted().add(p.getUniqueId());
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(p);
        manager.onQuit(quit); assertTrue(state.getLogoutGraceUntil().get(p.getUniqueId()) > System.currentTimeMillis());
        PlayerJoinEvent join = mock(PlayerJoinEvent.class); when(join.getPlayer()).thenReturn(p);
        manager.onJoin(join); runLater();
        assertTrue(state.getLogoutGraceUntil().isEmpty()); verify(p, never()).damage(anyDouble());
        state.getDeathOnLogin().add(p.getUniqueId()); manager.onJoin(join); runLater(); verify(p).damage(40.0);
        state.getDeathOnLogin().add(p.getUniqueId()); when(p.isDead()).thenReturn(true);
        manager.onJoin(join); runLater(); verify(p, times(1)).damage(anyDouble());
        state.getCommitted().clear(); state.getDeathOnLogin().add(p.getUniqueId()); when(p.isDead()).thenReturn(false);
        manager.onJoin(join); runLater(); verify(p, times(1)).damage(anyDouble());
        when(p.isOnline()).thenReturn(false); manager.onJoin(join); runLater(); verify(p, times(1)).damage(anyDouble());
    }

    @Test void provinceEventsWarnOnlyRelevantParticipants() {
        Player p = player(1); PlayerProvinceLeaveEvent leave = mock(PlayerProvinceLeaveEvent.class);
        when(leave.getPlayer()).thenReturn(p); when(leave.getProvinceId()).thenReturn(1);
        manager.onLeaveProvince(leave); Infestation state = infestation(1); manager.onLeaveProvince(leave);
        state.setPhase(LurePhase.JOINING); manager.onLeaveProvince(leave);
        state.getCommitted().add(p.getUniqueId());
        when(p.getGameMode()).thenReturn(GameMode.CREATIVE); manager.onLeaveProvince(leave);
        when(p.getGameMode()).thenReturn(GameMode.SPECTATOR); manager.onLeaveProvince(leave);
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL); manager.onLeaveProvince(leave);
        verify(p).sendMessage("lure-outside");
        PlayerProvinceEnterEvent enter = mock(PlayerProvinceEnterEvent.class);
        when(enter.getPlayer()).thenReturn(p); when(enter.getProvinceId()).thenReturn(1);
        manager.onEnterProvince(enter); verify(p).sendMessage("lure-leave-or-join");
        state.setPhase(LurePhase.ACTIVE); manager.onEnterProvince(enter); verify(p, never()).sendMessage("lure-nonjoiner");
        state.getCommitted().clear(); manager.onEnterProvince(enter); verify(p).sendMessage("lure-nonjoiner");
        when(enter.getProvinceId()).thenReturn(99); manager.onEnterProvince(enter);
    }

    @Test void chunkCallbacksSweepOrphansRefreshMatchingLureAndMarkTaggedEntityChanges() {
        Infestation matching = lure(1, LurePhase.JOINING); infestation(2);
        Infestation missingWorld = lure(3, LurePhase.ACTIVE); missingWorld.setWorldName("missing");
        Infestation otherChunk = lure(4, LurePhase.ACTIVE);
        otherChunk.setLureX(6); Block otherBlock = block(6, 64, 5); when(otherBlock.getChunk()).thenReturn(mock(Chunk.class));
        when(world.getBlockAt(6, 64, 5)).thenReturn(otherBlock);
        ChunkUnloadEvent unload = mock(ChunkUnloadEvent.class); when(unload.getChunk()).thenReturn(chunk);
        manager.onChunkUnload(unload); hologram.verify(() -> LureHologram.removeOrphans(chunk));
        ChunkLoadEvent load = mock(ChunkLoadEvent.class); when(load.getChunk()).thenReturn(chunk);
        manager.onChunkLoad(load); runLater();
        hologram.verify(() -> LureHologram.tick(matching));
        hologram.verify(() -> LureHologram.tick(otherChunk), never());
        EntitiesLoadEvent loaded = mock(EntitiesLoadEvent.class); EntitiesUnloadEvent unloaded = mock(EntitiesUnloadEvent.class);
        List<Entity> unrelated = List.of(mock(Entity.class), player(1), mob(null, null));
        when(loaded.getEntities()).thenReturn(unrelated); when(unloaded.getEntities()).thenReturn(unrelated);
        manager.onEntitiesLoad(loaded); manager.onEntitiesUnload(unloaded); verify(ambient, never()).markDirty();
        LivingEntity tagged = mob(1, Keys.KIND_AMBIENT);
        when(loaded.getEntities()).thenReturn(List.of(tagged)); when(unloaded.getEntities()).thenReturn(List.of(tagged));
        manager.onEntitiesLoad(loaded); manager.onEntitiesUnload(unloaded); verify(ambient, times(2)).markDirty();
    }

    @Test void mythicDeathSummonsInheritTagsAndExpiredParentsAreForgotten() throws Exception {
        Infestation state = lure(1, LurePhase.ACTIVE);
        MythicMobSpawnEvent spawn = mock(MythicMobSpawnEvent.class);
        ActiveMob activeMob = mock(ActiveMob.class, RETURNS_DEEP_STUBS); when(spawn.getMob()).thenReturn(activeMob);
        manager.onMythicSpawn(spawn); assertTrue(later.isEmpty());
        LivingEntity parent = mob(1, Keys.KIND_LURE); EntityDeathEvent death = mock(EntityDeathEvent.class);
        when(death.getEntity()).thenReturn(parent); manager.onDeath(death);
        LivingEntity child = mob(null, null);
        doReturn(Optl.of(parent.getUniqueId())).when(activeMob).getParentUUID();
        when(activeMob.getEntity().getBukkitEntity()).thenReturn(child);
        manager.onMythicSpawn(spawn); runLater();
        assertEquals(1, tags.get(child.getPersistentDataContainer()));
        assertEquals(Keys.KIND_LURE, kinds.get(child.getPersistentDataContainer()));
        assertEquals(1, state.getEnemiesAlive()); assertEquals(20, state.getLureRemaining());
        manager.onMythicSpawn(spawn); runLater(); assertEquals(1, state.getEnemiesAlive());
        state.setPhase(LurePhase.NONE); atTick(119); tick.run();
        LivingEntity lateChild = mob(null, null); when(activeMob.getEntity().getBukkitEntity()).thenReturn(lateChild);
        manager.onMythicSpawn(spawn); assertTrue(later.isEmpty());
        assertNull(tags.get(lateChild.getPersistentDataContainer()));
    }

    @Test void ambientSummonsAndInvalidSummonCandidatesDoNotCorruptTallies() {
        Infestation state = infestation(1);
        LivingEntity parent = mob(1, Keys.KIND_AMBIENT); EntityDeathEvent death = mock(EntityDeathEvent.class);
        when(death.getEntity()).thenReturn(parent); manager.onDeath(death);
        ActiveMob activeMob = mock(ActiveMob.class, RETURNS_DEEP_STUBS);
        LivingEntity child = mob(null, null); when(activeMob.getEntity().getBukkitEntity()).thenReturn(child);
        when(activeMob.getParentUUID()).thenReturn(Optl.empty()); call("adoptSummon", activeMob);
        assertEquals(0, state.getAmbientAlive());
        when(activeMob.getParentUUID()).thenReturn(Optl.of(UUID.randomUUID())); call("adoptSummon", activeMob);
        doReturn(Optl.of(parent.getUniqueId())).when(activeMob).getParentUUID();
        when(child.isDead()).thenReturn(true); call("adoptSummon", activeMob); assertEquals(0, state.getAmbientAlive());
        when(child.isDead()).thenReturn(false); call("adoptSummon", activeMob);
        assertEquals(1, state.getAmbientAlive()); assertEquals(Keys.KIND_AMBIENT, kinds.get(child.getPersistentDataContainer()));
        tags.clear(); state.setPhase(LurePhase.JOINING); call("adoptSummon", activeMob); assertTrue(tags.isEmpty());
        when(activeMob.getEntity().getBukkitEntity()).thenReturn(mock(Entity.class)); call("adoptSummon", activeMob);
        when(activeMob.getEntity()).thenReturn(null); call("adoptSummon", activeMob);
    }

    @ParameterizedTest @ValueSource(strings = {"world", "y", "z", "idle"})
    void reloadRemovesLuresWhenWorldVerticalCoordinateOrPhaseChanges(String change) {
        Infestation old = lure(1, LurePhase.ACTIVE);
        Infestation replacement = new Infestation(1, "zombies", Severity.MILD);
        replacement.placeLure(lureBlock, Long.MAX_VALUE, 20);
        switch (change) {
            case "world" -> replacement.setWorldName("other");
            case "y" -> replacement.setLureY(65);
            case "z" -> replacement.setLureZ(6);
            case "idle" -> replacement.clearLure();
        }
        database.when(InfestationDatabase::load).thenReturn(List.of(replacement));
        manager.reloadFromDisk();
        furniture.verify(() -> LureFurniture.removeFor(old));
        assertSame(replacement, manager.get(1));
    }

    @Test void tickWaitsForJoinDeadlineThenSkipsFurtherWorkAfterVictory() throws Exception {
        Infestation state = lure(1, LurePhase.JOINING);
        tick.run(); assertEquals(LurePhase.JOINING, state.getPhase());
        state.setPhase(LurePhase.ACTIVE); state.setLureRemaining(0); state.setVictoryAtTick(2);
        tick.run(); assertNull(manager.get(1));
        hologram.verify(() -> LureHologram.tick(state), times(1));
        Infestation another = lure(2, LurePhase.ACTIVE);
        another.getCommitted().add(player(2).getUniqueId());
        tick.run(); assertEquals(LurePhase.ACTIVE, another.getPhase());
        atTick(19); tick.run();
        assertNotNull(manager.get(2));
    }

    @Test void spreadFloatingPointRoundingStillSelectsTheLastCandidate() throws Exception {
        // At the greatest representable roll below 1, subtracting these finite
        // weights leaves a positive rounding residue after the final candidate.
        Infestation source = infestation(1); source.setSeverity(Severity.EXTREME);
        Cache.spread = true; Cache.debug = false; Cache.spreadIntervalSeconds = 1;
        Cache.spreadChanceLand = new double[]{.6, .6, .6, .6};
        Cache.spreadChanceWater = new double[]{1, 1, 1, 1};
        Cache.spreadChanceSea = new double[]{.00015, .00015, .00015, .00015};
        provinceApi.when(() -> Provinces.neighbours(1)).thenReturn(Set.of(2, 10, 11));
        provinceApi.when(() -> Provinces.validLand(10)).thenReturn(false);
        provinceApi.when(() -> Provinces.validLand(11)).thenReturn(false);
        provinceApi.when(() -> Provinces.isWater(10)).thenReturn(true);
        provinceApi.when(() -> Provinces.isSea(11)).thenReturn(true);
        provinceApi.when(() -> Provinces.neighbours(10)).thenReturn(Set.of(3));
        provinceApi.when(() -> Provinces.neighbours(11)).thenReturn(Set.of(4));
        when(random.nextDouble()).thenReturn(Math.nextDown(1.0), 0.0);
        atTick(20); call("tickSpread");
        assertNotNull(manager.get(4)); assertNull(manager.get(2)); assertNull(manager.get(3));
    }

    @Test void worseningWithoutDebugStillPersistsAndUpdatesTheMap() throws Exception {
        Infestation source = infestation(1); Cache.spread = true; Cache.worsenChance = 1;
        Cache.spreadIntervalSeconds = 1; Cache.debug = false;
        atTick(20); call("tickSpread");
        assertEquals(Severity.WORRYING, source.getSeverity());
        export.verify(() -> InfestationMapExport.exportAsync(anyCollection()), atLeastOnce());
    }

    @Test void leaveIdleCommitmentAndFailWithoutLoadedWorldStillNotifyRemoteMembers() {
        Player leaving = player(7); Infestation idle = infestation(1);
        idle.getCommitted().add(leaving.getUniqueId());
        assertTrue(manager.leaveLure(leaving)); assertEquals(LurePhase.NONE, idle.getPhase());
        Infestation missingWorld = lure(2, LurePhase.ACTIVE); missingWorld.setWorldName("missing");
        missingWorld.getCommitted().add(leaving.getUniqueId());
        call("failLure", missingWorld);
        verify(leaving).sendMessage("lure-fail"); assertEquals(LurePhase.NONE, missingWorld.getPhase());
        Infestation victory = lure(3, LurePhase.ACTIVE); victory.setWorldName("missing");
        call("victory", victory); assertNull(manager.get(3));
        verify(world, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
    }

    @Test void highlightMessagesRefreshOnNewTickOrDifferentProvince() throws Exception {
        Player p = player(1); Infestation first = lure(1, LurePhase.ACTIVE), second = lure(2, LurePhase.ACTIVE);
        call("handleLureClick", p, first); call("handleLureClick", p, second);
        atTick(1); call("handleLureClick", p, second);
        verify(p, times(3)).sendMessage("lure-highlight");
    }

    @Test void orphanFurnitureWithMissingLocationOrInfestationIsRemoved() {
        Furniture f = furniture(); when(f.getLoc()).thenReturn(null);
        FurnitureBreakEvent broken = mock(FurnitureBreakEvent.class); when(broken.getFurniture()).thenReturn(f);
        manager.onFurnitureBreak(broken); furniture.verify(() -> LureFurniture.remove(f));
        FurnitureInteractEvent event = mock(FurnitureInteractEvent.class); when(event.getFurniture()).thenReturn(f);
        manager.onFurnitureInteract(event); furniture.verify(() -> LureFurniture.remove(f), times(2));
    }

    @Test void summonAdoptionRechecksCurrentInfestationPhaseAndParentKind() throws Exception {
        Infestation state = infestation(1); LivingEntity parent = mob(1, Keys.KIND_LURE);
        EntityDeathEvent death = mock(EntityDeathEvent.class); when(death.getEntity()).thenReturn(parent);
        manager.onDeath(death);
        ActiveMob child = mock(ActiveMob.class, RETURNS_DEEP_STUBS); LivingEntity living = mob(null, null);
        doReturn(Optl.of(parent.getUniqueId())).when(child).getParentUUID();
        when(child.getEntity().getBukkitEntity()).thenReturn(living);
        call("adoptSummon", child); assertFalse(tags.containsKey(living.getPersistentDataContainer()));
        state.setPhase(LurePhase.ACTIVE);
        kinds.put(parent.getPersistentDataContainer(), Keys.KIND_AMBIENT); manager.onDeath(death);
        call("adoptSummon", child); assertEquals(0, state.getEnemiesAlive());
        state.setPhase(LurePhase.NONE); atTick(19); tick.run();
        call("adoptSummon", child); assertEquals(1, state.getAmbientAlive());
        tags.remove(living.getPersistentDataContainer()); manager.clear(1, false);
        call("adoptSummon", child); assertFalse(tags.containsKey(living.getPersistentDataContainer()));
    }

    @Test void breakingMismatchedFurnitureRemovesOnlyTheOrphan() {
        Infestation active = lure(1, LurePhase.ACTIVE);
        Furniture f = furniture();
        furniture.when(() -> LureFurniture.matches(active, f)).thenReturn(false);
        FurnitureBreakEvent event = mock(FurnitureBreakEvent.class);
        when(event.getFurniture()).thenReturn(f);
        manager.onFurnitureBreak(event);
        verify(event).setCancelled(true);
        furniture.verify(() -> LureFurniture.remove(f));
        assertSame(active, manager.get(1));
    }
}

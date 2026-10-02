package net.tfminecraft.infestations.spawn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;

import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.infestation.*;
import net.tfminecraft.infestations.loader.GroupLoader;
import net.tfminecraft.infestations.loader.GroupLoader.GroupTune;
import net.tfminecraft.infestations.utils.Provinces;

class SpawnServicesTest {
    private final List<AutoCloseable> mocks = new ArrayList<>();
    private final List<Player> players = new ArrayList<>();
    private final List<LivingEntity> entities = new ArrayList<>();
    private final List<Runnable> callbacks = new ArrayList<>();
    private final Map<Integer, Infestation> infestations = new HashMap<>();
    private final Map<List<Integer>, Material> blocks = new HashMap<>();
    private Infestations previous;
    private int previousDistance;
    private Infestations plugin;
    private InfestationManager manager;
    private World world;
    private Location origin;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<GroupLoader> groups;
    private MockedStatic<Provinces> provinces;
    private MockedStatic<SpawnLog> log;
    private ThreadLocalRandom random;
    private AmbientSpawnService ambient;
    private AmbientSpawnService.CollectionInfestations collection;
    private int floorY;

    @BeforeAll static void initializeRegistries() { MockBukkit.mock(); }
    @AfterAll static void releaseRegistries() { MockBukkit.unmock(); }
    @BeforeEach void setup() {
        previous = Infestations.plugin; previousDistance = Cache.minPlayerDistance; Cache.minPlayerDistance = 16;
        plugin = mock(Infestations.class); when(plugin.getName()).thenReturn("Infestations"); when(plugin.namespace()).thenReturn("infestations");
        when(plugin.getLogger()).thenReturn(mock(Logger.class)); Infestations.plugin = plugin;
        manager = mock(InfestationManager.class); when(plugin.getInfestationManager()).thenReturn(manager);
        when(manager.get(anyInt())).thenAnswer(i -> infestations.get(i.getArgument(0)));
        bukkit = scoped(mockStatic(Bukkit.class));
        groups = scoped(mockStatic(GroupLoader.class)); provinces = scoped(mockStatic(Provinces.class));
        log = scoped(mockStatic(SpawnLog.class));
        MockedStatic<ThreadLocalRandom> randomness = scoped(mockStatic(ThreadLocalRandom.class));
        random = mock(ThreadLocalRandom.class); randomness.when(ThreadLocalRandom::current).thenReturn(random);
        when(random.nextInt(20, 61)).thenReturn(25);
        world = mock(World.class); when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getPlayers()).thenReturn(players); when(world.getLivingEntities()).thenReturn(entities);
        floorY = 63;
        Map<Material, Block> materials = new EnumMap<>(Material.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(i -> {
            int x = i.getArgument(0), y = i.getArgument(1), z = i.getArgument(2);
            Material type = blocks.getOrDefault(List.of(x, y, z), y <= floorY ? Material.STONE : Material.AIR);
            return materials.computeIfAbsent(type, value -> {
                Block block = mock(Block.class); when(block.getType()).thenReturn(value); return block;
            });
        });
        origin = new Location(world, 0, 64, 0);
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(players);
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(i -> players.stream()
                .filter(p -> p.getUniqueId().equals(i.getArgument(0))).findFirst().orElse(null));
        BukkitScheduler scheduler = mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(i -> {
            callbacks.add(i.getArgument(1)); return mock(BukkitTask.class);
        });
        groups.when(() -> GroupLoader.tune(anyString(), any())).thenReturn(GroupTune.defaults(Severity.MILD));
        groups.when(() -> GroupLoader.allowsY(anyString(), anyInt())).thenReturn(true);
        provinces.when(() -> Provinces.at(any(Player.class))).thenReturn(1);
        provinces.when(() -> Provinces.at(any(Location.class))).thenReturn(1);
        ambient = new AmbientSpawnService();
        collection = new AmbientSpawnService.CollectionInfestations() {
            public Infestation get(int id) { return infestations.get(id); }
            public Iterable<Infestation> all() { return infestations.values(); }
        };
    }
    @AfterEach void cleanup() throws Exception {
        Collections.reverse(mocks); for (AutoCloseable mock : mocks) mock.close();
        Infestations.plugin = previous; Cache.minPlayerDistance = previousDistance;
    }
    private <T extends AutoCloseable> T scoped(T mock) { mocks.add(mock); return mock; }
    private Infestation infestation(int id) {
        Infestation state = new Infestation(id, "zombies", Severity.MILD); infestations.put(id, state); return state;
    }
    private Player player() {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getName()).thenReturn("Explorer"); when(p.isOnline()).thenReturn(true);
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL); when(p.getWorld()).thenReturn(world);
        when(p.getLocation()).thenReturn(origin); players.add(p); return p;
    }
    private LivingEntity mob(Integer id, String kind) {
        LivingEntity mob = mock(LivingEntity.class); PersistentDataContainerMock pdc = new PersistentDataContainerMock();
        when(mob.getPersistentDataContainer()).thenReturn(pdc);
        if (id != null) Keys.tagMob(pdc, id, kind);
        entities.add(mob); return mob;
    }
    private MockedStatic<SpawnPlanner> planner(Location spot) {
        MockedStatic<SpawnPlanner> planner = scoped(mockStatic(SpawnPlanner.class));
        planner.when(() -> SpawnPlanner.find(any(), anyInt(), anyInt(), anyInt(), any())).thenReturn(List.of(spot));
        planner.when(() -> SpawnPlanner.clearOfPlayers(any(), anyDouble())).thenReturn(true); return planner;
    }
    private void runCallbacks() { List<Runnable> pending = List.copyOf(callbacks); callbacks.clear(); pending.forEach(Runnable::run); }

    @Test void keysRoundTripIndependentMobAndDisplayTags() {
        PersistentDataContainerMock pdc = new PersistentDataContainerMock();
        assertNull(Keys.infestationId(pdc)); assertNull(Keys.kind(pdc)); assertNull(Keys.displayProvince(pdc));
        Keys.tagMob(pdc, 7, Keys.KIND_AMBIENT); Keys.tagDisplay(pdc, 11);
        assertEquals(7, Keys.infestationId(pdc)); assertEquals(Keys.KIND_AMBIENT, Keys.kind(pdc));
        assertEquals(11, Keys.displayProvince(pdc));
        assertEquals("infestations", Keys.infestationId().getNamespace());
        assertNotEquals(Keys.infestationId(), Keys.displayProvince());
    }

    @Test void plannerRejectsMissingOriginsZeroRequestsAndUnloadedAreas() {
        assertTrue(SpawnPlanner.find(null, 4, 8, 1, null).isEmpty());
        assertTrue(SpawnPlanner.find(origin, 4, 8, 0, null).isEmpty());
        assertTrue(SpawnPlanner.find(new Location(null, 0, 64, 0), 4, 8, 1, null).isEmpty());
        assertNull(SpawnPlanner.anchor(null)); assertNull(SpawnPlanner.anchor(new Location(null, 0, 0, 0)));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        assertTrue(SpawnPlanner.find(origin, 4, 8, 1, null).isEmpty());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        assertEquals(origin.clone().add(0, 1, 0), SpawnPlanner.anchor(origin));
    }

    @Test void plannerFindsCenteredStrictAndLooseSpotsAndEnforcesSpacingAndExtraPredicate() {
        Location expected = new Location(world, 4.5, 64, .5);
        assertEquals(List.of(expected), SpawnPlanner.find(origin, 4, 4, 1, null));
        assertEquals(List.of(expected), SpawnPlanner.findLoose(origin, 4, 4, 1, location -> location.getBlockY() == 64));
        assertEquals(1, SpawnPlanner.find(origin, 4, 4, 2, null).size());
        assertTrue(SpawnPlanner.find(origin, 4, 4, 1, ignored -> false).isEmpty());
        when(random.nextDouble()).thenReturn(0.0, 0.0, .5, 0.0);
        List<Location> pair = SpawnPlanner.find(origin, 4, 4, 2, null);
        assertEquals(2, pair.size()); assertTrue(pair.get(0).distanceSquared(pair.get(1)) >= 4);
    }

    @Test void strictPlannerRequiresClearVolumeAndSolidFloorWhileLooseNeedsOnlyOneColumn() {
        blocks.put(List.of(5, 65, 0), Material.STONE);
        assertTrue(SpawnPlanner.find(origin, 4, 4, 1, null).isEmpty());
        assertEquals(1, SpawnPlanner.findLoose(origin, 4, 4, 1, null).size());
        blocks.clear(); blocks.put(List.of(5, 63, 0), Material.AIR);
        assertTrue(SpawnPlanner.find(origin, 4, 4, 1, null).isEmpty());
        assertEquals(1, SpawnPlanner.findLoose(origin, 4, 4, 1, null).size());
        blocks.clear(); floorY = -100;
        assertTrue(SpawnPlanner.find(origin, 4, 4, 1, null).isEmpty());
    }

    @Test void plannerRejectsWorldHeightViolationsAndAnchorSearchesAboveBelowAndNearby() {
        assertEquals(new Location(world, .5, 64, .5), SpawnPlanner.anchor(origin));
        floorY = 64; assertEquals(new Location(world, .5, 65, .5), SpawnPlanner.anchor(origin));
        floorY = -100;
        blocks.put(List.of(1, 64, 0), Material.STONE);
        assertEquals(new Location(world, 1.5, 65, .5), SpawnPlanner.anchor(origin));
        blocks.clear(); assertEquals(origin.clone().add(0, 1, 0), SpawnPlanner.anchor(origin));
        assertTrue(SpawnPlanner.find(new Location(world, 0, -100, 0), 0, 0, 1, null).isEmpty());
        assertTrue(SpawnPlanner.findLoose(new Location(world, 0, 400, 0), 0, 0, 1, null).isEmpty());
        assertEquals(new Location(world, 0, 401, 0), SpawnPlanner.anchor(new Location(world, 0, 400, 0)));
    }

    @Test void proximityIncludesCreativePlayersExemptsSpectatorsAndAllowsExactBoundary() {
        assertTrue(SpawnPlanner.clearOfPlayers(null, 10));
        assertTrue(SpawnPlanner.clearOfPlayers(new Location(null, 0, 0, 0), 10));
        assertTrue(SpawnPlanner.clearOfPlayers(origin, 0));
        assertTrue(SpawnPlanner.clearOfPlayers(origin, 10));
        Player p = player(); assertFalse(SpawnPlanner.clearOfPlayers(origin, 10));
        when(p.getGameMode()).thenReturn(GameMode.CREATIVE); assertFalse(SpawnPlanner.clearOfPlayers(origin, 10));
        when(p.getGameMode()).thenReturn(GameMode.SPECTATOR); assertTrue(SpawnPlanner.clearOfPlayers(origin, 10));
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(p.getLocation()).thenReturn(origin.clone().add(10, 0, 0)); assertTrue(SpawnPlanner.clearOfPlayers(origin, 10));
    }

    @Test void ambientTickSkipsModesAbsentLuresIntervalsNightAndCaps() {
        Player p = player(); Infestation state = infestation(1); planner(origin);
        when(p.getGameMode()).thenReturn(GameMode.CREATIVE); ambient.tick(collection, 40);
        when(p.getGameMode()).thenReturn(GameMode.SPECTATOR); ambient.tick(collection, 40);
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL); infestations.clear(); ambient.tick(collection, 40);
        infestations.put(1, state); state.setPhase(LurePhase.JOINING); ambient.tick(collection, 40);
        state.setPhase(LurePhase.NONE); ambient.tick(collection, 1); assertTrue(callbacks.isEmpty());
        groups.when(() -> GroupLoader.blockedByNight("zombies", world)).thenReturn(true);
        ambient.tick(collection, 40); log.verify(() -> SpawnLog.line(state, "Explorer", "night"));
        groups.when(() -> GroupLoader.blockedByNight("zombies", world)).thenReturn(false);
        state.setAmbientAlive(8); ambient.tick(collection, 80);
        log.verify(() -> SpawnLog.line(state, "Explorer", "cap alive=8/8")); assertTrue(callbacks.isEmpty());
        state.setAmbientAlive(0); ambient.tick(collection, 120); assertEquals(1, callbacks.size());
    }

    @Test void ambientRecountCountsOnlyMatchingLiveAmbientEntitiesAndThrottlesDirtyUpdates() {
        Infestation idle = infestation(1), lure = infestation(2); lure.setPhase(LurePhase.ACTIVE);
        idle.setAmbientAlive(8); lure.setAmbientAlive(8);
        mob(1, Keys.KIND_AMBIENT); mob(1, Keys.KIND_AMBIENT); mob(1, Keys.KIND_LURE);
        mob(2, Keys.KIND_AMBIENT); mob(99, Keys.KIND_AMBIENT); mob(null, null);
        LivingEntity dead = mob(1, Keys.KIND_AMBIENT); when(dead.isDead()).thenReturn(true);
        Player p = player(); entities.add(p); players.clear();
        ambient.reconcileLoaded(collection); assertEquals(2, idle.getAmbientAlive()); assertEquals(0, lure.getAmbientAlive());
        entities.clear(); ambient.markDirty(); ambient.tick(collection, 99); assertEquals(2, idle.getAmbientAlive());
        ambient.tick(collection, 100); assertEquals(0, idle.getAmbientAlive());
        mob(1, Keys.KIND_AMBIENT); ambient.markDirty(); ambient.tick(collection, 150); assertEquals(0, idle.getAmbientAlive());
        ambient.tick(collection, 200); assertEquals(1, idle.getAmbientAlive());
    }

    @Test void ambientPlanningClampsRoomRejectsNoSpotsAndChecksAllFilters() {
        Player p = player(); Infestation state = infestation(1); MockedStatic<SpawnPlanner> planner = planner(origin);
        state.setAmbientAlive(8); ambient.spawnAround(p, state, 3); assertTrue(callbacks.isEmpty());
        state.setAmbientAlive(0); ambient.spawnAround(p, state, 0); assertTrue(callbacks.isEmpty());
        planner.when(() -> SpawnPlanner.find(any(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(i -> {
            assertEquals(16, i.<Integer>getArgument(1)); assertEquals(24, i.<Integer>getArgument(2));
            assertEquals(8, i.<Integer>getArgument(3)); Predicate<Location> filter = i.getArgument(4);
            assertTrue(filter.test(origin));
            groups.when(() -> GroupLoader.allowsY("zombies", 64)).thenReturn(false); assertFalse(filter.test(origin));
            groups.when(() -> GroupLoader.allowsY("zombies", 64)).thenReturn(true);
            planner.when(() -> SpawnPlanner.clearOfPlayers(origin, 16)).thenReturn(false); assertFalse(filter.test(origin));
            planner.when(() -> SpawnPlanner.clearOfPlayers(origin, 16)).thenReturn(true);
            provinces.when(() -> Provinces.at(origin)).thenReturn(2); assertFalse(filter.test(origin));
            return List.of();
        });
        ambient.spawnAround(p, state, 99); assertTrue(callbacks.isEmpty());
        log.verify(() -> SpawnLog.line(state, "Explorer", "no-spot"));
    }

    @ParameterizedTest @ValueSource(strings = {"manager", "replaced", "phase", "missing-player", "offline", "left", "night", "cap", "spot", "close", "unknown", "success"})
    void delayedAmbientSpawnRevalidatesLiveStateBeforeSpawning(String condition) {
        Player p = player(); Infestation state = infestation(1);
        Location spot = new Location(world, 20, 64, 20); MockedStatic<SpawnPlanner> planner = planner(spot);
        MockedStatic<MythicSpawner> spawner = scoped(mockStatic(MythicSpawner.class));
        ambient.spawnAround(p, state, 1); assertEquals(1, callbacks.size());
        LivingEntity spawned = mock(LivingEntity.class);
        switch (condition) {
            case "manager" -> when(plugin.getInfestationManager()).thenReturn(null);
            case "replaced" -> infestations.put(1, new Infestation(1, "other", Severity.SEVERE));
            case "phase" -> state.setPhase(LurePhase.ACTIVE);
            case "missing-player" -> players.clear();
            case "offline" -> when(p.isOnline()).thenReturn(false);
            case "left" -> provinces.when(() -> Provinces.at(p)).thenReturn(2);
            case "night" -> groups.when(() -> GroupLoader.blockedByNight("zombies", world)).thenReturn(true);
            case "cap" -> state.setAmbientAlive(8);
            case "spot" -> provinces.when(() -> Provinces.at(spot)).thenReturn(2);
            case "close" -> planner.when(() -> SpawnPlanner.clearOfPlayers(spot, 16)).thenReturn(false);
            case "success" -> spawner.when(() -> MythicSpawner.spawn(spot, "zombies", 1, Keys.KIND_AMBIENT)).thenReturn(spawned);
        }
        runCallbacks();
        if (condition.equals("success")) {
            assertEquals(1, state.getAmbientAlive());
            log.verify(() -> SpawnLog.spawned(state, "Explorer", spot, 1, 8));
        } else if (condition.equals("unknown")) {
            assertEquals(0, state.getAmbientAlive()); log.verify(() -> SpawnLog.line(state, "Explorer", "unknown-mythic"));
        } else {
            spawner.verifyNoInteractions(); assertEquals(condition.equals("cap") ? 8 : 0, state.getAmbientAlive());
        }
    }

    @Test void mythicSpawnerHandlesMissingMobEntitiesAndTagsSuccessfulSpawn() {
        MockedStatic<MythicBukkit> mythicApi = scoped(mockStatic(MythicBukkit.class));
        MythicBukkit mythic = mock(MythicBukkit.class, RETURNS_DEEP_STUBS); mythicApi.when(MythicBukkit::inst).thenReturn(mythic);
        assertNull(MythicSpawner.spawn(origin, "missing", 1, Keys.KIND_AMBIENT));
        groups.when(() -> GroupLoader.pickMobId("zombies")).thenReturn("Zombie");
        assertNull(MythicSpawner.spawn(null, "zombies", 1, Keys.KIND_AMBIENT));
        when(mythic.getMobManager().getMythicMob("Zombie")).thenReturn(Optional.empty());
        assertNull(MythicSpawner.spawn(origin, "zombies", 1, Keys.KIND_AMBIENT));
        verify(plugin.getLogger()).warning(contains("Unknown Mythic mob: Zombie"));
        MythicMob definition = mock(MythicMob.class); when(mythic.getMobManager().getMythicMob("Zombie")).thenReturn(Optional.of(definition));
        MockedStatic<BukkitAdapter> adapter = scoped(mockStatic(BukkitAdapter.class));
        AbstractLocation location = mock(AbstractLocation.class); adapter.when(() -> BukkitAdapter.adapt(origin)).thenReturn(location);
        assertNull(MythicSpawner.spawn(origin, "zombies", 1, Keys.KIND_AMBIENT));
        ActiveMob active = mock(ActiveMob.class, RETURNS_DEEP_STUBS); when(definition.spawn(location, 1)).thenReturn(active);
        when(active.getEntity().getBukkitEntity()).thenReturn(mock(Entity.class));
        assertNull(MythicSpawner.spawn(origin, "zombies", 1, Keys.KIND_AMBIENT));
        LivingEntity living = mob(null, null); when(active.getEntity().getBukkitEntity()).thenReturn(living);
        assertSame(living, MythicSpawner.spawn(origin, "zombies", 7, Keys.KIND_LURE));
        assertEquals(7, Keys.infestationId(living.getPersistentDataContainer()));
        assertEquals(Keys.KIND_LURE, Keys.kind(living.getPersistentDataContainer()));
        when(active.getEntity()).thenReturn(null); assertNull(MythicSpawner.spawn(origin, "zombies", 1, Keys.KIND_AMBIENT));
    }
}

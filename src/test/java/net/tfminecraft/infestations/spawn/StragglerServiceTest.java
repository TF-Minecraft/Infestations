package net.tfminecraft.infestations.spawn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.BiPredicate;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.potion.PotionEffect;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.infestation.Infestation;
import net.tfminecraft.infestations.infestation.LurePhase;
import net.tfminecraft.infestations.utils.Provinces;
import net.tfminecraft.simplefactions.installation.Installation;
import net.tfminecraft.simplefactions.installation.InstallationLookup;

class StragglerServiceTest {
    private final List<AutoCloseable> mocks = new ArrayList<>();
    private final List<LivingEntity> entities = new ArrayList<>();
    private final List<Player> worldPlayers = new ArrayList<>();
    private final Map<Integer, Infestation> infestations = new HashMap<>();
    private final Set<Player> canSee = new HashSet<>();
    private Infestations previous;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<SpawnPlanner> planner;
    private MockedStatic<Provinces> provinces;
    private MockedStatic<SpawnLog> log;
    private World world;
    private World otherWorld;
    private Location lure;
    private StragglerService service;
    private AmbientSpawnService.CollectionInfestations collection;

    @BeforeAll static void initializeRegistries() { MockBukkit.mock(); }
    @AfterAll static void releaseRegistries() { MockBukkit.unmock(); }

    @BeforeEach void setup() {
        previous = Infestations.plugin;
        Infestations plugin = mock(Infestations.class);
        when(plugin.getName()).thenReturn("Infestations"); when(plugin.namespace()).thenReturn("infestations");
        Infestations.plugin = plugin;
        Cache.lureStragglerSeconds = 60; Cache.ambientStragglerSeconds = 120;
        world = mock(World.class); otherWorld = mock(World.class);
        when(world.getLivingEntities()).thenReturn(entities); when(world.getPlayers()).thenReturn(worldPlayers);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        lure = new Location(world, 0, 64, 0);
        bukkit = scoped(mockStatic(Bukkit.class));
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
        planner = scoped(mockStatic(SpawnPlanner.class));
        provinces = scoped(mockStatic(Provinces.class));
        log = scoped(mockStatic(SpawnLog.class));
        BiPredicate<Player, LivingEntity> sight = (player, mob) -> canSee.contains(player);
        service = new StragglerService(sight);
        collection = new AmbientSpawnService.CollectionInfestations() {
            public Infestation get(int id) { return infestations.get(id); }
            public Iterable<Infestation> all() { return infestations.values(); }
        };
    }

    @AfterEach void cleanup() throws Exception {
        Collections.reverse(mocks); for (AutoCloseable mock : mocks) mock.close();
        Infestations.plugin = previous; Cache.lureStragglerSeconds = 60; Cache.ambientStragglerSeconds = 120;
        Cache.requireOpenSky = true;
    }

    private <T extends AutoCloseable> T scoped(T mock) { mocks.add(mock); return mock; }

    private Infestation infestation(int id, LurePhase phase, Set<UUID> committed) {
        Infestation state = mock(Infestation.class);
        when(state.getProvinceId()).thenReturn(id); when(state.getPhase()).thenReturn(phase);
        when(state.getCommitted()).thenReturn(committed); when(state.lureLocation()).thenReturn(lure);
        infestations.put(id, state); return state;
    }

    private LivingEntity mob(Integer id, String kind, Location at) {
        LivingEntity mob = mock(LivingEntity.class); PersistentDataContainerMock pdc = new PersistentDataContainerMock();
        when(mob.getPersistentDataContainer()).thenReturn(pdc); when(mob.getUniqueId()).thenReturn(UUID.randomUUID());
        when(mob.getLocation()).thenReturn(at);
        when(mob.teleport(any(Location.class), any(TeleportFlag.class))).thenReturn(true);
        if (id != null) Keys.tagMob(pdc, id, kind);
        entities.add(mob); return mob;
    }

    private Player player(Location at, GameMode mode) {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.isOnline()).thenReturn(true); when(p.getGameMode()).thenReturn(mode);
        when(p.getWorld()).thenReturn(at.getWorld()); when(p.getLocation()).thenReturn(at);
        bukkit.when(() -> Bukkit.getPlayer(p.getUniqueId())).thenReturn(p);
        return p;
    }

    @Test void runsOnlyOnScanTicksAndSkipsMobsItDoesNotWatch() {
        assertFalse(service.tick(collection, 1));
        verify(world, never()).getLivingEntities();
        infestation(1, LurePhase.NONE, Set.of()); infestation(2, LurePhase.JOINING, Set.of());
        entities.add(mock(Player.class));
        LivingEntity dead = mob(1, Keys.KIND_AMBIENT, lure); when(dead.isDead()).thenReturn(true);
        LivingEntity untagged = mob(null, null, lure);
        LivingEntity unknown = mob(9, Keys.KIND_AMBIENT, lure);
        LivingEntity idleLureMob = mob(1, Keys.KIND_LURE, lure);
        LivingEntity joiningAmbient = mob(2, Keys.KIND_AMBIENT, lure);
        Cache.ambientStragglerSeconds = 0;
        LivingEntity ambientOff = mob(1, Keys.KIND_AMBIENT, lure);
        for (int tick = 0; tick <= 4000; tick += StragglerService.SCAN_TICKS) assertFalse(service.tick(collection, tick));
        for (LivingEntity mob : List.of(dead, untagged, unknown, idleLureMob, joiningAmbient, ambientOff)) {
            verify(mob, never()).remove(); verify(mob, never()).teleport(any(Location.class));
        }
    }

    @Test void removesAnAmbientMobNoPlayerCouldSeeForTheWholeLimit() {
        Infestation state = infestation(1, LurePhase.NONE, Set.of());
        Location far = new Location(world, 100, 64, 0);
        LivingEntity hidden = mob(1, Keys.KIND_AMBIENT, lure);
        worldPlayers.add(player(lure, GameMode.SPECTATOR));
        worldPlayers.add(player(new Location(otherWorld, 0, 64, 0), GameMode.SURVIVAL));
        worldPlayers.add(player(far, GameMode.SURVIVAL));
        Player near = player(new Location(world, 10, 64, 0), GameMode.SURVIVAL); worldPlayers.add(near);
        canSee.addAll(worldPlayers); canSee.remove(near);
        assertFalse(service.tick(collection, 0));
        assertFalse(service.tick(collection, 2360));
        verify(hidden, never()).remove();
        assertTrue(service.tick(collection, 2400));
        verify(hidden).remove();
        log.verify(() -> SpawnLog.line(state, "-", "straggler-removed"));
    }

    @Test void seeingAnAmbientMobRestartsItsClockAndAMissingMobIsForgotten() {
        infestation(1, LurePhase.NONE, Set.of());
        LivingEntity mob = mob(1, Keys.KIND_AMBIENT, lure);
        Player near = player(new Location(world, 5, 64, 0), GameMode.SURVIVAL); worldPlayers.add(near);
        service.tick(collection, 0);
        canSee.add(near); service.tick(collection, 2000); canSee.clear();
        assertFalse(service.tick(collection, 4000));
        entities.clear(); service.tick(collection, 4400);
        entities.add(mob); service.tick(collection, 4440);
        assertFalse(service.tick(collection, 6800));
        verify(mob, never()).remove();
        assertTrue(service.tick(collection, 6840));
    }

    @Test void pullsAnUnseenLureMobNextToTheLureWithoutRemovingIt() {
        Player committed = player(new Location(world, 5, 64, 0), GameMode.SURVIVAL);
        Player offline = player(lure, GameMode.SURVIVAL); when(offline.isOnline()).thenReturn(false);
        UUID gone = UUID.randomUUID();
        Infestation state = infestation(1, LurePhase.ACTIVE, Set.of(committed.getUniqueId(), offline.getUniqueId(), gone));
        Player bystander = player(new Location(world, 3, 64, 0), GameMode.SURVIVAL);
        worldPlayers.add(bystander); canSee.add(bystander); canSee.add(offline);
        Location spot = new Location(world, 6, 64, 0);
        planner.when(() -> SpawnPlanner.findLoose(eq(lure), eq(4), eq(12), eq(1), any())).thenAnswer(i -> {
            java.util.function.Predicate<Location> filter = i.getArgument(4);
            planner.when(() -> SpawnPlanner.reachable(spot)).thenReturn(true);
            provinces.when(() -> Provinces.at(spot)).thenReturn(1); assertTrue(filter.test(spot));
            planner.when(() -> SpawnPlanner.reachable(spot)).thenReturn(false); assertFalse(filter.test(spot));
            provinces.when(() -> Provinces.at(spot)).thenReturn(2); assertFalse(filter.test(spot));
            return List.of(spot);
        });
        LivingEntity buried = mob(1, Keys.KIND_LURE, new Location(world, 20, 50, 0));
        service.tick(collection, 0);
        assertFalse(service.tick(collection, 1160));
        verify(buried, never()).teleport(any(Location.class), any(TeleportFlag.class));
        assertFalse(service.tick(collection, 1200));
        verify(buried).teleport(spot, TeleportFlag.EntityState.RETAIN_PASSENGERS); verify(buried).addPotionEffect(any(PotionEffect.class)); verify(buried, never()).remove();
        log.verify(() -> SpawnLog.line(state, "-", "straggler-pulled"));
    }

    @Test void pullFallsBackToTheLureAnchorAndSkipsALureWithoutALocation() {
        Infestation state = infestation(1, LurePhase.ACTIVE, Set.of());
        Location anchor = new Location(world, 0, 65, 0);
        planner.when(() -> SpawnPlanner.findLoose(any(), anyInt(), anyInt(), anyInt(), any())).thenReturn(List.of());
        planner.when(() -> SpawnPlanner.anchor(lure)).thenReturn(anchor);
        LivingEntity buried = mob(1, Keys.KIND_LURE, lure);
        service.tick(collection, 0); service.tick(collection, 1200);
        verify(buried).teleport(anchor, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        service.tick(collection, 2400);
        when(state.lureLocation()).thenReturn(null);
        service.tick(collection, 3600);
        verify(buried, times(1)).teleport(any(Location.class), any(TeleportFlag.class));
    }

    @Test void aFailedPullIsLoggedWithoutGlowAndBothLimitsOffSkipTheScan() {
        Infestation state = infestation(1, LurePhase.ACTIVE, Set.of());
        planner.when(() -> SpawnPlanner.findLoose(any(), anyInt(), anyInt(), anyInt(), any())).thenReturn(List.of(lure));
        LivingEntity carried = mob(1, Keys.KIND_LURE, lure);
        when(carried.teleport(any(Location.class), any(TeleportFlag.class))).thenReturn(false);
        service.tick(collection, 0); service.tick(collection, 1200);
        verify(carried, never()).addPotionEffect(any(PotionEffect.class));
        log.verify(() -> SpawnLog.line(state, "-", "straggler-pull-failed"));
        Cache.lureStragglerSeconds = 0; Cache.ambientStragglerSeconds = 0;
        clearInvocations(world);
        assertFalse(service.tick(collection, 2400));
        verify(world, never()).getLivingEntities();
    }

    @Test void reachableNeedsOpenSkyAndNoInstallation() {
        planner.close(); mocks.remove(planner);
        MockedStatic<InstallationLookup> lookup = scoped(mockStatic(InstallationLookup.class));
        Location spot = new Location(world, 3, 64, 7);
        when(world.getHighestBlockYAt(3, 7, HeightMap.MOTION_BLOCKING_NO_LEAVES)).thenReturn(63);
        assertTrue(SpawnPlanner.reachable(spot));
        when(world.getHighestBlockYAt(3, 7, HeightMap.MOTION_BLOCKING_NO_LEAVES)).thenReturn(70);
        assertFalse(SpawnPlanner.reachable(spot));
        Cache.requireOpenSky = false;
        assertTrue(SpawnPlanner.reachable(spot));
        lookup.when(() -> InstallationLookup.findCovering(spot)).thenReturn(mock(Installation.class));
        assertFalse(SpawnPlanner.reachable(spot));
    }
}

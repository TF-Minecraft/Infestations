package net.tfminecraft.infestations.wildlife;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.InfestationsTestState;
import net.tfminecraft.infestations.cache.Cache;

class WildlifeServiceTest {
    private final List<AutoCloseable> mocks = new ArrayList<>();
    private final List<Player> players = new ArrayList<>();
    private final Map<UUID, Entity> entities = new HashMap<>();
    private InfestationsTestState state;
    private Infestations plugin;
    private Logger logger;
    private MockedStatic<Bukkit> bukkit;
    private ThreadLocalRandom random;
    private MythicBukkit mythic;
    private BukkitScheduler scheduler;
    private World world;
    private World otherWorld;
    private Block water;
    private Block stone;
    private Biome ocean;
    private Biome river;
    private WildlifeService service;

    @BeforeAll static void initializeRegistries() { MockBukkit.mock(); }
    @AfterAll static void releaseRegistries() { MockBukkit.unmock(); }

    @BeforeEach void setup() throws Exception {
        state = new InfestationsTestState();
        plugin = mock(Infestations.class); when(plugin.getName()).thenReturn("Infestations");
        when(plugin.namespace()).thenReturn("infestations");
        logger = mock(Logger.class); when(plugin.getLogger()).thenReturn(logger); Infestations.plugin = plugin;
        bukkit = scoped(mockStatic(Bukkit.class));
        scheduler = mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(players);
        bukkit.when(() -> Bukkit.getEntity(any(UUID.class))).thenAnswer(i -> entities.get(i.<UUID>getArgument(0)));
        MockedStatic<ThreadLocalRandom> randomness = scoped(mockStatic(ThreadLocalRandom.class));
        random = mock(ThreadLocalRandom.class); randomness.when(ThreadLocalRandom::current).thenReturn(random);
        MockedStatic<MythicBukkit> mythics = scoped(mockStatic(MythicBukkit.class));
        mythic = mock(MythicBukkit.class, RETURNS_DEEP_STUBS); mythics.when(MythicBukkit::inst).thenReturn(mythic);
        MockedStatic<BukkitAdapter> adapter = scoped(mockStatic(BukkitAdapter.class));
        adapter.when(() -> BukkitAdapter.adapt(any(Location.class))).thenReturn(mock(AbstractLocation.class));

        world = mock(World.class); when(world.getName()).thenReturn("TFMC_Map");
        when(world.getPlayers()).thenReturn(players);
        otherWorld = mock(World.class); when(otherWorld.getName()).thenReturn("elsewhere");
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getHighestBlockYAt(anyInt(), anyInt(), eq(HeightMap.WORLD_SURFACE))).thenReturn(63);
        when(world.getHighestBlockYAt(anyInt(), anyInt(), eq(HeightMap.OCEAN_FLOOR))).thenReturn(55);
        water = block(Material.WATER); stone = block(Material.STONE);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(water);
        ocean = biome("ocean"); river = biome("river");
        when(world.getBiome(anyInt(), anyInt(), anyInt())).thenReturn(ocean);

        WildlifeConfig.enabled = true; WildlifeConfig.worlds = Set.of("TFMC_Map");
        WildlifeConfig.intervalTicks = 200; WildlifeConfig.ringMin = 24; WildlifeConfig.ringMax = 64;
        WildlifeConfig.attempts = 3; WildlifeConfig.playerCap = 6; WildlifeConfig.playerCapRadius = 64;
        WildlifeConfig.despawnDistance = 96;
        WildlifeConfig.rules = List.of(rule("cod", "entity: COD\nbiomes: [OCEAN]\nmax-nearby: 2\nnearby-radius: 32\n"));
        Cache.debug = false;
        service = new WildlifeService();
    }

    @AfterEach void cleanup() throws Exception {
        Collections.reverse(mocks); for (AutoCloseable mock : mocks) mock.close();
        state.close();
    }

    private <T extends AutoCloseable> T scoped(T mock) { mocks.add(mock); return mock; }
    private static Block block(Material type) { Block b = mock(Block.class); when(b.getType()).thenReturn(type); return b; }
    private static Biome biome(String name) { Biome b = mock(Biome.class); when(b.getKey()).thenReturn(NamespacedKey.minecraft(name)); return b; }

    private static WildlifeRule rule(String id, String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration(); config.loadFromString(yaml);
        return WildlifeRule.parse(id, config);
    }

    private Player player(GameMode mode, double x, double z) {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getGameMode()).thenReturn(mode); when(p.getWorld()).thenReturn(world);
        when(p.getLocation()).thenReturn(new Location(world, x, 64, z)); players.add(p); return p;
    }

    private LivingEntity creature() {
        LivingEntity e = mock(LivingEntity.class); UUID id = UUID.randomUUID();
        when(e.getUniqueId()).thenReturn(id); when(e.getPersistentDataContainer()).thenReturn(new PersistentDataContainerMock());
        when(e.isValid()).thenReturn(true); when(e.getWorld()).thenReturn(world);
        entities.put(id, e); return e;
    }

    /** A creature spawned at the next vanilla spawn; it reports the spot it was spawned at. */
    private LivingEntity nextVanilla() {
        LivingEntity e = creature();
        when(world.spawnEntity(any(Location.class), any(EntityType.class))).thenAnswer(i -> {
            Location at = i.getArgument(0); when(e.getLocation()).thenReturn(at); return e;
        });
        return e;
    }

    private void ticks(int count) { for (int i = 0; i < count; i++) service.tick(); }

    @Test void startSchedulesEverySecondAndStopCancelsAndRemovesSpawnedCreatures() throws Exception {
        BukkitTask first = mock(BukkitTask.class), second = mock(BukkitTask.class);
        Plugin owner = mock(Plugin.class);
        when(scheduler.runTaskTimer(eq(owner), any(Runnable.class), eq(20L), eq(20L))).thenReturn(first, second);
        service.start(owner);
        service.start(owner);
        verify(first).cancel();
        WildlifeConfig.rules = List.of(rule("cod", "entity: COD\nbiomes: [OCEAN]\ncooldown-seconds: 0\nmax-nearby: 5\n"));
        WildlifeConfig.intervalTicks = 20;
        player(GameMode.SURVIVAL, 0, 0);
        LivingEntity cod = nextVanilla(); service.tick();
        LivingEntity unloaded = nextVanilla(); service.tick();
        assertEquals(2, service.tracked());
        entities.remove(unloaded.getUniqueId());
        service.stop();
        verify(second).cancel(); verify(cod).remove(); verify(unloaded, never()).remove();
        assertEquals(0, service.tracked());
        service.stop();
    }

    @Test void spawnsATaggedUnsavedFishTwoBlocksUnderTheSurfaceOfAFittingColumn() {
        Cache.debug = true;
        player(GameMode.SURVIVAL, 0, 0);
        LivingEntity cod = nextVanilla();
        service.tick();
        verify(world).spawnEntity(argThat(l -> l.getX() == 24.5 && l.getY() == 61 && l.getZ() == 0.5), eq(EntityType.COD));
        assertEquals("cod", cod.getPersistentDataContainer().get(WildlifeService.key(), PersistentDataType.STRING));
        verify(cod).setPersistent(false);
        verify(logger).info("[Infestations] Wildlife cod at 24,61,0");
        assertEquals(1, service.tracked());
    }

    @Test void shallowWaterSpawnsOneBlockAboveTheFloor() throws Exception {
        WildlifeConfig.rules = List.of(rule("cod", "entity: COD\nbiomes: [OCEAN]\nmin-depth: 1\n"));
        when(world.getHighestBlockYAt(anyInt(), anyInt(), eq(HeightMap.OCEAN_FLOOR))).thenReturn(61);
        player(GameMode.ADVENTURE, 0, 0);
        nextVanilla();
        service.tick();
        verify(world).spawnEntity(argThat(l -> l.getY() == 62), eq(EntityType.COD));
    }

    @Test void onlySurvivalAndAdventurePlayersInListedWorldsWhoAreAliveGetTries() {
        player(GameMode.CREATIVE, 0, 0);
        player(GameMode.SPECTATOR, 0, 0);
        Player dead = player(GameMode.SURVIVAL, 0, 0); when(dead.isDead()).thenReturn(true);
        Player away = player(GameMode.SURVIVAL, 0, 0); when(away.getWorld()).thenReturn(otherWorld);
        service.tick();
        verify(world, never()).isChunkLoaded(anyInt(), anyInt());
    }

    @Test void eachPlayerTriesOncePerIntervalAndOfflinePlayersAreForgotten() {
        Player p = player(GameMode.SURVIVAL, 0, 0);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        service.tick();
        verify(world, times(3)).isChunkLoaded(anyInt(), anyInt());
        ticks(9);
        verify(world, times(3)).isChunkLoaded(anyInt(), anyInt());
        service.tick();
        verify(world, times(6)).isChunkLoaded(anyInt(), anyInt());
        players.remove(p);
        service.tick();
        players.add(p);
        service.tick();
        verify(world, times(9)).isChunkLoaded(anyInt(), anyInt());
    }

    @Test void disabledOrRuleLessConfigSpawnsNothingButStillSweeps() {
        player(GameMode.SURVIVAL, 0, 0);
        LivingEntity cod = nextVanilla();
        service.tick();
        when(cod.getLocation()).thenReturn(new Location(world, 400, 64, 0));
        WildlifeConfig.enabled = false;
        ticks(4);
        WildlifeConfig.enabled = true; WildlifeConfig.rules = List.of();
        ticks(5);
        verify(world, times(1)).isChunkLoaded(anyInt(), anyInt());
        verify(cod).remove();
        assertEquals(0, service.tracked());
    }

    @Test void columnsThatAreUnloadedDryTooShallowOrUnsuitedAreSkipped() throws Exception {
        WildlifeConfig.attempts = 5;
        WildlifeConfig.rules = List.of(rule("salmon", "entity: SALMON\nbiomes: [RIVER]\n"));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false, true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(stone, water);
        when(world.getHighestBlockYAt(anyInt(), anyInt(), eq(HeightMap.OCEAN_FLOOR))).thenReturn(63, 55);
        when(world.getBiome(anyInt(), anyInt(), anyInt())).thenReturn(ocean, river);
        player(GameMode.SURVIVAL, 0, 0);
        nextVanilla();
        service.tick();
        verify(world, times(5)).isChunkLoaded(anyInt(), anyInt());
        verify(world).spawnEntity(any(Location.class), eq(EntityType.SALMON));
    }

    @Test void playerCapStopsTriesAndOnlyNearbyLiveCreaturesCount() {
        WildlifeConfig.playerCap = 1;
        Player p = player(GameMode.SURVIVAL, 0, 0);
        LivingEntity cod = nextVanilla();
        service.tick();
        ticks(10);
        verify(world, times(1)).isChunkLoaded(anyInt(), anyInt());
        when(cod.getLocation()).thenReturn(new Location(world, 400, 64, 0));
        when(p.getLocation()).thenReturn(new Location(world, 330, 64, 0));
        ticks(10);
        verify(world, times(4)).isChunkLoaded(anyInt(), anyInt());
        verify(cod, never()).remove();
    }

    @Test void ruleCooldownAndNearbyLimitHoldBackFurtherSpawns() throws Exception {
        WildlifeConfig.rules = List.of(rule("cod", "entity: COD\nbiomes: [OCEAN]\nmax-nearby: 2\nnearby-radius: 32\n"
                + "cooldown-seconds: 10\n"));
        player(GameMode.SURVIVAL, 0, 0);
        when(world.spawnEntity(any(Location.class), any(EntityType.class))).thenAnswer(i -> {
            LivingEntity e = creature(); Location at = i.getArgument(0); when(e.getLocation()).thenReturn(at); return e;
        });
        service.tick();
        WildlifeConfig.intervalTicks = 20;
        service.tick();
        verify(world, times(1)).spawnEntity(any(Location.class), any(EntityType.class));
        ticks(9);
        verify(world, times(2)).spawnEntity(any(Location.class), any(EntityType.class));
        ticks(20);
        verify(world, times(2)).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test void nearbyCountOnlyCountsLiveCreaturesOfTheSameRule() throws Exception {
        WildlifeConfig.rules = List.of(rule("cod", "entity: COD\nbiomes: [OCEAN]\ncooldown-seconds: 0\n"),
                rule("squid", "entity: SQUID\nbiomes: [RIVER]\ncooldown-seconds: 0\n"));
        WildlifeConfig.intervalTicks = 20; WildlifeConfig.attempts = 1;
        player(GameMode.SURVIVAL, 0, 0);
        when(world.getBiome(anyInt(), anyInt(), anyInt())).thenReturn(river);
        nextVanilla(); service.tick();
        when(world.getBiome(anyInt(), anyInt(), anyInt())).thenReturn(ocean);
        LivingEntity cod = nextVanilla(); service.tick();
        service.tick();
        verify(world, times(1)).spawnEntity(any(Location.class), eq(EntityType.COD));
        when(cod.isValid()).thenReturn(false);
        nextVanilla(); service.tick();
        verify(world, times(2)).spawnEntity(any(Location.class), eq(EntityType.COD));
        verify(world, times(1)).spawnEntity(any(Location.class), eq(EntityType.SQUID));
    }

    @Test void creaturesInAnotherWorldOrGoneDoNotCount() throws Exception {
        WildlifeConfig.rules = List.of(rule("cod", "entity: COD\nbiomes: [OCEAN]\ncooldown-seconds: 0\nmax-nearby: 5\n"));
        WildlifeConfig.intervalTicks = 20; WildlifeConfig.attempts = 1; WildlifeConfig.playerCap = 1;
        player(GameMode.SURVIVAL, 0, 0);
        LivingEntity first = nextVanilla();
        service.tick();
        when(first.getWorld()).thenReturn(otherWorld);
        LivingEntity second = nextVanilla();
        service.tick();
        entities.remove(second.getUniqueId());
        nextVanilla();
        service.tick();
        verify(world, times(3)).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test void failedChanceEndsTheTryWithoutSpawningOrCooldown() throws Exception {
        WildlifeConfig.rules = List.of(rule("cod", "entity: COD\nbiomes: [OCEAN]\nchance: 0.5\n"));
        when(random.nextDouble()).thenReturn(0.0, 0.0, 0.9);
        player(GameMode.SURVIVAL, 0, 0);
        service.tick();
        verify(world, times(1)).isChunkLoaded(anyInt(), anyInt());
        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test void mythicRulesSpawnThroughMythicMobsAndUnknownOrFailedSpawnsAreHandled() throws Exception {
        WildlifeConfig.rules = List.of(rule("shark", "mythic-mob: Shark\nbiomes: [OCEAN]\ncooldown-seconds: 0\n"));
        WildlifeConfig.intervalTicks = 20; WildlifeConfig.attempts = 1;
        player(GameMode.SURVIVAL, 0, 0);
        when(mythic.getMobManager().getMythicMob("Shark")).thenReturn(Optional.empty());
        service.tick(); service.tick();
        verify(logger, times(1)).warning("[Infestations] Unknown Mythic mob in wildlife.yml: Shark");

        MythicMob shark = mock(MythicMob.class);
        when(mythic.getMobManager().getMythicMob("Shark")).thenReturn(Optional.of(shark));
        when(shark.spawn(any(AbstractLocation.class), eq(1.0))).thenReturn(null);
        service.tick();
        ActiveMob active = mock(ActiveMob.class);
        when(shark.spawn(any(AbstractLocation.class), eq(1.0))).thenReturn(active);
        service.tick();
        AbstractEntity wrapper = mock(AbstractEntity.class); when(active.getEntity()).thenReturn(wrapper);
        LivingEntity body = creature(); when(body.getLocation()).thenReturn(new Location(world, 24, 61, 0));
        when(wrapper.getBukkitEntity()).thenReturn(body);
        service.tick();
        assertEquals("shark", body.getPersistentDataContainer().get(WildlifeService.key(), PersistentDataType.STRING));
        assertEquals(1, service.tracked());
        verify(shark, times(3)).spawn(any(AbstractLocation.class), eq(1.0));
    }

    @Test void failedVanillaSpawnIsNotTracked() {
        player(GameMode.SURVIVAL, 0, 0);
        when(world.spawnEntity(any(Location.class), any(EntityType.class))).thenReturn(null);
        service.tick();
        assertEquals(0, service.tracked());
    }

    @Test void sweepForgetsGoneCreaturesAndRemovesThoseNoPlayerIsNear() throws Exception {
        WildlifeConfig.rules = List.of(rule("cod", "entity: COD\nbiomes: [OCEAN]\ncooldown-seconds: 0\nmax-nearby: 10\n"));
        WildlifeConfig.intervalTicks = 20; WildlifeConfig.attempts = 1;
        player(GameMode.SURVIVAL, 0, 0);
        LivingEntity near = nextVanilla(); service.tick();
        LivingEntity far = nextVanilla(); service.tick();
        LivingEntity gone = nextVanilla(); service.tick();
        LivingEntity dead = nextVanilla(); service.tick();
        assertEquals(4, service.tracked());
        WildlifeConfig.enabled = false;
        when(far.getLocation()).thenReturn(new Location(world, 400, 64, 0));
        entities.remove(gone.getUniqueId());
        when(dead.isValid()).thenReturn(false);
        player(GameMode.SPECTATOR, 400, 0);
        ticks(6);
        verify(far).remove(); verify(near, never()).remove(); verify(dead, never()).remove();
        assertEquals(1, service.tracked());
    }

}

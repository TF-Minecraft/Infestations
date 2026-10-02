package net.tfminecraft.infestations.loader;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.infestation.Severity;
import net.tfminecraft.infestations.utils.Provinces;
import net.tfminecraft.simplefactions.enums.Terrain;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoadersTest {
    @TempDir Path folder;
    private final Map<Field, Object> savedCache = new HashMap<>();
    private Infestations previousPlugin;
    private Logger logger;
    private int nextFile;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        for (Field field : Cache.class.getFields()) {
            if (!Modifier.isFinal(field.getModifiers())) savedCache.put(field, field.get(null));
        }
        previousPlugin = Infestations.plugin;
        Infestations.plugin = mock(Infestations.class);
        logger = mock(Logger.class);
        when(Infestations.plugin.getLogger()).thenReturn(logger);
        new GroupLoader().load(file(""));
    }

    @AfterEach
    void tearDown() throws Exception {
        new GroupLoader().load(file(""));
        for (Map.Entry<Field, Object> entry : savedCache.entrySet()) entry.getKey().set(null, entry.getValue());
        Infestations.plugin = previousPlugin;
        MockBukkit.unmock();
    }

    private File file(String yaml) throws Exception {
        return Files.writeString(folder.resolve("config-" + nextFile++ + ".yml"), yaml).toFile();
    }

    @Test
    void largeWeightsStillReachTheFinalMobBucket() throws Exception {
        new GroupLoader().load(file("""
                groups:
                  heavy:
                    mobs:
                      - {id: first, weight: 2147483647}
                      - {id: second, weight: 2147483647}
                """));
        ThreadLocalRandom random = mock(ThreadLocalRandom.class);
        when(random.nextInt(anyInt())).thenAnswer(call -> call.getArgument(0, Integer.class) - 1);
        when(random.nextLong(anyLong())).thenAnswer(call -> call.getArgument(0, Long.class) - 1L);
        try (MockedStatic<ThreadLocalRandom> current = mockStatic(ThreadLocalRandom.class)) {
            current.when(ThreadLocalRandom::current).thenReturn(random);
            assertEquals("second", GroupLoader.pickMobId("heavy"), "The final roll must reach the final weighted bucket");
        }
    }

    @Test
    void emptyConfigurationRestoresDocumentedDefaultsWithoutAliasingConstantTables() throws Exception {
        new ConfigLoader().load(file(""));
        assertFalse(Cache.debug);
        assertTrue(Cache.loggingEnabled);
        assertTrue(Cache.wipeLog);
        assertFalse(Cache.spread);
        assertEquals(600, Cache.spreadIntervalSeconds);
        assertEquals(0.0015, Cache.worsenChance);
        assertArrayEquals(Cache.DEFAULT_SPREAD_LAND, Cache.spreadChanceLand);
        assertArrayEquals(Cache.DEFAULT_SPREAD_WATER, Cache.spreadChanceWater);
        assertArrayEquals(Cache.DEFAULT_SPREAD_SEA, Cache.spreadChanceSea);
        assertNotSame(Cache.DEFAULT_SPREAD_LAND, Cache.spreadChanceLand);
        assertNotSame(Cache.DEFAULT_SPREAD_WATER, Cache.spreadChanceWater);
        assertNotSame(Cache.DEFAULT_SPREAD_SEA, Cache.spreadChanceSea);
        assertEquals("ia.tfmc:lure", Cache.lureItem);
        assertEquals(20, Cache.joinSeconds);
        assertEquals(48, Cache.lureSpawnRadius);
        assertEquals(16, Cache.minPlayerDistance);
        assertEquals(300, Cache.logoutGraceSeconds);
        assertEquals(2.0, Cache.deserterDamage);
        assertEquals(96.0, Cache.hologramViewRange);
        assertEquals(Set.of("water", "sea"), Cache.skipTerrains);
    }

    @Test
    void completeConfigClampsUnsafeRangesAndNormalizesTerrainNames() throws Exception {
        assertTrue(new ConfigLoader().loadSafe(file("""
                debug: true
                logging: false
                wipe-log: false
                spread: true
                spread-interval-seconds: 0
                worsen-chance: 2
                spread-chance-land: {mild: -1, worrying: 0.5, severe: 2, extreme: 0.75}
                spread-chance-water: {mild: 0.25}
                spread-chance-sea: {extreme: 0.125}
                lure-item: custom.lure
                join-seconds: -1
                lure-spawn-radius: 0
                min-player-distance: -1
                logout-grace-seconds: 0
                deserter-damage: 0
                hologram-view-range: 0
                skip-terrains: ['', 'FOREST', null]
                """)));
        assertTrue(Cache.debug);
        assertFalse(Cache.loggingEnabled);
        assertFalse(Cache.wipeLog);
        assertTrue(Cache.spread);
        assertEquals(1, Cache.spreadIntervalSeconds);
        assertEquals(1.0, Cache.worsenChance);
        assertArrayEquals(new double[]{0, 0.5, 1, 0.75}, Cache.spreadChanceLand);
        assertEquals(0.25, Cache.spreadChanceWater[0]);
        assertEquals(Cache.DEFAULT_SPREAD_WATER[1], Cache.spreadChanceWater[1]);
        assertEquals(0.125, Cache.spreadChanceSea[3]);
        assertEquals("custom.lure", Cache.lureItem);
        assertEquals(1, Cache.joinSeconds);
        assertEquals(4, Cache.lureSpawnRadius);
        assertEquals(0, Cache.minPlayerDistance);
        assertEquals(1, Cache.logoutGraceSeconds);
        assertEquals(0.5, Cache.deserterDamage);
        assertEquals(16.0, Cache.hologramViewRange);
        assertEquals(Set.of("forest"), Cache.skipTerrains);
    }

    @Test
    void configIoAndYamlErrorsPreserveLastGoodValues() throws Exception {
        Cache.joinSeconds = 47;
        ConfigLoader loader = new ConfigLoader();
        assertFalse(loader.loadSafe(folder.resolve("missing.yml").toFile()));
        assertFalse(loader.loadSafe(file("invalid: [")));
        assertEquals(47, Cache.joinSeconds);
        verify(logger, times(2)).severe(contains("Failed to load config.yml"));
    }

    @Test
    void absentSeveritySectionAfterSectionCheckRetainsFallbackProbabilities() throws Exception {
        File file = file("");
        try (MockedConstruction<YamlConfiguration> configs = mockConstruction(YamlConfiguration.class,
                (config, context) -> when(config.isConfigurationSection(anyString())).thenReturn(true))) {
            assertTrue(new ConfigLoader().loadSafe(file));
            assertArrayEquals(Cache.DEFAULT_SPREAD_LAND, Cache.spreadChanceLand);
            assertArrayEquals(Cache.DEFAULT_SPREAD_WATER, Cache.spreadChanceWater);
            assertArrayEquals(Cache.DEFAULT_SPREAD_SEA, Cache.spreadChanceSea);
        }
    }

    @Test
    void missingTerrainListFromConfigurationIsTreatedAsUnrestricted() throws Exception {
        File file = file("");
        ConfigurationSection root = mock(ConfigurationSection.class);
        ConfigurationSection group = mock(ConfigurationSection.class);
        when(root.getKeys(false)).thenReturn(Set.of("group"));
        when(root.getConfigurationSection("group")).thenReturn(group);
        when(group.getString("display", "group")).thenReturn("Group");
        when(group.getStringList("terrains")).thenReturn(null);
        when(group.getMapList("mobs")).thenReturn(List.of(Map.of("id", "mob")));
        try (MockedConstruction<YamlConfiguration> configs = mockConstruction(YamlConfiguration.class,
                (config, context) -> when(config.getConfigurationSection("groups")).thenReturn(root))) {
            assertTrue(new GroupLoader().loadSafe(file));
            assertTrue(GroupLoader.get("group").terrains().isEmpty());
            assertTrue(GroupLoader.allowsTerrain("group", 99));
        }
    }

    @Test
    void cacheSelectsTerrainAndSeverityTablesAndHandlesShortLegacyTables() {
        Cache.spreadChanceLand = new double[]{1, 2, 3, 4};
        Cache.spreadChanceWater = new double[]{5, 6, 7, 8};
        Cache.spreadChanceSea = new double[]{9, 10, 11, 12};
        assertEquals(2, Cache.hopChance(Severity.WORRYING, 0));
        assertEquals(7, Cache.hopChance(Severity.SEVERE, 1));
        assertEquals(12, Cache.hopChance(Severity.EXTREME, 2));
        assertEquals(9, Cache.hopChance(Severity.MILD, -1));
        Cache.spreadChanceLand = new double[]{0.4};
        assertEquals(0.4, Cache.hopChance(Severity.EXTREME, 0));
    }

    @Test
    void groupParsingSkipsUnusableRowsAndSuppliesDefaultTunesAndWeights() throws Exception {
        new GroupLoader().load(file("""
                groups:
                  scalar: ignored
                  empty: {mobs: []}
                  Mixed:
                    mobs:
                      - {weight: 5}
                      - {id: first}
                      - {id: second, weight: 0}
                      - {id: third, weight: text}
                    terrains: ['', ' PLAINS ', mystery]
                  unrestricted:
                    mobs: [{id: lone, weight: 7}]
                """));
        assertEquals(Set.of("mixed", "unrestricted"), GroupLoader.groupIds());
        assertThrows(UnsupportedOperationException.class, () -> GroupLoader.groupIds().clear());
        GroupLoader.MobGroup group = GroupLoader.get("MIXED");
        assertEquals("Mixed", group.id());
        assertEquals("Mixed", GroupLoader.displayName("mixed"));
        assertFalse(GroupLoader.nightOnly("mixed"));
        assertNull(group.minY());
        assertEquals(Set.of("plains", "mystery"), group.terrains());
        assertEquals(List.of(new GroupLoader.WeightedMob("first", 1), new GroupLoader.WeightedMob("second", 1),
                new GroupLoader.WeightedMob("third", 1)), group.mobs());
        for (Severity severity : Severity.values()) {
            assertEquals(GroupLoader.GroupTune.defaults(severity), GroupLoader.tune("mixed", severity));
        }
        assertEquals(new GroupLoader.GroupTune(8, 40, 10, 22, 20, 120), GroupLoader.tune("mixed", Severity.MILD));
        assertEquals(new GroupLoader.GroupTune(16, 40, 10, 20, 40, 120), GroupLoader.tune("mixed", Severity.WORRYING));
        assertEquals(new GroupLoader.GroupTune(28, 30, 9, 19, 60, 120), GroupLoader.tune("mixed", Severity.SEVERE));
        assertEquals(new GroupLoader.GroupTune(40, 20, 8, 18, 80, 120), GroupLoader.tune("mixed", Severity.EXTREME));
        verify(logger).warning(contains("Group empty has no mobs"));
        verify(logger).warning(contains("unknown terrain: mystery"));
        assertEquals(Set.of("plains", "mystery"), Set.of(GroupLoader.terrainList("mixed").split(", ")));
        assertEquals("any land", GroupLoader.terrainList("unrestricted"));
        assertTrue(GroupLoader.allowsY("unrestricted", -100));
        assertTrue(GroupLoader.allowsTerrain("unrestricted", 1));
        assertEquals("lone", GroupLoader.pickMobId("unrestricted"));
    }

    @Test
    void explicitTunesClampMinimumsAndApplyNightHeightAndTerrainRestrictions() throws Exception {
        assertTrue(new GroupLoader().loadSafe(file("""
                groups:
                  restricted:
                    display: Night hunters
                    night-only: true
                    min-y: 40
                    terrains: [forest]
                    mobs: [{id: hunter, weight: 1}]
                    severity:
                      mild: {ambient-cap: 0, ambient-interval-ticks: 0, ambient-ring-min: 0,
                             ambient-ring-max: 0, lure-count: 0, lure-duration-seconds: 0}
                      severe: {ambient-cap: 99, lure-count: 150}
                """)));
        assertEquals("Night hunters", GroupLoader.displayName("restricted"));
        assertTrue(GroupLoader.nightOnly("restricted"));
        assertFalse(GroupLoader.allowsY("restricted", 39));
        assertTrue(GroupLoader.allowsY("restricted", 40));
        assertEquals(new GroupLoader.GroupTune(1, 10, 1, 1, 1, 10), GroupLoader.tune("restricted", Severity.MILD));
        assertEquals(99, GroupLoader.tune("restricted", Severity.SEVERE).ambientCap());
        assertEquals(150, GroupLoader.tune("restricted", Severity.SEVERE).lureCount());
        try (MockedStatic<Provinces> provinces = mockStatic(Provinces.class)) {
            assertFalse(GroupLoader.allowsTerrain("restricted", 1));
            provinces.when(() -> Provinces.terrain(1)).thenReturn(Terrain.FOREST);
            assertTrue(GroupLoader.allowsTerrain("restricted", 1));
            provinces.when(() -> Provinces.terrain(1)).thenReturn(Terrain.PLAINS);
            assertFalse(GroupLoader.allowsTerrain("restricted", 1));
        }
        World world = mock(World.class);
        when(world.getTime()).thenReturn(12999L);
        assertTrue(GroupLoader.blockedByNight("restricted", world));
        when(world.getTime()).thenReturn(13000L);
        assertFalse(GroupLoader.blockedByNight("restricted", world));
        when(world.getTime()).thenReturn(22999L);
        assertTrue(GroupLoader.isNight(world));
        when(world.getTime()).thenReturn(23000L);
        assertFalse(GroupLoader.isNight(world));
        assertFalse(GroupLoader.isNight(null));
    }

    @Test
    void missingGroupsUseSafeLookupDefaultsAndFailedReloadKeepsExistingGroups() throws Exception {
        GroupLoader loader = new GroupLoader();
        assertNull(GroupLoader.get(null));
        assertNull(GroupLoader.get("missing"));
        assertEquals("missing", GroupLoader.displayName("missing"));
        assertEquals(GroupLoader.GroupTune.defaults(Severity.MILD), GroupLoader.tune("missing", null));
        assertEquals(GroupLoader.GroupTune.defaults(Severity.SEVERE), GroupLoader.tune("missing", Severity.SEVERE));
        assertFalse(GroupLoader.nightOnly("missing"));
        assertTrue(GroupLoader.allowsY("missing", 0));
        assertFalse(GroupLoader.allowsTerrain("missing", 1));
        assertFalse(GroupLoader.blockedByNight("missing", null));
        assertEquals("any land", GroupLoader.terrainList("missing"));
        assertNull(GroupLoader.pickMobId("missing"));
        loader.load(file("groups: {valid: {mobs: [{id: mob}]}}"));
        assertFalse(loader.loadSafe(folder.resolve("missing.yml").toFile()));
        assertFalse(loader.loadSafe(file("broken: [")));
        assertNotNull(GroupLoader.get("valid"));
        assertEquals(GroupLoader.GroupTune.defaults(Severity.MILD), GroupLoader.tune("valid", null));
        verify(logger, times(2)).severe(contains("Failed to load groups.yml"));
        assertTrue(loader.loadSafe(file("")));
        assertTrue(GroupLoader.groupIds().isEmpty());
    }

    @Test
    void weightedSelectionHonorsEachBucketBoundary() throws Exception {
        new GroupLoader().load(file("groups: {weighted: {mobs: [{id: first, weight: 2}, {id: second, weight: 3}]}}"));
        ThreadLocalRandom random = mock(ThreadLocalRandom.class);
        try (MockedStatic<ThreadLocalRandom> current = mockStatic(ThreadLocalRandom.class)) {
            current.when(ThreadLocalRandom::current).thenReturn(random);
            for (int roll = 0; roll < 5; roll++) {
                when(random.nextInt(anyInt())).thenReturn(roll);
                when(random.nextLong(anyLong())).thenReturn((long) roll);
                assertEquals(roll < 2 ? "first" : "second", GroupLoader.pickMobId("weighted"));
            }
        }
    }
}

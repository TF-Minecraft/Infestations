package net.tfminecraft.infestations.wildlife;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.InfestationsTestState;

class WildlifeConfigTest {
    @TempDir Path directory;
    private InfestationsTestState state;
    private Logger logger;

    @BeforeEach void setup() throws Exception {
        state = new InfestationsTestState();
        Infestations plugin = mock(Infestations.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        Infestations.plugin = plugin;
    }

    @AfterEach void cleanup() throws Exception { state.close(); }

    private static WildlifeRule rule(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return WildlifeRule.parse("r", config);
    }

    @Test void parsesMythicAndVanillaRulesWithDefaultsAndNormalisedBiomes() throws Exception {
        WildlifeRule shark = rule("mythic-mob: Shark\nbiomes: [DEEP_OCEAN, ' minecraft:Ocean ']\n");
        assertEquals("Shark", shark.mythicMob()); assertNull(shark.entity());
        assertEquals(Set.of("minecraft:deep_ocean", "minecraft:ocean"), shark.biomes());
        assertEquals(50, shark.minY()); assertEquals(64, shark.maxY()); assertEquals(2, shark.minDepth());
        assertEquals(1.0, shark.chance()); assertEquals(600, shark.cooldownTicks());
        assertEquals(1, shark.maxNearby()); assertEquals(64, shark.nearbyRadius());
        assertEquals(2, shark.belowSurface()); assertEquals(1.0, shark.level());
        assertEquals(61, shark.spawnY(63, 40)); assertEquals(62, shark.spawnY(63, 2));

        WildlifeRule cod = rule("entity: cod\nbiomes: [OCEAN]\nmin-y: 10\nmax-y: 20\nmin-depth: 0\nchance: 3\n"
                + "cooldown-seconds: -5\nmax-nearby: 0\nnearby-radius: 0\nbelow-surface: -3\nlevel: 0\n");
        assertEquals(EntityType.COD, cod.entity()); assertNull(cod.mythicMob());
        assertEquals(1, cod.minDepth()); assertEquals(1.0, cod.chance()); assertEquals(0, cod.cooldownTicks());
        assertEquals(1, cod.maxNearby()); assertEquals(1, cod.nearbyRadius());
        assertEquals(0, cod.belowSurface()); assertEquals(1.0, cod.level()); assertEquals(63, cod.spawnY(63, 9));
        WildlifeRule deep = rule("mythic-mob: Orca\nbiomes: [OCEAN]\nbelow-surface: 6\nlevel: 3\n");
        assertEquals(6, deep.belowSurface()); assertEquals(3.0, deep.level()); assertEquals(57, deep.spawnY(63, 30));
        assertEquals(0.0, rule("entity: COD\nbiomes: [OCEAN]\nchance: -1\n").chance());
    }

    @Test void rejectsRulesWithoutExactlyOneCreatureABiomeOrAHeightBand() throws Exception {
        assertNull(rule("biomes: [OCEAN]\n"));
        assertNull(rule("mythic-mob: Shark\nentity: COD\nbiomes: [OCEAN]\n"));
        assertNull(rule("entity: NOT_A_MOB\nbiomes: [OCEAN]\n"));
        assertNull(rule("entity: PLAYER\nbiomes: [OCEAN]\n"));
        assertNull(rule("entity: FISHING_BOBBER\nbiomes: [OCEAN]\n"));
        assertNull(rule("entity: ARROW\nbiomes: [OCEAN]\n"));
        assertNull(rule("entity: COD\n"));
        assertNull(rule("entity: COD\nbiomes: [OCEAN]\nmin-y: 70\nmax-y: 60\n"));
    }

    @Test void fitsNeedsBiomeHeightBandAndDepth() throws Exception {
        WildlifeRule r = rule("entity: COD\nbiomes: [OCEAN]\nmin-y: 50\nmax-y: 64\nmin-depth: 3\n");
        assertTrue(r.fits("minecraft:ocean", 50, 3));
        assertTrue(r.fits("minecraft:ocean", 64, 9));
        assertFalse(r.fits("minecraft:river", 55, 9));
        assertFalse(r.fits("minecraft:ocean", 49, 9));
        assertFalse(r.fits("minecraft:ocean", 65, 9));
        assertFalse(r.fits("minecraft:ocean", 55, 2));
    }

    @Test void loadsSettingsClampsThemAndSkipsBrokenRules() throws Exception {
        Path file = directory.resolve("wildlife.yml");
        Files.writeString(file, """
                enabled: true
                worlds: [TFMC_Map]
                interval-seconds: 0
                ring-min: -3
                ring-max: 0
                attempts: 500
                player-cap: -1
                player-cap-radius: 0
                despawn-distance: 0
                sweep-seconds: 0
                rules:
                  cod:
                    entity: COD
                    biomes: [OCEAN]
                  broken:
                    biomes: [OCEAN]
                  scalar: 5
                """);
        assertFalse(WildlifeConfig.loadSafe(file.toFile()));
        assertTrue(WildlifeConfig.enabled); assertEquals(Set.of("TFMC_Map"), WildlifeConfig.worlds);
        assertEquals(20, WildlifeConfig.intervalTicks); assertEquals(0, WildlifeConfig.ringMin);
        assertEquals(1, WildlifeConfig.ringMax); assertEquals(64, WildlifeConfig.attempts);
        assertEquals(0, WildlifeConfig.playerCap); assertEquals(1, WildlifeConfig.playerCapRadius);
        assertEquals(1, WildlifeConfig.despawnDistance); assertEquals(20, WildlifeConfig.sweepTicks);
        assertEquals(List.of("cod"), WildlifeConfig.rules.stream().map(WildlifeRule::id).toList());
        verify(logger).warning(contains("Skipping wildlife rule 'broken'"));
        verify(logger).warning(contains("Skipping wildlife rule 'scalar'"));
    }

    @Test void bundledDefaultsLoadCleanlyAndAFileWithoutRulesLoadsNone() throws Exception {
        Path bundled = directory.resolve("bundled.yml");
        try (var in = WildlifeConfig.class.getClassLoader().getResourceAsStream("wildlife.yml")) {
            Files.copy(in, bundled);
        }
        assertTrue(WildlifeConfig.loadSafe(bundled.toFile()));
        assertTrue(WildlifeConfig.enabled);
        assertEquals(11, WildlifeConfig.rules.size());
        assertEquals(200, WildlifeConfig.intervalTicks); assertEquals(96, WildlifeConfig.despawnDistance);
        assertEquals(200, WildlifeConfig.sweepTicks);
        verify(logger).info("[Infestations] Wildlife on: 11 rules, worlds [TFMC_Map]");

        Path empty = directory.resolve("empty.yml");
        Files.writeString(empty, "enabled: true\n");
        assertTrue(WildlifeConfig.loadSafe(empty.toFile()));
        assertTrue(WildlifeConfig.rules.isEmpty()); assertTrue(WildlifeConfig.worlds.isEmpty());
        verify(logger, never()).warning(anyString());
    }

    @Test void malformedFileTurnsWildlifeOff() throws Exception {
        WildlifeConfig.enabled = true;
        WildlifeConfig.rules = List.of(rule("entity: COD\nbiomes: [OCEAN]\n"));
        Path file = directory.resolve("wildlife.yml");
        Files.writeString(file, "rules: [broken");
        assertFalse(WildlifeConfig.loadSafe(file.toFile()));
        assertFalse(WildlifeConfig.enabled); assertTrue(WildlifeConfig.rules.isEmpty());
        verify(logger).severe(contains("Failed to load wildlife.yml"));
    }
}

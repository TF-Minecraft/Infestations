package net.tfminecraft.infestations.reward;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.InfestationsTestState;
import net.tfminecraft.infestations.infestation.Severity;
import net.tfminecraft.infestations.reward.RewardTable.Coin;
import net.tfminecraft.infestations.reward.RewardTable.Entry;
import net.tfminecraft.infestations.reward.RewardTable.Weighted;

class RewardConfigTest {
    @TempDir Path dir;
    private InfestationsTestState state;
    private Logger logger;

    @BeforeEach void setup() throws Exception {
        state = new InfestationsTestState();
        Infestations plugin = mock(Infestations.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        Infestations.plugin = plugin;
    }

    @AfterEach void restore() throws Exception { state.close(); }

    private File write(String yaml) throws Exception {
        Path file = dir.resolve("rewards.yml");
        Files.writeString(file, yaml);
        return file.toFile();
    }

    @Test void bundledFileLoadsEveryTier() {
        assertTrue(RewardConfig.loadSafe(new File("src/main/resources/rewards.yml")));
        assertTrue(RewardConfig.enabled);
        assertEquals(Set.of("swamp_mobs", "swamp_mobs_hill"), RewardConfig.groups);
        assertEquals(4, RewardConfig.coins.size());
        assertEquals("m.loot.trial_key", RewardConfig.keyItem);
        assertEquals(40, RewardConfig.keyCapPerHour);
        assertEquals(3, RewardConfig.lureMinKills);
        assertEquals(5, RewardConfig.lureMultiplier);
        assertEquals(0.05, RewardConfig.lureKeep.of("m.loot.trial_key"), 1e-9);
        for (Severity severity : Severity.values()) {
            assertNotNull(RewardConfig.ambient(severity, true));
            assertNotNull(RewardConfig.lure(severity));
            assertSame(RewardConfig.ambient.get(RewardConfig.OUTSIDE), RewardConfig.ambient(severity, false));
        }
        assertEquals(new Entry("m.loot.trial_key", 0.06, 1, 1), RewardConfig.ambient(Severity.EXTREME, true).drops().get(0));
        assertEquals(new Entry("m.loot.trial_key", 1, 14, 20), RewardConfig.lure(Severity.EXTREME).drops().get(0));
        // Tin, fragments and dust are Ignitium / 40 in every ambient table, and no table pays mythril.
        for (RewardTable table : RewardConfig.ambient.values()) {
            Map<String, Double> chance = new java.util.HashMap<>();
            table.drops().forEach(e -> chance.put(e.item(), e.chance()));
            double ignitium = chance.get("m.materials.ignitium");
            for (String trickle : List.of("m.materials.raw_tin", "m.materials.abyssalite_fragment", "m.currency.enchanted_dust")) {
                assertEquals(ignitium / 40, chance.get(trickle), 1e-9, trickle);
            }
            assertFalse(chance.keySet().stream().anyMatch(item -> item.contains("mythril")));
        }
        assertTrue(RewardConfig.appliesTo("SWAMP_MOBS"));
        assertFalse(RewardConfig.appliesTo("crypt"));
        assertFalse(RewardConfig.appliesTo(null));
    }

    @Test void oddRowsAreSkippedAndMissingKeysUseDefaults() throws Exception {
        assertTrue(RewardConfig.loadSafe(write("""
                groups: ['', '  ', ' Bog ']
                coins:
                  - {item: '', value: 5}
                  - {item: gold, value: 0}
                  - {item: silver, value: '3'}
                ambient:
                  key-cap-per-hour: -3
                  mild:
                    gems:
                      - {item: basic, weight: 0}
                      - {weight: 4}
                      - {item: fine, weight: 2}
                    drops:
                      - {percent: 5}
                      - {item: key}
                lure:
                  min-kills: -1
                  outside-zone:
                    keep-items:
                      - {keep-percent: 50}
                      - {item: key}
                  severe:
                    drops: []
                """)));
        assertTrue(RewardConfig.enabled);
        assertEquals(Set.of("bog"), RewardConfig.groups);
        assertFalse(RewardConfig.appliesTo("swamp"));
        assertEquals(List.of(new Coin("silver", 3)), RewardConfig.coins);
        assertEquals("m.loot.trial_key", RewardConfig.keyItem);
        assertEquals(0, RewardConfig.keyCapPerHour);
        assertEquals(Map.of(Severity.MILD.id(), new RewardTable(List.of(new Entry("key", 1, 1, 1)),
                List.of(new Weighted("fine", 2)))), RewardConfig.ambient);
        assertNull(RewardConfig.ambient(Severity.MILD, false));
        assertEquals(0, RewardConfig.lureMinKills);
        assertEquals(0, RewardConfig.lureMultiplier);
        assertEquals(1.0, RewardConfig.lureKeep.of("key"));
        assertEquals(1.0, RewardConfig.lureKeep.of("other"));
        assertEquals(Set.of(Severity.SEVERE.id()), RewardConfig.lure.keySet());
        assertNull(RewardConfig.lure(Severity.MILD));
    }

    @Test void lureMultiplierIsClampedToZeroToOneHundred() throws Exception {
        assertTrue(RewardConfig.loadSafe(write("lure:\n  multiplier: -2\n")));
        assertEquals(0, RewardConfig.lureMultiplier);
        assertTrue(RewardConfig.loadSafe(write("lure:\n  multiplier: 500\n")));
        assertEquals(100, RewardConfig.lureMultiplier);
        assertTrue(RewardConfig.loadSafe(write("lure:\n  multiplier: 7\n")));
        assertEquals(7, RewardConfig.lureMultiplier);
    }

    @Test void emptyGroupsApplyEverywhereUnlessDisabled() throws Exception {
        assertTrue(RewardConfig.loadSafe(write("groups: []\n")));
        assertTrue(RewardConfig.appliesTo(null));
        assertTrue(RewardConfig.appliesTo("anything"));
        assertTrue(RewardConfig.loadSafe(write("enabled: false\n")));
        assertFalse(RewardConfig.appliesTo("anything"));
    }

    @Test void badFilesKeepThePreviousSettings() throws Exception {
        RewardConfig.keyCapPerHour = 7;
        assertFalse(RewardConfig.loadSafe(write("ambient: [unclosed\n")));
        assertFalse(RewardConfig.loadSafe(dir.resolve("missing.yml").toFile()));
        assertEquals(7, RewardConfig.keyCapPerHour);
        verify(logger, times(2)).severe(startsWith("[Infestations] Failed to load rewards.yml: "));
    }

    @Test void valueHelpersParseAndFallBack() {
        assertNull(RewardConfig.string(null));
        assertNull(RewardConfig.string("  "));
        assertEquals("a", RewardConfig.string(" a "));

        assertEquals(4, RewardConfig.integer(4.9, 0));
        assertEquals(7, RewardConfig.integer(null, 7));
        assertEquals(12, RewardConfig.integer(" 12 ", 0));
        assertEquals(3, RewardConfig.integer("x", 3));

        assertEquals(0.25, RewardConfig.percent(25, 100));
        assertEquals(0.005, RewardConfig.percent(" 0.5 ", 100), 1e-12);
        assertEquals(1.0, RewardConfig.percent(null, 100));
        assertEquals(0.5, RewardConfig.percent("half", 50));
        assertEquals(1.0, RewardConfig.percent(250, 100));
        assertEquals(0.0, RewardConfig.percent(-5, 100));

        assertArrayEquals(new int[] {3, 3}, RewardConfig.range(3));
        assertArrayEquals(new int[] {0, 0}, RewardConfig.range(-2));
        assertArrayEquals(new int[] {1, 1}, RewardConfig.range(null));
        assertArrayEquals(new int[] {4, 4}, RewardConfig.range(" 4 "));
        assertArrayEquals(new int[] {0, 0}, RewardConfig.range("-4"));
        assertArrayEquals(new int[] {2, 5}, RewardConfig.range("2 - 5"));
        assertArrayEquals(new int[] {2, 5}, RewardConfig.range("5-2"));
        assertArrayEquals(new int[] {1, 1}, RewardConfig.range("a-b"));
        assertArrayEquals(new int[] {1, 1}, RewardConfig.range("lots"));
    }
}

package net.tfminecraft.infestations.database;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.infestation.Infestation;
import net.tfminecraft.infestations.infestation.LurePhase;
import net.tfminecraft.infestations.infestation.Severity;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class InfestationDataTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000011");
    @TempDir Path folder;
    private World world;
    private Logger logger;
    private Infestations previousPlugin;

    @BeforeEach
    void setUp() {
        world = MockBukkit.mock().addSimpleWorld("world");
        previousPlugin = Infestations.plugin;
        Infestations.plugin = mock(Infestations.class);
        logger = mock(Logger.class);
        when(Infestations.plugin.getLogger()).thenReturn(logger);
        when(Infestations.plugin.getDataFolder()).thenReturn(folder.toFile());
    }

    @AfterEach
    void tearDown() {
        Infestations.plugin = previousPlugin;
        MockBukkit.unmock();
    }

    private Infestation infestation() {
        return new Infestation(42, "test-group", Severity.MILD);
    }

    private void write(String json) throws Exception {
        Path file = InfestationDatabase.file().toPath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    @Test
    void partialLureCoordinatesAreRejectedWithoutUnboxingNull() {
        Infestation infestation = infestation();
        infestation.setPhase(LurePhase.ACTIVE);
        infestation.setWorldName("world");
        infestation.setLureX(1);
        Block block = world.getBlockAt(1, 64, 2);
        assertFalse(assertDoesNotThrow(() -> infestation.isLureBlock(block)));
        assertFalse(infestation.hasLure(), "A lure requires all three coordinates");
        infestation.setLureY(64);
        assertFalse(assertDoesNotThrow(() -> infestation.isLureBlock(block)));
        assertFalse(infestation.hasLure());
        assertNull(infestation.lureLocation());
        assertNull(infestation.lureBlock());
    }

    @Test
    void nullCommittedEntryDoesNotDiscardOtherSavedInfestations() throws Exception {
        write("""
                [
                  {"provinceId":1,"groupId":"one","severity":"mild","committed":[null,"bad","%s"]},
                  {"provinceId":2,"groupId":"two","severity":"severe"}
                ]
                """.formatted(PLAYER));
        List<Infestation> loaded = InfestationDatabase.load();
        assertEquals(2, loaded.size(), "One malformed UUID must not discard every saved province");
        assertEquals(Set.of(PLAYER), loaded.getFirst().getCommitted());
        assertEquals(2, loaded.get(1).getProvinceId());
    }

    @Test
    void nullDeathEntriesDoNotDiscardValidPlayersOrOtherProvinces() throws Exception {
        write("""
                [{"provinceId":1,"groupId":"one","severity":"mild","deathOnLogin":[null,"%s"]},
                 {"provinceId":2,"groupId":"two","severity":"mild"}]
                """.formatted(PLAYER));
        List<Infestation> loaded = InfestationDatabase.load();
        assertEquals(2, loaded.size());
        assertEquals(Set.of(PLAYER), loaded.getFirst().getDeathOnLogin());
    }

    @Test
    void nullGraceDeadlinesAreNotLoadedIntoTheExpiryQueue() throws Exception {
        write("""
                [{"provinceId":1,"groupId":"one","severity":"mild","logoutGraceUntil":{"%s":null}}]
                """.formatted(PLAYER));
        List<Infestation> loaded = InfestationDatabase.load();
        assertEquals(1, loaded.size());
        assertTrue(loaded.getFirst().getLogoutGraceUntil().isEmpty(), "Expiry compares each deadline as a primitive long");
    }

    @Test
    void severityParsingAndWorseningHaveStableDisplayAndTerminalSeverity() {
        Severity[] values = Severity.values();
        for (int i = 0; i < values.length; i++) {
            Severity severity = values[i];
            assertSame(severity, Severity.fromString("  " + severity.name() + "  "));
            assertSame(severity, Severity.fromString(severity.id()));
            assertEquals(severity.id(), severity.display().toLowerCase(java.util.Locale.ROOT));
            assertTrue(Character.isUpperCase(severity.display().charAt(0)));
            assertEquals(i < values.length - 1, severity.canWorsen());
            assertSame(values[Math.min(i + 1, values.length - 1)], severity.worse());
        }
        assertNull(Severity.fromString(null));
        assertNull(Severity.fromString("unrecognised"));
        assertEquals(List.of(LurePhase.NONE, LurePhase.JOINING, LurePhase.ACTIVE), List.of(LurePhase.values()));
    }

    @Test
    void lureLifecycleLocatesTheBlockAndResetsOnlyLureState() {
        Infestation infestation = infestation();
        assertFalse(infestation.hasLure());
        assertFalse(infestation.isLureBlock(null));
        assertNull(infestation.lureLocation());
        assertNull(infestation.lureBlock());
        infestation.setGroupId("changed");
        infestation.setSeverity(Severity.EXTREME);
        assertEquals("changed", infestation.getGroupId());
        assertSame(Severity.EXTREME, infestation.getSeverity());
        infestation.setAmbientAlive(7);
        infestation.getCommitted().add(PLAYER);
        infestation.getLogoutGraceUntil().put(PLAYER, 100L);
        infestation.getDeathOnLogin().add(PLAYER);
        infestation.setWaveRetryAtTick(5);
        infestation.setVictoryAtTick(6);
        infestation.setPendingSpawns(3);
        infestation.setEnemiesAlive(4);
        infestation.setLureReleased(5);
        infestation.setLureActivatedAt(6);
        Block lure = world.getBlockAt(1, 64, 2);
        infestation.placeLure(lure, 123L, 80);
        assertTrue(infestation.hasLure());
        assertSame(LurePhase.JOINING, infestation.getPhase());
        assertEquals(new Location(world, 1.5, 64, 2.5), infestation.lureLocation());
        assertEquals(lure, infestation.lureBlock());
        assertTrue(infestation.isLureBlock(lure));
        assertFalse(infestation.isLureBlock(world.getBlockAt(2, 64, 2)));
        assertFalse(infestation.isLureBlock(world.getBlockAt(1, 65, 2)));
        assertFalse(infestation.isLureBlock(world.getBlockAt(1, 64, 3)));
        assertEquals(123L, infestation.getJoinEndsAt());
        assertEquals(80, infestation.displayRemaining());
        assertTrue(infestation.getCommitted().isEmpty());
        assertTrue(infestation.getLogoutGraceUntil().isEmpty());
        assertTrue(infestation.getDeathOnLogin().isEmpty());
        assertEquals(0, infestation.getPendingSpawns());
        assertEquals(0, infestation.getEnemiesAlive());
        assertEquals(0, infestation.getLureReleased());
        assertEquals(0, infestation.getLureActivatedAt());
        assertEquals(0, infestation.getWaveRetryAtTick());
        assertEquals(0, infestation.getVictoryAtTick());
        infestation.getCommitted().add(PLAYER);
        assertTrue(infestation.isCommitted(PLAYER));
        infestation.getLogoutGraceUntil().put(PLAYER, 100L);
        infestation.getDeathOnLogin().add(PLAYER);
        infestation.clearLure();
        assertSame(LurePhase.NONE, infestation.getPhase());
        assertFalse(infestation.hasLure());
        assertNull(infestation.getWorldName());
        assertNull(infestation.getLureX());
        assertNull(infestation.getLureY());
        assertNull(infestation.getLureZ());
        assertEquals(0, infestation.getJoinEndsAt());
        assertEquals(0, infestation.getLureRemaining());
        assertEquals(7, infestation.getAmbientAlive());
        assertFalse(infestation.isCommitted(PLAYER));
        assertTrue(infestation.getLogoutGraceUntil().isEmpty());
        assertTrue(infestation.getDeathOnLogin().isEmpty());
    }

    @Test
    void unloadedWorldDoesNotProduceALocationOrBlock() {
        Infestation infestation = infestation();
        infestation.setWorldName("unloaded");
        infestation.setLureX(1);
        infestation.setLureY(64);
        infestation.setLureZ(2);
        assertNull(infestation.lureLocation());
        assertNull(infestation.lureBlock());
        assertFalse(infestation.isLureBlock(world.getBlockAt(1, 64, 2)));
    }

    @Test
    void countsClampAtZeroAndActiveDisplayIncludesPendingAndLivingEnemies() {
        Infestation infestation = infestation();
        infestation.setLureRemaining(-1);
        infestation.setPendingSpawns(-1);
        infestation.setEnemiesAlive(-1);
        infestation.setAmbientAlive(-1);
        infestation.setWaveRetryAtTick(-1);
        infestation.setVictoryAtTick(-1);
        infestation.setLureActivatedAt(-1);
        infestation.setLureReleased(-1);
        assertEquals(0, infestation.getLureRemaining());
        assertEquals(0, infestation.getPendingSpawns());
        assertEquals(0, infestation.getEnemiesAlive());
        assertEquals(0, infestation.getAmbientAlive());
        assertEquals(0, infestation.getWaveRetryAtTick());
        assertEquals(0, infestation.getVictoryAtTick());
        assertEquals(0, infestation.getLureActivatedAt());
        assertEquals(0, infestation.getLureReleased());
        infestation.setPhase(LurePhase.ACTIVE);
        infestation.setLureRemaining(4);
        infestation.setPendingSpawns(2);
        infestation.setEnemiesAlive(3);
        assertEquals(5, infestation.displayRemaining());
        infestation.setLureRemaining(6);
        assertEquals(6, infestation.displayRemaining());
        infestation.setJoinEndsAt(0);
        assertEquals(0, infestation.joinSecondsLeft());
        infestation.setJoinEndsAt(System.currentTimeMillis() + 60_000);
        assertTrue(infestation.joinSecondsLeft() > 0 && infestation.joinSecondsLeft() <= 60);
        assertEquals(8, infestation.ambientCap());
        assertEquals(20, infestation.lureBudget());
    }

    @Test
    void persistenceRoundTripKeepsRuntimeStateButRequeuesScheduledSpawns() throws Exception {
        Infestation original = infestation();
        original.placeLure(world.getBlockAt(3, 65, 4), 1234L, 30);
        original.setPhase(LurePhase.ACTIVE);
        original.setPendingSpawns(2);
        original.setEnemiesAlive(4);
        original.setAmbientAlive(5);
        original.setLureActivatedAt(5678L);
        original.setLureReleased(9);
        original.getCommitted().add(PLAYER);
        original.getLogoutGraceUntil().put(PLAYER, 9876L);
        original.getDeathOnLogin().add(PLAYER);
        InfestationDatabase.save(List.of(original));
        assertTrue(Files.readString(InfestationDatabase.file().toPath()).contains("test-group"));
        Infestation loaded = InfestationDatabase.load().getFirst();
        assertEquals(42, loaded.getProvinceId());
        assertEquals("test-group", loaded.getGroupId());
        assertSame(Severity.MILD, loaded.getSeverity());
        assertSame(LurePhase.ACTIVE, loaded.getPhase());
        assertEquals(original.lureLocation(), loaded.lureLocation());
        assertEquals(1234L, loaded.getJoinEndsAt());
        assertEquals(30, loaded.getLureRemaining());
        assertEquals(0, loaded.getPendingSpawns());
        assertEquals(4, loaded.getEnemiesAlive());
        assertEquals(5, loaded.getAmbientAlive());
        assertEquals(5678L, loaded.getLureActivatedAt());
        assertEquals(7, loaded.getLureReleased());
        assertEquals(Set.of(PLAYER), loaded.getCommitted());
        assertEquals(9876L, loaded.getLogoutGraceUntil().get(PLAYER));
        assertEquals(Set.of(PLAYER), loaded.getDeathOnLogin());
    }

    @Test
    void malformedRowsAndLegacyFieldsDoNotDiscardValidNeighbors() throws Exception {
        write("""
                [null,
                 {"provinceId":1,"groupId":"bad","severity":"unknown"},
                 {"provinceId":2,"severity":"mild"},
                 {"provinceId":3,"groupId":"valid","severity":"mild","phase":"old-phase",
                  "committed":["bad","%s"],"logoutGraceUntil":{"bad":5,"%s":6},
                  "deathOnLogin":["bad","%s"],"pendingSpawns":20,"lureReleased":3},
                 {"provinceId":4,"groupId":"legacy","severity":"worrying","committed":null,
                  "logoutGraceUntil":null,"deathOnLogin":null}]
                """.formatted(PLAYER, PLAYER, PLAYER));
        List<Infestation> loaded = InfestationDatabase.load();
        assertEquals(2, loaded.size());
        assertSame(LurePhase.NONE, loaded.getFirst().getPhase());
        assertEquals(0, loaded.getFirst().getLureReleased());
        assertEquals(Set.of(PLAYER), loaded.getFirst().getCommitted());
        assertEquals(6L, loaded.getFirst().getLogoutGraceUntil().get(PLAYER));
        assertEquals(Set.of(PLAYER), loaded.getFirst().getDeathOnLogin());
        assertTrue(loaded.get(1).getCommitted().isEmpty());
        assertTrue(loaded.get(1).getDeathOnLogin().isEmpty());
    }

    @Test
    void missingNullCorruptAndUnreadableFilesReturnEmptyAndLogFailures() throws Exception {
        assertTrue(InfestationDatabase.load().isEmpty());
        write("null");
        assertTrue(InfestationDatabase.load().isEmpty());
        write("not-json {");
        assertTrue(InfestationDatabase.load().isEmpty());
        verify(logger).severe(contains("Failed to load infestations"));
        Files.delete(InfestationDatabase.file().toPath());
        Files.createDirectory(InfestationDatabase.file().toPath());
        assertTrue(InfestationDatabase.load().isEmpty());
        verify(logger, times(2)).severe(contains("Failed to load infestations"));
    }

    @Test
    void failedSaveIsLoggedAndDoesNotThrow() throws Exception {
        Files.writeString(folder.resolve("Data"), "a regular file blocks the data directory");
        assertDoesNotThrow(() -> InfestationDatabase.save(List.of(infestation())));
        verify(logger).severe(contains("Failed to save infestations"));
    }
}

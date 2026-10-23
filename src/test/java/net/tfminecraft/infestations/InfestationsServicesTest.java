package net.tfminecraft.infestations;

import com.google.gson.JsonParser;
import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.database.InfestationDatabase;
import net.tfminecraft.infestations.infestation.*;
import net.tfminecraft.infestations.loader.GroupLoader;
import net.tfminecraft.infestations.map.InfestationMapExport;
import net.tfminecraft.infestations.spawn.SpawnLog;
import net.tfminecraft.infestations.utils.Characters;
import net.tfminecraft.infestations.utils.Provinces;
import net.tfminecraft.tlibs.character.CharacterDeathEvent;
import net.tfminecraft.tlibs.character.CharacterIdentity;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent.Reason;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.simplefactions.SimpleFactions;
import net.tfminecraft.simplefactions.enums.Terrain;
import net.tfminecraft.simplefactions.map.ProvinceGrid;
import net.tfminecraft.simplefactions.map.provinces.Province;
import net.tfminecraft.simplefactions.managers.ProvinceManager;
import net.tfminecraft.simplefactions.rest.RestServer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InfestationsServicesTest {
    @TempDir Path directory;
    private InfestationsTestState state;
    private Logger logger;
    @BeforeEach void setup() throws Exception {
        MockBukkit.mock(); state = new InfestationsTestState();
        Infestations.plugin = mock(Infestations.class); logger = mock(Logger.class);
        when(Infestations.plugin.getLogger()).thenReturn(logger);
        when(Infestations.plugin.getDataFolder()).thenReturn(directory.toFile());
    }
    @AfterEach void teardown() throws Exception { state.close(); MockBukkit.unmock(); }
    @Test void messagesLoadPrefixPlaceholdersColorsAndRecoverFromBadFiles() throws Exception {
        var field = Messages.class.getDeclaredField("config"); field.setAccessible(true); field.set(null,null);
        assertEquals("missing", Messages.getRaw("missing"));
        assertEquals("hello", Messages.get("hello"));
        assertEquals("", Messages.get(null));
        assertEquals("§cfallback", Messages.getOrDefault("missing", "{prefix}&cfallback"));
        Path file = Files.writeString(directory.resolve("messages.yml"), "prefix: '&a[Infestations] '\ngreet: '{prefix}Hello {who}{blank}'\n");
        Messages.load(file.toFile());
        assertEquals("§a[Infestations] Hello Ryan", Messages.get("greet", "who", "Ryan", "blank", null, "unpaired"));
        assertEquals("missing", Messages.getRaw("missing"));
        assertEquals("§a[Infestations] fallback", Messages.getOrDefault("missing", "{prefix}fallback"));
        assertEquals("§a[Infestations] Hello {who}{blank}", Messages.getOrDefault("greet", "fallback"));
        Files.writeString(file, "invalid: [bad"); Messages.load(file.toFile());
        assertEquals("missing", Messages.get("missing"));
        Messages.load(directory.resolve("absent").toFile());
        verify(logger, times(2)).severe(contains("Failed to load messages"));
    }
    @Test void sharedCharacterLookupsRequireReadySessionsAndVerifyHistoricalOwnership() {
        Player player = MockBukkit.getMock().addPlayer();
        UUID alice = UUID.fromString("ABCDEF00-0000-0000-0000-000000000001");
        assertFalse(Characters.enabled());
        assertNull(Characters.session(player));
        assertNull(Characters.activeId(player));
        assertFalse(Characters.isDead(player, alice.toString()));
        try (CharacterProvider provider = new CharacterProvider()) {
            assertTrue(Characters.enabled());
            assertNull(Characters.session(player));
            assertNull(Characters.activeId(player));
            CharacterSession first = provider.activate(player, alice);
            assertEquals(first, Characters.session(player));
            assertEquals("abcdef00-0000-0000-0000-000000000001", Characters.activeId(player));
            assertTrue(Characters.isCurrent(first));
            provider.invalidate(player);
            assertFalse(Characters.isCurrent(first));
            assertNull(Characters.activeId(player));
            CharacterSession next = provider.activate(player, alice);
            assertNotEquals(first, next);
            assertFalse(Characters.isCurrent(first));
            assertTrue(Characters.isCurrent(next));

            for (CharacterStatus status : CharacterStatus.values()) {
                provider.describe(player.getUniqueId(), alice, status);
                assertEquals(status != CharacterStatus.ALIVE, Characters.isDead(player, alice.toString()), status.name());
            }
            provider.describe(UUID.randomUUID(), alice, CharacterStatus.DEAD);
            assertFalse(Characters.isDead(player, alice.toString()));
            assertFalse(Characters.isDead(player, UUID.randomUUID().toString()));
            assertFalse(Characters.isDead(player, "malformed-character"));
            UUID shortId = UUID.fromString("1-1-1-1-1");
            provider.describe(player.getUniqueId(), shortId, CharacterStatus.DEAD);
            assertFalse(Characters.isDead(player, "1-1-1-1-1"));
            provider.unregister();
            assertFalse(Characters.enabled());
            assertNull(Characters.session(player));
            assertFalse(Characters.isCurrent(next));
            assertFalse(Characters.isDead(player, alice.toString()));
        }
    }
    @ParameterizedTest
    @EnumSource(value = Reason.class, names = {"SWITCH", "QUIT"})
    void sharedEndRecordsTheInvalidatedOutgoingCharacterAndPreservesPriorDebt(Reason reason) {
        Player player = MockBukkit.getMock().addPlayer();
        try (CharacterProvider provider = new CharacterProvider();
                var database = mockStatic(InfestationDatabase.class);
                var export = mockStatic(InfestationMapExport.class)) {
            InfestationManager manager = registeredManager(provider);
            UUID owner = player.getUniqueId(), alice = UUID.randomUUID(), bob = UUID.randomUUID();
            Infestation current = commitment(manager, 1, player);
            Infestation previous = commitment(manager, 2, player);
            previous.getLogoutCharacter().put(owner, bob.toString());
            previous.getLogoutGraceUntil().put(owner, 1234L);
            CharacterSession outgoing = provider.activate(player, alice);
            provider.invalidate(player);
            assertNull(Characters.session(player));
            assertFalse(Characters.isCurrent(outgoing));

            long before = System.currentTimeMillis();
            Bukkit.getPluginManager().callEvent(new CharacterSessionEndEvent(player, outgoing, reason));
            assertEquals(alice.toString(), current.getLogoutCharacter().get(owner));
            long deadline = current.getLogoutGraceUntil().get(owner);
            assertTrue(deadline >= before + Cache.logoutGraceSeconds * 1000L);
            assertEquals(Map.of(owner, bob.toString()), previous.getLogoutCharacter());
            assertEquals(Map.of(owner, 1234L), previous.getLogoutGraceUntil());
            Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty()));
            assertEquals(deadline, current.getLogoutGraceUntil().get(owner));
        }
    }
    @Test void sharedShutdownEndRecordsNoDeparture() {
        Player player = MockBukkit.getMock().addPlayer();
        try (CharacterProvider provider = new CharacterProvider();
                var database = mockStatic(InfestationDatabase.class);
                var export = mockStatic(InfestationMapExport.class)) {
            InfestationManager manager = registeredManager(provider);
            Infestation lure = commitment(manager, 1, player);
            CharacterSession outgoing = provider.activate(player, UUID.randomUUID());
            provider.invalidate(player);
            Bukkit.getPluginManager().callEvent(new CharacterSessionEndEvent(player, outgoing, Reason.SHUTDOWN));
            assertTrue(lure.isCommitted(player.getUniqueId()));
            assertTrue(lure.getLogoutCharacter().isEmpty());
            assertTrue(lure.getLogoutGraceUntil().isEmpty());
        }
    }
    @Test void sharedDeathSettlesOnlyOnceSavedAndOnlyTheDeadCharactersCommitments() {
        Player player = MockBukkit.getMock().addPlayer();
        try (CharacterProvider provider = new CharacterProvider();
                var database = mockStatic(InfestationDatabase.class);
                var export = mockStatic(InfestationMapExport.class)) {
            InfestationManager manager = registeredManager(provider);
            UUID owner = player.getUniqueId(), alice = UUID.randomUUID(), bob = UUID.randomUUID();
            Infestation matching = commitment(manager, 1, player);
            Infestation ongoing = commitment(manager, 2, player);
            Infestation other = commitment(manager, 3, player);
            for (Infestation infestation : List.of(matching, other)) {
                infestation.getLogoutGraceUntil().put(owner, 1234L);
                infestation.getDeathOnLogin().add(owner);
            }
            matching.getLogoutCharacter().put(owner, alice.toString().toUpperCase(Locale.ROOT));
            other.getLogoutCharacter().put(owner, bob.toString());
            CharacterSession outgoing = provider.activate(player, alice);
            provider.invalidate(player);
            // The session ends before RPCharacters saves the death; a failed save leaves the character alive.
            Bukkit.getPluginManager().callEvent(new CharacterSessionEndEvent(player, outgoing, Reason.DEATH));
            for (Infestation infestation : List.of(matching, ongoing, other)) {
                assertTrue(infestation.isCommitted(owner));
            }
            assertTrue(ongoing.getLogoutGraceUntil().isEmpty());

            provider.describe(owner, alice, CharacterStatus.DEAD);
            CharacterDeathEvent death = new CharacterDeathEvent(new CharacterRef(owner, alice), UUID.randomUUID(),
                    java.time.Instant.now(), null, null, "LURE");
            Bukkit.getPluginManager().callEvent(death);
            for (Infestation infestation : List.of(matching, ongoing)) {
                assertFalse(infestation.isCommitted(owner));
                assertTrue(infestation.getLogoutCharacter().isEmpty());
                assertTrue(infestation.getLogoutGraceUntil().isEmpty());
                assertTrue(infestation.getDeathOnLogin().isEmpty());
            }
            assertTrue(other.isCommitted(owner));
            assertEquals(Map.of(owner, bob.toString()), other.getLogoutCharacter());
            assertEquals(Map.of(owner, 1234L), other.getLogoutGraceUntil());
            assertEquals(Set.of(owner), other.getDeathOnLogin());

            // A replay after the character is revived and joins again settles nothing.
            provider.describe(owner, alice, CharacterStatus.ALIVE);
            provider.activate(player, alice);
            matching.getCommitted().add(owner);
            Bukkit.getPluginManager().callEvent(death);
            assertTrue(matching.isCommitted(owner));
        }
    }
    @Test void sharedStartAppliesOnlyTheCurrentCharactersGraceAndDeath() {
        Player player = actor();
        try (CharacterProvider provider = new CharacterProvider();
                var database = mockStatic(InfestationDatabase.class);
                var export = mockStatic(InfestationMapExport.class)) {
            InfestationManager manager = registeredManager(provider);
            UUID owner = player.getUniqueId(), alice = UUID.randomUUID();
            Infestation grace = commitment(manager, 1, player);
            Infestation debt = commitment(manager, 2, player);
            grace.getLogoutCharacter().put(owner, alice.toString());
            grace.getLogoutGraceUntil().put(owner, Long.MAX_VALUE);
            debt.getLogoutCharacter().put(owner, alice.toString());
            debt.getDeathOnLogin().add(owner);
            CharacterSession stale = provider.activate(player, alice);
            CharacterSession bob = provider.activate(player, UUID.randomUUID());
            Bukkit.getPluginManager().callEvent(new CharacterSessionStartEvent(player, stale));
            Bukkit.getPluginManager().callEvent(new CharacterSessionStartEvent(player, bob));
            assertEquals(Map.of(owner, Long.MAX_VALUE), grace.getLogoutGraceUntil());
            assertEquals(Set.of(owner), debt.getDeathOnLogin());
            verify(player, never()).damage(anyDouble());

            CharacterSession returned = provider.activate(player, alice);
            Bukkit.getPluginManager().callEvent(new CharacterSessionStartEvent(player, returned));
            assertTrue(grace.getLogoutGraceUntil().isEmpty());
            assertTrue(grace.getLogoutCharacter().isEmpty());
            verify(player).damage(40.0);
            // The kill did not land here, so the debt waits for a settled death.
            assertEquals(Set.of(owner), debt.getDeathOnLogin());
            assertEquals(Map.of(owner, alice.toString()), debt.getLogoutCharacter());
        }
    }
    @Test void unregisteringTheProviderHoldsTaggedPenaltiesInsteadOfUsingTheAccount() {
        Player player = actor();
        try (CharacterProvider provider = new CharacterProvider();
                var database = mockStatic(InfestationDatabase.class);
                var export = mockStatic(InfestationMapExport.class)) {
            InfestationManager manager = registeredManager(provider);
            UUID owner = player.getUniqueId(), alice = UUID.randomUUID();
            CharacterSession session = provider.activate(player, alice);
            Bukkit.getPluginManager().callEvent(new CharacterSessionStartEvent(player, session));
            Infestation grace = commitment(manager, 1, player);
            Infestation debt = commitment(manager, 2, player);
            grace.getLogoutCharacter().put(owner, alice.toString());
            grace.getLogoutGraceUntil().put(owner, Long.MAX_VALUE);
            debt.getLogoutCharacter().put(owner, alice.toString());
            debt.getDeathOnLogin().add(owner);
            provider.unregister();

            manager.characterReturned(player);
            Bukkit.getPluginManager().callEvent(new CharacterSessionStartEvent(player, session));
            Bukkit.getPluginManager().callEvent(new PlayerJoinEvent(player, net.kyori.adventure.text.Component.empty()));
            Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty()));
            assertFalse(manager.leaveLure(player));
            verify(player, never()).damage(anyDouble());
            assertEquals(Map.of(owner, Long.MAX_VALUE), grace.getLogoutGraceUntil());
            assertEquals(Set.of(owner), debt.getDeathOnLogin());
            for (Infestation infestation : List.of(grace, debt)) {
                assertTrue(infestation.isCommitted(owner));
                assertEquals(Map.of(owner, alice.toString()), infestation.getLogoutCharacter());
            }
        }
    }
    private InfestationManager registeredManager(CharacterProvider provider) {
        InfestationManager manager = new InfestationManager(Infestations.plugin);
        Bukkit.getPluginManager().registerEvents(manager, provider.plugin);
        return manager;
    }

    private Infestation commitment(InfestationManager manager, int province, Player player) {
        manager.set(province, "zombies", Severity.MILD);
        Infestation infestation = manager.get(province);
        infestation.getCommitted().add(player.getUniqueId());
        return infestation;
    }

    private Player actor() {
        PlayerMock player = spy(new PlayerMock(MockBukkit.getMock(), "Participant"));
        doNothing().when(player).damage(anyDouble());
        MockBukkit.getMock().addPlayer(player);
        return player;
    }

    private static final class CharacterProvider implements CharacterIdentity, AutoCloseable {
        private final Plugin plugin = MockBukkit.createMockPlugin("InfestationsIdentityTest");
        private final Map<UUID, CharacterSession> sessions = new HashMap<>();
        private final Map<UUID, CharacterInfo> directory = new HashMap<>();
        private long nextSession;

        private CharacterProvider() {
            Bukkit.getServicesManager().register(CharacterIdentity.class, this, plugin, ServicePriority.Normal);
        }

        private CharacterSession activate(Player player, UUID characterId) {
            CharacterSession session = new CharacterSession(new CharacterRef(player.getUniqueId(), characterId), ++nextSession);
            sessions.put(player.getUniqueId(), session);
            return session;
        }

        private void invalidate(Player player) {
            sessions.remove(player.getUniqueId());
        }

        private void describe(UUID owner, UUID characterId, CharacterStatus status) {
            directory.put(characterId, new CharacterInfo(new CharacterRef(owner, characterId),
                    "Character", status, null, Set.of(), 0, null));
        }

        @Override public Optional<CharacterSession> currentSession(Player player) {
            return player != null && player.isOnline()
                    ? Optional.ofNullable(sessions.get(player.getUniqueId())) : Optional.empty();
        }

        @Override public boolean isCurrent(CharacterSession session) {
            return session != null && session.equals(sessions.get(session.ref().ownerId()));
        }

        @Override public Optional<CharacterInfo> info(UUID characterId) {
            return Optional.ofNullable(directory.get(characterId));
        }

        @Override public Optional<List<CharacterInfo>> characters(UUID ownerId) {
            return Optional.of(directory.values().stream().filter(info -> info.ref().ownerId().equals(ownerId)).toList());
        }

        @Override public Optional<CharacterInfo> lastPlayed(UUID ownerId) { return Optional.empty(); }
        @Override public String displayName(Player player) { return player.getName(); }

        private void unregister() {
            Bukkit.getServicesManager().unregister(CharacterIdentity.class, this);
        }

        @Override public void close() {
            HandlerList.unregisterAll(plugin);
            unregister();
        }
    }

    @Test void provinceAdaptersHandleUnavailableDependenciesAndKnownTerrain() {
        try (var sf = mockStatic(SimpleFactions.class); var rest = mockStatic(RestServer.class)) {
            assertEquals(-2, Provinces.at((Player)null)); assertEquals(-2, Provinces.at((Location)null));
            Player player = mock(Player.class); rest.when(() -> RestServer.getProvince(player)).thenReturn(7);
            assertEquals(7, Provinces.at(player));
            Location location = new Location(null, 12, 64, -8);
            assertEquals(-2, Provinces.at(location)); assertNull(Provinces.terrain(1)); assertEquals(Set.of(), Provinces.neighbours(1));
            SimpleFactions factions = mock(SimpleFactions.class); sf.when(SimpleFactions::getInstance).thenReturn(factions);
            assertEquals(-2, Provinces.at(location)); assertNull(Provinces.terrain(1)); assertEquals(Set.of(), Provinces.neighbours(1));
            ProvinceGrid grid = mock(ProvinceGrid.class); when(factions.getProvinceGrid()).thenReturn(grid); when(grid.getAt(12,-8)).thenReturn(7);
            assertEquals(7, Provinces.at(location));
            ProvinceManager manager = mock(ProvinceManager.class); when(factions.getProvinceManager()).thenReturn(manager);
            assertNull(Provinces.terrain(-1)); assertNull(Provinces.terrain(1)); assertEquals(Set.of(), Provinces.neighbours(0)); assertEquals(Set.of(), Provinces.neighbours(1));
            assertTrue(Provinces.skipTerrain(1)); assertFalse(Provinces.validLand(-1));
            Province province = mock(Province.class); when(manager.get(7)).thenReturn(province);
            when(province.getNeighbours()).thenReturn(Set.of(8)); assertEquals(Set.of(8), Provinces.neighbours(7));
            when(province.getTerrain()).thenReturn(Terrain.WATER); assertTrue(Provinces.isWater(7)); assertFalse(Provinces.isSea(7));
            when(province.getTerrain()).thenReturn(Terrain.SEA); assertTrue(Provinces.isSea(7)); assertFalse(Provinces.isWater(7));
            Cache.skipTerrains = Set.of("sea"); assertFalse(Provinces.validLand(7));
            Cache.skipTerrains = Set.of(); assertTrue(Provinces.validLand(7));
        }
    }
    @Test void terrainNamesUseStableLocale() {
        Locale previous = Locale.getDefault();
        try (var sf = mockStatic(SimpleFactions.class)) {
            SimpleFactions factions = mock(SimpleFactions.class, RETURNS_DEEP_STUBS);
            sf.when(SimpleFactions::getInstance).thenReturn(factions);
            when(factions.getProvinceManager().get(7).getTerrain()).thenReturn(Terrain.HILLS);
            Cache.skipTerrains = Set.of("hills"); Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(Provinces.skipTerrain(7));
        } finally { Locale.setDefault(previous); }
    }
    @Test void spawnLogAppendsCoordinatesWipesAndToleratesUnwritablePaths() throws Exception {
        Infestation infestation = new Infestation(7, "rats", Severity.MILD);
        SpawnLog.configure(false, false, directory.toFile()); SpawnLog.line(infestation,"Ryan","ignored");
        assertFalse(Files.exists(directory.resolve("logs")));
        SpawnLog.configure(true, true, null); SpawnLog.line(infestation,null,"no-file");
        SpawnLog.configure(true, false, directory.toFile()); SpawnLog.line(null,null,"ignored");
        SpawnLog.line(infestation,null,"first"); SpawnLog.spawned(infestation,"Ryan",null,1,2);
        SpawnLog.spawned(infestation,"Ryan",new Location(null,1.2,64,-2.2),2,2);
        Path file = directory.resolve("logs/spawn.log"); String contents = Files.readString(file);
        assertTrue(contents.contains("p=7 mild rats - first"));
        assertTrue(contents.contains("spawned alive=1/2")); assertTrue(contents.contains("spawned 1,64,-3 alive=2/2"));
        assertEquals(3, contents.lines().count());
        SpawnLog.configure(true, true, directory.toFile()); assertFalse(Files.exists(file));
        Files.createDirectories(file); Files.writeString(file.resolve("child"),"blocked");
        SpawnLog.configure(true, true, directory.toFile());
        assertDoesNotThrow(() -> SpawnLog.line(infestation,null,"write-fails"));
    }
    @Test void mapExportCapturesSnapshotAndHandlesWriteAndUploadFailures() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var rest = mockStatic(RestServer.class); var groups = mockStatic(GroupLoader.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            List<Runnable> pending = new ArrayList<>();
            when(scheduler.runTaskAsynchronously(eq(Infestations.plugin), any(Runnable.class))).thenAnswer(call -> { pending.add(call.getArgument(1)); return null; });
            groups.when(() -> GroupLoader.displayName("rats")).thenReturn("Rats");
            List<Infestation> infestations = new ArrayList<>(List.of(new Infestation(7,"rats",Severity.MILD)));
            InfestationMapExport.exportAsync(infestations); infestations.clear(); pending.removeFirst().run();
            File file = directory.resolve("MapAPI/infestation_data.json").toFile();
            var province = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject().getAsJsonArray("provinces").get(0).getAsJsonObject();
            assertEquals(7, province.get("id").getAsInt()); assertEquals("Rats",province.get("display").getAsString());
            rest.verify(() -> RestServer.upload("infestation_data",file));
            rest.when(() -> RestServer.upload("infestation_data",file)).thenThrow(new IllegalStateException("offline"));
            InfestationMapExport.exportAsync(List.of()); pending.removeFirst().run();
            verify(logger).warning(contains("Map upload failed: offline"));
            Files.delete(file.toPath()); Files.delete(file.toPath().getParent()); Files.writeString(file.toPath().getParent(),"blocked");
            InfestationMapExport.exportAsync(List.of()); pending.removeFirst().run();
            verify(logger).warning(contains("Map export write failed"));
            rest.verify(() -> RestServer.upload("infestation_data",file),times(2));
        }
    }
}

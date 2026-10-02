package net.tfminecraft.infestations;

import com.google.gson.JsonParser;
import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.infestation.*;
import net.tfminecraft.infestations.loader.GroupLoader;
import net.tfminecraft.infestations.map.InfestationMapExport;
import net.tfminecraft.infestations.spawn.SpawnLog;
import net.tfminecraft.infestations.utils.Provinces;
import net.tfminecraft.simplefactions.SimpleFactions;
import net.tfminecraft.simplefactions.enums.Terrain;
import net.tfminecraft.simplefactions.map.ProvinceGrid;
import net.tfminecraft.simplefactions.map.provinces.Province;
import net.tfminecraft.simplefactions.managers.ProvinceManager;
import net.tfminecraft.simplefactions.rest.RestServer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
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
        Path file = Files.writeString(directory.resolve("messages.yml"), "prefix: '&a[Infestations] '\ngreet: '{prefix}Hello {who}{blank}'\n");
        Messages.load(file.toFile());
        assertEquals("§a[Infestations] Hello Ryan", Messages.get("greet", "who", "Ryan", "blank", null, "unpaired"));
        assertEquals("missing", Messages.getRaw("missing"));
        Files.writeString(file, "invalid: [bad"); Messages.load(file.toFile());
        assertEquals("missing", Messages.get("missing"));
        Messages.load(directory.resolve("absent").toFile());
        verify(logger, times(2)).severe(contains("Failed to load messages"));
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

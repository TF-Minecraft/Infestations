package net.tfminecraft.infestations.lure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.Messages;
import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.infestation.*;
import net.tfminecraft.infestations.spawn.Keys;
import net.tfminecraft.infestations.utils.Provinces;
import net.tfminecraft.interactiblefurniture.InteractibleFurniture;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.FurnitureType;
import net.tfminecraft.interactiblefurniture.manager.FurnitureManager;

@SuppressWarnings("deprecation")
class LurePresentationTest {
    private final List<AutoCloseable> mocks = new ArrayList<>();
    private final List<Player> players = new ArrayList<>();
    private final List<Entity> nearby = new ArrayList<>();
    private final Set<Furniture> furnishings = new LinkedHashSet<>();
    private final List<TextDisplay> spawned = new ArrayList<>();
    private Infestations previous;
    private String previousItem;
    private double previousRange;
    private Infestations plugin;
    private InfestationManager infestations;
    private PluginManager plugins;
    private InteractibleFurniture furniturePlugin;
    private FurnitureManager furnitureManager;
    private World world;
    private Chunk chunk;
    private Block block;
    private Infestation state;
    private boolean unsupportedDisplayOptions;

    @BeforeAll static void initializeRegistries() { MockBukkit.mock(); }
    @AfterAll static void releaseRegistries() { MockBukkit.unmock(); }
    @BeforeEach void setup() {
        previous = Infestations.plugin; previousItem = Cache.lureItem; previousRange = Cache.hologramViewRange;
        Cache.lureItem = "ia.tfmc:lure"; Cache.hologramViewRange = 96;
        plugin = mock(Infestations.class); Infestations.plugin = plugin;
        when(plugin.getName()).thenReturn("Infestations"); when(plugin.namespace()).thenReturn("infestations"); when(plugin.getLogger()).thenReturn(mock(Logger.class));
        infestations = mock(InfestationManager.class); when(plugin.getInfestationManager()).thenReturn(infestations);
        MockedStatic<Bukkit> bukkit = scoped(mockStatic(Bukkit.class));
        plugins = mock(PluginManager.class); bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(players);
        furniturePlugin = mock(InteractibleFurniture.class); furnitureManager = mock(FurnitureManager.class);
        when(furniturePlugin.isEnabled()).thenReturn(true); when(furniturePlugin.getFurnitureManager()).thenReturn(furnitureManager);
        when(plugins.getPlugin("InteractibleFurniture")).thenReturn(furniturePlugin);
        MockedStatic<InteractibleFurniture> furnitureApi = scoped(mockStatic(InteractibleFurniture.class));
        furnitureApi.when(InteractibleFurniture::getInstance).thenReturn(furniturePlugin);
        MockedStatic<Provinces> provinces = scoped(mockStatic(Provinces.class));
        provinces.when(() -> Provinces.at(any(Location.class))).thenReturn(1);
        scoped(mockStatic(Messages.class, invocation -> invocation.getArgument(0)));
        world = mock(World.class); when(world.getName()).thenReturn("world");
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        chunk = mock(Chunk.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);
        when(world.getChunkAt(any(Location.class))).thenReturn(chunk);
        block = mock(Block.class); when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(5); when(block.getY()).thenReturn(64); when(block.getZ()).thenReturn(5);
        when(block.getLocation()).thenAnswer(i -> new Location(world, 5, 64, 5));
        when(world.getBlockAt(5, 64, 5)).thenReturn(block);
        when(world.getNearbyEntities(any(Location.class), eq(2.0), eq(2.0), eq(2.0))).thenReturn(nearby);
        when(furnitureManager.getFurnitureInChunk(chunk)).thenReturn(furnishings);
        when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class))).thenAnswer(i -> {
            TextDisplay display = display(null);
            if (unsupportedDisplayOptions) {
                doThrow(new UnsupportedOperationException()).when(display).setBillboard(any());
                doThrow(new UnsupportedOperationException()).when(display).setShadowed(anyBoolean());
            }
            Consumer<TextDisplay> initializer = i.getArgument(2); initializer.accept(display);
            spawned.add(display); nearby.add(display); return display;
        });
        state = new Infestation(1, "zombies", Severity.MILD);
        state.placeLure(block, System.currentTimeMillis() + 10_000, 20);
        when(infestations.get(1)).thenReturn(state);
    }
    @AfterEach void cleanup() throws Exception {
        Collections.reverse(mocks); for (AutoCloseable mock : mocks) mock.close();
        Infestations.plugin = previous; Cache.lureItem = previousItem; Cache.hologramViewRange = previousRange;
    }
    private <T extends AutoCloseable> T scoped(T mock) { mocks.add(mock); return mock; }
    private Furniture furniture(String item, Location loc) {
        Furniture f = mock(Furniture.class); FurnitureType type = mock(FurnitureType.class);
        when(type.getItemPath()).thenReturn(item); when(f.getType()).thenReturn(type); when(f.getLoc()).thenReturn(loc);
        furnishings.add(f); return f;
    }
    private Location lureLocation() { return new Location(world, 5.5, 64, 5.5); }
    private Player player(World playerWorld, double x) {
        Player p = mock(Player.class); when(p.getWorld()).thenReturn(playerWorld);
        when(p.getLocation()).thenReturn(new Location(playerWorld, x, 65.6, 5.5)); players.add(p); return p;
    }
    private TextDisplay display(Integer province) {
        TextDisplay display = mock(TextDisplay.class); PersistentDataContainerMock pdc = new PersistentDataContainerMock();
        when(display.getPersistentDataContainer()).thenReturn(pdc);
        if (province != null) Keys.tagDisplay(pdc, province);
        return display;
    }

    @Test void furnitureAvailabilityAndItemMatchingHandleMissingDisabledAndConfiguredPlugins() {
        assertTrue(LureFurniture.available()); when(furniturePlugin.isEnabled()).thenReturn(false);
        assertFalse(LureFurniture.available()); when(plugins.getPlugin("InteractibleFurniture")).thenReturn(null);
        assertFalse(LureFurniture.available()); assertFalse(LureFurniture.isLure(null));
        Furniture f = mock(Furniture.class); assertFalse(LureFurniture.isLure(f));
        FurnitureType type = mock(FurnitureType.class); when(f.getType()).thenReturn(type);
        assertFalse(LureFurniture.isLure(f)); when(type.getItemPath()).thenReturn(" "); assertFalse(LureFurniture.isLure(f));
        when(type.getItemPath()).thenReturn("ia.tfmc:chair"); assertFalse(LureFurniture.isLure(f));
        when(type.getItemPath()).thenReturn("IA.TFMC:LURE"); assertTrue(LureFurniture.isLure(f));
    }

    @Test void furnitureMatchingRequiresActiveLureSameWorldAndAllCoordinates() {
        Furniture f = furniture(Cache.lureItem, lureLocation());
        assertTrue(LureFurniture.matches(state, f)); assertFalse(LureFurniture.matches(null, f));
        assertFalse(LureFurniture.matches(new Infestation(2, "other", Severity.MILD), f));
        assertFalse(LureFurniture.matches(state, null));
        when(f.getLoc()).thenReturn(null); assertFalse(LureFurniture.matches(state, f));
        when(f.getLoc()).thenReturn(new Location(null, 5, 64, 5)); assertFalse(LureFurniture.matches(state, f));
        World other = mock(World.class); when(other.getName()).thenReturn("other");
        when(f.getLoc()).thenReturn(new Location(other, 5, 64, 5)); assertFalse(LureFurniture.matches(state, f));
        for (Location wrong : List.of(new Location(world, 6, 64, 5), new Location(world, 5, 65, 5), new Location(world, 5, 64, 6))) {
            when(f.getLoc()).thenReturn(wrong); assertFalse(LureFurniture.matches(state, f));
        }
    }

    @Test void removingFurnitureHonorsAvailabilityNullStateChunkLoadingAndExactMatch() {
        Furniture matching = furniture(Cache.lureItem, lureLocation());
        Furniture wrongLocation = furniture(Cache.lureItem, new Location(world, 9, 64, 5));
        Furniture ordinary = furniture("ia.tfmc:chair", lureLocation());
        LureFurniture.remove(null); LureFurniture.removeFor(null);
        LureFurniture.removeFor(new Infestation(2, "none", Severity.MILD));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false); LureFurniture.removeFor(state);
        verify(furnitureManager, never()).removePlugin(any());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true); LureFurniture.removeFor(state);
        verify(furnitureManager).removePlugin(matching);
        verify(furnitureManager, never()).removePlugin(wrongLocation); verify(furnitureManager, never()).removePlugin(ordinary);
        when(furniturePlugin.isEnabled()).thenReturn(false); LureFurniture.remove(matching); LureFurniture.removeFor(state);
        verify(furnitureManager, times(1)).removePlugin(matching);
    }

    @Test void sweepingUsesSnapshotAndOnlyRemovesOrphanLures() {
        Furniture active = furniture(Cache.lureItem, lureLocation());
        Furniture orphan = furniture(Cache.lureItem, new Location(world, 10, 64, 5));
        Furniture ordinary = furniture("ia.tfmc:chair", lureLocation());
        when(furnitureManager.removePlugin(any())).thenAnswer(i -> furnishings.remove(i.getArgument(0)));
        LureFurniture.sweepChunk(chunk, id -> state);
        assertEquals(Set.of(active, ordinary), furnishings); verify(furnitureManager).removePlugin(orphan);
        LureFurniture.sweepChunk(chunk, id -> null);
        assertEquals(Set.of(ordinary), furnishings); verify(furnitureManager).removePlugin(active);
        LureFurniture.sweepChunk(null, id -> state);
        when(furniturePlugin.isEnabled()).thenReturn(false); LureFurniture.sweepChunk(chunk, id -> state);
        verify(furnitureManager, never()).removePlugin(ordinary);
    }

    @Test void hologramIgnoresMissingLuresAndRemovesExistingDisplayWhenNobodyIsNearby() {
        Infestation idle = new Infestation(2, "idle", Severity.MILD);
        LureHologram.tick(idle); LureHologram.remove(idle);
        TextDisplay display = display(1); nearby.add(display);
        player(mock(World.class), 5); player(world, 1000);
        LureHologram.tick(state); verify(display).remove(); assertTrue(spawned.isEmpty());
        nearby.clear(); LureHologram.tick(state); assertTrue(spawned.isEmpty());
        state.setPhase(LurePhase.NONE); LureHologram.tick(state); assertTrue(spawned.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void hologramSpawnSetsPresentationTagsAndJoiningOrActiveText(boolean unsupported) {
        unsupportedDisplayOptions = unsupported; player(world, 5.5);
        LureHologram.tick(state); assertEquals(1, spawned.size()); TextDisplay display = spawned.getFirst();
        verify(world).spawn(eq(new Location(world, 5.5, 65.6, 5.5)), eq(TextDisplay.class), any(Consumer.class));
        verify(display).setInvulnerable(true); verify(display).setPersistent(true); verify(display).setGravity(false);
        verify(display).setBillboard(Display.Billboard.CENTER); verify(display).setShadowed(true);
        assertEquals(1, Keys.displayProvince(display.getPersistentDataContainer()));
        verify(display).setText("hologram-joining");
        state.setPhase(LurePhase.ACTIVE); LureHologram.tick(state); assertEquals(1, spawned.size());
        verify(display).setText("hologram-remaining");
    }

    @Test void hologramFindSkipsUnrelatedEntitiesAndReplacesDeadMatchingDisplay() {
        player(world, 5.5);
        TextDisplay untagged = display(null), wrongProvince = display(2), dead = display(1);
        when(dead.isDead()).thenReturn(true);
        nearby.addAll(List.of(mock(Entity.class), untagged, wrongProvince, dead));
        LureHologram.tick(state); assertEquals(1, spawned.size());
        verify(untagged, never()).setText(anyString()); verify(wrongProvince, never()).setText(anyString());
        verify(spawned.getFirst()).setText("hologram-joining");
        LureHologram.remove(state); verify(dead).remove();
        nearby.clear(); LureHologram.remove(state);
    }

    @Test void orphanCleanupKeepsUntaggedAndActiveDisplaysButRemovesMissingAndInactiveStates() {
        TextDisplay untagged = display(null), active = display(1), missing = display(2), idle = display(3);
        when(infestations.get(3)).thenReturn(new Infestation(3, "zombies", Severity.MILD));
        when(chunk.getEntities()).thenReturn(new Entity[]{mock(Entity.class), untagged, active, missing, idle});
        LureHologram.removeOrphans(chunk);
        verify(missing).remove(); verify(idle).remove();
        verify(active, never()).remove(); verify(untagged, never()).remove();
    }
}

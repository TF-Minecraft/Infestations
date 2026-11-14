package net.tfminecraft.infestations.reward;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.InfestationsTestState;
import net.tfminecraft.infestations.Messages;
import net.tfminecraft.infestations.infestation.Infestation;
import net.tfminecraft.infestations.infestation.Severity;
import net.tfminecraft.infestations.reward.RewardTable.Entry;
import net.tfminecraft.tlibs.TLibs;

class RewardDropsTest {
    private final List<AutoCloseable> mocks = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private InfestationsTestState state;
    private Infestations plugin;
    private Logger logger;
    private World world;
    private Location location;
    private Infestation infestation;
    private MockedStatic<PermadeathZones> zones;

    @BeforeAll static void initializeRegistries() { MockBukkit.mock(); }
    @AfterAll static void releaseRegistries() { MockBukkit.unmock(); }

    @BeforeEach void setup() throws Exception {
        state = new InfestationsTestState();
        RewardDrops.warnedPaths.clear();
        plugin = mock(Infestations.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        Infestations.plugin = plugin;

        MockedStatic<Bukkit> bukkit = scoped(mockStatic(Bukkit.class, CALLS_REAL_METHODS));
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(i -> {
            later.add(i.getArgument(1)); delays.add(i.getArgument(2)); return null; });

        MockedStatic<TLibs> tlibs = scoped(mockStatic(TLibs.class, RETURNS_DEEP_STUBS));
        when(TLibs.getItemAPI().getCreator().getItemFromPath(anyString())).thenAnswer(i -> switch (i.<String>getArgument(0)) {
            case "key" -> new ItemStack(Material.TRIAL_KEY);
            case "stone" -> new ItemStack(Material.STONE);
            case "boom" -> throw new IllegalStateException("no MMOItems");
            default -> null;
        });
        MockedStatic<Messages> messages = scoped(mockStatic(Messages.class));
        messages.when(() -> Messages.getOrDefault(anyString(), anyString())).thenAnswer(i -> i.getArgument(0));

        zones = scoped(mockStatic(PermadeathZones.class));
        world = mock(World.class);
        location = new Location(world, 10.5, 64, 20.5);
        infestation = mock(Infestation.class);
        when(infestation.getGroupId()).thenReturn("swamp_mobs");
        when(infestation.getSeverity()).thenReturn(Severity.SEVERE);

        RewardDrops.random = new Random(7);
        RewardDrops.roller = new RewardRoller(RewardDrops.random);
        RewardDrops.keyCap = new KeyCap();
        RewardDrops.clock = () -> 1_000L;
        RewardConfig.enabled = true;
        RewardConfig.groups = Set.of("swamp_mobs");
        RewardConfig.coins = List.of();
        RewardConfig.keyItem = "key";
        RewardConfig.keyCapPerHour = 3;
        RewardConfig.lureMinKills = 3;
        RewardConfig.lureKeep = new RewardRoller.Keep(0, Map.of());
    }

    @AfterEach void restore() throws Exception {
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        RewardDrops.warnedPaths.clear();
        state.close();
    }

    private <T extends AutoCloseable> T scoped(T resource) { mocks.add(resource); return resource; }

    private static RewardTable table(Entry... entries) { return new RewardTable(List.of(entries), List.of()); }

    private static int total(List<ItemStack> stacks, Material type) {
        return stacks.stream().filter(s -> s.getType() == type).mapToInt(ItemStack::getAmount).sum();
    }

    @Test void ambientKillsAddCappedKeysAndOtherLootFromTheZoneTable() {
        Player killer = mock(Player.class);
        when(killer.getUniqueId()).thenReturn(UUID.randomUUID());
        RewardConfig.ambient = Map.of(
                RewardConfig.OUTSIDE, table(new Entry("stone", 1, 70, 70)),
                Severity.SEVERE.id(), table(new Entry("key", 1, 2, 2), new Entry("stone", 1, 1, 1)));
        zones.when(() -> PermadeathZones.inZone(location)).thenReturn(true);

        List<ItemStack> drops = new ArrayList<>();
        RewardDrops.ambientKill(infestation, killer, location, drops);
        RewardDrops.ambientKill(infestation, killer, location, drops);
        assertEquals(3, total(drops, Material.TRIAL_KEY));
        assertEquals(2, total(drops, Material.STONE));

        drops.clear();
        zones.when(() -> PermadeathZones.inZone(location)).thenReturn(false);
        RewardDrops.ambientKill(infestation, killer, location, drops);
        assertEquals(List.of(64, 6), drops.stream().map(ItemStack::getAmount).toList());
    }

    @Test void ambientKillsOutsideRewardGroupsOrWithoutATableDropNothing() {
        Player killer = mock(Player.class);
        List<ItemStack> drops = new ArrayList<>();
        RewardConfig.ambient = Map.of(RewardConfig.OUTSIDE, table(new Entry("stone", 1, 1, 1)));
        when(infestation.getGroupId()).thenReturn("crypt");
        RewardDrops.ambientKill(infestation, killer, location, drops);
        when(infestation.getGroupId()).thenReturn("swamp_mobs");
        zones.when(() -> PermadeathZones.inZone(location)).thenReturn(true);
        RewardDrops.ambientKill(infestation, killer, location, drops);
        assertEquals(List.of(), drops);
    }

    @Test void missingItemsWarnOncePerPathAndBuildNothing() {
        assertEquals(List.of(), RewardDrops.stacks("stone", 0));
        assertEquals(List.of(), RewardDrops.stacks("gone", 2));
        assertEquals(List.of(), RewardDrops.stacks("gone", 2));
        assertEquals(List.of(), RewardDrops.stacks("boom", 1));
        verify(logger, times(1)).warning("[Infestations] rewards.yml item not found: gone");
        verify(logger, times(1)).warning("[Infestations] rewards.yml item not found: boom");
    }

    @Test void splitSpreadsAStackOverAtMostThreePieces() {
        assertEquals(List.of(1), RewardDrops.split(new ItemStack(Material.STONE, 1)).stream().map(ItemStack::getAmount).toList());
        assertEquals(List.of(3, 2, 2), RewardDrops.split(new ItemStack(Material.STONE, 7)).stream().map(ItemStack::getAmount).toList());
    }

    @Test void lureVictoryBurstsOwnerLockedLootForPlayersWithEnoughKills() {
        RewardConfig.lure = Map.of(Severity.SEVERE.id(), table(new Entry("key", 1, 4, 4), new Entry("stone", 1, 1, 1)));
        zones.when(() -> PermadeathZones.inZone(location)).thenReturn(true);
        Player fighter = mock(Player.class), idler = mock(Player.class);
        UUID fighterId = UUID.randomUUID(), idlerId = UUID.randomUUID();
        when(fighter.getUniqueId()).thenReturn(fighterId);
        when(idler.getUniqueId()).thenReturn(idlerId);
        when(infestation.lureKills(fighterId)).thenReturn(3);
        when(infestation.lureKills(idlerId)).thenReturn(2);

        RewardDrops.lureVictory(infestation, location, List.of(fighter, idler));
        verify(fighter).sendMessage("lure-reward");
        verify(idler, never()).sendMessage(anyString());
        assertEquals(4, later.size());
        assertEquals(List.of(1L, 8L, 16L, 23L), delays);

        List<ItemStack> thrown = new ArrayList<>();
        Item entity = mock(Item.class);
        when(world.dropItem(any(Location.class), any(ItemStack.class), any())).thenAnswer(i -> {
            thrown.add(i.getArgument(1));
            i.<Consumer<Item>>getArgument(2).accept(entity);
            return entity;
        });
        later.forEach(Runnable::run);
        assertEquals(4, total(thrown, Material.TRIAL_KEY));
        assertEquals(1, total(thrown, Material.STONE));
        verify(entity, times(4)).setOwner(fighterId);
        verify(entity, times(4)).setPickupDelay(15);
        ArgumentCaptor<Vector> velocity = ArgumentCaptor.forClass(Vector.class);
        verify(entity, times(4)).setVelocity(velocity.capture());
        for (Vector v : velocity.getAllValues()) {
            assertTrue(Math.abs(v.getX()) <= 0.15 && Math.abs(v.getZ()) <= 0.15);
            assertTrue(v.getY() >= 0.35 && v.getY() <= 0.55);
        }
        ArgumentCaptor<Location> from = ArgumentCaptor.forClass(Location.class);
        verify(world, times(4)).playSound(from.capture(), eq(Sound.BLOCK_VAULT_EJECT_ITEM), eq(0.8f), anyFloat());
        assertEquals(new Location(world, 10.5, 65, 20.5), from.getValue());
    }

    @Test void lureVictoryOutsideZonesThinsLootAndSkipsEmptyBursts() {
        RewardConfig.lure = Map.of(Severity.SEVERE.id(), table(new Entry("key", 1, 4, 4)));
        zones.when(() -> PermadeathZones.inZone(location)).thenReturn(false);
        Player fighter = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(fighter.getUniqueId()).thenReturn(id);
        when(infestation.lureKills(id)).thenReturn(10);

        RewardDrops.lureVictory(infestation, location, List.of(fighter));
        verify(fighter, never()).sendMessage(anyString());
        assertEquals(List.of(), later);

        RewardConfig.lureKeep = new RewardRoller.Keep(1, Map.of());
        RewardDrops.lureVictory(infestation, location, List.of(fighter));
        verify(fighter).sendMessage("lure-reward");
        assertEquals(3, later.size());
    }

    @Test void lureVictoryNeedsALocationARewardGroupAndATable() {
        Player fighter = mock(Player.class);
        RewardConfig.lure = Map.of();
        RewardDrops.lureVictory(infestation, null, List.of(fighter));
        RewardDrops.lureVictory(infestation, location, List.of(fighter));
        when(infestation.getGroupId()).thenReturn("crypt");
        RewardConfig.lure = Map.of(Severity.SEVERE.id(), table(new Entry("key", 1, 1, 1)));
        RewardDrops.lureVictory(infestation, location, List.of(fighter));
        verifyNoInteractions(fighter);
        assertEquals(List.of(), later);
    }
}

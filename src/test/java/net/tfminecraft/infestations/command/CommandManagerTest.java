package net.tfminecraft.infestations.command;

import net.tfminecraft.infestations.*;
import net.tfminecraft.infestations.infestation.*;
import net.tfminecraft.infestations.loader.GroupLoader;
import net.tfminecraft.infestations.utils.Provinces;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandManagerTest {
    private final CommandManager commands = new CommandManager();
    private Infestations previous;
    private InfestationManager manager;
    private CommandSender console;
    private Player player;
    private Command command, lure;
    private MockedStatic<Messages> messages;
    private MockedStatic<Provinces> provinces;
    private MockedStatic<GroupLoader> groups;

    @BeforeEach void setup() {
        previous = Infestations.plugin;
        Infestations.plugin = mock(Infestations.class);
        manager = mock(InfestationManager.class);
        when(Infestations.plugin.getInfestationManager()).thenReturn(manager);
        console = mock(CommandSender.class);
        player = mock(Player.class);
        command = mock(Command.class);
        lure = mock(Command.class);
        when(command.getName()).thenReturn("infestation");
        when(lure.getName()).thenReturn("lure");
        messages = mockStatic(Messages.class, call -> call.getArgument(0));
        provinces = mockStatic(Provinces.class);
        groups = mockStatic(GroupLoader.class);
    }
    @AfterEach void teardown() {
        groups.close(); provinces.close(); messages.close(); Infestations.plugin = previous;
    }
    private void run(CommandSender sender, String... args) {
        assertTrue(commands.onCommand(sender, command, "infestation", args));
    }
    private void admin() { when(console.hasPermission("infestations.admin")).thenReturn(true); }

    @Test void permissionsUsageReloadAndUnknownSubcommands() {
        run(console); verify(console).sendMessage("no-permission");
        admin(); run(console); verify(console).sendMessage("usage");
        run(console, "reload"); verify(console, times(2)).sendMessage("no-permission");
        when(console.hasPermission("infestations.admin.reload")).thenReturn(true);
        run(console, "reload"); verify(console).sendMessage("reload-fail");
        when(Infestations.plugin.reloadAll()).thenReturn(true);
        run(console, "RELOAD"); verify(console).sendMessage("reload-ok");
        run(console, "nope"); verify(console).sendMessage("unknown-subcommand");
        when(console.hasPermission("infestations.admin")).thenReturn(false);
        run(console, "list"); verify(console, times(3)).sendMessage("no-permission");
        run(console); verify(console, times(2)).sendMessage("usage");
    }

    @Test void setChecksArgumentsProvinceGroupTerrainSeverityAndExistingInfestation() {
        admin();
        run(console, "set");
        run(console, "set", "wrong", "rats", "mild");
        run(console, "set", "here", "rats", "mild");
        run(console, "set", "-1", "rats", "mild");
        run(console, "set", "4", "rats", "mild");
        provinces.when(() -> Provinces.skipTerrain(4)).thenReturn(true);
        run(console, "set", "4", "rats", "mild");
        verify(console).sendMessage("usage");
        verify(console, times(3)).sendMessage("unknown-province");
        verify(console).sendMessage("skip-terrain");
        verify(console).sendMessage("player-only");
        provinces.when(() -> Provinces.validLand(4)).thenReturn(true);
        run(console, "set", "4", "rats", "mild");
        verify(console).sendMessage("unknown-group");
        groups.when(() -> GroupLoader.get("rats")).thenReturn(mock(GroupLoader.MobGroup.class));
        run(console, "set", "4", "rats", "mild"); verify(console).sendMessage("wrong-terrain");
        groups.when(() -> GroupLoader.allowsTerrain("rats", 4)).thenReturn(true);
        run(console, "set", "4", "rats", "invalid"); verify(console).sendMessage("unknown-severity");
        when(manager.get(4)).thenReturn(mock(Infestation.class));
        run(console, "set", "4", "rats", Severity.values()[0].id()); verify(console).sendMessage("already-infested");
        when(manager.get(4)).thenReturn(null);
        run(console, "set", "4", "rats", Severity.values()[0].id());
        verify(manager).set(4, "rats", Severity.values()[0]); verify(console).sendMessage("set-ok");
    }

    @Test void hereResolutionAndClearOnlyMutateKnownInfestations() {
        admin();
        run(console, "clear"); run(console, "clear", "wrong");
        run(console, "clear", "4"); verify(console).sendMessage("not-infested");
        when(player.hasPermission("infestations.admin")).thenReturn(true);
        provinces.when(() -> Provinces.at(player)).thenReturn(-2);
        run(player, "clear", "here"); verify(player).sendMessage("unknown-province");
        provinces.when(() -> Provinces.at(player)).thenReturn(4);
        provinces.when(() -> Provinces.skipTerrain(4)).thenReturn(true);
        run(player, "clear", "here"); verify(player).sendMessage("skip-terrain");
        provinces.when(() -> Provinces.validLand(4)).thenReturn(true);
        when(manager.get(4)).thenReturn(mock(Infestation.class));
        run(player, "clear", "here"); verify(manager).clear(4, false); verify(player).sendMessage("clear-ok");
    }

    @Test void listIncludesLureStateAndLureLeaveHandlesConsoleAndParticipation() {
        admin(); when(manager.all()).thenReturn(List.of()); run(console, "list"); verify(console).sendMessage("list-empty");
        Infestation first = new Infestation(4, "rats", Severity.values()[0]);
        Infestation second = mock(Infestation.class);
        when(second.getSeverity()).thenReturn(Severity.values()[1]);
        when(second.getPhase()).thenReturn(LurePhase.values()[1]);
        when(manager.all()).thenReturn(List.of(first, second));
        run(console, "list"); verify(console, times(2)).sendMessage("list-line");
        messages.verify(() -> Messages.get("list-lure-suffix"));
        assertTrue(commands.onCommand(console, lure, "lure", new String[0]));
        assertTrue(commands.onCommand(console, lure, "lure", new String[]{"bad"}));
        assertTrue(commands.onCommand(console, lure, "lure", new String[]{"leave"}));
        verify(console).sendMessage("player-only");
        assertTrue(commands.onCommand(player, lure, "lure", new String[]{"leave"}));
        verify(player).sendMessage("lure-not-in");
        when(manager.leaveLure(player)).thenReturn(true);
        commands.onCommand(player, lure, "lure", new String[]{"LEAVE"});
        verify(player, times(1)).sendMessage("lure-not-in");
    }

    @Test void completionFiltersByPermissionPrefixAndArgumentPosition() {
        assertEquals(List.of(), complete(console, command, ""));
        assertEquals(List.of(), complete(console, command, "set", ""));
        admin(); when(console.hasPermission("infestations.admin.reload")).thenReturn(true);
        assertEquals(List.of("reload", "set", "clear", "list"), complete(console, command, ""));
        assertEquals(List.of("set"), complete(console, command, "S"));
        assertEquals(List.of(), complete(console, command, "set", "H"));
        assertEquals(List.of(), complete(console, command, "clear", ""));
        groups.when(GroupLoader::groupIds).thenReturn(Set.of("rats"));
        provinces.when(() -> Provinces.validLand(4)).thenReturn(true);
        groups.when(() -> GroupLoader.allowsTerrain("rats", 4)).thenReturn(true);
        assertEquals(List.of("rats"), complete(console, command, "set", "4", "r"));
        assertEquals(Severity.values().length, complete(console, command, "set", "4", "rats", "").size());
        assertEquals(List.of(), complete(console, command));
        assertEquals(List.of(), complete(console, command, "list", ""));
        assertEquals(List.of(), complete(console, command, "clear", "4", ""));
        assertEquals(List.of(), complete(console, command, "clear", "4", "rats", ""));
        assertEquals(List.of("leave"), complete(console, lure, "L"));
        assertEquals(List.of(), complete(console, lure, "leave", ""));
    }
    @Test void provinceAndGroupCandidatesRespectLiveStateAndSender() {
        admin();
        when(player.hasPermission("infestations.admin")).thenReturn(true);
        var sf = mock(net.tfminecraft.simplefactions.SimpleFactions.class, RETURNS_DEEP_STUBS);
        var land = mock(net.tfminecraft.simplefactions.map.provinces.Province.class);
        var occupied = mock(net.tfminecraft.simplefactions.map.provinces.Province.class);
        var water = mock(net.tfminecraft.simplefactions.map.provinces.Province.class);
        when(land.getId()).thenReturn(42); when(occupied.getId()).thenReturn(43); when(water.getId()).thenReturn(44);
        when(sf.getProvinceManager().getProvinces()).thenReturn(List.of(land, occupied, water));
        provinces.when(() -> Provinces.validLand(42)).thenReturn(true);
        provinces.when(() -> Provinces.validLand(43)).thenReturn(true);
        provinces.when(() -> Provinces.at(player)).thenReturn(42);
        when(manager.get(43)).thenReturn(new Infestation(43, "rats", Severity.MILD));
        when(manager.all()).thenReturn(List.of(new Infestation(43, "rats", Severity.MILD)));
        try (var factions = mockStatic(net.tfminecraft.simplefactions.SimpleFactions.class)) {
            factions.when(net.tfminecraft.simplefactions.SimpleFactions::getInstance).thenReturn(sf);
            assertEquals(List.of("42"), complete(console, command, "set", "4"));
            assertEquals(List.of("43"), complete(console, command, "clear", "4"));
            assertEquals(List.of("42", "here"), complete(player, command, "set", ""));
            assertEquals(List.of(), complete(player, command, "clear", "H"));
            provinces.when(() -> Provinces.at(player)).thenReturn(43);
            assertEquals(List.of("here"), complete(player, command, "clear", "H"));
            provinces.when(() -> Provinces.at(player)).thenReturn(42);
        }
        groups.when(GroupLoader::groupIds).thenReturn(Set.of("rats", "swamp"));
        groups.when(() -> GroupLoader.allowsTerrain("rats", 42)).thenReturn(true);
        assertEquals(List.of("rats"), complete(player, command, "set", "HeRe", "R"));
        assertEquals(List.of("rats"), complete(console, command, "set", "42", ""));
        for (String invalid : List.of("here", "nonsense", "44", "43")) {
            assertTrue(complete(console, command, "set", invalid, "").isEmpty());
        }
        verify(console, never()).sendMessage(anyString());
        verify(player, never()).sendMessage(anyString());
    }

    @Test void setHereCompletionRequiresUninfestedLandAndStaysQuiet() {
        admin();
        when(player.hasPermission("infestations.admin")).thenReturn(true);
        provinces.when(() -> Provinces.at(player)).thenReturn(42);
        provinces.when(() -> Provinces.validLand(42)).thenReturn(true);
        provinces.when(() -> Provinces.validLand(43)).thenReturn(true);
        when(manager.get(43)).thenReturn(new Infestation(43, "rats", Severity.MILD));
        try (var factions = mockStatic(net.tfminecraft.simplefactions.SimpleFactions.class)) {
            assertEquals(List.of("here"), complete(player, command, "set", ""));
            assertEquals(List.of("here"), complete(player, command, "SeT", "H"));
            assertEquals(List.of("here"), complete(player, command, "set", "HeRe"));
            assertEquals(List.of(), complete(player, command, "set", "hereX"));
            assertEquals(List.of(), complete(console, command, "set", ""));
            assertEquals(List.of(), complete(console, command, "set", "H"));
            for (int ineligible : List.of(43, 44, 999, Provinces.UNKNOWN)) {
                provinces.when(() -> Provinces.at(player)).thenReturn(ineligible);
                assertEquals(List.of(), complete(player, command, "set", ""), "province " + ineligible);
                assertEquals(List.of(), complete(player, command, "SET", "H"), "province " + ineligible);
            }
        }
        verify(console, never()).sendMessage(anyString());
        verify(player, never()).sendMessage(anyString());
        messages.verifyNoInteractions();
        verify(manager, never()).set(anyInt(), anyString(), any());
        verify(manager, never()).clear(anyInt(), anyBoolean());
    }

    @Test void clearHereCompletionRequiresAnActiveInfestationOnLandAndStaysQuiet() {
        admin();
        when(player.hasPermission("infestations.admin")).thenReturn(true);
        provinces.when(() -> Provinces.at(player)).thenReturn(42);
        provinces.when(() -> Provinces.validLand(42)).thenReturn(true);
        provinces.when(() -> Provinces.validLand(43)).thenReturn(true);
        Infestation infestation = new Infestation(43, "rats", Severity.MILD);
        when(manager.get(43)).thenReturn(infestation);
        when(manager.get(44)).thenReturn(new Infestation(44, "rats", Severity.MILD));
        when(manager.all()).thenReturn(List.of(infestation));
        assertEquals(List.of("43"), complete(player, command, "clear", ""));
        assertEquals(List.of(), complete(player, command, "clear", "H"));
        provinces.when(() -> Provinces.at(player)).thenReturn(43);
        assertEquals(List.of("43", "here"), complete(player, command, "clear", ""));
        assertEquals(List.of("here"), complete(player, command, "ClEaR", "H"));
        assertEquals(List.of("here"), complete(player, command, "clear", "HeRe"));
        assertEquals(List.of(), complete(player, command, "clear", "hereX"));
        assertEquals(List.of("43"), complete(player, command, "clear", "4"));
        assertEquals(List.of("43"), complete(console, command, "clear", ""));
        assertEquals(List.of(), complete(console, command, "clear", "H"));
        for (int ineligible : List.of(44, 999, Provinces.UNKNOWN)) {
            provinces.when(() -> Provinces.at(player)).thenReturn(ineligible);
            assertEquals(List.of("43"), complete(player, command, "clear", ""), "province " + ineligible);
            assertEquals(List.of(), complete(player, command, "CLEAR", "H"), "province " + ineligible);
        }
        verify(console, never()).sendMessage(anyString());
        verify(player, never()).sendMessage(anyString());
        messages.verifyNoInteractions();
        verify(manager, never()).set(anyInt(), anyString(), any());
        verify(manager, never()).clear(anyInt(), anyBoolean());
    }

    @Test void registeredCommandAllowsReloadOnlySenderAndStillDeniesAdminActions() throws Exception {
        when(console.hasPermission("infestations.admin.reload")).thenReturn(true);
        when(Infestations.plugin.isEnabled()).thenReturn(true);
        var description = new org.bukkit.plugin.PluginDescriptionFile(
                getClass().getResourceAsStream("/plugin.yml"));
        assertEquals("infestations.admin;infestations.admin.reload",
                description.getCommands().get("infestation").get("permission"));
        var constructor = PluginCommand.class.getDeclaredConstructor(String.class, org.bukkit.plugin.Plugin.class);
        constructor.setAccessible(true);
        PluginCommand registered = constructor.newInstance("infestation", Infestations.plugin);
        registered.setPermission((String) description.getCommands().get("infestation").get("permission"));
        registered.setExecutor(commands);
        registered.setTabCompleter(commands);
        assertFalse(registered.testPermissionSilent(player));
        when(player.hasPermission("infestations.admin")).thenReturn(true);
        assertTrue(registered.testPermissionSilent(player));
        when(player.hasPermission("infestations.admin")).thenReturn(false);
        when(player.hasPermission("infestations.admin.reload")).thenReturn(true);
        assertTrue(registered.testPermissionSilent(player));
        assertTrue(registered.testPermissionSilent(console));
        assertEquals(List.of("reload"), registered.tabComplete(console, "infestations", new String[]{""}));
        assertEquals(List.of("reload"), registered.tabComplete(console, "infestations", new String[]{"RE"}));
        assertEquals(List.of(), registered.tabComplete(console, "infestations", new String[]{"set", "H"}));
        assertEquals(List.of(), registered.tabComplete(console, "infestations", new String[]{"clear", "H"}));
        assertTrue(registered.execute(console, "infestation", new String[]{"reload"}));
        verify(Infestations.plugin).reloadAll();
        assertTrue(registered.execute(console, "infestation", new String[]{"set", "42", "rats", "mild"}));
        verify(console).sendMessage("no-permission");
        verify(manager, never()).set(anyInt(), anyString(), any());
    }

    private List<String> complete(CommandSender sender, Command cmd, String... args) {
        return commands.onTabComplete(sender, cmd, cmd.getName(), args);
    }
}

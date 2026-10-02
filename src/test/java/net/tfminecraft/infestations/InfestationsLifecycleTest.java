package net.tfminecraft.infestations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.infestation.InfestationManager;

class InfestationsLifecycleTest {
    @TempDir Path directory;
    private InfestationsTestState state;
    private ServerMock server;
    private MockedConstruction<InfestationManager> managers;
    private Infestations plugin;

    @BeforeEach void setup() throws Exception {
        state = new InfestationsTestState();
        server = MockBukkit.mock();
        managers = mockConstruction(InfestationManager.class);
        PluginDescriptionFile description = new PluginDescriptionFile(new StringReader("""
                name: Infestations
                version: test
                main: net.tfminecraft.infestations.Infestations
                api-version: '1.21'
                commands:
                  infestation:
                    description: Infestation administration
                  lure:
                    description: Lure participation
                """));
        plugin = MockBukkit.loadWith(Infestations.class, description);
    }

    @AfterEach void cleanup() throws Exception {
        try {
            MockBukkit.unmock();
        } finally {
            if (managers != null) managers.close();
            state.close();
        }
    }

    private Infestations controlled(Path data, Logger logger) {
        Infestations controlled = spy(plugin);
        doReturn(data.toFile()).when(controlled).getDataFolder();
        doReturn(logger).when(controlled).getLogger();
        return controlled;
    }

    @Test void enableCreatesBundledFilesInitializesManagerAndRegistersBothCommands() {
        assertSame(plugin, Infestations.plugin);
        assertSame(managers.constructed().getFirst(), plugin.getInfestationManager());
        verify(plugin.getInfestationManager()).start();
        for (String file : List.of("config.yml", "groups.yml", "messages.yml")) {
            assertTrue(Files.isRegularFile(plugin.getDataFolder().toPath().resolve(file)));
        }
        for (String folder : List.of("Data", "MapAPI", "logs")) {
            assertTrue(Files.isDirectory(plugin.getDataFolder().toPath().resolve(folder)));
        }
        assertNotNull(plugin.getCommand("infestation")); assertNotNull(plugin.getCommand("lure"));
        assertSame(plugin.getCommand("infestation").getExecutor(), plugin.getCommand("lure").getExecutor());
        assertSame(plugin.getCommand("infestation").getExecutor(), plugin.getCommand("infestation").getTabCompleter());
        assertSame(plugin.getCommand("lure").getExecutor(), plugin.getCommand("lure").getTabCompleter());
        plugin.onDisable(); verify(plugin.getInfestationManager()).shutdown();
    }

    @Test void reloadAppliesCurrentConfigAndReportsDebugSettingsWithoutReplacingFiles() throws Exception {
        Path folder = plugin.getDataFolder().toPath(); Path config = folder.resolve("config.yml");
        String custom = "debug: true\nlogging: false\njoin-seconds: 42\nlure-item: ia.test:lure\n";
        Files.writeString(config, custom);
        Logger logger = mock(Logger.class); Infestations controlled = controlled(folder, logger);
        assertTrue(controlled.reloadAll());
        assertEquals(42, Cache.joinSeconds); assertEquals("ia.test:lure", Cache.lureItem);
        verify(controlled.getInfestationManager()).reloadFromDisk();
        verify(logger).info("[Infestations] Configs loaded.");
        verify(logger).info(contains("Debug: lure-item=ia.test:lure join-seconds=42"));
        controlled.onEnable();
        assertEquals(custom, Files.readString(config));
        verify(controlled.getInfestationManager()).start();
    }

    @Test void initializationCreatesMissingDataRootAndReportsUnavailableCommands() {
        Logger logger = mock(Logger.class); Path folder = directory.resolve("new-plugin-data");
        Infestations controlled = controlled(folder, logger);
        doReturn(null).when(controlled).getCommand("infestation");
        doReturn(null).when(controlled).getCommand("lure");
        controlled.onEnable();
        assertTrue(Files.isDirectory(folder.resolve("Data")));
        assertTrue(Files.isRegularFile(folder.resolve("config.yml")));
        verify(logger).severe("Command 'infestation' missing from plugin.yml");
        verify(logger).severe("Command 'lure' missing from plugin.yml");
        verify(controlled.getInfestationManager()).start();
    }

    @Test void missingBundledConfigIsReportedAndDoesNotPreventOtherResourcesBeingCopied() {
        Logger logger = mock(Logger.class); Infestations controlled = controlled(directory, logger);
        doReturn(null).when(controlled).getResource("config.yml");
        controlled.onEnable();
        assertFalse(Files.exists(directory.resolve("config.yml")));
        assertTrue(Files.isRegularFile(directory.resolve("groups.yml")));
        verify(logger).warning("Missing bundled resource: config.yml");
        verify(logger).severe(contains("Failed to load config.yml"));
        verify(logger).warning("Infestations loaded with config errors.");
        assertFalse(controlled.reloadAll());
        verify(controlled.getInfestationManager()).reloadFromDisk();
    }

    @Test void failedResourceCopyClosesStreamAndReportsItsCause() throws Exception {
        Logger logger = mock(Logger.class); Infestations controlled = controlled(directory, logger);
        InputStream broken = mock(InputStream.class);
        when(broken.transferTo(any())).thenThrow(new IOException("read failed"));
        doReturn(broken).when(controlled).getResource("config.yml");
        controlled.onEnable();
        verify(logger).severe("Failed to copy default resource config.yml: read failed");
        verify(broken).close();
        assertTrue(Files.isRegularFile(directory.resolve("messages.yml")));
    }

    @Test void shutdownAndReloadBeforeManagerInitializationRemainSafe() throws Exception {
        Logger logger = mock(Logger.class); Infestations controlled = controlled(plugin.getDataFolder().toPath(), logger);
        Field manager = Infestations.class.getDeclaredField("infestationManager");
        manager.setAccessible(true); manager.set(controlled, null);
        assertNull(controlled.getInfestationManager());
        controlled.onDisable(); verify(logger).info("Infestations disabled.");
        assertTrue(controlled.reloadAll()); assertNull(controlled.getInfestationManager());
    }

    @Test void malformedGroupsMakeReloadFailWhileRetainingWorkingManager() throws Exception {
        Files.writeString(plugin.getDataFolder().toPath().resolve("groups.yml"), "groups: [broken");
        InfestationManager running = plugin.getInfestationManager();
        assertFalse(plugin.reloadAll());
        assertSame(running, plugin.getInfestationManager()); verify(running).reloadFromDisk();
    }
}

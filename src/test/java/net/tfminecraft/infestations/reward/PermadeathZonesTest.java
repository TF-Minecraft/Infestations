package net.tfminecraft.infestations.reward;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.InfestationsTestState;
import net.tfminecraft.rpcharacters.permadeath.PermadeathAreaLookup;

class PermadeathZonesTest {
    private InfestationsTestState state;
    private MockedStatic<Bukkit> bukkit;
    private PluginManager plugins;
    private Logger logger;
    private final Location location = new Location(null, 1, 2, 3);

    @BeforeEach void setup() throws Exception {
        state = new InfestationsTestState();
        Infestations self = mock(Infestations.class);
        logger = mock(Logger.class);
        when(self.getLogger()).thenReturn(logger);
        Infestations.plugin = self;
        plugins = mock(PluginManager.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        PermadeathAreaLookup.zone = null;
        PermadeathAreaLookup.failure = null;
    }

    @AfterEach void restore() throws Exception {
        bukkit.close();
        PermadeathAreaLookup.zone = null;
        PermadeathAreaLookup.failure = null;
        state.close();
    }

    private void rpcharacters(boolean enabled) {
        // Mocks of a plugin class keep that class, so the lookup resolves through the test class loader.
        Infestations rp = mock(Infestations.class);
        when(rp.isEnabled()).thenReturn(enabled);
        when(plugins.getPlugin("RPCharacters")).thenReturn(rp);
    }

    @Test void missingDisabledOrNoLocationIsOutside() {
        assertFalse(PermadeathZones.inZone(null));
        assertFalse(PermadeathZones.inZone(location));
        rpcharacters(false);
        PermadeathAreaLookup.zone = new Object();
        assertFalse(PermadeathZones.inZone(location));
    }

    @Test void asksTheLookupForTheZone() {
        rpcharacters(true);
        assertFalse(PermadeathZones.inZone(location));
        PermadeathAreaLookup.zone = new Object();
        assertTrue(PermadeathZones.inZone(location));
        verifyNoInteractions(logger);
    }

    @Test void lookupFailuresWarnOnceAndCountAsOutside() {
        rpcharacters(true);
        PermadeathAreaLookup.failure = new IllegalStateException("boom");
        assertFalse(PermadeathZones.inZone(location));
        assertFalse(PermadeathZones.inZone(location));
        verify(logger, times(1)).warning(startsWith("[Infestations] Permadeath zone lookup failed"));
    }

    @Test void methodIsCachedPerClassLoader() throws Exception {
        ClassLoader test = getClass().getClassLoader();
        Method first = PermadeathZones.method(test);
        assertSame(first, PermadeathZones.method(test));
        ClassLoader child = new ClassLoader(test) {};
        Method again = PermadeathZones.method(child);
        assertEquals(first, again);
        assertNotSame(first, again);
        assertThrows(ClassNotFoundException.class, () -> PermadeathZones.method(new ClassLoader(null) {}));
    }
}

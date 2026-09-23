package net.tfminecraft.infestations;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;

import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.command.CommandManager;
import net.tfminecraft.infestations.infestation.InfestationManager;
import net.tfminecraft.infestations.loader.ConfigLoader;
import net.tfminecraft.infestations.loader.GroupLoader;

/**
 * Province infestations. See docs/ for locked rules and batches.
 */
public class Infestations extends JavaPlugin {

    public static Infestations plugin;

    private final ConfigLoader configLoader = new ConfigLoader();
    private final GroupLoader groupLoader = new GroupLoader();
    private final CommandManager commandManager = new CommandManager();
    private InfestationManager infestationManager;

    @Override
    public void onEnable() {
        plugin = this;
        createFolders();
        createConfigs();
        if (!loadConfigs()) {
            getLogger().warning("Infestations loaded with config errors.");
        }

        infestationManager = new InfestationManager(this);
        getServer().getPluginManager().registerEvents(infestationManager, this);
        infestationManager.start();

        var cmd = getCommand("infestation");
        if (cmd != null) {
            cmd.setExecutor(commandManager);
            cmd.setTabCompleter(commandManager);
        } else {
            getLogger().severe("Command 'infestation' missing from plugin.yml");
        }

        getLogger().info("Infestations enabled.");
    }

    @Override
    public void onDisable() {
        if (infestationManager != null) {
            infestationManager.shutdown();
        }
        getLogger().info("Infestations disabled.");
    }

    public InfestationManager getInfestationManager() {
        return infestationManager;
    }

    public boolean reloadAll() {
        boolean ok = loadConfigs();
        if (infestationManager != null) {
            infestationManager.reloadFromDisk();
        }
        return ok;
    }

    private boolean loadConfigs() {
        boolean ok = true;
        ok &= configLoader.loadSafe(new File(getDataFolder(), "config.yml"));
        Messages.load(new File(getDataFolder(), "messages.yml"));
        ok &= groupLoader.loadSafe(new File(getDataFolder(), "groups.yml"));
        net.tfminecraft.infestations.spawn.SpawnLog.configure(
                Cache.loggingEnabled, Cache.wipeLog, getDataFolder());
        if (ok) {
            getLogger().info("[Infestations] Configs loaded.");
        }
        if (Cache.debug) {
            getLogger().info("[Infestations] Debug: lure-item=" + Cache.lureItem
                    + " join-seconds=" + Cache.joinSeconds
                    + " lure-spawn-radius=" + Cache.lureSpawnRadius
                    + " spread=" + Cache.spread
                    + " spread-interval-seconds=" + Cache.spreadIntervalSeconds
                    + " worsen-chance=" + Cache.worsenChance
                    + " spread-chance-land=" + Arrays.toString(Cache.spreadChanceLand)
                    + " spread-chance-water=" + Arrays.toString(Cache.spreadChanceWater)
                    + " spread-chance-sea=" + Arrays.toString(Cache.spreadChanceSea)
                    + " groups=" + GroupLoader.groupIds());
        }
        return ok;
    }

    private void createFolders() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }
        mkdir("Data");
        mkdir("MapAPI");
        mkdir("logs");
    }

    private void mkdir(String relativePath) {
        File folder = new File(getDataFolder(), relativePath);
        if (!folder.exists()) {
            folder.mkdirs();
        }
    }

    private void createConfigs() {
        String[] defaultFiles = {
            "config.yml",
            "messages.yml",
            "groups.yml"
        };
        for (String path : defaultFiles) {
            copyResourceIfMissing(path);
        }
    }

    private void copyResourceIfMissing(String relativePath) {
        File target = new File(getDataFolder(), relativePath);
        if (target.exists()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (InputStream in = getResource(relativePath)) {
            if (in == null) {
                getLogger().warning("Missing bundled resource: " + relativePath);
                return;
            }
            Files.copy(in, target.toPath());
        } catch (IOException ex) {
            getLogger().severe("Failed to copy default resource " + relativePath + ": " + ex.getMessage());
        }
    }
}

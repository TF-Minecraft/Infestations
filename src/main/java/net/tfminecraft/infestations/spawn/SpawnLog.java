package net.tfminecraft.infestations.spawn;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.bukkit.Location;

import net.tfminecraft.infestations.infestation.Infestation;

/**
 * Immediate lines in {@code logs/spawn.log}. Same logging / wipe-log as SimpleFactions.
 */
public final class SpawnLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static volatile boolean enabled;
    private static volatile Path logFile;
    private static final Object LOCK = new Object();

    private SpawnLog() {}

    public static void configure(boolean loggingEnabled, boolean wipeLog, File dataFolder) {
        enabled = loggingEnabled;
        if (dataFolder != null) {
            Path dir = dataFolder.toPath().resolve("logs");
            logFile = dir.resolve("spawn.log");
        } else {
            logFile = null;
        }
        if (wipeLog) {
            wipe();
        }
    }

    private static void wipe() {
        if (logFile == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                Files.deleteIfExists(logFile);
            } catch (IOException ignored) {
            }
        }
    }

    public static void line(Infestation infestation, String player, String reason) {
        if (!enabled || infestation == null) {
            return;
        }
        String who = player != null ? player : "-";
        append("p=" + infestation.getProvinceId()
                + " " + infestation.getSeverity().id()
                + " " + infestation.getGroupId()
                + " " + who
                + " " + reason);
    }

    public static void spawned(Infestation infestation, String player, Location loc, int alive, int cap) {
        if (loc == null) {
            line(infestation, player, "spawned alive=" + alive + "/" + cap);
            return;
        }
        line(infestation, player, "spawned "
                + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                + " alive=" + alive + "/" + cap);
    }

    private static void append(String message) {
        if (!enabled || message == null || logFile == null) {
            return;
        }
        Path file = logFile;
        synchronized (LOCK) {
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (BufferedWriter writer = Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {
                    writer.write(TIME.format(Instant.now()) + " " + message);
                    writer.newLine();
                }
            } catch (IOException ignored) {
            }
        }
    }
}

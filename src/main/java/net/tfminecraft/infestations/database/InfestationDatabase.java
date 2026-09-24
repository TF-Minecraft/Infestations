package net.tfminecraft.infestations.database;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.infestation.Infestation;
import net.tfminecraft.infestations.infestation.LurePhase;
import net.tfminecraft.infestations.infestation.Severity;

public final class InfestationDatabase {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private InfestationDatabase() {}

    public static File file() {
        return new File(Infestations.plugin.getDataFolder(), "Data/infestations.json");
    }

    public static List<Infestation> load() {
        File file = file();
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            List<InfestationData> rows = GSON.fromJson(reader, new TypeToken<List<InfestationData>>() {}.getType());
            List<Infestation> out = new ArrayList<>();
            if (rows == null) {
                return out;
            }
            for (InfestationData row : rows) {
                Infestation infestation = fromData(row);
                if (infestation != null) {
                    out.add(infestation);
                }
            }
            return out;
        } catch (Exception ex) {
            Infestations.plugin.getLogger().severe("[Infestations] Failed to load infestations: " + ex.getMessage());
            return new ArrayList<>();
        }
    }

    public static void save(Collection<Infestation> infestations) {
        File file = file();
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        List<InfestationData> rows = new ArrayList<>();
        for (Infestation infestation : infestations) {
            rows.add(toData(infestation));
        }
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(rows, writer);
        } catch (IOException ex) {
            Infestations.plugin.getLogger().severe("[Infestations] Failed to save infestations: " + ex.getMessage());
        }
    }

    private static Infestation fromData(InfestationData row) {
        if (row == null) {
            return null;
        }
        Severity severity = Severity.fromString(row.severity);
        if (severity == null || row.groupId == null) {
            return null;
        }
        Infestation infestation = new Infestation(row.provinceId, row.groupId, severity);
        LurePhase phase = LurePhase.NONE;
        if (row.phase != null) {
            try {
                phase = LurePhase.valueOf(row.phase);
            } catch (IllegalArgumentException ignored) {
                phase = LurePhase.NONE;
            }
        }
        infestation.setPhase(phase);
        infestation.setWorldName(row.worldName);
        infestation.setLureX(row.lureX);
        infestation.setLureY(row.lureY);
        infestation.setLureZ(row.lureZ);
        infestation.setJoinEndsAt(row.joinEndsAt);
        infestation.setLureRemaining(row.lureRemaining);
        // Scheduled spawns do not survive a restart or reload, so pendingSpawns starts at zero.
        infestation.setEnemiesAlive(row.enemiesAlive);
        infestation.setAmbientAlive(row.ambientAlive);
        infestation.setLureActivatedAt(row.lureActivatedAt);
        // Pending spawns were counted as released when scheduled but never ran.
        infestation.setLureReleased(row.lureReleased - row.pendingSpawns);
        if (row.committed != null) {
            for (String id : row.committed) {
                try {
                    infestation.getCommitted().add(UUID.fromString(id));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (row.logoutGraceUntil != null) {
            for (Map.Entry<String, Long> e : row.logoutGraceUntil.entrySet()) {
                try {
                    infestation.getLogoutGraceUntil().put(UUID.fromString(e.getKey()), e.getValue());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (row.deathOnLogin != null) {
            for (String id : row.deathOnLogin) {
                try {
                    infestation.getDeathOnLogin().add(UUID.fromString(id));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return infestation;
    }

    private static InfestationData toData(Infestation infestation) {
        InfestationData row = new InfestationData();
        row.provinceId = infestation.getProvinceId();
        row.groupId = infestation.getGroupId();
        row.severity = infestation.getSeverity().id();
        row.phase = infestation.getPhase().name();
        row.worldName = infestation.getWorldName();
        row.lureX = infestation.getLureX();
        row.lureY = infestation.getLureY();
        row.lureZ = infestation.getLureZ();
        row.joinEndsAt = infestation.getJoinEndsAt();
        row.lureRemaining = infestation.getLureRemaining();
        row.pendingSpawns = infestation.getPendingSpawns();
        row.enemiesAlive = infestation.getEnemiesAlive();
        row.ambientAlive = infestation.getAmbientAlive();
        row.lureActivatedAt = infestation.getLureActivatedAt();
        row.lureReleased = infestation.getLureReleased();
        for (UUID id : infestation.getCommitted()) {
            row.committed.add(id.toString());
        }
        for (Map.Entry<UUID, Long> e : infestation.getLogoutGraceUntil().entrySet()) {
            row.logoutGraceUntil.put(e.getKey().toString(), e.getValue());
        }
        for (UUID id : infestation.getDeathOnLogin()) {
            row.deathOnLogin.add(id.toString());
        }
        return row;
    }
}

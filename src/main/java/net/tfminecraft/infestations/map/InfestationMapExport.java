package net.tfminecraft.infestations.map;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.bukkit.Bukkit;

import me.Plugins.SimpleFactions.REST.RestServer;
import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.infestation.Infestation;
import net.tfminecraft.infestations.loader.GroupLoader;

public final class InfestationMapExport {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private InfestationMapExport() {}

    public static void exportAsync(Collection<Infestation> infestations) {
        JsonObject root = new JsonObject();
        JsonArray provinces = new JsonArray();
        for (Infestation infestation : infestations) {
            JsonObject o = new JsonObject();
            o.addProperty("id", infestation.getProvinceId());
            o.addProperty("severity", infestation.getSeverity().id());
            o.addProperty("group", infestation.getGroupId());
            o.addProperty("display", GroupLoader.displayName(infestation.getGroupId()));
            provinces.add(o);
        }
        root.add("provinces", provinces);
        String json = GSON.toJson(root);

        Bukkit.getScheduler().runTaskAsynchronously(Infestations.plugin, () -> {
            File file = new File(Infestations.plugin.getDataFolder(), "MapAPI/infestation_data.json");
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                writer.write(json);
            } catch (IOException ex) {
                Infestations.plugin.getLogger().warning("[Infestations] Map export write failed: " + ex.getMessage());
                return;
            }
            try {
                RestServer.upload("infestation_data", file);
            } catch (Throwable t) {
                Infestations.plugin.getLogger().warning("[Infestations] Map upload failed: " + t.getMessage());
            }
        });
    }
}

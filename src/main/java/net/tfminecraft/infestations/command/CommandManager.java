package net.tfminecraft.infestations.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.Messages;
import net.tfminecraft.infestations.infestation.Infestation;
import net.tfminecraft.infestations.infestation.LurePhase;
import net.tfminecraft.infestations.infestation.Severity;
import net.tfminecraft.infestations.loader.GroupLoader;
import net.tfminecraft.infestations.utils.Provinces;

public final class CommandManager implements CommandExecutor, TabCompleter {

    public static final String CMD = "infestation";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!sender.hasPermission("infestations.admin")
                    && !sender.hasPermission("infestations.admin.reload")) {
                sender.sendMessage(Messages.get("no-permission"));
                return true;
            }
            sender.sendMessage(Messages.get("usage"));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("infestations.admin.reload")) {
                sender.sendMessage(Messages.get("no-permission"));
                return true;
            }
            boolean ok = Infestations.plugin.reloadAll();
            sender.sendMessage(Messages.get(ok ? "reload-ok" : "reload-fail"));
            return true;
        }
        if (!sender.hasPermission("infestations.admin")) {
            sender.sendMessage(Messages.get("no-permission"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set" -> handleSet(sender, args);
            case "clear" -> handleClear(sender, args);
            case "list" -> handleList(sender);
            default -> sender.sendMessage(Messages.get("unknown-subcommand"));
        }
        return true;
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Messages.get("usage"));
            return;
        }
        Integer provinceId = resolveProvince(sender, args[1]);
        if (provinceId == null) {
            return;
        }
        if (!Provinces.validLand(provinceId)) {
            sender.sendMessage(Messages.get(
                    provinceId > 0 && Provinces.skipTerrain(provinceId) ? "skip-terrain" : "unknown-province"));
            return;
        }
        if (GroupLoader.get(args[2]) == null) {
            sender.sendMessage(Messages.get("unknown-group", "group", args[2]));
            return;
        }
        if (!GroupLoader.allowsTerrain(args[2], provinceId)) {
            sender.sendMessage(Messages.get("wrong-terrain",
                    "group", GroupLoader.displayName(args[2]),
                    "terrains", GroupLoader.terrainList(args[2])));
            return;
        }
        Severity severity = Severity.fromString(args[3]);
        if (severity == null) {
            sender.sendMessage(Messages.get("unknown-severity", "severity", args[3]));
            return;
        }
        var manager = Infestations.plugin.getInfestationManager();
        if (manager.get(provinceId) != null) {
            sender.sendMessage(Messages.get("already-infested", "id", String.valueOf(provinceId)));
            return;
        }
        manager.set(provinceId, args[2].toLowerCase(Locale.ROOT), severity);
        sender.sendMessage(Messages.get("set-ok", "id", String.valueOf(provinceId),
                "group", GroupLoader.displayName(args[2]),
                "severity", severity.display()));
    }

    private void handleClear(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.get("usage"));
            return;
        }
        Integer provinceId = resolveProvince(sender, args[1]);
        if (provinceId == null) {
            return;
        }
        var manager = Infestations.plugin.getInfestationManager();
        if (manager.get(provinceId) == null) {
            sender.sendMessage(Messages.get("not-infested", "id", String.valueOf(provinceId)));
            return;
        }
        manager.clear(provinceId, false);
        sender.sendMessage(Messages.get("clear-ok", "id", String.valueOf(provinceId)));
    }

    private void handleList(CommandSender sender) {
        var all = Infestations.plugin.getInfestationManager().all();
        if (!all.iterator().hasNext()) {
            sender.sendMessage(Messages.get("list-empty"));
            return;
        }
        for (Infestation infestation : all) {
            String lure = infestation.getPhase() != LurePhase.NONE ? Messages.get("list-lure-suffix") : "";
            sender.sendMessage(Messages.get("list-line",
                    "id", String.valueOf(infestation.getProvinceId()),
                    "group", GroupLoader.displayName(infestation.getGroupId()),
                    "severity", infestation.getSeverity().display(),
                    "lure", lure));
        }
    }

    private Integer resolveProvince(CommandSender sender, String token) {
        if ("here".equalsIgnoreCase(token)) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Messages.get("player-only"));
                return null;
            }
            int id = Provinces.at(player);
            if (!Provinces.validLand(id)) {
                sender.sendMessage(Messages.get(
                        id > 0 && Provinces.skipTerrain(id) ? "skip-terrain" : "unknown-province"));
                return null;
            }
            return id;
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Messages.get("unknown-province"));
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("infestations.admin.reload")) {
                options.add("reload");
            }
            if (sender.hasPermission("infestations.admin")) {
                options.add("set");
                options.add("clear");
                options.add("list");
            }
            return prefix(options, args[0]);
        }
        if (!sender.hasPermission("infestations.admin")) {
            return List.of();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("clear"))) {
            return prefix(List.of("here"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return prefix(new ArrayList<>(GroupLoader.groupIds()), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("set")) {
            return prefix(Arrays.stream(Severity.values()).map(Severity::id).collect(Collectors.toList()), args[3]);
        }
        return List.of();
    }

    private static List<String> prefix(List<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t)) {
                out.add(o);
            }
        }
        return out;
    }
}

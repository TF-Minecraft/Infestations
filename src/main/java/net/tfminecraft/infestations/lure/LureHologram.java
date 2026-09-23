package net.tfminecraft.infestations.lure;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.Messages;
import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.infestation.Infestation;
import net.tfminecraft.infestations.infestation.LurePhase;
import net.tfminecraft.infestations.spawn.Keys;

public final class LureHologram {

    private LureHologram() {}

    public static void tick(Infestation infestation) {
        Block block = infestation.lureBlock();
        if (block == null || !infestation.hasLure()) {
            return;
        }
        Location pos = position(block);
        if (pos.getWorld() == null) {
            return;
        }
        double rangeSq = Cache.hologramViewRange * Cache.hologramViewRange;
        boolean nearby = false;
        for (var player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() == pos.getWorld() && player.getLocation().distanceSquared(pos) <= rangeSq) {
                nearby = true;
                break;
            }
        }
        TextDisplay display = find(block, infestation.getProvinceId());
        if (!nearby) {
            if (display != null) {
                display.remove();
            }
            return;
        }
        if (display == null || display.isDead()) {
            display = spawn(block, infestation.getProvinceId());
        }
        if (display != null) {
            display.setText(text(infestation));
        }
    }

    public static void remove(Infestation infestation) {
        Block block = infestation.lureBlock();
        if (block == null) {
            return;
        }
        TextDisplay display = find(block, infestation.getProvinceId());
        if (display != null) {
            display.remove();
        }
    }

    public static void removeOrphans(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof TextDisplay display)) {
                continue;
            }
            Integer provinceId = Keys.displayProvince(display.getPersistentDataContainer());
            if (provinceId == null) {
                continue;
            }
            Infestation infestation = Infestations.plugin.getInfestationManager().get(provinceId);
            if (infestation == null || !infestation.hasLure()) {
                display.remove();
            }
        }
    }

    private static TextDisplay spawn(Block block, int provinceId) {
        Location pos = position(block);
        World world = pos.getWorld();
        if (world == null) {
            return null;
        }
        return world.spawn(pos, TextDisplay.class, td -> {
            td.setInvulnerable(true);
            td.setPersistent(true);
            td.setGravity(false);
            try {
                td.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            } catch (Throwable ignored) {
            }
            try {
                td.setShadowed(true);
            } catch (Throwable ignored) {
            }
            Keys.tagDisplay(td.getPersistentDataContainer(), provinceId);
        });
    }

    private static TextDisplay find(Block block, int provinceId) {
        Location pos = position(block);
        World world = pos.getWorld();
        if (world == null) {
            return null;
        }
        for (Entity entity : world.getNearbyEntities(pos, 2, 2, 2)) {
            if (entity instanceof TextDisplay display) {
                Integer id = Keys.displayProvince(display.getPersistentDataContainer());
                if (id != null && id == provinceId) {
                    return display;
                }
            }
        }
        return null;
    }

    private static Location position(Block block) {
        return block.getLocation().clone().add(0.5, 1.6, 0.5);
    }

    private static String text(Infestation infestation) {
        if (infestation.getPhase() == LurePhase.JOINING) {
            return Messages.get("hologram-joining", "seconds", String.valueOf(infestation.joinSecondsLeft()));
        }
        return Messages.get("hologram-remaining", "remaining", String.valueOf(infestation.getLureRemaining()));
    }
}

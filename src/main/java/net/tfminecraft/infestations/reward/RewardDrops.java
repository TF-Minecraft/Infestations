package net.tfminecraft.infestations.reward;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.Messages;
import net.tfminecraft.infestations.infestation.Infestation;
import net.tfminecraft.tlibs.TLibs;

/**
 * Pays rewards.yml loot for ambient kills and cleared lures.
 */
public final class RewardDrops {

    /** Ticks over which a lure burst is spread. */
    static final int BURST_TICKS = 30;
    /** Most entities one rolled stack is split into during a burst. */
    static final int MAX_PIECES = 3;
    static final String LURE_REWARD_FALLBACK = "{prefix}#c9a44aThe lure bursts open. Your spoils are marked for you.";

    static Random random = new Random();
    static RewardRoller roller = new RewardRoller(random);
    static LongSupplier clock = System::currentTimeMillis;
    static KeyCap keyCap = new KeyCap();
    static final Set<String> warnedPaths = new HashSet<>();

    private RewardDrops() {}

    /**
     * Drops the roll for one ambient mob killed by {@code killer} at its death location. The items go straight
     * into the world because MythicMobs PreventOtherDrops clears the death event's drop list.
     */
    public static void ambientKill(Infestation infestation, Player killer, Location location) {
        if (location == null || !RewardConfig.appliesTo(infestation.getGroupId())) {
            return;
        }
        RewardTable table = RewardConfig.ambient(infestation.getSeverity(), PermadeathZones.inZone(location));
        if (table == null) {
            return;
        }
        for (RewardRoller.Drop drop : roller.roll(table, RewardConfig.coins, null)) {
            int amount = drop.amount();
            if (drop.item().equals(RewardConfig.keyItem)) {
                amount = keyCap.allow(killer.getUniqueId(), amount, RewardConfig.keyCapPerHour, clock.getAsLong());
            }
            for (ItemStack stack : stacks(drop.item(), amount)) {
                location.getWorld().dropItemNaturally(location, stack);
            }
        }
    }

    /**
     * Gives each player with enough lure kills a burst of their own items from the lure. Outside a permadeath
     * zone each item only survives with its keep chance.
     */
    public static void lureVictory(Infestation infestation, Location location, Collection<Player> players) {
        if (location == null || !RewardConfig.appliesTo(infestation.getGroupId())) {
            return;
        }
        RewardTable table = RewardConfig.lure(infestation.getSeverity());
        if (table == null) {
            return;
        }
        RewardRoller.Keep keep = PermadeathZones.inZone(location) ? null : RewardConfig.lureKeep;
        for (Player player : players) {
            if (infestation.lureKills(player.getUniqueId()) < RewardConfig.lureMinKills) {
                continue;
            }
            List<ItemStack> items = new ArrayList<>();
            for (RewardRoller.Drop drop : roller.roll(table, RewardConfig.coins, keep)) {
                items.addAll(stacks(drop.item(), drop.amount()));
            }
            if (items.isEmpty()) {
                continue;
            }
            burst(player.getUniqueId(), location, items);
            player.sendMessage(Messages.getOrDefault("lure-reward", LURE_REWARD_FALLBACK));
        }
    }

    /** Builds the item and splits it into stacks no larger than its max stack size. */
    static List<ItemStack> stacks(String path, int amount) {
        List<ItemStack> out = new ArrayList<>();
        if (amount <= 0) {
            return out;
        }
        ItemStack base;
        try {
            base = TLibs.getItemAPI().getCreator().getItemFromPath(path);
        } catch (RuntimeException ex) {
            base = null;
        }
        if (base == null || base.getType().isAir()) {
            if (warnedPaths.add(path)) {
                Infestations.plugin.getLogger().warning("[Infestations] rewards.yml item not found: " + path);
            }
            return out;
        }
        int max = Math.max(1, base.getMaxStackSize());
        for (int left = amount; left > 0; left -= max) {
            ItemStack stack = base.clone();
            stack.setAmount(Math.min(max, left));
            out.add(stack);
        }
        return out;
    }

    /** Throws the items out of the lure one by one over {@link #BURST_TICKS}, locked to their owner. */
    static void burst(UUID owner, Location location, List<ItemStack> items) {
        List<ItemStack> pieces = new ArrayList<>();
        for (ItemStack stack : items) {
            pieces.addAll(split(stack));
        }
        Collections.shuffle(pieces, random);
        Location from = location.clone().add(0, 1.0, 0);
        int count = pieces.size();
        for (int i = 0; i < count; i++) {
            ItemStack piece = pieces.get(i);
            long delay = 1L + (long) i * BURST_TICKS / count;
            Bukkit.getScheduler().runTaskLater(Infestations.plugin, () -> eject(from, piece, owner), delay);
        }
    }

    static List<ItemStack> split(ItemStack stack) {
        int amount = stack.getAmount();
        int parts = Math.min(MAX_PIECES, amount);
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            ItemStack piece = stack.clone();
            piece.setAmount(amount / parts + (i < amount % parts ? 1 : 0));
            out.add(piece);
        }
        return out;
    }

    static void eject(Location from, ItemStack piece, UUID owner) {
        World world = from.getWorld();
        world.dropItem(from, piece, item -> {
            item.setOwner(owner);
            item.setPickupDelay(15);
            item.setVelocity(new Vector(
                    (random.nextDouble() - 0.5) * 0.3,
                    0.35 + random.nextDouble() * 0.2,
                    (random.nextDouble() - 0.5) * 0.3));
        });
        world.playSound(from, Sound.BLOCK_VAULT_EJECT_ITEM, 0.8f, 0.9f + random.nextFloat() * 0.2f);
    }
}

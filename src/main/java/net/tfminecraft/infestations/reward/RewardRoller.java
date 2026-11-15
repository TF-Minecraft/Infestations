package net.tfminecraft.infestations.reward;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Rolls a {@link RewardTable} into item paths and amounts. Has no Bukkit dependencies.
 */
public final class RewardRoller {

    /** Paid out as physical coins. */
    public static final String DENARS = "denars";
    /** One gem pouch per unit, tier picked from the table's gem weights. */
    public static final String GEM = "gem";

    public record Drop(String item, int amount) {}

    /** Survival chance per unit for lures outside a permadeath zone. */
    public record Keep(double fallback, Map<String, Double> items) {
        public Keep {
            items = Map.copyOf(items);
        }

        public double of(String item) {
            return items.getOrDefault(item, fallback);
        }
    }

    private final Random random;

    public RewardRoller(Random random) {
        this.random = random;
    }

    /**
     * Rolls every entry in the table. When {@code keep} is set, each unit survives with its keep chance;
     * denars are thinned before they are turned into coins.
     */
    public List<Drop> roll(RewardTable table, List<RewardTable.Coin> coins, Keep keep) {
        return roll(table, coins, keep, 1, null);
    }

    /**
     * Rolls the table {@code times} times and adds the results together. Entries for {@code skip} never roll.
     * Denars from every roll are summed before they are turned into coins.
     */
    public List<Drop> roll(RewardTable table, List<RewardTable.Coin> coins, Keep keep, int times, String skip) {
        Map<String, Integer> out = new LinkedHashMap<>();
        int denars = 0;
        for (int n = 0; n < times; n++) {
            denars += rollOnce(table, keep, skip, out);
        }
        for (Drop coin : coins(denars, coins)) {
            add(out, coin.item(), coin.amount());
        }
        List<Drop> drops = new ArrayList<>();
        out.forEach((item, amount) -> drops.add(new Drop(item, amount)));
        return drops;
    }

    /** Rolls every entry once into {@code out} and returns the denars rolled. */
    private int rollOnce(RewardTable table, Keep keep, String skip, Map<String, Integer> out) {
        int denars = 0;
        for (RewardTable.Entry entry : table.drops()) {
            if (entry.item().equals(skip) || !hit(entry.chance())) {
                continue;
            }
            int amount = amount(entry.min(), entry.max());
            if (GEM.equals(entry.item())) {
                for (int i = 0; i < amount; i++) {
                    String gem = pick(table.gems());
                    if (gem != null) {
                        add(out, gem, kept(gem, 1, keep));
                    }
                }
            } else if (DENARS.equals(entry.item())) {
                denars += kept(DENARS, amount, keep);
            } else {
                add(out, entry.item(), kept(entry.item(), amount, keep));
            }
        }
        return denars;
    }

    /** Splits denars into coins, largest value first. Remainders smaller than the smallest coin are lost. */
    public static List<Drop> coins(int denars, List<RewardTable.Coin> coins) {
        List<Drop> out = new ArrayList<>();
        if (denars <= 0) {
            return out;
        }
        List<RewardTable.Coin> sorted = new ArrayList<>(coins);
        sorted.sort(Comparator.comparingInt(RewardTable.Coin::value).reversed());
        int left = denars;
        for (RewardTable.Coin coin : sorted) {
            if (coin.value() <= 0) {
                continue;
            }
            int count = left / coin.value();
            if (count > 0) {
                out.add(new Drop(coin.item(), count));
                left -= count * coin.value();
            }
        }
        return out;
    }

    private boolean hit(double chance) {
        if (chance >= 1) {
            return true;
        }
        return chance > 0 && random.nextDouble() < chance;
    }

    private int amount(int min, int max) {
        return max > min ? min + random.nextInt(max - min + 1) : min;
    }

    private int kept(String item, int amount, Keep keep) {
        if (keep == null) {
            return amount;
        }
        double chance = keep.of(item);
        if (chance >= 1) {
            return amount;
        }
        int survived = 0;
        for (int i = 0; i < amount; i++) {
            if (hit(chance)) {
                survived++;
            }
        }
        return survived;
    }

    private String pick(List<RewardTable.Weighted> gems) {
        int total = 0;
        for (RewardTable.Weighted gem : gems) {
            total += Math.max(0, gem.weight());
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        String chosen = null;
        for (RewardTable.Weighted gem : gems) {
            roll -= Math.max(0, gem.weight());
            if (roll < 0) {
                chosen = gem.item();
                break;
            }
        }
        return chosen;
    }

    private static void add(Map<String, Integer> out, String item, int amount) {
        if (amount > 0) {
            out.merge(item, amount, Integer::sum);
        }
    }
}

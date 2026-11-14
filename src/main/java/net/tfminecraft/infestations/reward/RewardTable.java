package net.tfminecraft.infestations.reward;

import java.util.List;

/**
 * One block of rewards.yml: the drop entries and, for the {@code gem} entry, the gem tier weights.
 */
public record RewardTable(List<Entry> drops, List<Weighted> gems) {

    public RewardTable {
        drops = List.copyOf(drops);
        gems = List.copyOf(gems);
    }

    /** A drop entry. {@code chance} is 0-1; the amount is rolled between min and max. */
    public record Entry(String item, double chance, int min, int max) {}

    /** A gem tier and its weight. */
    public record Weighted(String item, int weight) {}

    /** A coin item and its denar value. */
    public record Coin(String item, int value) {}
}

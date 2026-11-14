package net.tfminecraft.infestations.reward;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.tfminecraft.infestations.reward.RewardRoller.Drop;
import net.tfminecraft.infestations.reward.RewardRoller.Keep;
import net.tfminecraft.infestations.reward.RewardTable.Coin;
import net.tfminecraft.infestations.reward.RewardTable.Entry;
import net.tfminecraft.infestations.reward.RewardTable.Weighted;

class RewardRollerTest {

    /** Random that returns queued values. */
    static final class Script extends Random {
        final Deque<Double> doubles = new ArrayDeque<>();
        final Deque<Integer> ints = new ArrayDeque<>();
        Script d(double... values) { for (double v : values) doubles.add(v); return this; }
        Script i(int... values) { for (int v : values) ints.add(v); return this; }
        @Override public double nextDouble() { return doubles.remove(); }
        @Override public int nextInt(int bound) { int v = ints.remove(); assertTrue(v < bound); return v; }
    }

    private static final List<Coin> COINS = List.of(new Coin("gold", 1), new Coin("pouch", 100), new Coin("stack", 10));

    @Test void rollsHitsAmountsGemsAndCoinsWithoutKeep() {
        Script random = new Script().d(0.5, 0.01).i(2, 1, 0);
        RewardTable table = new RewardTable(List.of(
                new Entry("miss", 0.1, 1, 1),
                new Entry("key", 0.5, 1, 3),
                new Entry("never", 0, 1, 1),
                new Entry("gem", 1, 2, 2),
                new Entry("denars", 1, 123, 123),
                new Entry("key", 1, 1, 1)),
                List.of(new Weighted("basic", 1), new Weighted("broken", -4), new Weighted("radiant", 9)));
        // miss: 0.5 misses 0.1. key: 0.01 hits, amount 1+2. gems: roll 1 passes basic and broken to radiant, roll 0 is basic.
        List<Drop> drops = new RewardRoller(random).roll(table, COINS, null);
        assertEquals(List.of(new Drop("key", 4), new Drop("radiant", 1), new Drop("basic", 1),
                new Drop("pouch", 1), new Drop("stack", 2), new Drop("gold", 3)), drops);
        assertTrue(random.doubles.isEmpty() && random.ints.isEmpty());
    }

    @Test void gemsWithoutWeightsAreSkipped() {
        RewardTable table = new RewardTable(List.of(new Entry("gem", 1, 3, 3)), List.of(new Weighted("zero", 0)));
        assertEquals(List.of(), new RewardRoller(new Script()).roll(table, COINS, null));
    }

    @Test void keepThinsEachUnitAndDenarsBeforeCoins() {
        Script random = new Script().d(0.05, 0.5, 0.05, 0.5, 0.05, 0.05, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5).i(0, 0);
        Keep keep = new Keep(0.1, Map.of("whole", 1.0, "gone", 0.0));
        RewardTable table = new RewardTable(List.of(
                new Entry("whole", 1, 2, 2),
                new Entry("gone", 1, 4, 4),
                new Entry("thin", 1, 2, 2),
                new Entry("gem", 1, 2, 2),
                new Entry("denars", 1, 10, 10)), List.of(new Weighted("basic", 1)));
        // whole keeps all, gone keeps none. thin: one of two. gems: one of two. denars: two of ten.
        List<Drop> drops = new RewardRoller(random).roll(table, List.of(new Coin("gold", 1)), keep);
        assertEquals(List.of(new Drop("whole", 2), new Drop("thin", 1), new Drop("basic", 1), new Drop("gold", 2)), drops);
        assertTrue(random.doubles.isEmpty() && random.ints.isEmpty());
        assertEquals(0.1, keep.of("other"));
        assertEquals(1.0, keep.of("whole"));
    }

    @Test void coinsUseLargestFirstAndSkipBadValues() {
        assertEquals(List.of(), RewardRoller.coins(0, COINS));
        assertEquals(List.of(), RewardRoller.coins(-5, COINS));
        assertEquals(List.of(new Drop("five", 1)),
                RewardRoller.coins(7, List.of(new Coin("bad", 0), new Coin("five", 5))));
    }
}

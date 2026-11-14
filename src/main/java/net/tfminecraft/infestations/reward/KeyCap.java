package net.tfminecraft.infestations.reward;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Counts ambient key drops per player over a rolling hour.
 */
final class KeyCap {

    static final long WINDOW_MS = 3_600_000L;

    private final Map<UUID, ArrayDeque<Long>> given = new HashMap<>();
    private long lastSweep;

    /** How many of {@code wanted} keys the player may still get. A cap of 0 or less means no cap. */
    int allow(UUID player, int wanted, int cap, long now) {
        if (wanted <= 0) {
            return 0;
        }
        if (cap <= 0) {
            return wanted;
        }
        sweep(now);
        ArrayDeque<Long> times = given.computeIfAbsent(player, k -> new ArrayDeque<>());
        expire(times, now);
        int allowed = Math.min(wanted, cap - times.size());
        for (int i = 0; i < allowed; i++) {
            times.addLast(now);
        }
        return Math.max(0, allowed);
    }

    /** Forgets players whose drops have all left the window, at most once per window. */
    private void sweep(long now) {
        if (now - lastSweep < WINDOW_MS) {
            return;
        }
        lastSweep = now;
        given.values().removeIf(times -> {
            expire(times, now);
            return times.isEmpty();
        });
    }

    private static void expire(ArrayDeque<Long> times, long now) {
        while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_MS) {
            times.pollFirst();
        }
    }

    int tracked() {
        return given.size();
    }

    void clear() {
        given.clear();
    }
}

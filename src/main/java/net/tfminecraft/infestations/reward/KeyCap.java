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

    /** How many of {@code wanted} keys the player may still get. A cap of 0 or less means no cap. */
    int allow(UUID player, int wanted, int cap, long now) {
        if (wanted <= 0) {
            return 0;
        }
        if (cap <= 0) {
            return wanted;
        }
        ArrayDeque<Long> times = given.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_MS) {
            times.pollFirst();
        }
        int allowed = Math.min(wanted, cap - times.size());
        for (int i = 0; i < allowed; i++) {
            times.addLast(now);
        }
        return Math.max(0, allowed);
    }

    void clear() {
        given.clear();
    }
}

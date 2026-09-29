package net.tfminecraft.infestations.spawn;

/**
 * How many lure mobs to put in the field on this attempt.
 *
 * <p>The duration spreads {@code lureCount} as introductions: mobs already sent out wait for the clock
 * before the next ones. A tally that has run ahead of both the clock and the mobs actually alive or
 * pending cannot freeze the lure while enemies remain.
 */
public final class LureSpawnQuota {

    private LureSpawnQuota() {}

    public static int toSpawn(
            int remaining,
            int alive,
            int pending,
            int released,
            long elapsedMs,
            long durationMs,
            int lureCount) {
        int inField = Math.max(0, alive) + Math.max(0, pending);
        int shortfall = Math.max(0, remaining) - inField;
        if (shortfall <= 0) {
            return 0;
        }
        if (lureCount <= 0 || durationMs <= 0 || elapsedMs >= durationMs) {
            return shortfall;
        }
        long allowed = Math.max(0, elapsedMs) * (long) lureCount / durationMs;
        if (allowed < 1) {
            allowed = 1;
        }
        if (allowed > Integer.MAX_VALUE) {
            allowed = Integer.MAX_VALUE;
        }
        int paceRoom = (int) allowed - Math.max(0, released);
        int clockTarget = (int) Math.min(Math.max(0, remaining), allowed);
        if (paceRoom < 0 && inField < clockTarget) {
            paceRoom = clockTarget - inField;
        }
        if (paceRoom <= 0) {
            return 0;
        }
        return Math.min(shortfall, paceRoom);
    }
}

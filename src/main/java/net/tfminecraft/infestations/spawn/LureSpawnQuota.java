package net.tfminecraft.infestations.spawn;

import java.math.BigInteger;

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
        long inField = (long) Math.max(0, alive) + Math.max(0, pending);
        long shortfall = Math.max(0, remaining) - inField;
        if (shortfall <= 0) {
            return 0;
        }
        if (lureCount <= 0 || durationMs <= 0 || elapsedMs >= durationMs) {
            return (int) shortfall;
        }
        // The product can exceed long, although elapsed < duration bounds the quotient below lureCount.
        int allowed = Math.max(1, BigInteger.valueOf(Math.max(0, elapsedMs))
                .multiply(BigInteger.valueOf(lureCount))
                .divide(BigInteger.valueOf(durationMs)).intValueExact());
        long paceRoom = (long) allowed - Math.max(0, released);
        int clockTarget = Math.min(Math.max(0, remaining), allowed);
        if (paceRoom < 0 && inField < clockTarget) {
            paceRoom = clockTarget - inField;
        }
        if (paceRoom <= 0) {
            return 0;
        }
        return (int) Math.min(shortfall, paceRoom);
    }
}

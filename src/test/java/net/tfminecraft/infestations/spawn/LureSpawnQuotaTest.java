package net.tfminecraft.infestations.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LureSpawnQuotaTest {

    @Test
    void emptyLureSpawnsImmediatelyWhileEnemiesRemain() {
        assertEquals(1, LureSpawnQuota.toSpawn(20, 0, 0, 0, 0, 120_000, 20));
    }

    @Test
    void clockReleasesOnlyWhatHasNotAlreadyBeenSent() {
        // 60s of a 120s, 20-mob lure allows 10. Seven have not been sent yet.
        assertEquals(7, LureSpawnQuota.toSpawn(20, 3, 0, 3, 60_000, 120_000, 20));
        assertEquals(0, LureSpawnQuota.toSpawn(20, 10, 0, 10, 60_000, 120_000, 20));
        assertEquals(0, LureSpawnQuota.toSpawn(20, 8, 2, 10, 60_000, 120_000, 20));
    }

    @Test
    void killsWaitForTheNextPacedIntroduction() {
        // Ten were sent and all killed. The clock has not allowed an eleventh yet.
        assertEquals(0, LureSpawnQuota.toSpawn(10, 0, 0, 10, 60_000, 120_000, 20));
    }

    @Test
    void aTallyAheadOfTheFieldCannotLeaveTheLureEmpty() {
        // Twenty marked released, none alive, and the clock has only reached 10.
        assertEquals(10, LureSpawnQuota.toSpawn(20, 0, 0, 20, 60_000, 120_000, 20));
        assertEquals(4, LureSpawnQuota.toSpawn(20, 4, 2, 20, 60_000, 120_000, 20));
    }

    @Test
    void adoptedMobsAboveTheClockBlockExtraSpawns() {
        assertEquals(0, LureSpawnQuota.toSpawn(20, 12, 0, 12, 30_000, 120_000, 20));
    }

    @Test
    void afterTheDurationEveryMissingMobIsSpawned() {
        assertEquals(15, LureSpawnQuota.toSpawn(15, 0, 0, 40, 120_000, 120_000, 20));
        assertEquals(4, LureSpawnQuota.toSpawn(6, 1, 1, 6, 180_000, 120_000, 20));
    }

    @Test
    void noPaceLimitReleasesTheWholeShortfall() {
        assertEquals(8, LureSpawnQuota.toSpawn(8, 0, 0, 0, 0, 0, 20));
        assertEquals(8, LureSpawnQuota.toSpawn(8, 0, 0, 8, 0, 120_000, 0));
    }

    @Test
    void nothingOwedWhenTheFieldAlreadyCoversTheRemainder() {
        assertEquals(0, LureSpawnQuota.toSpawn(4, 3, 1, 4, 0, 120_000, 20));
        assertEquals(0, LureSpawnQuota.toSpawn(0, 0, 0, 10, 10_000, 120_000, 20));
    }

    @Test
    void largeConfiguredDurationsPreserveTheExactPacedQuota() {
        long duration = Integer.MAX_VALUE * 1000L;
        assertEquals(Integer.MAX_VALUE / 2, LureSpawnQuota.toSpawn(
                Integer.MAX_VALUE, 0, 0, 0, duration / 2, duration, Integer.MAX_VALUE));
    }

    @Test
    void finalMillisecondBeforeExpiryDoesNotRoundUpToTheFinalIntroduction() {
        assertEquals(Integer.MAX_VALUE - 1, LureSpawnQuota.toSpawn(
                Integer.MAX_VALUE, 0, 0, 0, Long.MAX_VALUE - 1, Long.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, LureSpawnQuota.toSpawn(
                Integer.MAX_VALUE, 0, 0, 0, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void combinedAliveAndPendingCountsCannotWrapIntoAnEmptyField() {
        assertEquals(0, LureSpawnQuota.toSpawn(
                1, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 120_000, 120_000, 20));
        assertEquals(0, LureSpawnQuota.toSpawn(
                0, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 120_000, 20));
    }

    @Test
    void negativeCountersAndBackwardClockAreTreatedAsZero() {
        assertEquals(1, LureSpawnQuota.toSpawn(
                10, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Long.MIN_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(0, LureSpawnQuota.toSpawn(
                Integer.MIN_VALUE, 0, 0, 0, 0, 120_000, 20));
    }
}

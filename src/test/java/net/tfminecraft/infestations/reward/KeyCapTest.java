package net.tfminecraft.infestations.reward;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class KeyCapTest {
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test void capsKeysPerPlayerOverARollingHour() {
        KeyCap cap = new KeyCap();
        assertEquals(0, cap.allow(A, 0, 5, 0));
        assertEquals(9, cap.allow(A, 9, 0, 0));
        assertEquals(3, cap.allow(A, 3, 5, 1_000));
        assertEquals(2, cap.allow(A, 4, 5, 2_000));
        assertEquals(0, cap.allow(A, 1, 5, 3_000));
        assertEquals(1, cap.allow(B, 1, 5, 3_000));
        assertEquals(3, cap.allow(A, 4, 5, 1_000 + KeyCap.WINDOW_MS));
        assertEquals(0, cap.allow(A, 1, 2, 1_000 + KeyCap.WINDOW_MS));
        cap.clear();
        assertEquals(5, cap.allow(A, 5, 5, 0));
    }

    @Test void forgetsPlayersWhoseKeysLeftTheWindowButKeepsRecentOnes() {
        KeyCap cap = new KeyCap();
        long w = KeyCap.WINDOW_MS;
        cap.allow(A, 2, 5, 10);
        cap.allow(B, 2, 5, w - 10);
        assertEquals(2, cap.tracked());
        cap.allow(B, 1, 5, w + 10);
        assertEquals(1, cap.tracked());
        assertEquals(2, cap.allow(B, 5, 5, w + 20));
        cap.allow(A, 1, 5, w + 30);
        assertEquals(2, cap.tracked());
        assertEquals(0, cap.allow(B, 5, 5, w + 40));
    }
}

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
}

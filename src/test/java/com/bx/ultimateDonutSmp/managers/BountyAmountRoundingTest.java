package com.bx.ultimateDonutSmp.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BountyAmountRoundingTest {

    @Test
    void bountyAmountsAreRoundedToCentsLikeTheEconomy() {
        assertEquals(1.23D, BountyManager.roundToCents(1.234D));
        assertEquals(2.5D, BountyManager.roundToCents(2.499D));
        assertEquals(1000D, BountyManager.roundToCents(999.999D));
        assertEquals(100D, BountyManager.roundToCents(100D));
    }
}

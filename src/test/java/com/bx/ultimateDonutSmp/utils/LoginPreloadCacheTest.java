package com.bx.ultimateDonutSmp.utils;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoginPreloadCacheTest {

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final LoginPreloadCache<String> cache = new LoginPreloadCache<>(10_000L, clock::get);
    private final UUID uuid = UUID.randomUUID();

    @Test
    void completedPreloadIsClaimedOnce() {
        cache.complete(cache.begin(uuid), "row");

        LoginPreloadCache.Preloaded<String> claimed = cache.take(uuid);
        assertNotNull(claimed);
        assertEquals("row", claimed.value());
        assertNull(cache.take(uuid));
    }

    @Test
    void emptyReadIsStillAClaimablePreload() {
        cache.complete(cache.begin(uuid), null);

        LoginPreloadCache.Preloaded<String> claimed = cache.take(uuid);
        assertNotNull(claimed, "a player with no stored row is a valid preload result");
        assertNull(claimed.value());
    }

    @Test
    void invalidationWhileTheReadRunsDiscardsIt() {
        LoginPreloadCache.Ticket<String> ticket = cache.begin(uuid);
        cache.invalidate(uuid);
        cache.complete(ticket, "read before the write");

        assertNull(cache.take(uuid));
    }

    @Test
    void unfinishedPreloadIsNotHandedOut() {
        cache.begin(uuid);

        assertNull(cache.take(uuid));
    }

    @Test
    void cancellingAnOldTicketKeepsTheNewerOne() {
        LoginPreloadCache.Ticket<String> older = cache.begin(uuid);
        LoginPreloadCache.Ticket<String> newer = cache.begin(uuid);
        cache.complete(newer, "newer");

        cache.cancel(uuid, older);

        LoginPreloadCache.Preloaded<String> claimed = cache.take(uuid);
        assertNotNull(claimed);
        assertEquals("newer", claimed.value());
    }

    @Test
    void unclaimedPreloadsExpireAndAreSweptByTheNextLogin() {
        cache.complete(cache.begin(uuid), "refused login");
        clock.addAndGet(10_000L);

        assertNull(cache.take(uuid));

        cache.complete(cache.begin(uuid), "refused login");
        clock.addAndGet(10_000L);
        cache.begin(UUID.randomUUID());
        assertEquals(1, cache.size(), "the expired entry is swept when another login begins");
    }
}

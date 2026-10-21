package com.bx.ultimateDonutSmp.managers;

import com.bx.ultimateDonutSmp.UltimateDonutSmp;
import com.bx.ultimateDonutSmp.models.PlayerData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeaderboardRefreshTest {

    /** Counts full scans and runs async work only when the test says so. */
    private static final class CountingDatabase extends DatabaseManager {
        private final AtomicInteger scans = new AtomicInteger();
        private final Deque<Runnable> pending = new ArrayDeque<>();
        private final List<PlayerData> rows;
        private Runnable duringFirstScan;

        private CountingDatabase(List<PlayerData> rows) {
            super(null);
            this.rows = rows;
        }

        @Override
        public List<PlayerData> loadAllPlayers() {
            if (scans.incrementAndGet() == 1 && duringFirstScan != null) {
                duringFirstScan.run();
            }
            return rows;
        }

        @Override
        public void executeAsync(Runnable task) {
            pending.add(task);
        }

        private void runPending() {
            Runnable task;
            while ((task = pending.poll()) != null) {
                task.run();
            }
        }
    }

    @Test
    void oneScanFillsEveryBoardAndReadsDoNotQueueMoreScans() throws Exception {
        CountingDatabase database = new CountingDatabase(List.of(player("Rich", 500D), player("Poor", 5D)));
        LeaderboardManager leaderboards = new LeaderboardManager(plugin(database));
        database.runPending();

        assertEquals(1, database.scans.get());
        for (LeaderboardManager.LeaderboardType type : LeaderboardManager.LeaderboardType.values()) {
            leaderboards.getEntries(type, 0, 10);
        }
        database.runPending();
        assertEquals(1, database.scans.get(), "fresh boards are served from the cache");
        assertEquals("Rich", leaderboards.getEntryAt(LeaderboardManager.LeaderboardType.MONEY, 1).playerData().getUsername());
        assertEquals(0, leaderboards.getTotalEntries(LeaderboardManager.LeaderboardType.BOUNTIES));
    }

    @Test
    void forcedRefreshDuringARunningScanScansAgainAfterwards() throws Exception {
        CountingDatabase database = new CountingDatabase(List.of(player("Only", 1D)));
        LeaderboardManager leaderboards = new LeaderboardManager(plugin(database));
        // A wipe lands while the first scan is reading, so that scan may already be out of date.
        database.duringFirstScan = () -> {
            leaderboards.invalidateAll();
            leaderboards.invalidateAll();
        };
        database.runPending();

        assertEquals(2, database.scans.get(), "one extra scan, however many requests came in");
    }

    @Test
    void cacheLifetimeIsClamped() {
        assertEquals(LeaderboardManager.MIN_CACHE_TTL_MS, LeaderboardManager.clampCacheTtl(0L));
        assertEquals(30_000L, LeaderboardManager.clampCacheTtl(30_000L));
        assertEquals(LeaderboardManager.MAX_CACHE_TTL_MS, LeaderboardManager.clampCacheTtl(Long.MAX_VALUE / 2));
    }

    private static PlayerData player(String name, double money) {
        PlayerData data = new PlayerData(UUID.randomUUID(), name);
        data.setMoney(money);
        return data;
    }

    private static UltimateDonutSmp plugin(DatabaseManager database) throws Exception {
        UltimateDonutSmp plugin = PlayerDataManagerLoginPreloadTest.pluginWith(database);
        Field playersField = UltimateDonutSmp.class.getDeclaredField("playerDataManager");
        playersField.setAccessible(true);
        playersField.set(plugin, new PlayerDataManager(plugin));
        return plugin;
    }
}

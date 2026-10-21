package com.bx.ultimateDonutSmp.managers;

import com.bx.ultimateDonutSmp.UltimateDonutSmp;
import com.bx.ultimateDonutSmp.models.EconomyFailureReason;
import com.bx.ultimateDonutSmp.models.EconomyReason;
import com.bx.ultimateDonutSmp.models.EconomyTransactionResult;
import com.bx.ultimateDonutSmp.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyManagerOfflineBalanceTest {

    private Server originalServer;
    private Connection connection;
    private DatabaseManager database;
    private EconomyManager economy;
    private final UUID uuid = UUID.randomUUID();
    private EconomyManager.AccountReference account;

    @BeforeEach
    void setUp() throws Exception {
        originalServer = Bukkit.getServer();
        // Nobody is online, so every account goes through the offline path.
        setServer((Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType() == boolean.class ? false : null;
                }
        ));

        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        database = new DatabaseManager(null);
        Field connectionField = DatabaseManager.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(database, connection);
        PlayerDataManagerLoginPreloadTest.invoke(database, "createTables");
        PlayerDataManagerLoginPreloadTest.invoke(database, "ensurePlayerColumns");

        UltimateDonutSmp plugin = PlayerDataManagerLoginPreloadTest.pluginWith(database);
        economy = new EconomyManager(plugin);

        PlayerData stored = new PlayerData(uuid, "Offline");
        stored.setMoney(0D);
        stored.setKills(3);
        database.savePlayer(stored);
        account = new EconomyManager.AccountReference(uuid, "Offline", null);
    }

    @AfterEach
    void tearDown() throws Exception {
        setServer(originalServer);
        connection.close();
    }

    @Test
    void concurrentOfflineDepositsAreAllKept() throws Exception {
        int threads = 8;
        int depositsPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < depositsPerThread; i++) {
                        assertTrue(economy.deposit(account, 1D, EconomyReason.SELL_PAYOUT).success());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * depositsPerThread, database.loadPlayer(uuid).getMoney(), 0.0001D);
    }

    @Test
    void offlineDepositOnlyTouchesTheBalance() {
        assertTrue(economy.deposit(account, 10D, EconomyReason.SELL_PAYOUT).success());

        PlayerData stored = database.loadPlayer(uuid);
        assertEquals(10D, stored.getMoney(), 0.0001D);
        assertEquals(3, stored.getKills());
    }

    @Test
    void offlineWithdrawalNeverOverdraws() {
        assertTrue(economy.deposit(account, 5D, EconomyReason.SELL_PAYOUT).success());

        EconomyTransactionResult tooMuch = economy.withdraw(account, 6D, EconomyReason.SHOP_PURCHASE);
        assertFalse(tooMuch.success());
        assertEquals(EconomyFailureReason.INSUFFICIENT_FUNDS, tooMuch.failureReason());

        assertTrue(economy.withdraw(account, 5D, EconomyReason.SHOP_PURCHASE).success());
        assertEquals(0D, database.loadPlayer(uuid).getMoney(), 0.0001D);
    }

    @Test
    void conditionalWithdrawalRefusesWhenTheStoredBalanceIsTooLow() {
        assertTrue(database.addPlayerMoney(uuid, 2.5D));

        assertFalse(database.withdrawPlayerMoneyIfAvailable(uuid, 3D));
        assertTrue(database.withdrawPlayerMoneyIfAvailable(uuid, 2.5D));
        assertEquals(0D, database.loadPlayer(uuid).getMoney(), 0.0001D);
        assertFalse(database.addPlayerMoney(UUID.randomUUID(), 1D), "no row, nothing changed");
    }

    @Test
    void offlineSetBalanceOnlyTouchesTheBalance() {
        assertTrue(economy.setBalance(account, 42D, EconomyReason.SHOP_REFUND).success());

        PlayerData stored = database.loadPlayer(uuid);
        assertEquals(42D, stored.getMoney(), 0.0001D);
        assertEquals(3, stored.getKills());
    }

    private static void setServer(Server server) throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, server);
    }
}

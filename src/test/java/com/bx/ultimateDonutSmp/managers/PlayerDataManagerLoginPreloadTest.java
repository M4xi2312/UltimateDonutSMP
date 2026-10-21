package com.bx.ultimateDonutSmp.managers;

import com.bx.ultimateDonutSmp.UltimateDonutSmp;
import com.bx.ultimateDonutSmp.models.PlayerData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.reflect.ReflectionFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataManagerLoginPreloadTest {

    private Connection connection;
    private DatabaseManager database;
    private PlayerDataManager players;
    private final UUID uuid = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        database = new DatabaseManager(null);
        Field connectionField = DatabaseManager.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(database, connection);
        invoke(database, "createTables");
        invoke(database, "ensurePlayerColumns");

        players = new PlayerDataManager(pluginWith(database));

        PlayerData stored = new PlayerData(uuid, "Joiner");
        stored.setMoney(100D);
        database.savePlayer(stored);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void joinUsesThePreloadedRowInsteadOfQueryingAgain() throws Exception {
        players.preload(uuid);
        // Changed behind the manager's back, so only a fresh query would see it.
        rawSetMoney(999D);

        assertEquals(100D, players.loadOrCreate(player()).getMoney());
    }

    @Test
    void savingTheRowAfterThePreloadMakesTheJoinReadItAgain() {
        players.preload(uuid);
        PlayerData offlineCopy = database.loadPlayer(uuid);
        offlineCopy.setMoney(500D);
        database.savePlayer(offlineCopy);

        assertEquals(500D, players.loadOrCreate(player()).getMoney());
    }

    @Test
    void offlineBalanceChangeAfterThePreloadMakesTheJoinReadItAgain() {
        players.preload(uuid);
        assertTrue(database.addPlayerMoney(uuid, 50D));

        assertEquals(150D, players.loadOrCreate(player()).getMoney());
    }

    @Test
    void secondLoginWhileStillLoadedDoesNotOverwriteUnsavedChanges() {
        PlayerData live = players.loadOrCreate(player());
        live.setMoney(777D);

        players.preload(uuid);
        players.unload(uuid);

        assertEquals(777D, players.loadOrCreate(player()).getMoney());
    }

    @Test
    void quitWithoutJoinDropsThePreload() throws Exception {
        players.preload(uuid);
        players.unload(uuid);
        rawSetMoney(321D);

        assertEquals(321D, players.loadOrCreate(player()).getMoney());
    }

    @Test
    void discardedPreloadIsNotUsed() throws Exception {
        players.preload(uuid);
        players.discardLoginPreload(uuid);
        rawSetMoney(42D);

        assertEquals(42D, players.loadOrCreate(player()).getMoney());
        assertTrue(players.isLoaded(uuid));
        assertFalse(players.isLoaded(null));
    }

    private void rawSetMoney(double money) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE players SET money = " + money + " WHERE uuid = '" + uuid + "'");
        }
    }

    private Player player() {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "Joiner";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                }
        );
    }

    static UltimateDonutSmp pluginWith(DatabaseManager database) throws Exception {
        Constructor<Object> objectConstructor = Object.class.getConstructor();
        Constructor<?> pluginConstructor = ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(UltimateDonutSmp.class, objectConstructor);
        UltimateDonutSmp plugin = (UltimateDonutSmp) pluginConstructor.newInstance();
        Field databaseField = UltimateDonutSmp.class.getDeclaredField("databaseManager");
        databaseField.setAccessible(true);
        databaseField.set(plugin, database);
        return plugin;
    }

    static void invoke(DatabaseManager manager, String methodName) throws Exception {
        Method method = DatabaseManager.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(manager);
    }
}

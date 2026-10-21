package com.bx.ultimateDonutSmp.managers;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerCrateKeysTest {

    @Test
    void removeCrateKeysOnlySpendsAvailableKeysAndDeletesEmptyRows() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseManager manager = managerWithConnection(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE player_crate_keys (player_uuid TEXT, crate_id TEXT, "
                        + "amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0, PRIMARY KEY (player_uuid, crate_id))");
            }
            UUID player = UUID.randomUUID();

            assertTrue(manager.removeCrateKeys(player, "vote", 0));
            assertFalse(manager.removeCrateKeys(player, "vote", 1));

            assertTrue(manager.setCrateKeyAmount(player, "vote", 3));
            assertFalse(manager.removeCrateKeys(player, "vote", 4));
            assertEquals(3, manager.getCrateKeyAmount(player, "vote"));

            assertTrue(manager.removeCrateKeys(player, "vote", 2));
            assertEquals(1, manager.getCrateKeyAmount(player, "vote"));

            assertTrue(manager.removeCrateKeys(player, "vote", 1));
            assertEquals(0, manager.getCrateKeyAmount(player, "vote"));
            assertEquals(0, countRows(connection));
            assertFalse(manager.removeCrateKeys(player, "vote", 1));
        }
    }

    private DatabaseManager managerWithConnection(Connection connection) throws Exception {
        DatabaseManager manager = new DatabaseManager(null);
        Field connectionField = DatabaseManager.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(manager, connection);
        return manager;
    }

    private int countRows(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM player_crate_keys")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}

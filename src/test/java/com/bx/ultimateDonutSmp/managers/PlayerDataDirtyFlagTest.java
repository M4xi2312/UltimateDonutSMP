package com.bx.ultimateDonutSmp.managers;

import com.bx.ultimateDonutSmp.models.PlayerData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataDirtyFlagTest {

    @Test
    void clearDirtyReportsAndClearsTheFlag() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "Dirty");
        assertFalse(data.isDirty());

        data.setMoney(5D);
        assertTrue(data.clearDirty());
        assertFalse(data.isDirty());
        assertFalse(data.clearDirty());

        data.setKills(1);
        assertTrue(data.isDirty(), "a change after the clear is kept for the next save");
    }

    @Test
    void successfulSaveClearsTheFlagAndFailedSaveKeepsIt() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        DatabaseManager database = new DatabaseManager(null);
        Field connectionField = DatabaseManager.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(database, connection);
        invoke(database, "createTables");
        invoke(database, "ensurePlayerColumns");

        PlayerData data = new PlayerData(UUID.randomUUID(), "Saver");
        data.setMoney(10D);
        database.savePlayer(data);
        assertFalse(data.isDirty());

        connection.close();
        data.setMoney(20D);
        data.clearDirty();
        database.savePlayer(data);
        assertTrue(data.isDirty(), "a save that never reached the database must be retried");
    }

    private static void invoke(DatabaseManager manager, String methodName) throws Exception {
        Method method = DatabaseManager.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(manager);
    }
}

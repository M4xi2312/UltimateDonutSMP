package com.bx.ultimateDonutSmp.managers;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DatabaseManagerSqlLiteralTest {

    private static final String[] TRICKY_STRINGS = {
            "plain",
            "it's",
            "back\\slash",
            "\\'); DROP TABLE players; --",
            "line\nbreak\r\ttab",
            "nul\u0000byte",
            "ctrl\u001az",
            "unicode é中🍩",
            ""
    };

    @Test
    void sqliteLiteralsRoundTrip() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (v TEXT)");
            for (String value : TRICKY_STRINGS) {
                statement.executeUpdate("DELETE FROM t");
                statement.executeUpdate("INSERT INTO t (v) VALUES (" + DatabaseManager.toSqlLiteral(value, false) + ")");
                try (ResultSet rs = statement.executeQuery("SELECT v, typeof(v) FROM t")) {
                    rs.next();
                    assertEquals(value, rs.getString(1));
                    assertEquals("text", rs.getString(2));
                }
            }
        }
    }

    @Test
    void mySqlLiteralsNeverContainRawBackslashOrControlCharacters() {
        for (String value : TRICKY_STRINGS) {
            String literal = DatabaseManager.toSqlLiteral(value, true);
            assertFalse(literal.contains("\\"), literal);
            assertFalse(literal.chars().anyMatch(c -> c < 0x20), literal);
        }
        assertEquals("CONVERT(X'615c62' USING utf8mb4)", DatabaseManager.toSqlLiteral("a\\b", true));
        assertEquals("'it''s'", DatabaseManager.toSqlLiteral("it's", true));
    }

    @Test
    void nonFiniteNumbersBecomeNull() {
        assertEquals("NULL", DatabaseManager.toSqlLiteral(Double.NaN, true));
        assertEquals("NULL", DatabaseManager.toSqlLiteral(Double.POSITIVE_INFINITY, false));
        assertEquals("NULL", DatabaseManager.toSqlLiteral(Float.NEGATIVE_INFINITY, false));
        assertEquals("NULL", DatabaseManager.toSqlLiteral(null, false));
        assertEquals("1.5", DatabaseManager.toSqlLiteral(1.5D, false));
        assertEquals("42", DatabaseManager.toSqlLiteral(42L, true));
        assertEquals("12.34", DatabaseManager.toSqlLiteral(new BigDecimal("12.34"), true));
        assertEquals("X'00ff'", DatabaseManager.toSqlLiteral(new byte[]{0, (byte) 0xff}, false));
    }
}

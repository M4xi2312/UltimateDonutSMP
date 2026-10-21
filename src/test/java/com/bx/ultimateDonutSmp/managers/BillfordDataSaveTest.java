package com.bx.ultimateDonutSmp.managers;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class BillfordDataSaveTest {

    @TempDir
    Path folder;

    @Test
    void savedStateWritesTheKeysLoadReadsBack() throws Exception {
        UUID player = UUID.randomUUID();
        BillfordManager.SavedState state = new BillfordManager.SavedState(3, 1_700_000_000_000L, Map.of(player, 2));

        Path file = folder.resolve("billford-data.yml");
        BillfordManager.writeAtomically(file, state.toYaml().saveToString());

        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(3, loaded.getInt("current-trade"));
        assertEquals(1_700_000_000_000L, loaded.getLong("next-advance-millis"));
        assertEquals(2, loaded.getInt("player-counts." + player));
    }

    @Test
    void anEmptyRotationLeavesNoCountsBehind() throws Exception {
        Path file = folder.resolve("billford-data.yml");
        Files.writeString(file, "player-counts:\n  " + UUID.randomUUID() + ": 5\n");

        BillfordManager.writeAtomically(file, new BillfordManager.SavedState(1, 5L, Map.of()).toYaml().saveToString());

        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file.toFile());
        assertNull(loaded.getConfigurationSection("player-counts"));
        assertEquals(1, loaded.getInt("current-trade"));
    }

    @Test
    void theTemporaryFileDoesNotLinger() throws Exception {
        Path file = folder.resolve("nested").resolve("billford-data.yml");

        BillfordManager.writeAtomically(file, "current-trade: 4\n");

        assertEquals("current-trade: 4\n", Files.readString(file));
        assertFalse(Files.exists(file.resolveSibling("billford-data.yml.tmp")));
    }
}

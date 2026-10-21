package com.bx.ultimateDonutSmp.models;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpawnerSlotLootTransferTest {

    @Test
    void secondViewerCannotTakeAStackTheFirstAlreadyTook() {
        SpawnerInstance instance = spawnerWith(slot(0, Material.BONE, 64));

        // both viewers still show 64 bones in slot 0
        assertEquals(64L, instance.takeSlotLoot(0, Material.BONE, 64));
        assertEquals(0L, instance.takeSlotLoot(0, Material.BONE, 64));
        assertNull(instance.getSlotLoot(0));
    }

    @Test
    void takingIsClampedToWhatIsStillStored() {
        SpawnerInstance instance = spawnerWith(slot(3, Material.ARROW, 10));

        assertEquals(10L, instance.takeSlotLoot(3, Material.ARROW, 64));
        assertNull(instance.getSlotLoot(3));
    }

    @Test
    void partialTakeLeavesTheRestInTheSlot() {
        SpawnerInstance instance = spawnerWith(slot(0, Material.STRING, 40));

        assertEquals(20L, instance.takeSlotLoot(0, Material.STRING, 20));
        assertEquals(20L, instance.getSlotLoot(0).getAmount());
        assertEquals(Material.STRING, instance.getSlotLoot(0).getMaterial());
    }

    @Test
    void slotNowHoldingSomethingElseHandsOutNothing() {
        // the view still shows bones, but regrouping moved arrows into that slot
        SpawnerInstance instance = spawnerWith(slot(0, Material.ARROW, 64));

        assertEquals(0L, instance.takeSlotLoot(0, Material.BONE, 64));
        assertEquals(0L, instance.takeSlotLoot(1, Material.BONE, 64));
        assertEquals(64L, instance.getSlotLoot(0).getAmount());
        assertEquals(0L, instance.takeSlotLoot(0, Material.ARROW, 0));
    }

    @Test
    void addingTopsUpToTheStackLimit() {
        SpawnerInstance instance = spawnerWith(slot(0, Material.BONE, 60));

        assertEquals(4L, instance.addSlotLoot(0, Material.BONE, 10, 64));
        assertEquals(64L, instance.getSlotLoot(0).getAmount());
        assertEquals(0L, instance.addSlotLoot(0, Material.BONE, 1, 64));
    }

    @Test
    void addingIntoAnEmptySlotStartsAStack() {
        SpawnerInstance instance = spawnerWith();

        assertEquals(16L, instance.addSlotLoot(5, Material.ENDER_PEARL, 20, 16));
        assertEquals(16L, instance.getSlotLoot(5).getAmount());
        assertEquals(0L, instance.addSlotLoot(-1, Material.ENDER_PEARL, 1, 16));
    }

    @Test
    void addingNeverOverwritesADifferentMaterial() {
        // a stale view shows slot 0 empty, but it holds rotten flesh in storage
        SpawnerInstance instance = spawnerWith(slot(0, Material.ROTTEN_FLESH, 64));

        assertEquals(0L, instance.addSlotLoot(0, Material.DIAMOND, 1, 64));
        assertEquals(Material.ROTTEN_FLESH, instance.getSlotLoot(0).getMaterial());
        assertEquals(64L, instance.getSlotLoot(0).getAmount());
    }

    @Test
    void concurrentTakesNeverHandOutMoreThanIsStored() throws Exception {
        int slots = 200;
        SpawnerLootEntry[] entries = new SpawnerLootEntry[slots];
        for (int i = 0; i < slots; i++) {
            entries[i] = slot(i, Material.BONE, 64);
        }
        SpawnerInstance instance = spawnerWith(entries);

        AtomicLong handedOut = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] viewers = new Thread[4];
        for (int v = 0; v < viewers.length; v++) {
            viewers[v] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < slots; i++) {
                    handedOut.addAndGet(instance.takeSlotLoot(i, Material.BONE, 64));
                }
            });
            viewers[v].start();
        }
        start.countDown();
        for (Thread viewer : viewers) {
            viewer.join();
        }

        assertEquals(slots * 64L, handedOut.get());
        assertEquals(0L, instance.getTotalStoredItems());
    }

    private static SpawnerLootEntry slot(int slotIndex, Material material, long amount) {
        return new SpawnerLootEntry("SLOT_" + slotIndex, material, amount);
    }

    private static SpawnerInstance spawnerWith(SpawnerLootEntry... entries) {
        SpawnerInstance instance = new SpawnerInstance(
                1L,
                "world",
                0,
                64,
                0,
                UUID.randomUUID(),
                "BeestoXd",
                "SKELETON",
                1L,
                SpawnerInstance.AccessMode.OWNER_ONLY,
                0L,
                0L,
                0L
        );
        instance.setStoredLootEntries(List.of(entries));
        return instance;
    }
}

package com.bx.ultimateDonutSmp.managers;

import com.bx.ultimateDonutSmp.UltimateDonutSmp;
import com.bx.ultimateDonutSmp.models.PlayerData;
import com.bx.ultimateDonutSmp.utils.LoginPreloadCache;
import com.bx.ultimateDonutSmp.utils.PlayerSettingDefaults;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {

    private final UltimateDonutSmp plugin;
    // Read from async threads (autosave, leaderboards) while joins and quits change it.
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final LoginPreloadCache<PlayerData> loginPreloads = new LoginPreloadCache<>();

    public PlayerDataManager(UltimateDonutSmp plugin) {
        this.plugin = plugin;
        if (plugin != null && plugin.getDatabaseManager() != null) {
            plugin.getDatabaseManager().setPlayerRowWriteListener(this::discardLoginPreload);
        }
    }

    /**
     * Reads a joining player's row while the login is still off the main thread, so
     * {@link #loadOrCreate} can skip the query. Any later write to the row discards the result
     * again (see {@link DatabaseManager#setPlayerRowWriteListener}), and so does a quit, so the
     * join never starts from a copy older than what is stored.
     */
    public void preload(UUID uuid) {
        if (uuid == null) {
            return;
        }
        LoginPreloadCache.Ticket<PlayerData> ticket = loginPreloads.begin(uuid);
        if (cache.containsKey(uuid)) {
            // Still loaded from a session whose quit has not run yet; its unsaved changes are
            // newer than the row, so the join has to read the row after that quit saved it.
            loginPreloads.cancel(uuid, ticket);
            return;
        }
        loginPreloads.complete(ticket, plugin.getDatabaseManager().loadPlayer(uuid));
    }

    /** Drops a preload that will not be used, for example because the login was refused. */
    public void discardLoginPreload(UUID uuid) {
        loginPreloads.invalidate(uuid);
    }

    public void discardLoginPreloads() {
        loginPreloads.clear();
    }

    public PlayerData loadOrCreate(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData data = cache.get(uuid);
        if (data != null) {
            data.setUsername(player.getName());
            data.setSessionStartMillis(System.currentTimeMillis());
            return data;
        }

        LoginPreloadCache.Preloaded<PlayerData> preloaded = loginPreloads.take(uuid);
        data = preloaded != null ? preloaded.value() : plugin.getDatabaseManager().loadPlayer(uuid);
        if (data == null) {
            data = new PlayerData(uuid, player.getName());
            PlayerSettingDefaults.applyDefaults(plugin, data);
            double startMoney = plugin.getConfigManager().getConfig()
                    .getDouble("SETTINGS.MONEY-PER-DEFAULT", 1000.0);
            data.setMoney(startMoney);
            plugin.getDatabaseManager().savePlayer(data);
        } else {
            data.setUsername(player.getName());
        }
        PlayerSettingDefaults.applyDisabledOptions(plugin, data);
        data.setSessionStartMillis(System.currentTimeMillis());
        cache.put(uuid, data);
        return data;
    }

    public void unload(UUID uuid) {
        if (uuid == null) {
            return;
        }
        loginPreloads.invalidate(uuid);
        PlayerData data = cache.get(uuid);
        if (data != null) {
            data.commitSession();
            plugin.getDatabaseManager().savePlayer(data);
            cache.remove(uuid);
        }
    }

    public void saveAll() {
        for (PlayerData data : cache.values()) {
            data.commitSession();
            plugin.getDatabaseManager().savePlayer(data);
        }
    }

    public PlayerData get(UUID uuid) {
        return uuid == null ? null : cache.get(uuid);
    }

    public PlayerData get(Player player) {
        return get(player.getUniqueId());
    }

    public Collection<PlayerData> getAll() {
        return cache.values();
    }

    /**
     * Drops one player's cached copy without saving it. A restore has just rewritten their row, so
     * writing the in-memory totals back would undo it before they have even noticed.
     */
    public void discardWithoutSaving(UUID uuid) {
        if (uuid == null) {
            return;
        }
        loginPreloads.invalidate(uuid);
        cache.remove(uuid);
    }

    public void discardAllForServerWipe() {
        loginPreloads.clear();
        cache.clear();
    }

    public boolean isLoaded(UUID uuid) {
        return uuid != null && cache.containsKey(uuid);
    }

    /** Re-pins options removed with ENABLED: false after menus.yml was reloaded. */
    public void refreshRemovedSettingOptions() {
        for (PlayerData data : cache.values()) {
            PlayerSettingDefaults.applyDisabledOptions(plugin, data);
        }
    }

    /** Periodic dirty-save without committing sessions */
    public void autoSaveDirty() {
        if (plugin.getStatsWipeManager() != null && plugin.getStatsWipeManager().isWipeInProgress()) {
            return;
        }
        for (PlayerData data : cache.values()) {
            if (data.isDirty()) {
                plugin.getDatabaseManager().savePlayerAsync(data);
            }
        }
    }
}

package com.bx.ultimateDonutSmp.managers;

import com.bx.ultimateDonutSmp.UltimateDonutSmp;
import com.bx.ultimateDonutSmp.models.EconomyFailureReason;
import com.bx.ultimateDonutSmp.models.EconomyReason;
import com.bx.ultimateDonutSmp.models.EconomyTransactionResult;
import com.bx.ultimateDonutSmp.models.EconomyTransferResult;
import com.bx.ultimateDonutSmp.models.PlayerData;
import com.bx.ultimateDonutSmp.utils.PlayerSettingDefaults;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

public class EconomyManager {

    private static final double EPSILON = 0.0000001D;

    public record AccountReference(UUID uuid, String displayName, Player onlinePlayer) {
        public boolean isOnline() {
            return onlinePlayer != null;
        }
    }

    private record LoadedAccount(AccountReference reference, PlayerData data) {
    }

    private static final int ACCOUNT_LOCK_STRIPES = 64;

    private final UltimateDonutSmp plugin;
    // Balance changes arrive from region threads and async Vault callers, so every
    // read-modify-write of one account holds that account's lock from read to write.
    private final Object[] accountLocks = new Object[ACCOUNT_LOCK_STRIPES];

    public EconomyManager(UltimateDonutSmp plugin) {
        this.plugin = plugin;
        for (int i = 0; i < accountLocks.length; i++) {
            accountLocks[i] = new Object();
        }
    }

    private int lockStripe(UUID uuid) {
        return Math.floorMod(uuid.hashCode(), ACCOUNT_LOCK_STRIPES);
    }

    private Object accountLock(UUID uuid) {
        return accountLocks[lockStripe(uuid)];
    }

    public AccountReference resolveAccount(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String normalized = input.trim();
        Player onlinePlayer = Bukkit.getPlayerExact(normalized);
        if (onlinePlayer != null) {
            return new AccountReference(
                    onlinePlayer.getUniqueId(),
                    onlinePlayer.getName(),
                    onlinePlayer
            );
        }

        UUID storedUuid = plugin.getDatabaseManager().findPlayerUuidByUsername(normalized);
        if (storedUuid != null) {
            return resolveAccount(storedUuid);
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(normalized);
        if (offlinePlayer.isOnline() || offlinePlayer.hasPlayedBefore()) {
            return new AccountReference(
                    offlinePlayer.getUniqueId(),
                    resolveDisplayName(offlinePlayer.getUniqueId(), offlinePlayer.getName()),
                    offlinePlayer.getPlayer()
            );
        }

        return null;
    }

    public AccountReference resolveAccount(UUID uuid) {
        if (uuid == null) {
            return null;
        }

        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null) {
            return new AccountReference(uuid, onlinePlayer.getName(), onlinePlayer);
        }

        if (plugin.getPlayerDataManager().isLoaded(uuid) || plugin.getDatabaseManager().loadPlayer(uuid) != null) {
            return new AccountReference(uuid, resolveDisplayName(uuid, null), null);
        }

        return null;
    }

    public String getDisplayName(UUID uuid) {
        return resolveDisplayName(uuid, null);
    }

    public boolean hasAccount(OfflinePlayer player) {
        return player != null && hasAccount(player.getUniqueId());
    }

    public boolean hasAccount(UUID uuid) {
        return resolveAccount(uuid) != null;
    }

    public boolean createPlayerAccount(OfflinePlayer player) {
        if (player == null || player.getUniqueId() == null) {
            return false;
        }

        synchronized (accountLock(player.getUniqueId())) {
            LoadedAccount existing = loadAccount(player.getUniqueId(), player.getName(), false);
            if (existing != null) {
                return true;
            }

            return loadAccount(player.getUniqueId(), player.getName(), true) != null;
        }
    }

    public double getBalance(Player player) {
        return player == null ? 0D : getBalance(player.getUniqueId());
    }

    public double getBalance(OfflinePlayer player) {
        return player == null ? 0D : getBalance(player.getUniqueId());
    }

    public double getBalance(UUID uuid) {
        LoadedAccount loadedAccount = loadAccount(uuid, null, false);
        return loadedAccount == null ? 0D : roundCurrency(loadedAccount.data().getMoney());
    }

    public boolean has(Player player, double amount) {
        return player != null && has(player.getUniqueId(), amount);
    }

    public boolean has(OfflinePlayer player, double amount) {
        return player != null && has(player.getUniqueId(), amount);
    }

    public boolean has(UUID uuid, double amount) {
        if (!Double.isFinite(amount)) {
            return false;
        }
        if (amount <= 0D) {
            return true;
        }

        return getBalance(uuid) + EPSILON >= roundCurrency(amount);
    }

    public EconomyTransactionResult deposit(Player player, double amount, EconomyReason reason) {
        if (player == null) {
            return failure(EconomyFailureReason.PLAYER_NOT_FOUND, reason, null, null, amount, 0D, 0D);
        }
        return depositInternal(player.getUniqueId(), player.getName(), amount, reason, true);
    }

    public EconomyTransactionResult deposit(OfflinePlayer player, double amount, EconomyReason reason) {
        if (player == null) {
            return failure(EconomyFailureReason.PLAYER_NOT_FOUND, reason, null, null, amount, 0D, 0D);
        }
        return depositInternal(player.getUniqueId(), player.getName(), amount, reason, true);
    }

    public EconomyTransactionResult deposit(AccountReference account, double amount, EconomyReason reason) {
        if (account == null) {
            return failure(EconomyFailureReason.PLAYER_NOT_FOUND, reason, null, null, amount, 0D, 0D);
        }
        return depositInternal(account.uuid(), account.displayName(), amount, reason, false);
    }

    public EconomyTransactionResult withdraw(Player player, double amount, EconomyReason reason) {
        if (player == null) {
            return failure(EconomyFailureReason.PLAYER_NOT_FOUND, reason, null, null, amount, 0D, 0D);
        }
        return withdrawInternal(player.getUniqueId(), player.getName(), amount, reason);
    }

    public EconomyTransactionResult withdraw(OfflinePlayer player, double amount, EconomyReason reason) {
        if (player == null) {
            return failure(EconomyFailureReason.PLAYER_NOT_FOUND, reason, null, null, amount, 0D, 0D);
        }
        return withdrawInternal(player.getUniqueId(), player.getName(), amount, reason);
    }

    public EconomyTransactionResult withdraw(AccountReference account, double amount, EconomyReason reason) {
        if (account == null) {
            return failure(EconomyFailureReason.PLAYER_NOT_FOUND, reason, null, null, amount, 0D, 0D);
        }
        return withdrawInternal(account.uuid(), account.displayName(), amount, reason);
    }

    public EconomyTransactionResult setBalance(AccountReference account, double amount, EconomyReason reason) {
        if (account == null) {
            return failure(EconomyFailureReason.PLAYER_NOT_FOUND, reason, null, null, amount, 0D, 0D);
        }
        return setBalanceInternal(account.uuid(), account.displayName(), amount, reason);
    }

    public EconomyTransactionResult setBalance(Player player, double amount, EconomyReason reason) {
        if (player == null) {
            return failure(EconomyFailureReason.PLAYER_NOT_FOUND, reason, null, null, amount, 0D, 0D);
        }
        return setBalanceInternal(player.getUniqueId(), player.getName(), amount, reason);
    }

    public EconomyTransactionResult setBalance(OfflinePlayer player, double amount, EconomyReason reason) {
        if (player == null) {
            return failure(EconomyFailureReason.PLAYER_NOT_FOUND, reason, null, null, amount, 0D, 0D);
        }
        return setBalanceInternal(player.getUniqueId(), player.getName(), amount, reason);
    }

    public EconomyTransferResult transfer(Player sender, Player recipient, double amount, EconomyReason reason) {
        if (sender == null || recipient == null) {
            return transferFailure(EconomyFailureReason.PLAYER_NOT_FOUND, reason, sender, recipient, amount);
        }

        return transfer(
                sender.getUniqueId(),
                sender.getName(),
                recipient.getUniqueId(),
                recipient.getName(),
                amount,
                reason
        );
    }

    public EconomyTransferResult transfer(
            UUID senderUuid,
            String senderName,
            UUID recipientUuid,
            String recipientName,
            double amount,
            EconomyReason reason
    ) {
        if (!isValidPositiveAmount(amount)) {
            return transferFailure(
                    EconomyFailureReason.INVALID_AMOUNT,
                    reason,
                    senderUuid,
                    senderName,
                    recipientUuid,
                    recipientName,
                    amount
            );
        }

        if (senderUuid == null || recipientUuid == null) {
            return transferFailure(
                    EconomyFailureReason.PLAYER_NOT_FOUND,
                    reason,
                    senderUuid,
                    senderName,
                    recipientUuid,
                    recipientName,
                    amount
            );
        }

        if (senderUuid.equals(recipientUuid)) {
            return transferFailure(
                    EconomyFailureReason.SAME_ACCOUNT,
                    reason,
                    senderUuid,
                    senderName,
                    recipientUuid,
                    recipientName,
                    amount
            );
        }

        // Both locks, always taken in stripe order, so two opposite transfers cannot deadlock.
        int firstStripe = Math.min(lockStripe(senderUuid), lockStripe(recipientUuid));
        int secondStripe = Math.max(lockStripe(senderUuid), lockStripe(recipientUuid));
        synchronized (accountLocks[firstStripe]) {
            synchronized (accountLocks[secondStripe]) {
                return transferLocked(senderUuid, senderName, recipientUuid, recipientName, amount, reason);
            }
        }
    }

    private EconomyTransferResult transferLocked(
            UUID senderUuid,
            String senderName,
            UUID recipientUuid,
            String recipientName,
            double amount,
            EconomyReason reason
    ) {
        LoadedAccount sender = loadAccount(senderUuid, senderName, false);
        LoadedAccount recipient = loadAccount(recipientUuid, recipientName, false);
        if (sender == null || recipient == null) {
            return transferFailure(
                    EconomyFailureReason.NO_PLAYER_DATA,
                    reason,
                    senderUuid,
                    senderName,
                    recipientUuid,
                    recipientName,
                    amount
            );
        }

        double normalizedAmount = roundCurrency(amount);
        double senderBefore = roundCurrency(sender.data().getMoney());
        if (senderBefore + EPSILON < normalizedAmount) {
            return transferFailure(
                    EconomyFailureReason.INSUFFICIENT_FUNDS,
                    reason,
                    sender.reference().uuid(),
                    sender.reference().displayName(),
                    recipient.reference().uuid(),
                    recipient.reference().displayName(),
                    normalizedAmount
            );
        }

        double recipientBefore = roundCurrency(recipient.data().getMoney());
        if (!commitChange(sender, senderBefore, -normalizedAmount)) {
            return transferFailure(
                    EconomyFailureReason.INSUFFICIENT_FUNDS,
                    reason,
                    sender.reference().uuid(),
                    sender.reference().displayName(),
                    recipient.reference().uuid(),
                    recipient.reference().displayName(),
                    normalizedAmount
            );
        }
        if (!commitChange(recipient, recipientBefore, normalizedAmount)) {
            // The recipient's row could not be credited, so hand the money back.
            commitChange(sender, roundCurrency(sender.data().getMoney()), normalizedAmount);
            return transferFailure(
                    EconomyFailureReason.NO_PLAYER_DATA,
                    reason,
                    sender.reference().uuid(),
                    sender.reference().displayName(),
                    recipient.reference().uuid(),
                    recipient.reference().displayName(),
                    normalizedAmount
            );
        }

        String sName = senderName != null ? senderName : sender.reference().displayName();
        String rName = recipientName != null ? recipientName : recipient.reference().displayName();
        plugin.getPlayerLogsManager().log(
                senderUuid,
                sName,
                "Economy",
                "ECONOMY_TRANSFER",
                "Paid " + rName + " " + plugin.getCurrencyManager().formatMoney(normalizedAmount)
        );
        plugin.getPlayerLogsManager().log(
                recipientUuid,
                rName,
                "Economy",
                "ECONOMY_TRANSFER",
                "Received " + plugin.getCurrencyManager().formatMoney(normalizedAmount) + " from " + sName
        );

        return new EconomyTransferResult(
                true,
                EconomyFailureReason.NONE,
                reason,
                sender.reference().uuid(),
                sender.reference().displayName(),
                recipient.reference().uuid(),
                recipient.reference().displayName(),
                normalizedAmount,
                senderBefore,
                roundCurrency(sender.data().getMoney()),
                recipientBefore,
                roundCurrency(recipient.data().getMoney())
        );
    }

    private EconomyTransactionResult depositInternal(
            UUID uuid,
            String displayNameHint,
            double amount,
            EconomyReason reason,
            boolean createIfMissing
    ) {
        if (!isValidPositiveAmount(amount)) {
            return failure(EconomyFailureReason.INVALID_AMOUNT, reason, uuid, displayNameHint, amount, 0D, 0D);
        }

        if (uuid == null) {
            return failure(EconomyFailureReason.NO_PLAYER_DATA, reason, null, displayNameHint, amount, 0D, 0D);
        }
        synchronized (accountLock(uuid)) {
            return depositLocked(uuid, displayNameHint, amount, reason, createIfMissing);
        }
    }

    private EconomyTransactionResult depositLocked(
            UUID uuid,
            String displayNameHint,
            double amount,
            EconomyReason reason,
            boolean createIfMissing
    ) {
        LoadedAccount loadedAccount = loadAccount(uuid, displayNameHint, createIfMissing);
        if (loadedAccount == null) {
            return failure(EconomyFailureReason.NO_PLAYER_DATA, reason, uuid, displayNameHint, amount, 0D, 0D);
        }

        double normalizedAmount = roundCurrency(amount);
        double beforeBalance = roundCurrency(loadedAccount.data().getMoney());
        if (!commitChange(loadedAccount, beforeBalance, normalizedAmount)) {
            return failure(
                    EconomyFailureReason.NO_PLAYER_DATA,
                    reason,
                    uuid,
                    loadedAccount.reference().displayName(),
                    normalizedAmount,
                    beforeBalance,
                    beforeBalance
            );
        }

        if (reason == EconomyReason.ADMIN_ADD) {
            plugin.getPlayerLogsManager().log(
                    uuid,
                    loadedAccount.reference().displayName(),
                    "Economy",
                    "ECONOMY_ADD",
                    "Admin added " + plugin.getCurrencyManager().formatMoney(normalizedAmount) + " to balance (New Balance: " + plugin.getCurrencyManager().formatMoney(beforeBalance + normalizedAmount) + ")"
            );
        }

        return new EconomyTransactionResult(
                true,
                EconomyFailureReason.NONE,
                reason,
                loadedAccount.reference().uuid(),
                loadedAccount.reference().displayName(),
                normalizedAmount,
                beforeBalance,
                roundCurrency(loadedAccount.data().getMoney())
        );
    }

    private EconomyTransactionResult withdrawInternal(
            UUID uuid,
            String displayNameHint,
            double amount,
            EconomyReason reason
    ) {
        if (!isValidPositiveAmount(amount)) {
            return failure(EconomyFailureReason.INVALID_AMOUNT, reason, uuid, displayNameHint, amount, 0D, 0D);
        }

        if (uuid == null) {
            return failure(EconomyFailureReason.NO_PLAYER_DATA, reason, null, displayNameHint, amount, 0D, 0D);
        }
        synchronized (accountLock(uuid)) {
            return withdrawLocked(uuid, displayNameHint, amount, reason);
        }
    }

    private EconomyTransactionResult withdrawLocked(
            UUID uuid,
            String displayNameHint,
            double amount,
            EconomyReason reason
    ) {
        LoadedAccount loadedAccount = loadAccount(uuid, displayNameHint, false);
        if (loadedAccount == null) {
            return failure(EconomyFailureReason.NO_PLAYER_DATA, reason, uuid, displayNameHint, amount, 0D, 0D);
        }

        double normalizedAmount = roundCurrency(amount);
        double beforeBalance = roundCurrency(loadedAccount.data().getMoney());
        if (beforeBalance + EPSILON < normalizedAmount
                || !commitChange(loadedAccount, beforeBalance, -normalizedAmount)) {
            return failure(
                    EconomyFailureReason.INSUFFICIENT_FUNDS,
                    reason,
                    loadedAccount.reference().uuid(),
                    loadedAccount.reference().displayName(),
                    normalizedAmount,
                    beforeBalance,
                    beforeBalance
            );
        }

        if (reason == EconomyReason.ADMIN_REMOVE) {
            plugin.getPlayerLogsManager().log(
                    uuid,
                    loadedAccount.reference().displayName(),
                    "Economy",
                    "ECONOMY_REMOVE",
                    "Admin removed " + plugin.getCurrencyManager().formatMoney(normalizedAmount) + " from balance (New Balance: " + plugin.getCurrencyManager().formatMoney(beforeBalance - normalizedAmount) + ")"
            );
        }

        return new EconomyTransactionResult(
                true,
                EconomyFailureReason.NONE,
                reason,
                loadedAccount.reference().uuid(),
                loadedAccount.reference().displayName(),
                normalizedAmount,
                beforeBalance,
                roundCurrency(loadedAccount.data().getMoney())
        );
    }

    private EconomyTransactionResult setBalanceInternal(
            UUID uuid,
            String displayNameHint,
            double amount,
            EconomyReason reason
    ) {
        if (!isValidNonNegativeAmount(amount)) {
            return failure(EconomyFailureReason.INVALID_AMOUNT, reason, uuid, displayNameHint, amount, 0D, 0D);
        }

        if (uuid == null) {
            return failure(EconomyFailureReason.NO_PLAYER_DATA, reason, null, displayNameHint, amount, 0D, 0D);
        }
        synchronized (accountLock(uuid)) {
            return setBalanceLocked(uuid, displayNameHint, amount, reason);
        }
    }

    private EconomyTransactionResult setBalanceLocked(
            UUID uuid,
            String displayNameHint,
            double amount,
            EconomyReason reason
    ) {
        LoadedAccount loadedAccount = loadAccount(uuid, displayNameHint, false);
        if (loadedAccount == null) {
            return failure(EconomyFailureReason.NO_PLAYER_DATA, reason, uuid, displayNameHint, amount, 0D, 0D);
        }

        double normalizedAmount = roundCurrency(amount);
        double beforeBalance = roundCurrency(loadedAccount.data().getMoney());
        if (!loadedAccount.reference().isOnline()
                && !plugin.getDatabaseManager().setPlayerMoney(uuid, normalizedAmount)) {
            return failure(
                    EconomyFailureReason.NO_PLAYER_DATA,
                    reason,
                    uuid,
                    loadedAccount.reference().displayName(),
                    normalizedAmount,
                    beforeBalance,
                    beforeBalance
            );
        }
        loadedAccount.data().setMoney(normalizedAmount);
        invalidateMoneyLeaderboard();

        if (reason == EconomyReason.ADMIN_SET) {
            plugin.getPlayerLogsManager().log(
                    uuid,
                    loadedAccount.reference().displayName(),
                    "Economy",
                    "ECONOMY_SET",
                    "Admin set balance to " + plugin.getCurrencyManager().formatMoney(normalizedAmount) + " (Previous Balance: " + plugin.getCurrencyManager().formatMoney(beforeBalance) + ")"
            );
        }

        return new EconomyTransactionResult(
                true,
                EconomyFailureReason.NONE,
                reason,
                loadedAccount.reference().uuid(),
                loadedAccount.reference().displayName(),
                normalizedAmount,
                beforeBalance,
                roundCurrency(loadedAccount.data().getMoney())
        );
    }

    private LoadedAccount loadAccount(UUID uuid, String displayNameHint, boolean createIfMissing) {
        if (uuid == null) {
            return null;
        }

        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null) {
            PlayerData data = plugin.getPlayerDataManager().get(onlinePlayer);
            if (data == null) {
                data = plugin.getPlayerDataManager().loadOrCreate(onlinePlayer);
            }

            return new LoadedAccount(
                    new AccountReference(uuid, onlinePlayer.getName(), onlinePlayer),
                    data
            );
        }

        PlayerData offlineData = plugin.getDatabaseManager().loadPlayer(uuid);
        if (offlineData != null) {
            return new LoadedAccount(
                    new AccountReference(uuid, resolveDisplayName(uuid, displayNameHint), null),
                    offlineData
            );
        }

        if (!createIfMissing) {
            return null;
        }

        String username = resolveDisplayName(uuid, displayNameHint);
        PlayerData created = new PlayerData(uuid, username);
        PlayerSettingDefaults.applyDefaults(plugin, created);
        double startMoney = plugin.getConfigManager().getConfig()
                .getDouble("SETTINGS.MONEY-PER-DEFAULT", 1000.0D);
        created.setMoney(roundCurrency(startMoney));
        plugin.getDatabaseManager().savePlayer(created);

        return new LoadedAccount(
                new AccountReference(uuid, username, null),
                created
        );
    }

    /**
     * Applies a balance change while the caller holds the account's lock. An online account
     * changes in memory and is saved with the rest of the player's data. An offline account
     * changes only its stored balance, in one statement, so neither another change to the same
     * account nor a save of the player's other columns can overwrite it. Writing the whole
     * loaded row back later, as this used to, let the second of two quick deposits erase the
     * first. An offline withdrawal fails when the stored balance no longer covers it.
     */
    private boolean commitChange(LoadedAccount account, double beforeBalance, double delta) {
        if (!account.reference().isOnline()) {
            UUID uuid = account.reference().uuid();
            boolean stored = delta >= 0D
                    ? plugin.getDatabaseManager().addPlayerMoney(uuid, delta)
                    : plugin.getDatabaseManager().withdrawPlayerMoneyIfAvailable(uuid, -delta);
            if (!stored) {
                return false;
            }
        }
        account.data().setMoney(roundCurrency(beforeBalance + delta));
        invalidateMoneyLeaderboard();
        return true;
    }

    private void invalidateMoneyLeaderboard() {
        if (plugin.getLeaderboardManager() != null) {
            plugin.getLeaderboardManager().invalidate(LeaderboardManager.LeaderboardType.MONEY);
        }
    }

    private String resolveDisplayName(UUID uuid, String fallbackName) {
        if (uuid == null) {
            return fallbackName != null && !fallbackName.isBlank() ? fallbackName : "unknown";
        }

        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null) {
            return onlinePlayer.getName();
        }

        if (fallbackName != null && !fallbackName.isBlank()) {
            return fallbackName;
        }

        String storedName = plugin.getDatabaseManager().getLastKnownUsername(uuid);
        if (storedName != null && !storedName.isBlank()) {
            return storedName;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        if (offlinePlayer.getName() != null && !offlinePlayer.getName().isBlank()) {
            return offlinePlayer.getName();
        }

        return uuid.toString();
    }

    private boolean isValidPositiveAmount(double amount) {
        return Double.isFinite(amount) && amount > 0D;
    }

    private boolean isValidNonNegativeAmount(double amount) {
        return Double.isFinite(amount) && amount >= 0D;
    }

    private double roundCurrency(double amount) {
        return Math.round(amount * 100D) / 100D;
    }

    private EconomyTransactionResult failure(
            EconomyFailureReason failureReason,
            EconomyReason reason,
            UUID uuid,
            String displayName,
            double amount,
            double beforeBalance,
            double afterBalance
    ) {
        return new EconomyTransactionResult(
                false,
                failureReason,
                reason,
                uuid,
                displayName != null && !displayName.isBlank()
                        ? displayName
                        : (uuid != null ? resolveDisplayName(uuid, null) : "Unknown"),
                roundCurrency(Math.max(0D, Double.isFinite(amount) ? amount : 0D)),
                roundCurrency(Math.max(0D, beforeBalance)),
                roundCurrency(Math.max(0D, afterBalance))
        );
    }

    private EconomyTransferResult transferFailure(
            EconomyFailureReason failureReason,
            EconomyReason reason,
            Player sender,
            Player recipient,
            double amount
    ) {
        return transferFailure(
                failureReason,
                reason,
                sender != null ? sender.getUniqueId() : null,
                sender != null ? sender.getName() : null,
                recipient != null ? recipient.getUniqueId() : null,
                recipient != null ? recipient.getName() : null,
                amount
        );
    }

    private EconomyTransferResult transferFailure(
            EconomyFailureReason failureReason,
            EconomyReason reason,
            UUID senderUuid,
            String senderName,
            UUID recipientUuid,
            String recipientName,
            double amount
    ) {
        return new EconomyTransferResult(
                false,
                failureReason,
                reason,
                senderUuid,
                senderName != null && !senderName.isBlank()
                        ? senderName
                        : (senderUuid != null ? resolveDisplayName(senderUuid, null) : "Unknown"),
                recipientUuid,
                recipientName != null && !recipientName.isBlank()
                        ? recipientName
                        : (recipientUuid != null ? resolveDisplayName(recipientUuid, null) : "Unknown"),
                roundCurrency(Math.max(0D, Double.isFinite(amount) ? amount : 0D)),
                0D,
                0D,
                0D,
                0D
        );
    }
}

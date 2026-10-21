package com.bx.ultimateDonutSmp.utils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Holds data read for a player while their login is still being processed off the main thread,
 * so the join handler can pick it up instead of querying the database on the main thread.
 *
 * <p>A preload is registered with {@link #begin} before the database read starts and can be
 * claimed only while nothing has invalidated it. Every write to the same player's stored data
 * calls {@link #invalidate}, which drops the entry even while its read is still running, so a
 * read that raced a write is never handed out; the join then simply loads the data itself.
 * Entries nobody claims, because the login was refused later on, expire after the time to live
 * and are swept on the next {@link #begin}.</p>
 */
public final class LoginPreloadCache<T> {

    public static final long DEFAULT_TTL_MILLIS = 60_000L;

    public static final class Ticket<T> {
        private final long startedAtMillis;
        private volatile T value;
        private volatile boolean completed;

        private Ticket(long startedAtMillis) {
            this.startedAtMillis = startedAtMillis;
        }
    }

    /** A claimed preload. {@code value} may be null, meaning the read found nothing stored. */
    public record Preloaded<T>(T value) {
    }

    private final Map<UUID, Ticket<T>> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final LongSupplier clock;

    public LoginPreloadCache() {
        this(DEFAULT_TTL_MILLIS, System::currentTimeMillis);
    }

    public LoginPreloadCache(long ttlMillis, LongSupplier clock) {
        this.ttlMillis = Math.max(1L, ttlMillis);
        this.clock = clock;
    }

    /** Registers a preload for the player. Call it before reading anything from the database. */
    public Ticket<T> begin(UUID uuid) {
        long now = clock.getAsLong();
        entries.values().removeIf(ticket -> isExpired(ticket, now));
        Ticket<T> ticket = new Ticket<>(now);
        if (uuid != null) {
            entries.put(uuid, ticket);
        }
        return ticket;
    }

    /** Stores the result of the read. It is only claimable if the ticket was not invalidated meanwhile. */
    public void complete(Ticket<T> ticket, T value) {
        if (ticket == null) {
            return;
        }
        ticket.value = value;
        ticket.completed = true;
    }

    /** Abandons this ticket, leaving any newer preload for the same player alone. */
    public void cancel(UUID uuid, Ticket<T> ticket) {
        if (uuid != null && ticket != null) {
            entries.remove(uuid, ticket);
        }
    }

    /** Claims and removes the player's preload, or returns null when there is none to trust. */
    public Preloaded<T> take(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Ticket<T> ticket = entries.remove(uuid);
        if (ticket == null || !ticket.completed || isExpired(ticket, clock.getAsLong())) {
            return null;
        }
        return new Preloaded<>(ticket.value);
    }

    /** Drops the player's preload, including one whose read is still running. */
    public void invalidate(UUID uuid) {
        if (uuid != null) {
            entries.remove(uuid);
        }
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private boolean isExpired(Ticket<T> ticket, long now) {
        return now - ticket.startedAtMillis >= ttlMillis;
    }
}

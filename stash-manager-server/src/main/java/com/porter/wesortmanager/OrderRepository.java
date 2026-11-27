package com.porter.wesortmanager;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * PR3 (#142): durable order storage behind an interface.
 *
 * <p>Domain code ({@link OrderStore}) programs against this contract instead of
 * the SQLite implementation, so the JSON transport shape stays out of the
 * domain API and the repository can be substituted in tests. Implementations
 * must commit the book, releases, events and receipts in one transaction
 * (#140) and publish via {@link EventPublisher#afterCommit} only after the
 * commit succeeds.
 */
interface OrderRepository {
    void load(Consumer<OrderBookRepository.Snapshot> validate) throws IOException;

    long commit(
            String server, String stash, long expected, com.google.gson.JsonObject root, StockLedger.BoxRelease release)
            throws IOException;

    /**
     * Commits with staged audit entries written in the same transaction (#140):
     * a failed commit persists neither the book nor its audit trail.
     */
    default long commit(
            String server,
            String stash,
            long expected,
            com.google.gson.JsonObject root,
            StockLedger.BoxRelease release,
            java.util.List<AuditLog.Entry> audit)
            throws IOException {
        return commit(server, stash, expected, root, release);
    }

    /**
     * PR3 (#140): one transaction for the book, custody writes and audit.
     * Extra writes (custody/deposits/handoffs/jobs on the same connection) run
     * inside the commit transaction; any failure rolls back book, custody and
     * audit together. Follows the global lock order ledger → index → book →
     * store: callers must not hold a later lock when entering the commit.
     */
    @FunctionalInterface
    interface SqliteWrite {
        void apply(java.sql.Connection c) throws java.sql.SQLException, java.io.IOException;
    }

    default long commitExtended(
            String server,
            String stash,
            long expected,
            com.google.gson.JsonObject root,
            StockLedger.BoxRelease release,
            java.util.List<AuditLog.Entry> audit,
            java.util.List<SqliteWrite> extra)
            throws IOException {
        return commit(server, stash, expected, root, release, audit);
    }

    com.google.gson.JsonObject resumeReceipt(String server, String stash, String agent, String id) throws IOException;
}

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
            String server,
            String stash,
            long expected,
            com.google.gson.JsonObject root,
            StockLedger.BoxRelease release)
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

    com.google.gson.JsonObject resumeReceipt(String server, String stash, String agent, String id)
            throws IOException;
}

package com.porter.wesortmanager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Owns a single SQLite IMMEDIATE write transaction, not the underlying connection.
 *
 * <p>Closing without a successful commit rolls back even when the caller throws
 * an unchecked exception or error. A connection must not be reused concurrently
 * while this transaction is open.
 */
final class SqliteWriteTransaction implements AutoCloseable {
    private final Connection connection;
    private boolean finished;

    SqliteWriteTransaction(Connection connection) throws SQLException {
        this.connection = Objects.requireNonNull(connection, "connection");
        try (var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
        }
    }

    void commit() throws SQLException {
        if (finished) throw new IllegalStateException("SQLite transaction already completed");
        try (var statement = connection.createStatement()) {
            statement.execute("COMMIT");
            finished = true;
        }
    }

    @Override
    public void close() throws SQLException {
        if (finished) return;
        try (var statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } finally {
            finished = true;
        }
    }
}

package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteWriteTransactionTest {
    @TempDir
    java.nio.file.Path data;

    private StockLedger ledger() throws Exception {
        StockLedger ledger = new StockLedger(data);
        try (var connection = ledger.open();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS txn_test(id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
        }
        return ledger;
    }

    private static void insert(Connection connection, int id, String value) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO txn_test(id,value) VALUES(?,?)")) {
            insert.setInt(1, id);
            insert.setString(2, value);
            insert.executeUpdate();
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM txn_test")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    @Test
    void commitMakesAllWritesVisibleAfterConnectionCloses() throws Exception {
        StockLedger ledger = ledger();
        try (var connection = ledger.open();
                var transaction = new SqliteWriteTransaction(connection)) {
            insert(connection, 1, "one");
            insert(connection, 2, "two");
            transaction.commit();
            assertThrows(IllegalStateException.class, transaction::commit);
        }
        try (var reader = ledger.open()) {
            assertEquals(2, count(reader));
        }
    }

    @Test
    void ordinaryCloseRollsBackUncommittedChanges() throws Exception {
        StockLedger ledger = ledger();
        try (var connection = ledger.open();
                var transaction = new SqliteWriteTransaction(connection)) {
            insert(connection, 1, "uncommitted");
        }
        try (var reader = ledger.open()) {
            assertEquals(0, count(reader));
        }
    }

    @Test
    void runtimeFailureRollsBackAndDoesNotPreventNextWriter() throws Exception {
        StockLedger ledger = ledger();
        assertThrows(IllegalStateException.class, () -> {
            try (var connection = ledger.open();
                    var transaction = new SqliteWriteTransaction(connection)) {
                insert(connection, 1, "rolled back");
                throw new IllegalStateException("injected fault");
            }
        });
        try (var connection = ledger.open();
                var transaction = new SqliteWriteTransaction(connection)) {
            insert(connection, 1, "committed");
            transaction.commit();
        }
        try (var reader = ledger.open()) {
            assertEquals(1, count(reader));
        }
    }

    @Test
    void sqliteConstraintFailureRollsBackEarlierStatements() throws Exception {
        StockLedger ledger = ledger();
        assertThrows(SQLException.class, () -> {
            try (var connection = ledger.open();
                    var transaction = new SqliteWriteTransaction(connection)) {
                insert(connection, 1, "first");
                insert(connection, 1, "duplicate");
                transaction.commit();
            }
        });
        try (var reader = ledger.open()) {
            assertEquals(0, count(reader));
        }
    }
}

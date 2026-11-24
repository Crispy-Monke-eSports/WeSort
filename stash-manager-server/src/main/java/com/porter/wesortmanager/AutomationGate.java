package com.porter.wesortmanager;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Conservative server-wide exclusion until W-024 can allocate individual physical resources. */
final class AutomationGate {
    static final long TTL = 15_000;
    /**
     * A lease left NEEDS_RECONCILIATION by a restart is only ever inspected via a posted journal
     * (PhysicalOperations.releaseInspectedLease). When no journal exists at all, nothing can ever
     * inspect it, so the lease is dropped once it is this far past its deadline instead of denying
     * every future acquisition forever. Leases with a live journal are never touched here.
     */
    static final long ORPHAN_GRACE_MS = 60_000;

    record Lease(String agent, String token, long deadline, String state) {}

    private final StockLedger stock;
    private final LongSupplier clock;

    AutomationGate(StockLedger stock) {
        this(stock, System::currentTimeMillis);
    }

    AutomationGate(StockLedger stock, LongSupplier clock) {
        this.stock = stock;
        this.clock = clock;
    }

    synchronized Lease current(String server) throws IOException {
        try (var c = stock.open();
                var tx = new SqliteWriteTransaction(c)) {
            var lease = current(c, server, clock.getAsLong());
            tx.commit();
            return lease;
        } catch (SQLException e) {
            throw new IOException("Cannot verify automation exclusion", e);
        }
    }

    private Lease current(Connection c, String server, long now) throws SQLException {
        var lease = read(c, server);
        if (lease != null && lease.state.equals("ACTIVE") && now >= lease.deadline) {
            markUncertain(c, server);
            return read(c, server);
        }
        if (lease != null
                && lease.state.equals("NEEDS_RECONCILIATION")
                && now - lease.deadline >= ORPHAN_GRACE_MS
                && !unsettled(c, server)) {
            // Orphaned by a restart before any journal was posted: releaseInspectedLease can never
            // run for it, so drop it rather than denying every future acquisition forever.
            // A lease with a live journal row is left alone for inspection.
            try (var p = c.prepareStatement("DELETE FROM automation_leases WHERE server=?")) {
                p.setString(1, server);
                p.executeUpdate();
            }
            stock.invalidateServer(c, server);
            return null;
        }
        return lease;
    }

    /** Caller holds IndexStore's monitor and has checked every open order on this server. */
    synchronized Lease acquire(String server, String agent, String token) throws IOException {
        try (var c = stock.open();
                var tx = new SqliteWriteTransaction(c)) {
            long now = clock.getAsLong();
            var before = current(c, server, now);
            if (before != null
                    && (!before.agent.equals(agent) || !before.token.equals(token) || !before.state.equals("ACTIVE"))) {
                tx.commit(); // Persist any newly discovered expiry.
                return null;
            }
            // Renewal is not a new acquisition. An old token may not revive a
            // lease already released by recovery or a completed operation.
            if (before == null && token != null) {
                tx.commit();
                return null;
            }
            var lease = new Lease(
                    agent, before == null ? UUID.randomUUID().toString() : token, Math.addExact(now, TTL), "ACTIVE");
            try (var p = c.prepareStatement(
                    "INSERT INTO automation_leases VALUES(?,?,?,?,?) ON CONFLICT(server) DO UPDATE SET deadline=excluded.deadline")) {
                p.setString(1, server);
                p.setString(2, agent);
                p.setString(3, lease.token);
                p.setLong(4, lease.deadline);
                p.setString(5, lease.state);
                p.executeUpdate();
            }
            tx.commit();
            return lease;
        } catch (SQLException e) {
            throw new IOException("Cannot fence sorting", e);
        }
    }

    synchronized boolean finish(String server, String agent, String token, boolean verified) throws IOException {
        try (var c = stock.open();
                var tx = new SqliteWriteTransaction(c)) {
            var before = current(c, server, clock.getAsLong());
            if (before == null
                    || !before.agent.equals(agent)
                    || !before.token.equals(token)
                    || !before.state.equals("ACTIVE")) {
                tx.commit();
                return false;
            }
            boolean unsettled = verified && unsettled(c, server);
            if (verified && !unsettled) {
                stock.invalidateServer(c, server);
                try (var p = c.prepareStatement("DELETE FROM automation_leases WHERE server=?")) {
                    p.setString(1, server);
                    p.executeUpdate();
                }
            } else {
                markUncertain(c, server);
            }
            tx.commit();
            return !unsettled;
        } catch (SQLException e) {
            throw new IOException("Cannot finish sorting exclusion", e);
        }
    }

    private static boolean unsettled(Connection c, String server) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT 1 FROM physical_operations WHERE server=? AND state NOT IN ('COMPLETED','FAILED') LIMIT 1")) {
            p.setString(1, server);
            try (var rows = p.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void markUncertain(Connection c, String server) throws SQLException {
        try (var p = c.prepareStatement("UPDATE automation_leases SET state='NEEDS_RECONCILIATION' WHERE server=?")) {
            p.setString(1, server);
            p.executeUpdate();
        }
        stock.invalidateServer(c, server);
    }

    private static Lease read(Connection c, String server) throws SQLException {
        try (var p = c.prepareStatement("SELECT agent,token,deadline,state FROM automation_leases WHERE server=?")) {
            p.setString(1, server);
            try (var r = p.executeQuery()) {
                return r.next() ? new Lease(r.getString(1), r.getString(2), r.getLong(3), r.getString(4)) : null;
            }
        }
    }
}

package com.porter.wesortmanager;

import com.google.gson.*;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Conservative server-wide exclusion until W-024 can allocate individual physical resources. */
final class AutomationGate {
    static final long TTL = 15_000;
    record Lease(String agent, String token, long deadline, String state) {}
    private final StockLedger stock;
    private final LongSupplier clock;
    AutomationGate(StockLedger stock) { this(stock, System::currentTimeMillis); }
    AutomationGate(StockLedger stock, LongSupplier clock) { this.stock = stock; this.clock = clock; }

    synchronized Lease current(String server) throws IOException {
        try (var c = stock.open()) {
            Lease lease = read(c, server);
            if (lease != null && lease.state.equals("ACTIVE") && clock.getAsLong() >= lease.deadline) {
                c.setAutoCommit(false);
                try {
                    markUncertain(c, server);
                    c.commit();
                } catch (Exception e) { c.rollback(); throw e; }
                lease = read(c, server);
            }
            return lease;
        } catch (SQLException e) { throw new IOException("Cannot verify automation exclusion", e); }
    }

    /** Caller holds IndexStore's monitor and has checked every open order on this server. */
    synchronized Lease acquire(String server, String agent, String token) throws IOException {
        Lease before = current(server);
        if (before != null && (!before.agent.equals(agent) || !before.token.equals(token) || !before.state.equals("ACTIVE"))) return null;
        Lease lease = new Lease(agent, before == null ? UUID.randomUUID().toString() : token, clock.getAsLong() + TTL, "ACTIVE");
        try (var c = stock.open(); var p = c.prepareStatement("INSERT INTO automation_leases VALUES(?,?,?,?,?) ON CONFLICT(server) DO UPDATE SET deadline=excluded.deadline")) {
            p.setString(1, server); p.setString(2, agent); p.setString(3, lease.token); p.setLong(4, lease.deadline); p.setString(5, lease.state); p.executeUpdate();
            return lease;
        } catch (SQLException e) { throw new IOException("Cannot fence sorting", e); }
    }

    synchronized boolean finish(String server, String agent, String token, boolean verified) throws IOException {
        Lease before = current(server);
        if (before == null || !before.agent.equals(agent) || !before.token.equals(token)) return false;
        // An expired lease is an unknown physical outcome. It requires explicit reconciliation, not a late release.
        if (!before.state.equals("ACTIVE")) return false;
        try (var c = stock.open()) {
            c.setAutoCommit(false);
            try {
                if (verified) {
                    stock.invalidateServer(c, server);
                    try (var p = c.prepareStatement("DELETE FROM automation_leases WHERE server=?")) { p.setString(1, server); p.executeUpdate(); }
                } else markUncertain(c, server);
                c.commit(); return true;
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IOException("Cannot finish sorting exclusion", e); }
    }

    private void markUncertain(Connection c, String server) throws SQLException {
        try (var p = c.prepareStatement("UPDATE automation_leases SET state='NEEDS_RECONCILIATION' WHERE server=?")) { p.setString(1, server); p.executeUpdate(); }
        stock.invalidateServer(c, server);
    }
    private static Lease read(Connection c, String server) throws SQLException {
        try (var p = c.prepareStatement("SELECT agent,token,deadline,state FROM automation_leases WHERE server=?")) {
            p.setString(1, server);
            try (var r = p.executeQuery()) { return r.next() ? new Lease(r.getString(1), r.getString(2), r.getLong(3), r.getString(4)) : null; }
        }
    }
}

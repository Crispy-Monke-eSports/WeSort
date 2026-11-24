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
    /**
     * Silence after which a different agent may take over a fenced lease. The fence means the
     * previous holder may have moved items; the wait plus the requirement that no journal row
     * exists for the server keeps the handover from racing a reconnecting agent or live evidence.
     */
    static final long TAKEOVER_SILENCE_MS = 2 * TTL;

    static final String REASON_UNKNOWN_TOKEN = "unknown_token";
    static final String REASON_HELD_BY_OTHER = "held_by_other_agent";
    static final String REASON_TOKEN_MISMATCH = "token_mismatch";
    static final String REASON_RECONCILIATION_REQUIRED = "reconciliation_required";
    static final String REASON_NO_LEASE = "no_lease";

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
            delete(c, server);
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
                    && before.state.equals("NEEDS_RECONCILIATION")
                    && !before.agent.equals(agent)
                    && now - before.deadline >= TAKEOVER_SILENCE_MS
                    && !unsettled(c, server)) {
                // A fenced lease whose holder has been silent for 2xTTL with no journal behind
                // it can never be inspected or renewed: hand a fresh lease to the waiting agent
                // instead of denying every future acquisition forever. A live journal still
                // blocks the handover, so a reconnecting agent keeps its inspection path.
                System.err.println("Automation lease on '" + server + "' taken over after " + TAKEOVER_SILENCE_MS
                        + "ms of holder silence");
                delete(c, server);
                before = null;
            }
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
            if (verified
                    && before != null
                    && before.state.equals("NEEDS_RECONCILIATION")
                    && before.agent.equals(agent)
                    && before.token.equals(token)
                    && !unsettled(c, server)) {
                // The holder verified that nothing is in flight and no journal can ever
                // inspect this fence: a matching release frees the orphan immediately
                // instead of waiting out the grace period. A live journal still blocks,
                // so recorded evidence always goes through inspection first.
                delete(c, server);
                tx.commit();
                return true;
            }
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
                delete(c, server);
            } else {
                markUncertain(c, server);
            }
            tx.commit();
            return !unsettled;
        } catch (SQLException e) {
            throw new IOException("Cannot finish sorting exclusion", e);
        }
    }

    /**
     * Owner escape hatch for an orphaned fence: frees a NEEDS_RECONCILIATION lease that no
     * journal can ever inspect. Returns false when there is nothing to free. An ACTIVE lease
     * is never freed by hand, and a lease with live journal rows stays fenced for inspection.
     */
    synchronized boolean freeLease(String server) throws IOException {
        try (var c = stock.open();
                var tx = new SqliteWriteTransaction(c)) {
            var lease = current(c, server, clock.getAsLong());
            if (lease == null || !lease.state.equals("NEEDS_RECONCILIATION") || unsettled(c, server)) {
                tx.commit();
                return false;
            }
            System.err.println("Automation lease on '" + server + "' freed by the owner");
            delete(c, server);
            tx.commit();
            return true;
        } catch (SQLException e) {
            throw new IOException("Cannot free sorting exclusion", e);
        }
    }

    /**
     * Machine-readable reason for a denied acquisition, for automation 409 bodies: "unknown_token"
     * (no row — drop the token and acquire again), "held_by_other_agent", "token_mismatch"
     * (same agent, wrong token — drop it and acquire again), or "reconciliation_required"
     * (fenced — post the journal for inspection). "ok" means the denial came from elsewhere
     * (open orders or held reservations, checked by the caller).
     */
    synchronized String denyReason(String server, String agent, String token) throws IOException {
        try (var c = stock.open();
                var tx = new SqliteWriteTransaction(c)) {
            var lease = current(c, server, clock.getAsLong());
            tx.commit();
            if (lease == null) return token == null ? REASON_NO_LEASE : REASON_UNKNOWN_TOKEN;
            if (!lease.agent.equals(agent)) return REASON_HELD_BY_OTHER;
            if (!lease.token.equals(token)) return REASON_TOKEN_MISMATCH;
            if (!lease.state.equals("ACTIVE")) return REASON_RECONCILIATION_REQUIRED;
            return "ok";
        } catch (SQLException e) {
            throw new IOException("Cannot verify automation exclusion", e);
        }
    }

    /**
     * Machine-readable reason for a denied release/uncertain report: "no_lease", "held_by_other_agent",
     * "token_mismatch", or "reconciliation_required" (fenced with live journals — post the journal).
     */
    synchronized String releaseDenyReason(String server, String agent, String token) throws IOException {
        try (var c = stock.open();
                var tx = new SqliteWriteTransaction(c)) {
            var lease = current(c, server, clock.getAsLong());
            tx.commit();
            if (lease == null) return REASON_NO_LEASE;
            if (!lease.agent.equals(agent)) return REASON_HELD_BY_OTHER;
            if (!lease.token.equals(token)) return REASON_TOKEN_MISMATCH;
            if (lease.state.equals("ACTIVE")) return "ok";
            // A matching orphan release with no journals behind it would have succeeded above,
            // so a fenced lease here always has live evidence waiting for inspection.
            return REASON_RECONCILIATION_REQUIRED;
        } catch (SQLException e) {
            throw new IOException("Cannot verify automation exclusion", e);
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

    private static void delete(Connection c, String server) throws SQLException {
        try (var p = c.prepareStatement("DELETE FROM automation_leases WHERE server=?")) {
            p.setString(1, server);
            p.executeUpdate();
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

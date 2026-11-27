package com.porter.wesortmanager;
    
    import com.google.gson.JsonArray;
    import com.google.gson.JsonObject;
    import com.google.gson.JsonParser;
    import java.io.IOException;
    import java.sql.Connection;
    import java.sql.SQLException;
    import java.util.ArrayList;
    
    /**
     * PR3 Step 5: the physical-operation journal readers (staging proofs and
     * settlement fences), moved verbatim out of StockLedger (no behavior change,
     * Refs #142). Runs under the StockLedger lock.
     */
    final class OperationJournal {
        private final StockLedger ledger;
    
        OperationJournal(StockLedger ledger) {
            this.ledger = ledger;
        }
    
        JsonObject stagingManifest(String server, String stash, String order, String agent, int packages)
            throws IOException {
        var proofs = new ArrayList<JsonObject>(packages);
        try (var c = ledger.open();
                var p = c.prepareStatement("""
                SELECT payload FROM physical_operations
                WHERE state='COMPLETED' AND server=? AND stash=? AND agent=?
                  AND json_extract(payload,'$.context.orderId')=?
                  AND json_extract(payload,'$.context.execution')='STAGE_PACKAGE'
                ORDER BY json_extract(payload,'$.createdAt'), id LIMIT ?
                """)) {
            p.setString(1, server);
            p.setString(2, stash);
            p.setString(3, agent);
            p.setString(4, order);
            p.setInt(5, packages + 1);
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    var proof = PackageManifest.transfer(
                            JsonParser.parseString(rows.getString(1)).getAsJsonObject());
                    if (proof == null) return null;
                    proofs.add(proof);
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read verified warehouse staging", failure);
        }
        return PackageManifest.staged(order, proofs, packages);
    }
    /**
     * Abandoned journal evidence cannot fence an order forever: an operation unseen for a full
     * day counts as settled for release purposes (its NEEDS_RECONCILIATION fence, if any, still
     * drives order recovery through {@link StockLedger#reconciliationRequiredPhysicalOrder}).
     */
    static final long OPERATION_FENCE_MS = 24 * 60 * 60 * 1000;

    boolean unsettledPhysicalOrder(String order) throws IOException {
        try (var c = ledger.open();
                var p = c.prepareStatement("""
                SELECT 1 FROM physical_operations
                WHERE state NOT IN ('COMPLETED','FAILED') AND json_extract(payload,'$.context.orderId')=?
                  AND last_seen > ? LIMIT 1
                """)) {
            p.setString(1, order);
            p.setLong(2, ledger.clock.getAsLong() - OPERATION_FENCE_MS);
            try (var rows = p.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot verify referenced physical work", failure);
        }
    }
    /** Only a Manager-fenced physical operation is grounds for failing an active order.
     * A live PLANNED, IN_PROGRESS or VERIFYING operation is expected during packing. */
    boolean reconciliationRequiredPhysicalOrder(String order) throws IOException {
        try (var c = ledger.open();
                var p = c.prepareStatement("""
                SELECT 1 FROM physical_operations
                WHERE state='NEEDS_RECONCILIATION'
                  AND json_extract(payload,'$.context.orderId')=? LIMIT 1
                """)) {
            p.setString(1, order);
            try (var rows = p.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot verify physical reconciliation fence", failure);
        }
    }
    JsonArray orderOperationHistory(String order, String server, String stash) throws IOException {
        var history = new JsonArray();
        try (var connection = ledger.open();
                var query = connection.prepareStatement("""
                SELECT id,agent,state,revision,last_seen,json_extract(payload,'$.context.kind'),
                    json_extract(payload,'$.context.jobId'),json_extract(payload,'$.context.execution')
                FROM physical_operations WHERE server=? AND stash=? AND json_extract(payload,'$.context.orderId')=?
                ORDER BY last_seen DESC,id LIMIT 128
                """)) {
            query.setString(1, server);
            query.setString(2, stash);
            query.setString(3, order);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var event = new JsonObject();
                    event.addProperty("id", rows.getString(1));
                    event.addProperty("actor", rows.getString(2));
                    event.addProperty("state", rows.getString(3));
                    event.addProperty("revision", rows.getLong(4));
                    event.addProperty("at", rows.getLong(5));
                    event.addProperty("kind", rows.getString(6));
                    event.addProperty("jobId", rows.getString(7));
                    event.addProperty("execution", rows.getString(8));
                    history.add(event);
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read order operation history", failure);
        }
        return history;
    }
}

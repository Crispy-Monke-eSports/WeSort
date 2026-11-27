package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.sql.SQLException;

/**
 * PR3 Step 5: return intake (slot decisions), moved verbatim out of
 * StockLedger (no behavior change, Refs #142). Runs under the StockLedger lock.
 */
final class ReturnIntakeService {
    private final StockLedger ledger;

    ReturnIntakeService(StockLedger ledger) {
        this.ledger = ledger;
    }

    StockLedger.ReturnDecision recordReturn(
            String source, JsonObject slot, String snapshot, String actor, boolean markUuid, long expectedDecision)
            throws IOException {
        String path = slot.get("path").getAsString();
        long revision = slot.get("revision").getAsLong();
        var result = new JsonObject();
        result.addProperty("source", source);
        result.addProperty("path", path);
        result.addProperty("slotRevision", revision);
        result.addProperty("stackFingerprint", slot.get("fingerprint").getAsString());
        result.addProperty("snapshotFingerprint", snapshot);
        result.addProperty("markUuid", markUuid);
        result.addProperty("state", markUuid ? "UUID_REQUESTED" : "VERIFIED_KEEP_NAME");
        result.addProperty("actor", actor);
        result.addProperty("recordedAt", ledger.clock.getAsLong());
        try (var c = ledger.open()) {
            long currentDecision = 0;
            try (var read = c.prepareStatement(
                    "SELECT payload FROM return_intake WHERE source=? AND path=? AND slot_revision=?")) {
                read.setString(1, source);
                read.setString(2, path);
                read.setLong(3, revision);
                try (var rows = read.executeQuery()) {
                    if (rows.next()) {
                        var saved = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                        if (saved.get("markUuid").getAsBoolean() == markUuid)
                            return new StockLedger.ReturnDecision(saved, false);
                        currentDecision = saved.has("decisionRevision")
                                ? saved.get("decisionRevision").getAsLong()
                                : 0;
                    }
                }
            }
            if (expectedDecision != currentDecision) return null;
            result.addProperty("decisionRevision", currentDecision + 1);
            try (var write = c.prepareStatement("""
                    INSERT INTO return_intake VALUES(?,?,?,?)
                    ON CONFLICT(source,path,slot_revision) DO UPDATE SET payload=excluded.payload
                    """)) {
                write.setString(1, source);
                write.setString(2, path);
                write.setLong(3, revision);
                write.setString(4, result.toString());
                write.executeUpdate();
            }
            return new StockLedger.ReturnDecision(result, true);
        } catch (SQLException failure) {
            throw new IOException("Cannot save return intake", failure);
        }
    }

    JsonArray returnDecisions(String source) throws IOException {
        var decisions = new JsonArray();
        try (var c = ledger.open();
                var p = c.prepareStatement("""
                SELECT r.payload FROM return_intake r JOIN physical_slots s
                  ON r.source=s.source AND r.path=s.path AND r.slot_revision=s.revision
                WHERE r.source=? AND s.present=1 ORDER BY s.path
                """)) {
            p.setString(1, source);
            try (var rows = p.executeQuery()) {
                while (rows.next()) decisions.add(JsonParser.parseString(rows.getString(1)));
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read return intake", failure);
        }
        return decisions;
    }
}

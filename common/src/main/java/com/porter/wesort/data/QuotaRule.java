package com.porter.wesort.data;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * W-059: one optional withdrawal rule. Quotas are disabled by default and never create private inventories;
 * they only limit how much of the shared guild stock one member may withdraw. A rule matches a subject
 * (a member id, a role, or {@code *} for everyone) and a line reference ({@code item:<key>},
 * {@code shulker:<key>}, or {@code *} for everything), with a max count per rolling time window.
 */
public record QuotaRule(String subject, String key, long max, long windowSeconds) {
    public static final String EVERYONE = "*";

    public QuotaRule {
        if (subject == null || subject.isBlank() || subject.length() > 64)
            throw new IllegalArgumentException("A quota subject is required");
        if (key == null || key.isBlank() || key.length() > 512)
            throw new IllegalArgumentException("A quota key is required");
        if (max < 1) throw new IllegalArgumentException("A quota max must be at least 1");
        if (windowSeconds < 1) throw new IllegalArgumentException("A quota window must be positive");
    }

    /** Whether this rule's key covers the given line reference. */
    public boolean covers(String ref) {
        return key.equals(EVERYONE) || key.equals(ref);
    }

    /**
     * The most specific rule for a member's line: a member-specific rule beats a role rule, which beats the
     * {@code *} rule. When several rules share a specificity the earliest declared one wins.
     */
    public static QuotaRule matching(List<QuotaRule> rules, String member, List<String> roles, String ref) {
        QuotaRule best = null;
        int bestRank = -1;
        for (QuotaRule rule : rules) {
            if (!rule.covers(ref)) continue;
            int rank;
            if (rule.subject().equals(member)) rank = 2;
            else if (roles != null && roles.contains(rule.subject())) rank = 1;
            else if (rule.subject().equals(EVERYONE)) rank = 0;
            else continue;
            if (rank > bestRank) {
                best = rule;
                bestRank = rank;
            }
        }
        return best;
    }

    public static List<QuotaRule> read(JsonObject policy) {
        var rules = new ArrayList<QuotaRule>();
        for (var entry : policy.getAsJsonArray("rules")) {
            var rule = entry.getAsJsonObject();
            rules.add(new QuotaRule(
                    rule.get("subject").getAsString(),
                    rule.get("key").getAsString(),
                    rule.get("max").getAsLong(),
                    rule.get("windowSeconds").getAsLong()));
        }
        return rules;
    }

    public JsonObject json() {
        var out = new JsonObject();
        out.addProperty("subject", subject);
        out.addProperty("key", key);
        out.addProperty("max", max);
        out.addProperty("windowSeconds", windowSeconds);
        return out;
    }
}

package com.porter.wesort.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** An HTTP status alone is never a physical order acknowledgement. */
public record OrderReportReceipt(String state, String status, boolean resumeComplete) {
    public static OrderReportReceipt parse(String body, String expectedId) {
        try {
            JsonObject order = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("order");
            if (!expectedId.equals(order.get("id").getAsString())) return null;
            String state = order.get("state").getAsString();
            String status = order.get("status").getAsString();
            boolean complete = order.has("resumeComplete") && order.get("resumeComplete").getAsBoolean();
            var receipt = new OrderReportReceipt(state, status, complete);
            if (complete && !receipt.staged() && !receipt.acknowledges("failed") && !receipt.acknowledges("cancelled"))
                return null;
            return receipt;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public boolean recovering() {
        return "RECOVERING".equals(state) && "in_progress".equals(status);
    }

    public boolean staged() {
        return ("PICKUP_READY".equals(state) && "packed".equals(status))
                || ("DELIVERED".equals(state) && "delivered".equals(status));
    }

    public boolean acknowledges(String reported) {
        return switch (reported) {
            case "delivered" -> staged();
            case "failed" -> "FAILED".equals(state) && "failed".equals(status);
            case "cancelled" -> "CANCELLED".equals(state) && "cancelled".equals(status);
            default -> false;
        };
    }
}

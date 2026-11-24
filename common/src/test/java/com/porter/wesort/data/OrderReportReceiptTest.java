package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OrderReportReceiptTest {
    private static String reply(String id, String state, String status, boolean complete) {
        return "{\"order\":{\"id\":\"" + id + "\",\"state\":\"" + state + "\",\"status\":\"" + status
                + "\",\"resumeComplete\":" + complete + "}}";
    }

    @Test
    void onlyExactTerminalStateAcknowledgesPhysicalReport() {
        assertNull(OrderReportReceipt.parse("{}", "o-one"));
        assertNull(OrderReportReceipt.parse(reply("o-other", "PICKUP_READY", "packed", false), "o-one"));
        var recovery = OrderReportReceipt.parse(reply("o-one", "RECOVERING", "in_progress", false), "o-one");
        assertTrue(recovery.recovering());
        assertFalse(recovery.acknowledges("delivered"));
        var packed = OrderReportReceipt.parse(reply("o-one", "PICKUP_READY", "packed", false), "o-one");
        assertTrue(packed.acknowledges("delivered"));
        assertFalse(packed.acknowledges("cancelled"));
        var failed = OrderReportReceipt.parse(reply("o-one", "FAILED", "failed", false), "o-one");
        assertTrue(failed.acknowledges("failed"));
        assertFalse(failed.acknowledges("delivered"));
    }

    @Test
    void resumeCompleteRequiresARealTerminalDisposition() {
        assertNull(OrderReportReceipt.parse(reply("o-one", "RECOVERING", "in_progress", true), "o-one"));
        assertNull(OrderReportReceipt.parse(reply("o-one", "CANCELLED", "packed", true), "o-one"));
        assertTrue(OrderReportReceipt.parse(reply("o-one", "CANCELLED", "cancelled", true), "o-one")
                .resumeComplete());
    }
}

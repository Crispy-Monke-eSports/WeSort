package com.porter.wesort.inventory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ToolWearReadbackTest {
    private InventoryReadback gate() {
        var gate = new InventoryReadback();
        gate.predict(new InventoryReadback.Expected(0,List.of("packed-box","tool-damage-0"),"empty",Map.of(1,Set.of("tool-damage-1"))));
        gate.fence(0); return gate;
    }
    @Test void permitsOnlyTheDerivedSingleToolWearOutcome() {
        for (String tool : List.of("tool-damage-0","tool-damage-1")) {
            var gate=gate(); gate.observe(new InventoryReadback.Expected(0,List.of("packed-box",tool),"empty"));gate.barrier();assertTrue(gate.ready());
        }
        for (String tool : List.of("tool-damage-2","different-tool","empty")) {
            var gate=gate();gate.observe(new InventoryReadback.Expected(0,List.of("packed-box",tool),"empty"));gate.barrier();assertTrue(gate.failed());
        }
    }
    @Test void otherSlotsAndCursorMustStillMatchExactly() {
        var gate=gate();gate.observe(new InventoryReadback.Expected(0,List.of("different-box","tool-damage-1"),"empty"));gate.barrier();assertTrue(gate.failed());
        gate=gate();gate.observe(new InventoryReadback.Expected(0,List.of("packed-box","tool-damage-1"),"unexpected"));gate.barrier();assertTrue(gate.failed());
    }
    @Test void restoredIntentKeepsItsBoundedOutcomeDuringExplicitReadOnlyReconciliation() {
        var original=gate();var restored=new InventoryReadback();restored.restorePending(original.expected());
        var expected=restored.reconcile(4,0);assertEquals(Map.of(1,Set.of("tool-damage-1")),expected.alternatives());
        restored.observe(new InventoryReadback.Expected(4,List.of("packed-box","tool-damage-1"),"empty"));restored.barrier();assertTrue(restored.ready());
    }
}

package com.porter.wesortmanager;

import com.porter.wesort.data.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class KitCatalogTest {
    @TempDir Path data;
    private KitTemplate template(int count) {
        var item=new PhysicalSlots.Item("minecraft:diamond","minecraft:diamond",64,"{\"type\":10,\"value\":{}}",PhysicalSlots.Kind.ITEM,0,List.of(),"");
        return new KitTemplate("Raid alpha",new PhysicalSlots.Snapshot(27,List.of(new PhysicalSlots.Slot(26,count,item))));
    }
    @Test void sharedDefinitionIsDurableAndIdempotentWithoutInventingStock()throws Exception{
        var ledger=new StockLedger(data);var catalog=new KitCatalog(ledger);
        assertTrue(catalog.publish("s","owner-1",template(32)).created());
        assertFalse(catalog.publish("s","owner-1",template(32)).created());
        assertEquals(0,ledger.revision());assertTrue(ledger.sources().isEmpty());
        var restarted=new KitCatalog(new StockLedger(data));var result=restarted.list("s");assertEquals(1,result.get("revision").getAsLong());
        assertEquals(template(32).json(),result.getAsJsonArray("templates").get(0).getAsJsonObject().getAsJsonObject("template"));
        assertTrue(restarted.list("another-server").getAsJsonArray("templates").isEmpty());
    }
    @Test void editsRequireCurrentVersionAndPreserveHistoricalExecutionData()throws Exception {
        var catalog=new KitCatalog(new StockLedger(data));
        long first=catalog.publish("s","owner",template(32)).revision();
        long second=catalog.save("s","owner",template(31),first).revision();
        assertTrue(second>first);
        assertThrows(KitCatalog.Conflict.class,()->catalog.save("s","owner",template(30),first));
        assertThrows(KitCatalog.Conflict.class,()->catalog.save("s","owner",template(30),0));
        var restarted=new KitCatalog(new StockLedger(data));
        assertEquals(template(32).json(),restarted.version("s","Raid alpha",first,template(32).fingerprint()).json());
        assertThrows(KitCatalog.Conflict.class,()->restarted.version("s","Raid alpha",first,template(31).fingerprint()));
        assertTrue(restarted.delete("s","Raid alpha",template(31).fingerprint(),second));
        assertEquals(template(31).json(),restarted.version("s","Raid alpha",second,template(31).fingerprint()).json());
        assertTrue(restarted.list("s").getAsJsonArray("templates").isEmpty());
    }
    @Test void concurrentEditorsCannotSilentlyOverwriteDifferentVariants()throws Exception{
        var catalog=new KitCatalog(new StockLedger(data));var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            List<Future<Boolean>> results=new ArrayList<>();
            for(int count:List.of(31,32))results.add(pool.submit(()->{start.await();try{catalog.publish("s","owner",template(count));return true;}catch(KitCatalog.Conflict conflict){return false;}}));
            start.countDown();int winners=0;for(var result:results)if(result.get())winners++;assertEquals(1,winners);
        }
        assertEquals(1,catalog.list("s").getAsJsonArray("templates").size());
        assertEquals(1,catalog.list("s").get("revision").getAsLong());
    }
}

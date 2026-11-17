package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.data.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class KitJobsTest {
    @TempDir Path data;
    private final PhysicalSlots.Item diamond=new PhysicalSlots.Item("minecraft:diamond","minecraft:diamond",64,"{}",PhysicalSlots.Kind.ITEM,0,List.of(),"");
    private KitTemplate template(int count){return new KitTemplate("Raid",new PhysicalSlots.Snapshot(27,List.of(new PhysicalSlots.Slot(26,count,diamond))));}
    private PhysicalSlots.Snapshot frame(int count,int slot){
        return new PhysicalSlots.Snapshot(27,count==0?List.of():List.of(new PhysicalSlots.Slot(0,1,new PhysicalSlots.Item("minecraft:shulker_box","minecraft:shulker_box",1,"{}",PhysicalSlots.Kind.SHULKER,27,List.of(new PhysicalSlots.Slot(slot,count,diamond)),""))));
    }
    private String observe(StockLedger ledger,PhysicalSlots.Snapshot snapshot)throws Exception {
        var root=new JsonObject();root.addProperty("version",1);root.addProperty("server","s");root.addProperty("stash","a");var amounts=new JsonArray();
        snapshot.totals().forEach((key,count)->{var row=new JsonObject();row.addProperty("key",key);row.addProperty("id",key);row.addProperty("count",count);amounts.add(row);});root.add("items",amounts);
        root=StockFixtures.physical(root,ledger.epoch(),ledger.revision(),ledger.revision()+1,0);var source=root.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        var writer=new PhysicalSlots.Writer();source.add("slots",writer.slots(snapshot.slots()));source.addProperty("fingerprint",snapshot.fingerprint());root.addProperty("physicalVersion",2);root.add("slotVariants",writer.variants());
        ledger.accept(root);return source.get("id").getAsString();
    }
    private JsonObject request(String destination,long revision){
        var request=new JsonObject();request.addProperty("server","s");request.addProperty("stash","a");request.addProperty("destination",destination);request.addProperty("name","Raid");request.addProperty("revision",revision);request.addProperty("fingerprint",template(32).fingerprint());
        request.add("workspace",JsonParser.parseString("{\"dimension\":\"minecraft:overworld\",\"x\":2,\"y\":64,\"z\":0}"));return request;
    }
    @Test void exactlyOneOfFiveAgentsReceivesThePinnedDefinitionAndNoTokenAppearsInWebList()throws Exception {
        var ledger=new StockLedger(data);var catalog=new KitCatalog(ledger);long revision=catalog.publish("s","owner",template(32)).revision();var jobs=new KitJobs(ledger,catalog);
        String source=observe(ledger,frame(0,26));jobs.create("owner",request(source,revision));
        try(var pool=Executors.newFixedThreadPool(5)){
            var tasks=new ArrayList<Callable<JsonObject>>();for(int i=0;i<5;i++){String agent=UUID.randomUUID().toString();tasks.add(()->jobs.claim("s","a",agent));}
            var claimed=new ArrayList<JsonObject>();for(var result:pool.invokeAll(tasks)){var job=result.get();if(job!=null)claimed.add(job);}assertEquals(1,claimed.size());
            assertEquals(template(32).json(),claimed.getFirst().getAsJsonObject("template"));assertEquals(revision,claimed.getFirst().get("revision").getAsLong());
        }
        var publicJob=jobs.list("s").get(0).getAsJsonObject();assertFalse(publicJob.has("token"));assertFalse(publicJob.has("template"));
    }
    @Test void onlyFreshExactStorageAfterPackedCanCompleteAndNeverMeansDelivered()throws Exception {
        var ledger=new StockLedger(data);var catalog=new KitCatalog(ledger);long revision=catalog.publish("s","owner",template(32)).revision();var jobs=new KitJobs(ledger,catalog);
        String source=observe(ledger,frame(0,26));jobs.create("owner",request(source,revision));String agent=UUID.randomUUID().toString();var job=jobs.claim("s","a",agent);String id=job.get("id").getAsString(),token=job.get("token").getAsString();
        assertThrows(IllegalArgumentException.class,()->jobs.advance(id,agent,token,"complete"));
        long stockRevision=ledger.revision();var supply=JsonParser.parseString("{\"built\":0,\"possible\":1,\"estimateExact\":true,\"shortage\":\"\"}").getAsJsonObject();
        assertEquals("VERIFYING",jobs.advance(id,agent,token,"packed","",supply).get("state").getAsString());
        assertEquals(stockRevision,ledger.revision());assertEquals(1,jobs.list("s").get(0).getAsJsonObject().getAsJsonObject("agentEstimate").get("possible").getAsInt());
        assertEquals("VERIFYING",jobs.advance(id,agent,token,"complete").get("state").getAsString());
        observe(ledger,frame(32,25));assertEquals("VERIFYING",jobs.advance(id,agent,token,"complete").get("state").getAsString());
        observe(ledger,frame(31,26));assertEquals("VERIFYING",jobs.advance(id,agent,token,"complete").get("state").getAsString());
        observe(ledger,frame(32,26));assertEquals("COMPLETED",jobs.advance(id,agent,token,"complete").get("state").getAsString());
        assertThrows(KitCatalog.Conflict.class,()->jobs.advance(id,UUID.randomUUID().toString(),token,"renew"));
    }
    @Test void editingAssignedVersionFencesExecutionAndRetainsOriginalFingerprintAcrossRestart()throws Exception {
        var ledger=new StockLedger(data);var catalog=new KitCatalog(ledger);long revision=catalog.publish("s","owner",template(32)).revision();var jobs=new KitJobs(ledger,catalog);
        jobs.create("owner",request(observe(ledger,frame(0,26)),revision));String agent=UUID.randomUUID().toString();var job=jobs.claim("s","a",agent);
        catalog.save("s","owner",template(31),revision);
        assertEquals("NEEDS_RECONCILIATION",jobs.advance(job.get("id").getAsString(),agent,job.get("token").getAsString(),"renew").get("state").getAsString());
        var restarted=new KitJobs(new StockLedger(data),catalog);var persisted=restarted.list("s").get(0).getAsJsonObject();
        assertEquals(template(32).fingerprint(),persisted.get("fingerprint").getAsString());assertEquals("NEEDS_RECONCILIATION",persisted.get("state").getAsString());
        assertNull(restarted.claim("s","a",UUID.randomUUID().toString()));
    }
    @Test void offlineExpiryAndManagerRestartNeverReassignOrRenewUncertainWork()throws Exception {
        var ledger=new StockLedger(data);var catalog=new KitCatalog(ledger);var clock=new AtomicLong(System.currentTimeMillis());long revision=catalog.publish("s","owner",template(32)).revision();var jobs=new KitJobs(ledger,catalog,clock::get);
        jobs.create("owner",request(observe(ledger,frame(0,26)),revision));String agent=UUID.randomUUID().toString();var job=jobs.claim("s","a",agent);clock.addAndGet(KitJobs.TTL);
        assertEquals("NEEDS_RECONCILIATION",jobs.advance(job.get("id").getAsString(),agent,job.get("token").getAsString(),"renew").get("state").getAsString());assertNull(jobs.claim("s","a",UUID.randomUUID().toString()));
        try(var c=ledger.open();var statement=c.createStatement()){statement.executeUpdate("UPDATE kit_jobs SET state='IN_PROGRESS'");}
        var restarted=new KitJobs(new StockLedger(data),catalog,clock::get);assertEquals("NEEDS_RECONCILIATION",restarted.list("s").get(0).getAsJsonObject().get("state").getAsString());
    }
}

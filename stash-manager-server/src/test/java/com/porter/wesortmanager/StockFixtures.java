package com.porter.wesortmanager;
import com.google.gson.*;
import com.porter.wesort.data.ContainerIdentity;
import java.util.*;
/** Synthetic protocol witnesses for auth/order unit tests; not evidence of physical Minecraft operations. */
final class StockFixtures {
    static JsonObject physical(JsonObject index, String epoch, long base, long sequence, int x) {
        JsonObject copy = index.deepCopy();
        String server = copy.get("server").getAsString();
        var identity = new ContainerIdentity(server,"minecraft:overworld",List.of(new ContainerIdentity.Position(x,64,0)));
        JsonObject source = new JsonObject();source.addProperty("id",identity.id());source.addProperty("dimension","minecraft:overworld");
        JsonArray parts=new JsonArray(),pos=new JsonArray();pos.add(x);pos.add(64);pos.add(0);parts.add(pos);source.add("parts",parts);
        source.addProperty("kind","minecraft:barrel");source.addProperty("size",27);source.addProperty("state","FRESH");
        source.addProperty("session","00000000-0000-0000-0000-000000000001");source.addProperty("sequence",sequence);source.addProperty("epoch",epoch);source.addProperty("baseRevision",base);
        source.addProperty("observedAt",System.currentTimeMillis());source.addProperty("fingerprint",ContainerIdentity.hash(copy.get("items").toString()));
        source.add("items",copy.getAsJsonArray("items"));source.add("shulkers",copy.has("shulkers")?copy.get("shulkers"):new JsonArray());
        JsonArray sources=new JsonArray();sources.add(source);copy.add("physicalSources",sources);copy.addProperty("physicalVersion",1);return copy;
    }
    static void put(IndexStore store,JsonObject index)throws Exception{
        var ledger=store.stock();long revision=ledger.revision();
        store.putIndex(physical(index,ledger.epoch(),revision,revision+1,index.get("stash").getAsString().hashCode()));
    }
}

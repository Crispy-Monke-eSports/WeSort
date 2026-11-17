package com.porter.wesort.data;

import java.util.*;

/** Buildability from exact variant counts. Partial bases consume only their deficits, never another kit's material. */
public final class KitAvailability {
    private KitAvailability() {}
    public record Result<K>(int buildable, boolean exact, Map<K,Integer> missing) {}
    public static <K> Result<K> assess(Map<K,Integer> required, Map<K,Integer> available, List<Map<K,Integer>> partials, int emptyBoxes, int newHomeSlots) {
        if(required.isEmpty()||required.values().stream().anyMatch(count->count<1)||available.values().stream().anyMatch(count->count<0)||emptyBoxes<0||newHomeSlots<0)
            throw new IllegalArgumentException("Invalid kit supply");
        List<Map<K,Integer>> costs=new ArrayList<>();
        for(var have:partials){
            if(have.entrySet().stream().anyMatch(e->e.getValue()<0||e.getValue()>required.getOrDefault(e.getKey(),0)))throw new IllegalArgumentException("Invalid partial kit");
            Map<K,Integer> cost=new HashMap<>();required.forEach((key,count)->cost.put(key,count-have.getOrDefault(key,0)));costs.add(Map.copyOf(cost));
        }
        int empties=Math.min(emptyBoxes,newHomeSlots);
        boolean exact=costs.size()<=12;
        int count;
        if(exact)count=search(required,costs,0,new HashMap<>(available),empties,0);
        else{
            costs.sort(Comparator.comparingInt(cost->cost.values().stream().mapToInt(Integer::intValue).sum()));
            var left=new HashMap<>(available);int completed=0;
            for(var cost:costs)if(covers(left,cost)){subtract(left,cost);completed++;}
            count=completed+whole(required,left,empties);
        }
        Map<K,Integer> missing=new HashMap<>();int least=Integer.MAX_VALUE;
        List<Map<K,Integer>> next=new ArrayList<>(costs);next.add(required);
        for(var cost:next){
            Map<K,Integer> deficit=new HashMap<>();int total=0;
            for(var entry:cost.entrySet()){int n=Math.max(0,entry.getValue()-available.getOrDefault(entry.getKey(),0));if(n>0){deficit.put(entry.getKey(),n);total+=n;}}
            if(total<least){least=total;missing=deficit;}
        }
        return new Result<>(count,exact,Map.copyOf(missing));
    }
    private static <K> int search(Map<K,Integer> required,List<Map<K,Integer>> costs,int at,Map<K,Integer> left,int empty,int built){
        if(at==costs.size())return built+whole(required,left,empty);
        int best=search(required,costs,at+1,left,empty,built);var cost=costs.get(at);
        if(covers(left,cost)){var after=new HashMap<>(left);subtract(after,cost);best=Math.max(best,search(required,costs,at+1,after,empty,built+1));}
        return best;
    }
    private static <K> int whole(Map<K,Integer> required,Map<K,Integer> available,int cap){int n=cap;for(var e:required.entrySet())n=Math.min(n,available.getOrDefault(e.getKey(),0)/e.getValue());return n;}
    private static <K> boolean covers(Map<K,Integer> available,Map<K,Integer> cost){for(var e:cost.entrySet())if(available.getOrDefault(e.getKey(),0)<e.getValue())return false;return true;}
    private static <K> void subtract(Map<K,Integer> left,Map<K,Integer> cost){cost.forEach((key,n)->left.put(key,left.getOrDefault(key,0)-n));}
}

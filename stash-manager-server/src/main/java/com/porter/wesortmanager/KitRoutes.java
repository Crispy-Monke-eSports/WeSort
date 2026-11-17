package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.data.KitTemplate;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;

final class KitRoutes {
    private final IndexStore index; private final AuditLog audit; private KitCatalog catalog; private KitJobs jobs;
    KitRoutes(IndexStore index,AuditLog audit){this.index=index;this.audit=audit;}
    private synchronized KitCatalog catalog() throws IOException {if(catalog==null){catalog=new KitCatalog(index.stock());jobs=new KitJobs(index.stock(),catalog);}return catalog;}
    void register(Router router){
        router.exact(Router.GET,"/api/v1/kits/jobs",Router.Guard.SESSION,Router.Perm.OWNER,this::jobList);
        router.exact(Router.POST,"/api/v1/kits/jobs",Router.Guard.BROWSER_WRITE,Router.Perm.OWNER,this::jobCreate);
        router.exact(Router.GET,"/api/v1/kits",Router.Guard.SESSION,Router.Perm.VIEW,this::get);
        router.exact(Router.POST,"/api/v1/kits",Router.Guard.BROWSER_WRITE,Router.Perm.OWNER,this::ownerPublish);
        router.exact(Router.GET,"/api/v1/kits/editor",Router.Guard.SESSION,Router.Perm.OWNER,this::editor);
        router.exact(Router.POST,"/api/v1/kits/agent",Router.Guard.ADDON_KEY,this::agent);
    }
    private void jobList(HttpExchange ex)throws IOException {
        if(Redact.forced(Router.user(ex))){Http.error(ex,404,"not found");return;}
        catalog();var out=new JsonObject();out.add("jobs",jobs.list(Http.query(ex).getOrDefault("server","")));Http.json(ex,200,out);
    }
    private void jobCreate(HttpExchange ex)throws IOException {
        var raw=AddonRoutes.readJson(ex);if(raw==null)return;
        try{catalog();var job=jobs.create(Router.user(ex).id(),raw.getAsJsonObject());audit.add(Router.user(ex),"kit.job.create",job.get("id").getAsString(),AuthRoutes.address(ex));Http.json(ex,200,job);}
        catch(KitCatalog.Conflict conflict){Http.error(ex,409,conflict.getMessage());}
        catch(RuntimeException invalid){Http.error(ex,400,invalid.getMessage());}
    }
    private void get(HttpExchange ex)throws IOException{
        if(Redact.forced(Router.user(ex))){Http.error(ex,404,"not found");return;}
        try{Http.json(ex,200,catalog().list(Http.query(ex).getOrDefault("server","")));}
        catch(IllegalArgumentException invalid){Http.error(ex,400,"A Minecraft server is required");}
    }
    private void editor(HttpExchange ex)throws IOException {
        if(Redact.forced(Router.user(ex))){Http.error(ex,404,"not found");return;}
        try{Http.json(ex,200,catalog().editor(Http.query(ex).getOrDefault("server","")));}
        catch(IllegalArgumentException invalid){Http.error(ex,400,"A Minecraft server is required");}
    }
    private void ownerPublish(HttpExchange ex)throws IOException{handle(ex,false);}
    private void agent(HttpExchange ex)throws IOException{handle(ex,true);}
    private void handle(HttpExchange ex,boolean agent)throws IOException{
        var raw=AddonRoutes.readJson(ex);if(raw==null)return;
        if(!raw.isJsonObject()){Http.error(ex,400,"Expected kit catalog request");return;}
        var body=raw.getAsJsonObject();String server=AddonRoutes.string(body,"server");
        String action=AddonRoutes.string(body,"action");
        if(action==null||action.isBlank()){if(agent){Http.error(ex,400,"Agent kit action required");return;}action="publish";}
        try{
            if(agent&&List.of("claim","renew","packed","complete","uncertain").contains(action)){
                catalog();String agentId=AddonRoutes.string(body,"agent");JsonObject job;
                if("claim".equals(action))job=jobs.claim(server,AddonRoutes.string(body,"stash"),agentId);
                else job=jobs.advance(AddonRoutes.string(body,"id"),agentId,AddonRoutes.string(body,"token"),action,AddonRoutes.string(body,"message"),body.has("supply")?body.getAsJsonObject("supply"):null);
                var out=new JsonObject();out.add("job",job==null?JsonNull.INSTANCE:job);
                if(job!=null&&!"renew".equals(action))audit.add("agent:"+agentId,"Signed WeSort agent","kit.job."+action,job.get("id").getAsString(),AuthRoutes.address(ex));
                Http.json(ex,200,out);return;
            }
            if(agent && !"list".equals(action)){Http.error(ex,403,"Kit administration is only available in Manager");return;}
            if("publish".equals(action)){
                var template=body.has("layout")?catalog().compile(server,body):KitTemplate.read(body.getAsJsonObject("template"));
                String actor=Router.user(ex).id();
                long expected=body.has("expectedRevision")?body.get("expectedRevision").getAsBigDecimal().longValueExact():0;
                var result=catalog().save(server,actor,template,expected);
                if(result.created()){
                    audit.add(Router.user(ex),"kit.publish",template.fingerprint()+" revision="+result.revision(),AuthRoutes.address(ex));
                }
            }else if("delete".equals(action)&&!agent){
                String fingerprint=AddonRoutes.string(body,"fingerprint");
                catalog().delete(server,AddonRoutes.string(body,"name"),fingerprint,body.get("expectedRevision").getAsBigDecimal().longValueExact());
                audit.add(Router.user(ex),"kit.delete",fingerprint,AuthRoutes.address(ex));
            }else if(!"list".equals(action)){Http.error(ex,400,"Unknown kit catalog action");return;}
            if(agent){Http.error(ex,403,"Claim an assigned execution job to receive its exact definition");return;}
            Http.json(ex,200,catalog().list(server));
        }catch(KitCatalog.Conflict conflict){Http.error(ex,409,conflict.getMessage());}
        catch(RuntimeException invalid){Http.error(ex,400,"Invalid exact Minecraft 26.3 WeSort template");}
    }
}

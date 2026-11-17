package com.porter.wesortmanager;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Path;

final class GuildRoutes {
    private final Path file;
    private final AuditLog audit;
    private GuildProfile current;

    GuildRoutes(Path volume, AuditLog audit) {
        this.file = volume.resolve("guild.json");
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/guild", Router.Guard.SESSION, Router.Perm.ANY, this::get);
        router.exact(Router.POST, "/api/v1/guild", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::change);
    }

    synchronized GuildProfile profile() throws IOException {
        if (current == null) current = GuildProfile.open(file);
        return current;
    }

    private void get(HttpExchange ex) throws IOException {
        Http.json(ex, 200, profile().json());
    }

    private void change(HttpExchange ex) throws IOException {
        var request = AddonRoutes.readJson(ex);
        if (request == null) return;
        if (!request.isJsonObject()) { Http.error(ex, 400, "Expected guild profile"); return; }
        try {
            var body = request.getAsJsonObject();
            if (!body.has("expectedRevision")) throw new IllegalArgumentException();
            long observed = body.get("expectedRevision").getAsBigDecimal().longValueExact();
            GuildProfile updated;
            synchronized (this) {
                var before = profile();
                if (before.revision() != observed) { Http.error(ex, 409, "Reload the updated guild profile"); return; }
                updated = before.updated(body);
                updated.save(file);
                current = updated;
            }
            audit.add(Router.user(ex), "guild.profile.change", updated.id().toString(), AuthRoutes.address(ex));
            Http.json(ex, 200, updated.json());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, "Invalid guild name, description or revision");
        }
    }
}

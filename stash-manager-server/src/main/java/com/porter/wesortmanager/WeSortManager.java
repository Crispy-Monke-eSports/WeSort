package com.porter.wesortmanager;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Entry point: java -jar wesort-manager.jar [--data <dir>] */
public final class WeSortManager {
    private WeSortManager() {}

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        // Drop connections that take too long to send a request or to read a response (slow clients).
        // Read once by the JDK's HttpServer, so they must be set before it is created. -D on the command line wins.
        setDefault("sun.net.httpserver.maxReqTime", "30");
        setDefault("sun.net.httpserver.maxRspTime", "60");

        String command = args.length > 0 && !args[0].startsWith("-") ? args[0] : "serve";
        if (!List.of(
                        "serve",
                        "bootstrap",
                        "healthcheck",
                        "agent-key",
                        "agent-issue",
                        "agent-revoke",
                        "agent-require-scoped")
                .contains(command)) usage("unknown command");
        int firstOption = args.length > 0 && args[0].equals(command) ? 1 : 0;
        String request = switch (command) {
            case "bootstrap" -> "BOOTSTRAP";
            case "agent-key" -> "AGENT_KEY";
            case "agent-require-scoped" -> "AGENT_REQUIRE_SCOPED";
            case "agent-revoke" -> {
                if (args.length < 2) usage("agent-revoke needs an agent key id");
                firstOption = 2;
                yield "AGENT_REVOKE " + args[1];
            }
            case "agent-issue" -> {
                // Key slots carry no bindings: an optional human label is all there is.
                String label = "bot";
                if (args.length > 1 && !args[1].startsWith("-")) {
                    label = args[1];
                    firstOption = 2;
                }
                if (!AgentCredentials.validLabel(label)) usage("agent-issue label is invalid: " + label);
                var row = new com.google.gson.JsonObject();
                row.addProperty("label", label);
                yield "AGENT_ISSUE "
                        + java.util.Base64.getEncoder()
                                .encodeToString(row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            default -> "HEALTH";
        };
        Path dataDir = Path.of("wesort-manager-data");
        for (int i = firstOption; i < args.length; i++) {
            switch (args[i]) {
                case "--data" -> {
                    if (i + 1 >= args.length) usage("--data needs a directory");
                    dataDir = Path.of(args[++i]);
                }
                case "--help", "-h" -> usage(null);
                default -> usage("unknown argument: " + args[i]);
            }
        }
        if (!command.equals("serve")) {
            String reply = LocalControl.request(dataDir, request);
            System.out.println(reply); // Only this explicitly requested administrator channel returns the token.
            return;
        }
        serve(com.porter.wesort.data.DataDirectory.open(dataDir, "manager"));
    }

    private static void serve(Path dataDir) throws IOException {
        ManagerLock lock = ManagerLock.acquire(dataDir);
        boolean started = false;
        try {
            AtomicFiles.ownerOnlyDir(dataDir);
            int swept = AtomicFiles.sweepTemp(dataDir);
            if (swept > 0) System.out.println("Removed " + swept + " leftover temp file(s).");

            Config config;
            try {
                config = Config.load(dataDir);
                AtomicFiles.ownerOnlyDir(dataDir);
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                System.exit(1);
                return;
            }
            // The service must not serve HTTP or use Minecraft assets without express approval.
            requireEula(config);
            try {
                VanillaAssets.client26(dataDir.resolve("icons"), config.eulaAccepted);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Official Minecraft 26.3 client download interrupted", e);
            }

            IndexStore store = new IndexStore(dataDir);
            store.load();
            OrderStore orders = new OrderStore(dataDir, store);
            orders.load();
            GameIcons game = new GameIcons(dataDir.resolve("icons"), true, config.eulaAccepted);
            // Tab icons the page shows are allowed even when no index has them.
            java.util.function.Function<String, IndexStore.IconInfo> known = id -> {
                IndexStore.IconInfo info = store.iconInfo(id);
                if (info == null && UiIcons.NAMES.containsKey(id))
                    info = new IndexStore.IconInfo(UiIcons.NAMES.get(id), store.anyMcVersion());
                return info;
            };
            IconService icons = new IconService(dataDir, known, store::hasMcVersion, game, false);
            System.out.println(
                    "  Minecraft 26.3 icons: verified official client in the private Manager cache (eula=true).");
            LinkState link = new LinkState(config.keyState().generation());
            List<String> urls = urls(config);

            SessionStore sessions = new SessionStore(dataDir);
            sessions.load();
            if (config.dashboardPassword() == null && sessions.size() > 0) {
                // The password line was deleted (a reset): old logins must not survive it, sub-users' included.
                sessions.removeAllExcept(null);
            }
            Users users = new Users(dataDir);
            try {
                users.load();
            } catch (IOException e) {
                System.err.println("Can't read the website users: " + e.getMessage());
                System.exit(1);
                return;
            }
            AuditLog audit = new AuditLog(dataDir);
            audit.load();
            AuthRoutes.trustProxies(config.trustedProxies);
            if (!config.trustedProxies.isEmpty())
                System.out.println("  Trusted proxies (client address from X-Forwarded-For): "
                        + String.join(", ", config.trustedProxies));
            Auth.dummyHash(); // so the first login for an unknown name isn't slower than the rest
            BotHub hub = new BotHub();
            RuntimeHealth health = new RuntimeHealth(false);
            Routes built = routes(
                    config, store, orders, icons, link, urls, sessions, users, audit, hub, new LoginLimiter(), health);
            Router router = built.router();
            AuthRoutes authRoutes = built.auth();
            if (!users.list().isEmpty())
                System.out.println(
                        "  Website users: the owner and " + users.list().size() + " sub-user(s).");

            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(config.bind), config.port), 64);
            } catch (BindException e) {
                System.err.println("Can't listen on " + config.bind + ":" + config.port + " (" + e.getMessage() + ").");
                System.err.println("Another program (or another copy of this server) is probably using the port.");
                System.err.println("Stop it, or change port= in " + dataDir.resolve("config.properties") + ".");
                System.exit(1);
                return;
            }
            // Bot long-polls are bounded independently of short browser requests.
            ExecutorService pool = Executors.newFixedThreadPool(32, daemonThreads());
            server.setExecutor(pool);
            server.createContext("/", router);
            LocalControl control;
            try {
                control = new LocalControl(
                        dataDir, authRoutes, health, config, config.port, built.agentCredentials(), audit);
            } catch (IOException e) {
                server.stop(0);
                icons.close();
                pool.shutdownNow();
                throw e;
            }
            server.start();

            // Only once the port is ours: clean up the old icon cache, then queue every missing icon.
            int migrated = icons.migrateCache();
            if (migrated > 0)
                System.out.println("Icon cache: removed " + migrated + " stale file(s); they will be fetched again.");
            icons.prefetch(store.allIconIds());

            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                System.out.println("Stopping WeSort Manager...");
                                health.ready(false);
                                try {
                                    control.close();
                                } catch (IOException e) {
                                    System.err.println("Could not close local control socket");
                                }
                                server.stop(1);
                                icons.close();
                                pool.shutdown();
                                try {
                                    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
                                } catch (InterruptedException ignored) {
                                }
                                try {
                                    lock.close();
                                } catch (IOException e) {
                                    System.err.println("Could not release installation lock");
                                }
                            },
                            "wesort-manager-shutdown"));
            health.ready(true);
            started = true;

            System.out.println("WeSort Manager server running.");
            System.out.println("  Data directory: " + dataDir);
            System.out.println("  Loaded " + store.size() + " stash index(es).");
            for (String url : urls) System.out.println("  Open: " + url);
            System.out.println(
                    "  Link agents through the authenticated Owner portal; persistent API credentials are never logged.");
            if (config.allowRemote)
                System.out.println("  WARNING: allowRemote=true, non-LAN addresses are not blocked.");
            if (config.allowBearer) {
                System.out.println(
                        "  WARNING: allowBearer=true: the API key is sent in clear text by old bots (Bearer). Update every bot, then set allowBearer=false.");
            } else {
                System.out.println(
                        "  Bots must sign their requests (allowBearer=false). Older addons/plugins get 401 until updated; allowBearer=true in config.properties lets them in meanwhile.");
            }
            if (config.dashboardPassword() == null) {
                System.out.println(
                        "  Owner setup required: run the local bootstrap command through docker compose exec.");
            }
        } finally {
            if (!started) lock.close();
        }
    }

    static void requireEula(Config config) throws IOException {
        if (!config.eulaAccepted) {
            throw new IOException("WeSort Manager refuses to start: read https://www.minecraft.net/en-us/eula "
                    + "and set eula=true in config.properties or WESORT_EULA=true.");
        }
    }

    record Routes(Router router, AuthRoutes auth, DeliveryContents delivery, AgentCredentials agentCredentials) {}

    /** Every route, in order (the static files last). Shared with the tests, so they check the real registrations. */
    static Routes routes(
            Config config,
            IndexStore store,
            OrderStore orders,
            IconService icons,
            LinkState link,
            List<String> urls,
            SessionStore sessions,
            Users users,
            AuditLog audit,
            BotHub hub,
            LoginLimiter limiter)
            throws IOException {
        return routes(
                config,
                store,
                orders,
                icons,
                link,
                urls,
                sessions,
                users,
                audit,
                hub,
                limiter,
                new RuntimeHealth(true));
    }

    static Routes routes(
            Config config,
            IndexStore store,
            OrderStore orders,
            IconService icons,
            LinkState link,
            List<String> urls,
            SessionStore sessions,
            Users users,
            AuditLog audit,
            BotHub hub,
            LoginLimiter limiter,
            RuntimeHealth health)
            throws IOException {
        var credentials = new AgentCredentials(config.dataDir());
        credentials.onRevocation(hub::retire);
        Router router = new Router(new AccessControl(config), sessions, config, users, credentials, orders);
        health.register(router);
        AuthRoutes authRoutes = new AuthRoutes(config, sessions, limiter, users, audit);
        authRoutes.register(router);
        DeliveryContents delivery = new DeliveryContents(); // in memory only
        var stashDefs = new StashDefs(config.dataDir());
        var kits = orders.kits(); // the shared catalog: routes and reservations see the same templates
        new AddonRoutes(store, link, icons, orders, delivery, config, stashDefs).register(router);
        new BrowserRoutes(store, config, link, urls, orders, sessions, audit, delivery, credentials).register(router);
        new DeliveryRoutes(delivery, config, stashDefs).register(router);
        var guild = new GuildRoutes(config.dataDir(), audit);
        new OrderRoutes(orders, audit, guild, users, config, stashDefs).register(router);
        new OrderAuditRoutes(orders, store).register(router);
        new OperationsRoutes(store, orders, hub).register(router);
        new StockRoutes(store, orders).register(router, audit);
        new BoxRoutes(store, audit, orders, config, stashDefs).register(router);
        new TransportRoutes(new TransportProfiles(config.dataDir()), audit).register(router);
        new QuotaRoutes(new Quotas(config.dataDir()), audit).register(router);
        new CustodyRoutes(new PackageCustody(config.dataDir()), audit, config).register(router);
        new DeliveryPointRoutes(new DeliveryPoints(config.dataDir()), audit).register(router);
        new ServerRoutes(config, audit).register(router);
        new StashDefRoutes(stashDefs, audit).register(router);
        new KitTemplateRoutes(kits, audit).register(router);
        new DepositRoutes(new Deposits(config.dataDir()), audit, config).register(router);
        new JobRoutes(new JobScheduler(config.dataDir()), audit, config).register(router);
        new HandoffRoutes(new Handoffs(config.dataDir()), audit, config).register(router);
        new PhysicalRoutes(store, audit, config).register(router);
        new BotRoutes(hub, audit, orders, config, stashDefs).register(router);
        new DashboardRoutes(hub, store, sessions, audit).register(router);
        new UserRoutes(users, sessions, audit).register(router);
        guild.register(router);
        new IconRoutes(icons).register(router);
        // The vanilla dirt background for the Classic theme (from the verified official client);
        // 404 here falls back to the bundled dirt.svg layer in style.css.
        new DirtRoutes(VanillaTexture.dirt(config.dataDir().resolve("icons"))).register(router);
        new StaticRoutes().register(router); // last: the catch-all
        return new Routes(router, authRoutes, delivery, credentials);
    }

    private static void setDefault(String key, String value) {
        if (System.getProperty(key) == null) System.setProperty(key, value);
    }

    private static void usage(String error) {
        if (error != null) System.err.println(error);
        System.err.println(
                "Usage: java -jar wesort-manager.jar [serve|bootstrap|healthcheck|agent-key|agent-require-scoped] [--data <dir>]");
        System.err.println("       agent-issue [label] [--data <dir>]");
        System.err.println("       agent-revoke <key-id> [--data <dir>]");
        System.exit(error == null ? 0 : 2);
    }

    /** URLs the server can be reached at: localhost plus each LAN IPv4 address (or just the bind address). */
    private static List<String> urls(Config config) {
        List<String> urls = new ArrayList<>();
        boolean any = config.bind.equals("0.0.0.0") || config.bind.equals("::") || config.bind.equals("[::]");
        if (!any) {
            String host = config.bind.contains(":") ? "[" + config.bind + "]" : config.bind;
            urls.add("http://" + host + ":" + config.port);
            return urls;
        }
        urls.add("http://localhost:" + config.port);
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        urls.add("http://" + addr.getHostAddress() + ":" + config.port);
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return urls;
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "stash-manager-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}

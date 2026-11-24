package com.porter.wesortmanager;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Settings from config.properties in the data directory. The file is created with comments on first run. */
public final class Config {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern KEY_LINE = Pattern.compile("^\\s*apiKey\\s*[=:].*$");
    private static final String PASSWORD_KEY = "dashboardPassword";

    /** The current API key. generation goes up by one each time the key is regenerated. */
    public record KeyState(String key, long generation, byte[] signingKey) {
        KeyState(String key, long generation) {
            this(key, generation, Signer.deriveKey(key));
        }
    }

    public final int port;
    public final String bind;
    public final boolean allowRemote;
    public final List<String> allowedHosts;
    public final boolean generatedKey;
    /** Explicit Minecraft EULA approval; mandatory before starting Manager. */
    public final boolean eulaAccepted;

    public final boolean secureCookies;
    /**
     * trustedProxies=: addresses of reverse proxies / tunnels (e.g. cloudflared) in front of this server. Only for a
     * request whose socket address is one of these is the client address taken from the last
     * X-Forwarded-For entry, for the login rate limit and the audit log. Empty: headers are never trusted.
     */
    public final List<String> trustedProxies;
    /** The key before the last regenerate, accepted for 10 minutes on non-dashboard requests. */
    private volatile KeyState previousKey;

    private volatile long previousUntil;
    public static final long PREVIOUS_KEY_MS = 10 * 60_000;
    private final Path file;
    private volatile KeyState key;
    /** dashboardPassword=: the website password hash (see Auth), or null before the first-run setup. */
    private volatile String dashboardPassword;
    /** minecraftServer=: the single Minecraft server this Manager serves (set once in the web UI by the owner). */
    private volatile String minecraftServer;

    private Config(
            Path file,
            int port,
            String bind,
            String apiKey,
            boolean allowRemote,
            List<String> allowedHosts,
            boolean generatedKey,
            boolean eulaAccepted,
            List<String> trustedProxies,
            boolean secureCookies) {
        this.secureCookies = secureCookies;
        this.trustedProxies = List.copyOf(trustedProxies);
        this.file = file;
        this.eulaAccepted = eulaAccepted;
        this.port = port;
        this.bind = bind;
        this.key = new KeyState(apiKey, 0);
        this.allowRemote = allowRemote;
        this.allowedHosts = List.copyOf(allowedHosts);
        this.generatedKey = generatedKey;
    }

    public String apiKey() {
        return key.key();
    }

    /** The data folder (where config.properties lives). */
    public Path dataDir() {
        return file.toAbsolutePath().getParent();
    }

    public KeyState keyState() {
        return key;
    }

    /** The previous key while it is still accepted (10 minutes after a regenerate), else null. */
    public KeyState previousKey() {
        return System.currentTimeMillis() < previousUntil ? previousKey : null;
    }

    /** The stored password hash, or null when none is set (the website then asks for the first-run setup). */
    public String dashboardPassword() {
        return dashboardPassword;
    }

    /** The single Minecraft server this Manager serves, or null until the owner sets it in the web UI. */
    public String minecraftServer() {
        return minecraftServer;
    }

    /** Saves the single Minecraft server address (owner-only, from the web UI). Empty clears it. */
    public synchronized void setMinecraftServer(String server) throws IOException {
        // Stored in storage form so admission and every database key agree no matter how the
        // owner spelled the address (case, trailing dot, alt. alias, default :25565 port).
        String v = ServerRoutes.storageKey(server == null ? "" : server);
        if (v.length() > 256) throw new IllegalArgumentException("server address too long");
        writeLine(file, Pattern.compile("^\\s*minecraftServer\\s*[=:].*$"), "minecraftServer", v);
        minecraftServer = v.isEmpty() ? null : v;
    }

    /** Saves a new password hash in its own line of config.properties (other lines and comments are kept). */
    public synchronized void setDashboardPassword(String encoded) throws IOException {
        writeLine(file, Pattern.compile("^\\s*" + PASSWORD_KEY + "\\s*[=:].*$"), PASSWORD_KEY, encoded);
        dashboardPassword = encoded;
    }

    public static Config load(Path dataDir) throws IOException {
        dataDir = com.porter.wesort.data.DataDirectory.open(dataDir, "manager");
        Path file = dataDir.resolve("config.properties");
        Properties props = new Properties();
        boolean existed = Files.exists(file);
        if (existed) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            AtomicFiles.ownerOnly(file);
        }

        // Deployment knobs contain no credentials. File values remain the fallback for developer runs.
        for (String[] setting : new String[][] {
            {"WESORT_ALLOWED_HOSTS", "allowedHosts"},
            {"WESORT_TRUSTED_PROXIES", "trustedProxies"},
            {"WESORT_SECURE_COOKIES", "secureCookies"}
        }) {
            String value = System.getenv(setting[0]);
            if (value != null) props.setProperty(setting[1], value);
        }
        int port = parsePort(props.getProperty("port", "8765").trim());
        String bind = props.getProperty("bind", "0.0.0.0").trim();
        if (bind.isEmpty()) bind = "0.0.0.0";
        String apiKey = props.getProperty("apiKey", "").trim();
        boolean allowRemote =
                Boolean.parseBoolean(props.getProperty("allowRemote", "false").trim());
        List<String> allowedHosts = new ArrayList<>();
        for (String host : props.getProperty("allowedHosts", "").split(",")) {
            String h = host.trim().toLowerCase(Locale.ROOT);
            if (!h.isEmpty()) allowedHosts.add(h);
        }

        // Fail closed: no other consent key or local-client override can authorize startup.
        String envEula = System.getenv("WESORT_EULA");
        String eulaValue = (envEula == null || envEula.isBlank()) ? props.getProperty("eula", "false") : envEula;
        boolean eulaAccepted = "true".equalsIgnoreCase(eulaValue.trim());
        if (envEula != null
                && !envEula.isBlank()
                && "true".equalsIgnoreCase(envEula.trim())
                && !"true".equalsIgnoreCase(props.getProperty("eula", "false").trim())) {
            System.out.println(
                    "WeSort Manager: WESORT_EULA=true overrides config eula=false; update config.properties eula=true so a restart without the env var still starts.");
        }

        boolean generated = false;
        if (apiKey.isEmpty()) {
            apiKey = generateKey();
            generated = true;
        }

        if (!existed) {
            writeTemplate(file, port, bind, apiKey, allowRemote, String.join(",", allowedHosts));
        } else {
            if (generated) writeKeyLine(file, apiKey);
            appendMissing(file, props);
        }
        List<String> trustedProxies = new ArrayList<>();
        for (String a : props.getProperty("trustedProxies", "").split(",")) {
            String t = a.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (!AuthRoutes.isIpLiteral(t)) {
                System.err.println(
                        "config.properties: ignoring trustedProxies entry '" + t + "' (an IP address is needed)");
                continue;
            }
            trustedProxies.add(t);
        }
        Config config = new Config(
                file,
                port,
                bind,
                apiKey,
                allowRemote,
                allowedHosts,
                generated,
                eulaAccepted,
                trustedProxies,
                Boolean.parseBoolean(props.getProperty("secureCookies", "false")));
        String password = props.getProperty(PASSWORD_KEY, "").trim();
        if (!password.isEmpty()) {
            if (!Auth.wellFormed(password)) {
                System.err.println(
                        "config.properties: dashboardPassword is not a valid password hash, so nobody can log in."
                                + " Delete that line and restart to set a new password.");
            }
            config.dashboardPassword = password;
        }
        String single = props.getProperty("minecraftServer", "").trim();
        // Older installs may have any spelling here; normalize in memory so admission and
        // every database key agree from the first request (persisted in storage form on next save).
        if (!single.isEmpty()) config.minecraftServer = ServerRoutes.storageKey(single);
        return config;
    }

    /** Saves a new key before publishing it. The previous key is available for the warehouse migration window. */
    public synchronized String regenerateKey() throws IOException {
        String next = generateKey();
        writeKeyLine(file, next);
        previousKey = key;
        previousUntil = System.currentTimeMillis() + PREVIOUS_KEY_MS;
        key = new KeyState(next, key.generation() + 1);
        return next;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port > 0 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(
                "config.properties: port must be a number from 1 to 65535, got '" + value + "'");
    }

    private static String generateKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Replaces the apiKey line of an existing file, keeping the user's other lines and comments. */
    private static void writeKeyLine(Path file, String apiKey) throws IOException {
        writeLine(file, KEY_LINE, "apiKey", apiKey);
    }

    /**
     * Replaces the first line matching {@code pattern} with name=value (appending it when there is none), drops any
     * later duplicates (the last one would win in Properties.load), keeps every other line, and writes atomically
     * with owner-only permissions.
     */
    private static void writeLine(Path file, Pattern pattern, String name, String value) throws IOException {
        String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        StringBuilder out = new StringBuilder();
        boolean replaced = false;
        for (String line :
                new java.io.BufferedReader(new StringReader(text)).lines().toList()) {
            if (pattern.matcher(line).matches()) {
                if (!replaced) {
                    out.append(name).append('=').append(clean(value)).append('\n');
                    replaced = true;
                }
            } else {
                out.append(line).append('\n');
            }
        }
        if (!replaced) out.append(name).append('=').append(clean(value)).append('\n');
        AtomicFiles.write(file, out.toString().getBytes(StandardCharsets.UTF_8), true);
    }

    private static final String EULA_BLOCK = """
        # REQUIRED: Manager refuses to start until eula=true is explicitly set.
        # Read Mojang's EULA first: https://www.minecraft.net/en-us/eula
        # Setting true confirms you accept the Minecraft EULA, have a valid
        # Minecraft Java Edition license and will not redistribute game assets.
        # Manager automatically downloads and verifies the official 26.3 client.
        # Local game installations and user-supplied client JARs are NOT supported.
        eula=false
        """;
    private static final String PROXY_BLOCK = """
        # IP addresses (comma separated) of a reverse proxy or tunnel in front of this server, such as the machine
        # running cloudflared. Requests from exactly these addresses have their real client address read from
        # the last X-Forwarded-For entry for the login rate limit and the audit log. Leave empty otherwise.
        trustedProxies=
        """;
    /** Adds settings introduced after the file was created, with their comments, leaving every existing line alone. */
    private static void appendMissing(Path file, Properties props) throws IOException {
        StringBuilder add = new StringBuilder();
        if (!props.containsKey("eula")) add.append('\n').append(EULA_BLOCK);
        if (!props.containsKey("trustedProxies")) add.append('\n').append(PROXY_BLOCK);
        if (add.isEmpty()) return;
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && !text.endsWith("\n")) text += "\n";
        AtomicFiles.write(file, (text + add).getBytes(StandardCharsets.UTF_8), true);
    }

    private static void writeTemplate(
            Path file, int port, String bind, String apiKey, boolean allowRemote, String allowedHosts)
            throws IOException {
        String text = ("""
            # WeSort Manager server settings. Restart the server after editing.

            # TCP port the web server listens on.
            port=%d

            # Address to bind to. 0.0.0.0 listens on every network interface.
            bind=%s

            # Local shared HMAC key for deployments that have not enabled named-key enforcement.
            apiKey=%s

            # By default only loopback and private/LAN addresses may connect.
            # true turns that check off. Do not do this unless you know why you need it.
            allowRemote=%s

            # Extra host names (comma separated) the browser may use to reach this server,
            # e.g. a DNS name on your LAN. IP addresses, localhost and this machine's name always work.
            allowedHosts=%s
            """ + "\n" + EULA_BLOCK + "\n" + PROXY_BLOCK)
                .formatted(port, clean(bind), clean(apiKey), allowRemote, clean(allowedHosts));
        AtomicFiles.write(file, text.getBytes(StandardCharsets.UTF_8), true);
    }

    /** Keeps values safe for the properties format (they are expected to be plain ASCII anyway). */
    private static String clean(String value) {
        return value.replace("\\", "\\\\").replace("\n", "").replace("\r", "");
    }
}

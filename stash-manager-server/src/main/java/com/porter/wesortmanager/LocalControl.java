package com.porter.wesortmanager;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

/** Owner-only Unix socket. The container administrator, rather than an HTTP source IP, defines bootstrap trust. */
final class LocalControl implements AutoCloseable {
    static final String FILE = ".control.sock";
    private final Path path;
    private final ServerSocketChannel server;
    private final Thread worker;
    private final java.net.http.HttpClient http;

    LocalControl(
            Path data,
            AuthRoutes auth,
            RuntimeHealth health,
            Config config,
            int port,
            AgentCredentials credentials,
            AuditLog audit)
            throws IOException {
        path = data.resolve(FILE);
        Files.deleteIfExists(path); // Only after acquiring ManagerLock and validating the own data root.
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            server.bind(UnixDomainSocketAddress.of(path));
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException e) {
            server.close();
            throw e;
        }
        http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .build();
        worker = new Thread(
                () -> {
                    while (server.isOpen()) {
                        try (SocketChannel client = server.accept()) {
                            client.configureBlocking(false);
                            String command = line(client);
                            String response;
                            if (command.equals("HEALTH"))
                                response = health.ready() && httpReady(port) ? "OK ready" : "ERR not ready";
                            else if (command.equals("BOOTSTRAP")) {
                                String token = auth.issueBootstrap();
                                response = token == null ? "ERR owner already configured" : "OK " + token;
                            } else if (command.equals("AGENT_KEY")) {
                                response = config.dashboardPassword() == null
                                        ? "ERR configure the owner first"
                                        : credentials.scopedOnly()
                                                ? "ERR shared agent keys are disabled"
                                                : "OK " + config.apiKey();
                            } else if (command.startsWith("AGENT_")) {
                                response = config.dashboardPassword() == null
                                        ? "ERR configure the owner first"
                                        : agentCommand(command, credentials, audit);
                            } else response = "ERR unknown command";
                            write(client, response + "\n");
                        } catch (IOException e) {
                            if (server.isOpen()) System.err.println("Local administrator request failed");
                        }
                    }
                },
                "wesort-local-control");
        worker.setDaemon(true);
        worker.start();
    }

    private static String agentCommand(String command, AgentCredentials credentials, AuditLog audit)
            throws IOException {
        try {
            if (command.startsWith("AGENT_ISSUE ")) {
                String raw = new String(
                        java.util.Base64.getDecoder().decode(command.substring("AGENT_ISSUE ".length())),
                        StandardCharsets.UTF_8);
                var row = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
                var scope = AgentCredentials.readScope(row, !row.has("stashes"));
                String token;
                try {
                    token = credentials.issue(scope);
                } catch (IllegalArgumentException invalid) {
                    return "ERR " + invalid.getMessage();
                }
                audit.add("local", "Manager host administrator", "agent.credential.issue", scope.agent(), "local");
                return "OK " + token;
            }
            if (command.startsWith("AGENT_REVOKE ")) {
                String id = command.substring("AGENT_REVOKE ".length());
                if (!credentials.revoke(id)) return "ERR no active agent credential";
                audit.add("local", "Manager host administrator", "agent.credential.revoke", id, "local");
                return "OK revoked";
            }
            if (command.equals("AGENT_REQUIRE_SCOPED")) {
                credentials.requireScoped();
                audit.add("local", "Manager host administrator", "agent.credential.require_scoped", null, "local");
                return "OK scoped credentials required";
            }
        } catch (RuntimeException invalid) {
            return "ERR invalid agent credential request or scope";
        }
        return "ERR unknown command";
    }

    private boolean httpReady(int port) {
        try {
            var request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("http://127.0.0.1:" + port + "/health/ready"))
                    .timeout(java.time.Duration.ofSeconds(2))
                    .GET()
                    .build();
            return http.send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                            .statusCode()
                    == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static String request(Path data, String command) throws IOException {
        Path path = com.porter.wesort.data.DataDirectory.existingRoot(data, "manager")
                .resolve(FILE);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IOException("No owner-only Manager control socket");
        }
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(path));
            channel.configureBlocking(false);
            write(channel, command + "\n");
            String response = line(channel);
            if (!response.startsWith("OK ")) throw new IOException(response);
            return response.substring(3);
        }
    }

    private static String line(SocketChannel channel) throws IOException {
        // A provisioning command can carry every registered stash, encoded as base64 JSON.
        ByteBuffer buffer = ByteBuffer.allocate(131072);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (buffer.hasRemaining() && System.nanoTime() < deadline) {
            if (channel.read(buffer) < 0) break;
            String s = new String(buffer.array(), 0, buffer.position(), StandardCharsets.US_ASCII);
            if (s.contains("\n")) return s.substring(0, s.indexOf('\n'));
            pause();
        }
        throw new IOException("Local control response incomplete");
    }

    private static void write(SocketChannel channel, String value) throws IOException {
        ByteBuffer buffer = StandardCharsets.US_ASCII.encode(value);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (buffer.hasRemaining() && System.nanoTime() < deadline) {
            channel.write(buffer);
            if (buffer.hasRemaining()) pause();
        }
        if (buffer.hasRemaining()) throw new IOException("Local control write timeout");
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        worker.interrupt();
        try {
            worker.join(3500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        http.close();
        Files.deleteIfExists(path);
    }
}

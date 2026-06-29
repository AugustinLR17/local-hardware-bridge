package io.github.augustinlr17.localhardwarebridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fazecast.jSerialComm.SerialPort;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.ContentType;
import io.javalin.plugin.bundled.CorsPluginConfig;
import io.javalin.util.JavalinBindException;
import io.javalin.websocket.WsContext;
import lombok.extern.log4j.Log4j2;
import io.github.augustinlr17.localhardwarebridge.dtos.*;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServerInterface;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServiceInterface;
import io.github.augustinlr17.localhardwarebridge.responses.PrintDocument;
import io.github.augustinlr17.localhardwarebridge.responses.PrintResult;
import io.github.augustinlr17.localhardwarebridge.services.ConfigService;
import io.github.augustinlr17.localhardwarebridge.utils.CertificateGenerator;
import io.github.augustinlr17.localhardwarebridge.utils.ThreadUtil;
import io.github.augustinlr17.localhardwarebridge.websocketservices.PrinterWebSocketService;
import io.github.augustinlr17.localhardwarebridge.websocketservices.SerialWebSocketService;

import javax.print.PrintService;
import java.awt.print.PrinterJob;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.Locale;

@Log4j2
public class Server implements WebSocketServerInterface {
    private Javalin javalinServer;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ConfigService configService = ConfigService.getInstance();

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<WsContext>> socketChannelSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<WebSocketServiceInterface>> serviceChannelSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<WebSocketServiceInterface> services = new ConcurrentLinkedQueue<>();

    // Services that must outlive a restart (e.g. the GUI notification listener).
    // Printer/serial services are recreated by start() and are NOT kept here.
    private final java.util.Set<WebSocketServiceInterface> persistentServices = ConcurrentHashMap.newKeySet();

    private PrinterWebSocketService printerWebSocketService;

    // Guards against overlapping restarts triggered via /system/restart.json.
    private final AtomicBoolean restarting = new AtomicBoolean(false);

    // Process start timestamp, used to report uptime in the health endpoint.
    private static final long START_TIME = System.currentTimeMillis();

    public static void main(String[] args) {
        // Defensive anchor in case Server is used as a direct entry point; the Launcher
        // already anchors before any app class loads. Idempotent, no-op outside a JAR.
        AppHome.anchor();
        try {
            new Server().start();
        } catch (JavalinBindException e) {
            // Top-level entry point only: a bind failure here is fatal for the process.
            log.error("Unable to bind port, another instance is already running?");
            System.exit(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    synchronized public void start() throws Exception {
        Config config = configService.getConfig();

        Config.Server serverConfig = config.getServer();

        // Create Javalin Server
        javalinServer = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.staticFiles.add(staticFiles -> staticFiles.directory = "web");

            Config.Server.Cors corsConfig = serverConfig.getCors();
            cfg.bundledPlugins.enableCors(cors -> {
                if (corsConfig.isAllowAllOrigins() || corsConfig.getAllowedOrigins() == null || corsConfig.getAllowedOrigins().isEmpty()) {
                    // Default behaviour (and safe fallback for an empty allow-list): open to any host.
                    cors.addRule(CorsPluginConfig.CorsRule::anyHost);
                } else {
                    for (String origin : corsConfig.getAllowedOrigins()) {
                        cors.addRule(it -> it.allowHost(origin));
                    }
                }
            });

            if (serverConfig.getTls().isEnabled()) {
                if (serverConfig.getTls().isSelfSigned()) {
                    log.info("TLS Enabled with self-signed certificate");

                    CertificateGenerator.generateSelfSignedCertificate(serverConfig.getAddress(), serverConfig.getTls().getCert(), serverConfig.getTls().getKey());

                    log.info("For first time setup, open in browser and trust the certificate: {}", serverConfig.getUri());
                }

                SslPlugin plugin = new SslPlugin(conf -> {
                    conf.insecure = false;
                    conf.securePort = serverConfig.getPort();
                    conf.pemFromPath(serverConfig.getTls().getCert(), serverConfig.getTls().getKey());
                    conf.sniHostCheck = !serverConfig.getTls().isSelfSigned();
                });
                cfg.registerPlugin(plugin);
            }
        });

        // Add WebSocket Auth
        javalinServer.wsBefore(ctx -> {
            ctx.onConnect(wsConnectContext -> {
                wsConnectContext.session.getPolicy().setMaxBinaryMessageSize(-1);
                wsConnectContext.session.getPolicy().setMaxTextMessageSize(-1);

                wsConnectContext.enableAutomaticPings(5, TimeUnit.SECONDS);

                if (serverConfig.getAuthentication().isEnabled()) {
                    String expectedToken = serverConfig.getAuthentication().getToken();
                    String providedToken = wsConnectContext.queryParam("token");
                    if (expectedToken != null && !expectedToken.isBlank() && constantTimeEquals(providedToken, expectedToken)) {
                        return;
                    }

                    wsConnectContext.closeSession(1003, "Invalid token");
                }
            });
        });

        // Add WebSocket Printer Service
        Config.Printer printerConfig = config.getPrinter();
        if (printerConfig.isEnabled()) {
            printerWebSocketService = new PrinterWebSocketService();
            printerWebSocketService.start();

            javalinServer.ws(printerWebSocketService.getChannel(), ws -> {
                ws.onConnect(ctx -> {
                    log.info("{} connected to {}", ctx.host(), printerWebSocketService.getChannel());

                    addSocketToChannel(printerWebSocketService.getChannel(), ctx);
                });

                ws.onClose(ctx -> {
                    log.info("{} disconnected from {}", ctx.host(), printerWebSocketService.getChannel());

                    removeSocketFromChannel(printerWebSocketService.getChannel(), ctx);
                });

                ws.onMessage(ctx -> {
                    log.info("{} sent message to {}: {}", ctx.host(), printerWebSocketService.getChannel(), ctx.message());

                    messageToService("/printer", ctx.message());
                });
            });

            registerService(printerWebSocketService);
        }

        // Add WebSocket Serial Service
        Config.Serial serialConfig = config.getSerial();
        if (serialConfig.isEnabled()) {
            serialConfig.getMappings().forEach(mapping -> {
                try {
                    log.info("Starting SerialWebSocketService: {}", mapping.toString());
                    SerialWebSocketService serialWebSocketService = new SerialWebSocketService(mapping);
                    serialWebSocketService.start();

                    registerService(serialWebSocketService);

                    javalinServer.ws(serialWebSocketService.getChannel(), ws -> {
                        ws.onConnect(ctx -> {
                            log.info("{} connected to {}", ctx.host(), serialWebSocketService.getChannel());

                            addSocketToChannel(serialWebSocketService.getChannel(), ctx);
                        });

                        ws.onClose(ctx -> {
                            log.info("{} disconnected from {}", ctx.host(), serialWebSocketService.getChannel());

                            removeSocketFromChannel(serialWebSocketService.getChannel(), ctx);
                        });

                        ws.onMessage(ctx -> {
                            log.info("{} sent message to {}: {}", ctx.host(), serialWebSocketService.getChannel(), ctx.message());

                            messageToService(serialWebSocketService.getChannel(), ctx.message());
                        });

                        ws.onBinaryMessage(ctx -> {
                            log.info("{} sent binary message to {}: {}", ctx.host(), serialWebSocketService.getChannel(), ctx.data());

                            messageToService(serialWebSocketService.getChannel(), ctx.data());
                        });
                    });
                } catch (Exception e) {
                    String message = "Failed to start SerialWebSocketService for " + mapping.getType() + ": " + e.getMessage();
                    log.error(message);

                    try {
                        messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("ERROR", "Serial", message)));
                    } catch (JsonProcessingException ex) {
                        log.error("Failed to send notification: {}", ex.getMessage());
                    }
                }
            });
        }

        // Add HTTP Auth & endpoint security
        javalinServer.before(ctx -> {
            Config.Security security = configService.getConfig().getSecurity();
            String path = ctx.path();

            // Find matching endpoint rule: exact match first, then prefix match (e.g. /serial/*)
            Config.EndpointRule rule = security.getEndpoints().get(path);
            if (rule == null) {
                // Try prefix match for dynamic paths like /serial/SCALE → /serial/{type}
                if (path.startsWith("/serial/")) {
                    rule = security.getEndpoints().get("/serial/{type}");
                }
            }

            // The health endpoint must always be reachable without authentication so that
            // Docker/Kubernetes/load-balancer health probes and monitoring work regardless of
            // the global token or endpoint security configuration.
            if ("/system/health".equals(path)) {
                return;
            }

            // Critical endpoints required for the Web UI must always stay enabled
            if ("/config.json".equals(path)) {
                rule = null; // ignore any disable/password rule
            }

            // Check global token if enabled
            if (serverConfig.getAuthentication().isEnabled()) {
                String expectedToken = serverConfig.getAuthentication().getToken();
                // A null/empty/blank configured token never auto-passes.
                if (expectedToken != null && !expectedToken.isBlank()) {
                    try {
                        // Bearer Token
                        String bearer = extractBearerToken(ctx.header("Authorization"));
                        if (bearer != null && constantTimeEquals(bearer, expectedToken)) {
                            return;
                        }

                        // Basic Auth
                        if (ctx.basicAuthCredentials() != null && constantTimeEquals(ctx.basicAuthCredentials().getPassword(), expectedToken)) {
                            return;
                        }
                    } catch (Exception e) {
                        // NOOP
                    }
                }

                ctx.header("WWW-Authenticate", "Basic realm=\"Token required\"");
                ctx.res().sendError(401, "Token mismatch");
                return;
            }

            // Block disabled endpoints with 403
            if (rule != null && !rule.isEnabled()) {
                ctx.res().sendError(403, "Endpoint disabled");
                return;
            }

            // Check endpoint-specific password if set
            if (rule != null && rule.getPassword() != null && !rule.getPassword().isEmpty()) {
                String expectedPassword = rule.getPassword();
                try {
                    String bearer = extractBearerToken(ctx.header("Authorization"));
                    if (bearer != null && constantTimeEquals(bearer, expectedPassword)) {
                        return;
                    }
                    if (ctx.basicAuthCredentials() != null && constantTimeEquals(ctx.basicAuthCredentials().getPassword(), expectedPassword)) {
                        return;
                    }
                } catch (Exception e) {
                    // NOOP
                }
                ctx.header("WWW-Authenticate", "Basic realm=\"Password required\"");
                ctx.res().sendError(401, "Password mismatch");
            }
        });

        // Add HTTP API endpoints
        registerConfigEndpoints();
        registerPrinterEndpoints();
        registerSerialEndpoints();
        registerSystemEndpoints();

        // Re-attach services that must survive a restart (stop() removed them).
        for (WebSocketServiceInterface service : persistentServices) {
            registerService(service);
        }

        try {
            javalinServer.start(serverConfig.getBind(), serverConfig.getPort());
            log.info("{} {} running on {}", Constants.APP_NAME, Constants.VERSION, serverConfig.getUri());
        } catch (JavalinBindException e) {
            // Do NOT kill the process here: callers (GUI.restart, the restart thread, main)
            // decide how to handle a bind failure. Rethrow so they can log/recover.
            log.error("Unable to bind port {}, another instance is already running?", serverConfig.getPort());
            throw e;
        }
    }

    synchronized public void stop() throws Exception {
        for (Iterator<WebSocketServiceInterface> it = services.iterator(); it.hasNext(); ) {
            WebSocketServiceInterface service = it.next();
            unregisterService(service);
            service.stop();
            it.remove();
        }

        if (javalinServer != null) {
            javalinServer.stop();
        }
    }

    /**
     * Constant-time string comparison to avoid leaking secrets via timing side-channels.
     * Returns false if either argument is null.
     */
    private static boolean constantTimeEquals(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Extract the token from an {@code Authorization: Bearer <token>} header.
     * Returns null if the header is missing or not a Bearer credential.
     */
    private static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring("Bearer ".length());
    }

    /*
     * Service to Server listener
     */
    @Override
    public void messageToServer(String channel, String message) {
        log.debug("Received data from channel: {}, Data: {}", channel, message);

        ConcurrentLinkedQueue<WsContext> connectionList = socketChannelSubscriptions.getOrDefault(channel, new ConcurrentLinkedQueue<>());

        for (Iterator<WsContext> it = connectionList.iterator(); it.hasNext(); ) {
            try {
                WsContext conn = it.next();
                conn.send(message);
            } catch (Exception e) {
                log.warn("Exception {}: {}, removing connection from list", e.getClass().getSimpleName(), e.getMessage());
                it.remove();
            }
        }
    }

    @Override
    public void messageToServer(String channel, byte[] message) {
        log.debug("Received data from channel: {}, Data: {}", channel, message);

        ConcurrentLinkedQueue<WsContext> connectionList = socketChannelSubscriptions.getOrDefault(channel, new ConcurrentLinkedQueue<>());

        for (Iterator<WsContext> it = connectionList.iterator(); it.hasNext(); ) {
            WsContext conn = it.next();
            try {
                conn.send(ByteBuffer.wrap(message));
            } catch (Exception e) {
                log.warn("Exception: Removing connection from list");
                it.remove();
            }
        }
    }

    /*
     * Service to Service listener
     */
    @Override
    public void messageToService(String channel, String message) {
        ConcurrentLinkedQueue<WebSocketServiceInterface> services = getServicesForChannel(channel);
        for (WebSocketServiceInterface service : services) {
            log.debug("Sending: {} to channel: {}, service = {}", message, channel, service.getClass().getSimpleName());

            service.messageToService(message);
        }
    }

    @Override
    public void messageToService(String channel, byte[] bytes) {
        ConcurrentLinkedQueue<WebSocketServiceInterface> services = getServicesForChannel(channel);
        for (WebSocketServiceInterface service : services) {
            log.debug("Sending: {} to channel: {}, service = {}", bytes, channel, service.getClass().getSimpleName());

            service.messageToService(bytes);
        }
    }

    @Override
    public void registerService(WebSocketServiceInterface service) {
        service.onRegister(this);
        addServiceToChannel(service.getChannel(), service);
    }

    /**
     * Register a service that must be re-attached automatically after a restart
     * (e.g. the GUI notification listener). Unlike {@link #registerService}, the
     * service is remembered and re-registered by {@link #start()}.
     */
    public void registerPersistentService(WebSocketServiceInterface service) {
        persistentServices.add(service);
        registerService(service);
    }

    @Override
    public void unregisterService(WebSocketServiceInterface service) {
        service.onUnregister();
        removeServiceFromChannel(service.getChannel(), service);
    }

    /*
     * Socket to Channel operations
     */
    private ConcurrentLinkedQueue<WsContext> getSocketsForChannel(String channel) {
        return socketChannelSubscriptions.getOrDefault(channel, new ConcurrentLinkedQueue<>());
    }

    void addSocketToChannel(String channel, WsContext socket) {
        socketChannelSubscriptions.computeIfAbsent(channel, k -> new ConcurrentLinkedQueue<>()).add(socket);
    }

    private void removeSocketFromChannel(String channel, WsContext socket) {
        ConcurrentLinkedQueue<WsContext> connectionList = socketChannelSubscriptions.get(channel);
        if (connectionList != null) {
            connectionList.remove(socket);
        }
    }

    /*
     * Service to Channel operations
     */
    private ConcurrentLinkedQueue<WebSocketServiceInterface> getServicesForChannel(String channel) {
        ConcurrentLinkedQueue<WebSocketServiceInterface> services = new ConcurrentLinkedQueue<>();

        services.addAll(serviceChannelSubscriptions.getOrDefault(channel, new ConcurrentLinkedQueue<>()));
        services.addAll(serviceChannelSubscriptions.getOrDefault("*", new ConcurrentLinkedQueue<>()));

        return services;
    }

    private void addServiceToChannel(String channel, WebSocketServiceInterface service) {
        serviceChannelSubscriptions.computeIfAbsent(channel, k -> new ConcurrentLinkedQueue<>()).add(service);

        if (!services.contains(service)) {
            services.add(service);
        }
    }

    private void removeServiceFromChannel(String channel, WebSocketServiceInterface service) {
        ConcurrentLinkedQueue<WebSocketServiceInterface> serviceList = serviceChannelSubscriptions.get(channel);
        if (serviceList != null) {
            serviceList.remove(service);
        }

        services.remove(service);
    }

    /*
     * HTTP API - Config endpoints
     */
    private void registerConfigEndpoints() {
        javalinServer.get("/config.json", ctx -> {
            ctx.contentType(ContentType.APPLICATION_JSON).result(configService.getConfig().toJson());
        });

        javalinServer.put("/config.json", ctx -> {
            configService.loadFromJson(ctx.body());
            configService.save();

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Setting", "Setting saved successfully")));

            ctx.contentType(ContentType.APPLICATION_JSON).result(configService.getConfig().toJson());
        });
    }

    /*
     * HTTP API - Printer endpoints
     */
    private void registerPrinterEndpoints() {
        // List OS printers
        javalinServer.get("/system/printers.json", ctx -> {
            ArrayList<PrintServiceDTO> dtos = new ArrayList<>();
            for (PrintService service : PrinterJob.lookupPrintServices()) {
                dtos.add(new PrintServiceDTO(service.getName(), ""));
            }
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(dtos));
        });

        // Submit print job
        javalinServer.post("/printer", ctx -> {
            if (printerWebSocketService == null) {
                ctx.status(503).json("{\"error\": \"Printer service is disabled\"}");
                return;
            }

            String body = ctx.body();
            log.info("HTTP print request received: {}", body);

            try {
                PrintDocument printDocument = objectMapper.readValue(body, PrintDocument.class);
                PrintResult result = printerWebSocketService.printDocument(printDocument);
                ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                log.error("HTTP print error: {}", e.getMessage());
                ctx.status(500).json("{\"error\": \"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
            }
        });

        // List printer mappings
        javalinServer.get("/printer/mappings", ctx -> {
            Config config = configService.getConfig();
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(config.getPrinter()));
        });

        // Add printer mapping
        javalinServer.post("/printer/mappings", ctx -> {
            Config.PrinterMapping mapping = objectMapper.readValue(ctx.body(), Config.PrinterMapping.class);
            configService.getConfig().getPrinter().getMappings().add(mapping);
            configService.save();

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Printer", "Printer mapping added: " + mapping.getType())));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getPrinter()));
        });

        // Update printer mapping by type
        javalinServer.put("/printer/mappings/{type}", ctx -> {
            String type = ctx.pathParam("type");
            Config.PrinterMapping updated = objectMapper.readValue(ctx.body(), Config.PrinterMapping.class);

            ArrayList<Config.PrinterMapping> mappings = configService.getConfig().getPrinter().getMappings();
            boolean found = false;
            for (int i = 0; i < mappings.size(); i++) {
                if (type.equals(mappings.get(i).getType())) {
                    mappings.set(i, updated);
                    found = true;
                    break;
                }
            }

            if (!found) {
                ctx.status(404).json("{\"error\": \"Printer mapping not found: " + type + "\"}");
                return;
            }

            configService.save();

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Printer", "Printer mapping updated: " + type)));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getPrinter()));
        });

        // Delete printer mapping by type
        javalinServer.delete("/printer/mappings/{type}", ctx -> {
            String type = ctx.pathParam("type");
            ArrayList<Config.PrinterMapping> mappings = configService.getConfig().getPrinter().getMappings();
            boolean removed = mappings.removeIf(m -> type.equals(m.getType()));

            if (!removed) {
                ctx.status(404).json("{\"error\": \"Printer mapping not found: " + type + "\"}");
                return;
            }

            configService.save();

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Printer", "Printer mapping deleted: " + type)));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getPrinter()));
        });

        // Enable/disable printer service
        javalinServer.put("/printer/enabled", ctx -> {
            JsonNode node = objectMapper.readTree(ctx.body());
            configService.getConfig().getPrinter().setEnabled(node.get("enabled").asBoolean());
            configService.save();

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Printer", "Printer service " + (node.get("enabled").asBoolean() ? "enabled" : "disabled"))));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getPrinter()));
        });
    }

    /*
     * HTTP API - Serial endpoints
     */
    private void registerSerialEndpoints() {
        // List OS serial ports
        javalinServer.get("/system/serials.json", ctx -> {
            ArrayList<SerialPortDTO> dtos = new ArrayList<>();
            for (SerialPort port : SerialPort.getCommPorts()) {
                dtos.add(new SerialPortDTO(port.getSystemPortName(), port.getPortDescription(), port.getManufacturer()));
            }
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(dtos));
        });

        // List serial mappings (registered BEFORE wildcard /serial/{type})
        javalinServer.get("/serial/mappings", ctx -> {
            Config config = configService.getConfig();
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(config.getSerial()));
        });

        // Add serial mapping
        javalinServer.post("/serial/mappings", ctx -> {
            Config.SerialMapping mapping = objectMapper.readValue(ctx.body(), Config.SerialMapping.class);
            configService.getConfig().getSerial().getMappings().add(mapping);
            configService.save();

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Serial", "Serial mapping added: " + mapping.getType())));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getSerial()));
        });

        // Update serial mapping by type
        javalinServer.put("/serial/mappings/{type}", ctx -> {
            String type = ctx.pathParam("type");
            Config.SerialMapping updated = objectMapper.readValue(ctx.body(), Config.SerialMapping.class);

            ArrayList<Config.SerialMapping> mappings = configService.getConfig().getSerial().getMappings();
            boolean found = false;
            for (int i = 0; i < mappings.size(); i++) {
                if (type.equals(mappings.get(i).getType())) {
                    mappings.set(i, updated);
                    found = true;
                    break;
                }
            }

            if (!found) {
                ctx.status(404).json("{\"error\": \"Serial mapping not found: " + type + "\"}");
                return;
            }

            configService.save();

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Serial", "Serial mapping updated: " + type)));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getSerial()));
        });

        // Delete serial mapping by type
        javalinServer.delete("/serial/mappings/{type}", ctx -> {
            String type = ctx.pathParam("type");
            ArrayList<Config.SerialMapping> mappings = configService.getConfig().getSerial().getMappings();
            boolean removed = mappings.removeIf(m -> type.equals(m.getType()));

            if (!removed) {
                ctx.status(404).json("{\"error\": \"Serial mapping not found: " + type + "\"}");
                return;
            }

            configService.save();

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Serial", "Serial mapping deleted: " + type)));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getSerial()));
        });

        // Enable/disable serial service
        javalinServer.put("/serial/enabled", ctx -> {
            JsonNode node = objectMapper.readTree(ctx.body());
            configService.getConfig().getSerial().setEnabled(node.get("enabled").asBoolean());
            configService.save();

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Serial", "Serial service " + (node.get("enabled").asBoolean() ? "enabled" : "disabled"))));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getSerial()));
        });

        // Get serial port status (registered BEFORE wildcard /serial/{type})
        javalinServer.get("/serial/status", ctx -> {
            ArrayList<SerialPortDTO> dtos = new ArrayList<>();
            for (SerialPort port : SerialPort.getCommPorts()) {
                SerialPortDTO dto = new SerialPortDTO(port.getSystemPortName(), port.getPortDescription(), port.getManufacturer());
                dtos.add(dto);
            }

            ArrayList<JsonNode> statuses = new ArrayList<>();
            for (SerialPortDTO dto : dtos) {
                ObjectNode statusNode = objectMapper.createObjectNode();
                statusNode.put("port", dto.name);
                statusNode.put("description", dto.description);
                statusNode.put("manufacturer", dto.manufacturer);

                SerialPort port = SerialPort.getCommPort(dto.name);
                statusNode.put("open", port.isOpen());

                Config.SerialMapping mapping = configService.getConfig().getSerial().getMappings().stream()
                    .filter(m -> dto.name.equals(m.getName()))
                    .findFirst().orElse(null);
                statusNode.put("mapped", mapping != null);
                if (mapping != null) {
                    statusNode.put("type", mapping.getType());
                }

                statuses.add(statusNode);
            }

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(statuses));
        });

        // Get connected WebSocket clients per channel (registered BEFORE wildcard)
        javalinServer.get("/serial/connections", ctx -> {
            ObjectNode connections = objectMapper.createObjectNode();
            for (Map.Entry<String, ConcurrentLinkedQueue<WsContext>> entry : socketChannelSubscriptions.entrySet()) {
                if (entry.getKey().startsWith("/serial/")) {
                    connections.put(entry.getKey(), entry.getValue().size());
                }
            }
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(connections));
        });

        // Write to serial port by type (wildcard - registered AFTER static routes)
        javalinServer.post("/serial/{type}", ctx -> {
            String type = ctx.pathParam("type");
            String body = ctx.body();
            log.info("HTTP serial write request received for type {}: {}", type, body);

            messageToService("/serial/" + type, body);

            ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"submitted\"}");
        });
    }

    /*
     * HTTP API - System endpoints
     */
    private void registerSystemEndpoints() {
        // Serve app icon
        javalinServer.get("/icon.png", ctx -> {
            var stream = getClass().getClassLoader().getResourceAsStream("icon.png");
            if (stream != null) {
                ctx.contentType("image/png").result(stream.readAllBytes());
            } else {
                ctx.status(404);
            }
        });

        // Version (includes legacy identifiers for backward compatibility)
        javalinServer.get("/system/version.json", ctx -> {
            VersionDTO dto = new VersionDTO(Constants.APP_NAME, Constants.APP_ID, Constants.VERSION);
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(dto));
        });

        // Restart
        javalinServer.post("/system/restart.json", ctx -> {
            // No-op if a restart is already in progress.
            if (!restarting.compareAndSet(false, true)) {
                ctx.status(409).contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"already restarting\"}");
                return;
            }

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("WARNING", "Restart", "Server is restarting...")));

            // Respond before restarting: stop()/start() must NOT run on this Jetty
            // worker thread, otherwise javalinServer.stop() deadlocks shutting down the
            // very thread pool that is serving this request.
            ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"restarting\"}");

            Thread restartThread = new Thread(() -> {
                try {
                    stop();
                    ThreadUtil.silentSleep(500);
                    start();
                    messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Restart", "Server restarted successfully")));
                } catch (Exception e) {
                    log.error("Failed to restart server", e);
                } finally {
                    restarting.set(false);
                }
            }, "server-restart");
            restartThread.setDaemon(false);
            restartThread.start();
        });

        // Health check
        javalinServer.get("/system/health", ctx -> {
            ObjectNode health = objectMapper.createObjectNode();
            health.put("status", "UP");
            health.put("appName", Constants.APP_NAME);
            health.put("version", Constants.VERSION);

            Config currentConfig = configService.getConfig();
            health.put("printerEnabled", currentConfig.getPrinter().isEnabled());
            health.put("serialEnabled", currentConfig.getSerial().isEnabled());
            health.put("uptimeMillis", System.currentTimeMillis() - START_TIME);

            ObjectNode servicesNode = objectMapper.createObjectNode();
            for (WebSocketServiceInterface svc : services) {
                servicesNode.put(svc.getClass().getSimpleName(), true);
            }
            health.set("services", servicesNode);

            int activeConnections = 0;
            ObjectNode connectionsNode = objectMapper.createObjectNode();
            for (Map.Entry<String, ConcurrentLinkedQueue<WsContext>> entry : socketChannelSubscriptions.entrySet()) {
                connectionsNode.put(entry.getKey(), entry.getValue().size());
                activeConnections += entry.getValue().size();
            }
            health.put("activeConnections", activeConnections);
            health.set("connections", connectionsNode);

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(health));
        });

        // Send notification
        javalinServer.post("/system/notification", ctx -> {
            NotificationDTO notification = objectMapper.readValue(ctx.body(), NotificationDTO.class);
            messageToService("/notification", objectMapper.writeValueAsString(notification));

            ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"sent\"}");
        });

        // List all WebSocket connections
        javalinServer.get("/system/connections", ctx -> {
            ObjectNode connections = objectMapper.createObjectNode();
            for (Map.Entry<String, ConcurrentLinkedQueue<WsContext>> entry : socketChannelSubscriptions.entrySet()) {
                connections.put(entry.getKey(), entry.getValue().size());
            }
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(connections));
        });

        // Server config (bind, port, auth, tls)
        javalinServer.get("/system/server.json", ctx -> {
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getServer()));
        });

        // Update server config
        javalinServer.put("/system/server.json", ctx -> {
            Config.Server updated = objectMapper.readValue(ctx.body(), Config.Server.class);
            configService.getConfig().setServer(updated);
            configService.save();

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Server", "Server configuration updated. Restart required.")));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getServer()));
        });

        // Downloader config
        javalinServer.get("/system/downloader.json", ctx -> {
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getDownloader()));
        });

        // Update downloader config
        javalinServer.put("/system/downloader.json", ctx -> {
            Config.Downloader updated = objectMapper.readValue(ctx.body(), Config.Downloader.class);
            configService.getConfig().setDownloader(updated);
            configService.save();

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getDownloader()));
        });

        // GUI config
        javalinServer.get("/system/gui.json", ctx -> {
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getGui()));
        });

        // Update GUI config
        javalinServer.put("/system/gui.json", ctx -> {
            Config.GUI updated = objectMapper.readValue(ctx.body(), Config.GUI.class);
            configService.getConfig().setGui(updated);
            configService.save();

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getGui()));
        });

        // Install systemd service (Linux only) — also migrates from legacy service name
        javalinServer.post("/system/install-service", ctx -> {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!os.contains("linux")) {
                ctx.status(400).result("{\"error\": \"Service installation is only available on Linux\"}");
                return;
            }

            String jarPath = Paths.get(Server.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().toString();
            String workingDir = System.getProperty("user.dir");
            String javaHome = System.getProperty("java.home");
            String javaExec = javaHome + "/bin/java";

            String serviceContent = "[Unit]\n"
                + "# LHB_VERSION=" + Constants.VERSION + "\n"
                + "Description=Local Hardware Bridge\n"
                + "After=network.target\n\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "ExecStart=" + javaExec + " -cp " + jarPath + " io.github.augustinlr17.localhardwarebridge.Server\n"
                + "WorkingDirectory=" + workingDir + "\n"
                + "Restart=on-failure\n"
                + "RestartSec=5\n\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n";

            String serviceName = "local-hardware-bridge.service";
            String legacyServiceName = Constants.LEGACY_SERVICE_NAME + ".service";
            Path serviceFile = Path.of("/etc/systemd/system/" + serviceName);

            try {
                // Migrate from legacy service name if it exists
                Path legacyServiceFile = Path.of("/etc/systemd/system/" + legacyServiceName);
                if (Files.exists(legacyServiceFile)) {
                    log.info("Migrating legacy service {} to {}", legacyServiceName, serviceName);
                    ProcessBuilder disableLegacy = new ProcessBuilder("systemctl", "disable", "--now", legacyServiceName);
                    disableLegacy.redirectErrorStream(true).start().waitFor();
                    Files.deleteIfExists(legacyServiceFile);
                }

                Files.writeString(serviceFile, serviceContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                ProcessBuilder daemonReload = new ProcessBuilder("systemctl", "daemon-reload");
                daemonReload.redirectErrorStream(true).start().waitFor();

                ProcessBuilder enable = new ProcessBuilder("systemctl", "enable", "--now", serviceName);
                enable.redirectErrorStream(true).start().waitFor();

                messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Service", "Systemd service installed and started")));

                ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"installed\", \"service\": \"" + serviceName + "\"}");
            } catch (Exception e) {
                log.error("Failed to install systemd service", e);
                ctx.status(500).result("{\"error\": \"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
            }
        });

        // Uninstall systemd service (Linux only) — also cleans up legacy service
        javalinServer.post("/system/uninstall-service", ctx -> {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!os.contains("linux")) {
                ctx.status(400).result("{\"error\": \"Service uninstallation is only available on Linux\"}");
                return;
            }

            String serviceName = "local-hardware-bridge.service";
            String legacyServiceName = Constants.LEGACY_SERVICE_NAME + ".service";

            try {
                // Uninstall current service
                ProcessBuilder disable = new ProcessBuilder("systemctl", "disable", "--now", serviceName);
                disable.redirectErrorStream(true).start().waitFor();
                Files.deleteIfExists(Path.of("/etc/systemd/system/" + serviceName));

                // Also clean up legacy service if it exists
                ProcessBuilder disableLegacy = new ProcessBuilder("systemctl", "disable", "--now", legacyServiceName);
                disableLegacy.redirectErrorStream(true).start().waitFor();
                Files.deleteIfExists(Path.of("/etc/systemd/system/" + legacyServiceName));

                ProcessBuilder daemonReload = new ProcessBuilder("systemctl", "daemon-reload");
                daemonReload.redirectErrorStream(true).start().waitFor();

                messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Service", "Systemd service uninstalled")));

                ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"uninstalled\"}");
            } catch (Exception e) {
                log.error("Failed to uninstall systemd service", e);
                ctx.status(500).result("{\"error\": \"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
            }
        });
    }
}

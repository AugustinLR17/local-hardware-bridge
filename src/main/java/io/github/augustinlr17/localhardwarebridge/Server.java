package io.github.augustinlr17.localhardwarebridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fazecast.jSerialComm.SerialPort;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.ContentType;
import io.javalin.http.HandlerType;
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
import io.github.augustinlr17.localhardwarebridge.services.UpdateService;
import io.github.augustinlr17.localhardwarebridge.utils.CertificateGenerator;
import io.github.augustinlr17.localhardwarebridge.utils.SystemdServiceGenerator;
import io.github.augustinlr17.localhardwarebridge.utils.ThreadUtil;
import io.github.augustinlr17.localhardwarebridge.websocketservices.PrinterWebSocketService;
import io.github.augustinlr17.localhardwarebridge.websocketservices.SerialWebSocketService;

import javax.print.PrintService;
import javax.print.attribute.standard.Media;
import javax.print.attribute.standard.MediaTray;
import java.awt.print.PrinterJob;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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

    // Channel and path constants (avoid duplicated literals — java:S1192)
    private static final String NOTIFICATION_CHANNEL = "/notification";
    private static final String CONFIG_PATH = "/config.json";
    private static final String SERIAL_PREFIX = "/serial/";
    private static final String ERROR_JSON_PREFIX = "{\"error\": \"";
    private static final String ALREADY_RESTARTING = "{\"status\": \"already restarting\"}";
    private static final String SYSTEMD_PATH = "/etc/systemd/system/";
    private static final String SYSTEMCTL = "systemctl";
    private static final String NOW_FLAG = "--now";

    // Service name constants for health/logging
    private static final String PRINTER_SERVICE = "Printer";
    private static final String SERIAL_SERVICE = "Serial";
    private static final String UPDATE_SERVICE = "Update";
    private static final String WARNING_LEVEL = "WARNING";
    private static final String MASKED_TOKEN = "***";
    private static final String ENABLED_FIELD = "enabled";

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

        // Add WebSocket Auth — reject the HTTP upgrade request before the WS
        // connection is established. Using wsBeforeUpgrade (HTTP context) instead
        // of wsBefore (WS context) because closeSession() in wsBefore leaves the
        // connection briefly open, allowing messages to slip through.
        javalinServer.wsBeforeUpgrade(ctx -> {
            Config.Authentication currentAuth = configService.getConfig().getServer().getAuthentication();
            if (currentAuth.isEnabled()) {
                String expectedToken = currentAuth.getToken();
                if (expectedToken != null && !expectedToken.isBlank()) {
                    String providedToken = ctx.queryParam("token");
                    // Also accept Bearer token from the Authorization header
                    String bearer = extractBearerToken(ctx.header("Authorization"));
                    if (constantTimeEquals(providedToken, expectedToken) || constantTimeEquals(bearer, expectedToken)) {
                        return;
                    }
                }
                ctx.status(401).result("WebSocket authentication required");
            }
        });

        // Add WebSocket config (message size limits, pings) — runs after auth passes
        javalinServer.wsBefore(ctx -> {
            ctx.onConnect(wsConnectContext -> {
                wsConnectContext.session.getPolicy().setMaxBinaryMessageSize(-1);
                wsConnectContext.session.getPolicy().setMaxTextMessageSize(-1);

                wsConnectContext.enableAutomaticPings(5, TimeUnit.SECONDS);
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
                        messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("ERROR", SERIAL_SERVICE, message)));
                    } catch (JsonProcessingException ex) {
                        log.error("Failed to send notification: {}", ex.getMessage());
                    }
                }
            });
        }

        // Handle CORS preflight (OPTIONS) requests BEFORE the auth handler.
        // The bundled CORS plugin should handle this, but in some Javalin versions
        // the before() auth handler runs before the CORS plugin intercepts OPTIONS,
        // causing preflight requests to be rejected with 401. This ensures browsers
        // can always send preflight checks regardless of authentication.
        javalinServer.before(ctx -> {
            if (ctx.method() == HandlerType.OPTIONS) {
                Config.Server.Cors corsConfig = configService.getConfig().getServer().getCors();
                String origin = ctx.header("Origin");
                if (corsConfig.isAllowAllOrigins() || corsConfig.getAllowedOrigins() == null || corsConfig.getAllowedOrigins().isEmpty()) {
                    ctx.header("Access-Control-Allow-Origin", "*");
                } else if (origin != null && corsConfig.getAllowedOrigins().contains(origin)) {
                    ctx.header("Access-Control-Allow-Origin", origin);
                } else {
                    ctx.header("Access-Control-Allow-Origin", "*");
                }
                ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                ctx.header("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Requested-With");
                ctx.header("Access-Control-Max-Age", "3600");
                ctx.status(200);
                return;
            }
        });

        // Add HTTP Auth & endpoint security
        javalinServer.before(ctx -> {
            Config.Security security = configService.getConfig().getSecurity();
            String path = ctx.path();

            // Find matching endpoint rule: exact match first, then prefix match (e.g. /serial/*)
            Config.EndpointRule rule = security.getEndpoints().get(path);
            if (rule == null) {
                // Try prefix match for dynamic paths like /serial/SCALE → /serial/{type}
                if (path.startsWith(SERIAL_PREFIX)) {
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
            if (CONFIG_PATH.equals(path)) {
                rule = null; // ignore any disable/password rule
            }

            // Block disabled endpoints with 403 — this must run before the global token
            // check so that a valid token does NOT bypass an endpoint that has been
            // explicitly disabled. (e.g. /printer disabled on a quality-control zone.)
            if (rule != null && !rule.isEnabled()) {
                ctx.res().sendError(403, "Endpoint disabled");
                return;
            }

            // Check global token if enabled — read live from configService so that
            // changes via PUT /config.json or PUT /system/server.json take effect
            // immediately without requiring a server restart.
            Config.Authentication currentAuth = configService.getConfig().getServer().getAuthentication();
            if (currentAuth.isEnabled()) {
                String expectedToken = currentAuth.getToken();
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

                        // Query param ?token= (same as WebSocket auth, for REST clients
                        // that can't set headers — e.g. browser <img> or simple redirects)
                        String queryToken = ctx.queryParam("token");
                        if (queryToken != null && constantTimeEquals(queryToken, expectedToken)) {
                            return;
                        }
                    } catch (Exception e) {
                        // NOOP
                    }
                }

                // If this endpoint has its own password, check it as an alternative
                // to the global token. This allows per-endpoint passwords to work even
                // when global auth is enabled (the global token OR the endpoint password
                // is accepted for that specific endpoint).
                if (rule != null && rule.getPassword() != null && !rule.getPassword().isEmpty()) {
                    try {
                        String bearer = extractBearerToken(ctx.header("Authorization"));
                        if (bearer != null && constantTimeEquals(bearer, rule.getPassword())) {
                            return;
                        }
                        if (ctx.basicAuthCredentials() != null && constantTimeEquals(ctx.basicAuthCredentials().getPassword(), rule.getPassword())) {
                            return;
                        }
                        String queryToken = ctx.queryParam("token");
                        if (queryToken != null && constantTimeEquals(queryToken, rule.getPassword())) {
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

            // Check endpoint-specific password if set (only reached when global auth is
            // disabled OR no global token is configured — a valid global token returns
            // above before reaching this point).
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
                    String queryToken = ctx.queryParam("token");
                    if (queryToken != null && constantTimeEquals(queryToken, expectedPassword)) {
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
        registerUpdateEndpoints();

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

        // Start the update checker scheduler (if enabled in config)
        UpdateService.getInstance().startScheduledChecks();
    }

    synchronized public void stop() throws Exception {
        // Stop the update scheduler before the server shuts down
        UpdateService.getInstance().stopScheduledChecks();

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

    /**
     * Mask the authentication token in a config section before returning it via API.
     * The Web UI knows the token (it authenticated with it), but exposing it in API
     * responses means any other client that intercepts the response gets the token.
     * We replace it with "***" so the UI can detect "token is set" without leaking it.
     */
    private static String maskToken(String json) {
        if (json == null) return json;
        return json.replaceAll("\"token\"\\s*:\\s*\"[^\"]*\"", "\"token\":\"" + MASKED_TOKEN + "\"");
    }

    /**
     * Check whether the caller provided a confirmation parameter for destructive
     * operations (rollback, apply, restart). This prevents accidental triggers
     * from automated tools or curious users who just POST to the endpoint.
     *
     * Accepts either:
     * - query param: ?confirm=true
     * - header: X-Confirm: true
     *
     * @param ctx the Javalin context
     * @return true if confirmation is provided
     */
    private static boolean isConfirmed(io.javalin.http.Context ctx) {
        String confirm = ctx.queryParam("confirm");
        if (confirm == null) {
            confirm = ctx.header("X-Confirm");
        }
        return "true".equalsIgnoreCase(confirm);
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
        javalinServer.get(CONFIG_PATH, ctx -> {
            ctx.contentType(ContentType.APPLICATION_JSON).result(maskToken(configService.getConfig().toJson()));
        });

        javalinServer.put(CONFIG_PATH, ctx -> {
            configService.loadFromJson(ctx.body());
            configService.save();

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", "Setting", "Setting saved successfully")));

            ctx.contentType(ContentType.APPLICATION_JSON).result(maskToken(configService.getConfig().toJson()));
        });

        // POST /config.json — alias for PUT, so clients that can only send POST
        // (e.g. HTML forms, some JS frameworks) can also update the config.
        javalinServer.post(CONFIG_PATH, ctx -> {
            configService.loadFromJson(ctx.body());
            configService.save();

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", "Setting", "Setting saved successfully")));

            ctx.contentType(ContentType.APPLICATION_JSON).result(maskToken(configService.getConfig().toJson()));
        });
    }

    /**
     * Converts a {@link MediaTray} to the string value expected by the
     * {@code paper_tray} field in {@link io.github.augustinlr17.localhardwarebridge.responses.PrintDocument}.
     * Returns null for trays that have no standard string mapping.
     */
    private static String mediaTrayToString(MediaTray tray) {
        if (tray == null) return null;
        if (tray == MediaTray.MAIN) return "MAIN";
        if (tray == MediaTray.MANUAL) return "MANUAL";
        if (tray == MediaTray.TOP) return "TOP";
        if (tray == MediaTray.BOTTOM) return "BOTTOM";
        if (tray == MediaTray.SIDE) return "SIDE";
        if (tray == MediaTray.ENVELOPE) return "ENVELOPE";
        if (tray == MediaTray.LARGE_CAPACITY) return "LARGE_CAPACITY";
        // Non-standard tray — use its enum name as fallback
        return tray.toString().toUpperCase().replace(' ', '_');
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

        // List available paper trays for a specific printer
        javalinServer.get("/system/printers/{name}/trays.json", ctx -> {
            String printerName = ctx.pathParam("name");
            PrintService[] services = PrinterJob.lookupPrintServices();
            PrintService target = null;
            for (PrintService s : services) {
                if (s.getName().equalsIgnoreCase(printerName)) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                ctx.status(404).json("{\"error\": \"Printer not found: " + printerName.replace("\"", "'") + "\"}");
                return;
            }

            ArrayList<PrinterTrayDTO> trays = new ArrayList<>();
            try {
                Media[] media = (Media[]) target.getSupportedAttributeValues(Media.class, null, null);
                if (media != null) {
                    for (Media m : media) {
                        if (m instanceof MediaTray) {
                            MediaTray tray = (MediaTray) m;
                            String value = mediaTrayToString(tray);
                            if (value != null) {
                                trays.add(new PrinterTrayDTO(value, tray.toString()));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to query trays for printer {}: {}", printerName, e.getMessage());
                ctx.status(500).json("{\"error\": \"Failed to query trays: " + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
                return;
            }

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(trays));
        });

        // Submit print job
        javalinServer.post("/printer", ctx -> {
            if (printerWebSocketService == null || !configService.getConfig().getPrinter().isEnabled()) {
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
                ctx.status(500).json(ERROR_JSON_PREFIX + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
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

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", PRINTER_SERVICE, "Printer mapping added: " + mapping.getType())));

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

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", PRINTER_SERVICE, "Printer mapping updated: " + type)));

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

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", PRINTER_SERVICE, "Printer mapping deleted: " + type)));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getPrinter()));
        });

        // Enable/disable printer service
        javalinServer.put("/printer/enabled", ctx -> {
            JsonNode node = objectMapper.readTree(ctx.body());
            configService.getConfig().getPrinter().setEnabled(node.get(ENABLED_FIELD).asBoolean());
            configService.save();

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", PRINTER_SERVICE, "Printer service " + (node.get(ENABLED_FIELD).asBoolean() ? ENABLED_FIELD : "disabled"))));

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

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", SERIAL_SERVICE, "Serial mapping added: " + mapping.getType())));

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

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", SERIAL_SERVICE, "Serial mapping updated: " + type)));

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

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", SERIAL_SERVICE, "Serial mapping deleted: " + type)));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getSerial()));
        });

        // Enable/disable serial service
        javalinServer.put("/serial/enabled", ctx -> {
            JsonNode node = objectMapper.readTree(ctx.body());
            configService.getConfig().getSerial().setEnabled(node.get(ENABLED_FIELD).asBoolean());
            configService.save();

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", SERIAL_SERVICE, "Serial service " + (node.get(ENABLED_FIELD).asBoolean() ? ENABLED_FIELD : "disabled"))));

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
                statusNode.put("port", dto.getName());
                statusNode.put("description", dto.getDescription());
                statusNode.put("manufacturer", dto.getManufacturer());

                SerialPort port = SerialPort.getCommPort(dto.getName());
                statusNode.put("open", port.isOpen());

                Config.SerialMapping mapping = configService.getConfig().getSerial().getMappings().stream()
                    .filter(m -> dto.getName().equals(m.getName()))
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
                if (entry.getKey().startsWith(SERIAL_PREFIX)) {
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

            messageToService(SERIAL_PREFIX + type, body);

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

        // Restart — requires ?confirm=true or X-Confirm: true header
        javalinServer.post("/system/restart.json", ctx -> {
            if (!isConfirmed(ctx)) {
                ctx.status(400).contentType(ContentType.APPLICATION_JSON)
                        .result("{\"error\": \"Confirmation required. Add ?confirm=true or X-Confirm: true header.\"}");
                return;
            }

            // No-op if a restart is already in progress.
            if (!restarting.compareAndSet(false, true)) {
                ctx.status(409).contentType(ContentType.APPLICATION_JSON).result(ALREADY_RESTARTING);
                return;
            }

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO(WARNING_LEVEL, "Restart", "Server is restarting...")));

            // Respond before restarting: stop()/start() must NOT run on this Jetty
            // worker thread, otherwise javalinServer.stop() deadlocks shutting down the
            // very thread pool that is serving this request.
            ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"restarting\"}");

            Thread restartThread = new Thread(() -> {
                try {
                    stop();
                    ThreadUtil.silentSleep(500);
                    start();
                    messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", "Restart", "Server restarted successfully")));
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
            try {
                String body = ctx.body();
                if (body == null || body.isBlank()) {
                    ctx.status(400).contentType(ContentType.APPLICATION_JSON)
                            .result("{\"error\": \"Request body is empty\"}");
                    return;
                }
                NotificationDTO notification = objectMapper.readValue(body, NotificationDTO.class);
                messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(notification));

                ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"sent\"}");
            } catch (Exception e) {
                log.error("Failed to send notification: {}", e.getMessage());
                ctx.status(400).contentType(ContentType.APPLICATION_JSON)
                        .result(ERROR_JSON_PREFIX + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
            }
        });

        // List all WebSocket connections
        javalinServer.get("/system/connections", ctx -> {
            ObjectNode connections = objectMapper.createObjectNode();
            for (Map.Entry<String, ConcurrentLinkedQueue<WsContext>> entry : socketChannelSubscriptions.entrySet()) {
                connections.put(entry.getKey(), entry.getValue().size());
            }
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(connections));
        });

        // Server config (bind, port, auth, tls) — token masked in response
        javalinServer.get("/system/server.json", ctx -> {
            ctx.contentType(ContentType.APPLICATION_JSON)
                    .result(maskToken(objectMapper.writeValueAsString(configService.getConfig().getServer())));
        });

        // Update server config — token masked in response
        javalinServer.put("/system/server.json", ctx -> {
            Config.Server updated = objectMapper.readValue(ctx.body(), Config.Server.class);
            configService.getConfig().setServer(updated);
            configService.save();

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", "Server", "Server configuration updated. Restart required.")));

            ctx.contentType(ContentType.APPLICATION_JSON)
                    .result(maskToken(objectMapper.writeValueAsString(configService.getConfig().getServer())));
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
            String javaHome = System.getProperty("java.home");
            String javaExec = javaHome + "/bin/java";

            // Copy the JAR to a stable location so the service doesn't break if
            // the user moves/deletes the download.
            String installDir = SystemdServiceGenerator.getInstallDir();
            String installedJarPath = SystemdServiceGenerator.getInstalledJarPath();

            String serviceContent = SystemdServiceGenerator.generateServiceUnit(
                javaExec, jarPath, installDir, Constants.VERSION);

            String serviceName = "local-hardware-bridge.service";
            String legacyServiceName = Constants.LEGACY_SERVICE_NAME + ".service";
            Path serviceFile = Path.of(SYSTEMD_PATH + serviceName);

            try {
                // Copy the JAR to /opt/local-hardware-bridge/ (stable location)
                Path installDirPath = Path.of(installDir);
                Files.createDirectories(installDirPath);
                Files.copy(Path.of(jarPath), Path.of(installedJarPath),
                    StandardCopyOption.REPLACE_EXISTING);

                // Migrate from legacy service name if it exists
                Path legacyServiceFile = Path.of(SYSTEMD_PATH + legacyServiceName);
                if (Files.exists(legacyServiceFile)) {
                    log.info("Migrating legacy service {} to {}", legacyServiceName, serviceName);
                    ProcessBuilder disableLegacy = new ProcessBuilder(SYSTEMCTL, "disable", NOW_FLAG, legacyServiceName);
                    disableLegacy.redirectErrorStream(true).start().waitFor();
                    Files.deleteIfExists(legacyServiceFile);
                }

                Files.writeString(serviceFile, serviceContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                ProcessBuilder daemonReload = new ProcessBuilder(SYSTEMCTL, "daemon-reload");
                daemonReload.redirectErrorStream(true).start().waitFor();

                ProcessBuilder enable = new ProcessBuilder(SYSTEMCTL, "enable", NOW_FLAG, serviceName);
                enable.redirectErrorStream(true).start().waitFor();

                messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", "Service", "Systemd service installed and started")));

                ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"installed\", \"service\": \"" + serviceName + "\"}");
            } catch (Exception e) {
                log.error("Failed to install systemd service", e);
                ctx.status(500).result(ERROR_JSON_PREFIX + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
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
                ProcessBuilder disable = new ProcessBuilder(SYSTEMCTL, "disable", NOW_FLAG, serviceName);
                disable.redirectErrorStream(true).start().waitFor();
                Files.deleteIfExists(Path.of(SYSTEMD_PATH + serviceName));

                // Also clean up legacy service if it exists
                ProcessBuilder disableLegacy = new ProcessBuilder(SYSTEMCTL, "disable", NOW_FLAG, legacyServiceName);
                disableLegacy.redirectErrorStream(true).start().waitFor();
                Files.deleteIfExists(Path.of(SYSTEMD_PATH + legacyServiceName));

                ProcessBuilder daemonReload = new ProcessBuilder(SYSTEMCTL, "daemon-reload");
                daemonReload.redirectErrorStream(true).start().waitFor();

                messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", "Service", "Systemd service uninstalled")));

                ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"uninstalled\"}");
            } catch (Exception e) {
                log.error("Failed to uninstall systemd service", e);
                ctx.status(500).result(ERROR_JSON_PREFIX + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
            }
        });
    }

    /*
     * HTTP API - Update endpoints
     */
    private void registerUpdateEndpoints() {
        UpdateService updateService = UpdateService.getInstance();

        // Get update status
        javalinServer.get("/system/update/status", ctx -> {
            UpdateStatusDTO status = updateService.getStatus();
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(status));
        });

        // Trigger an update check (synchronous)
        javalinServer.get("/system/update/check", ctx -> {
            try {
                UpdateStatusDTO status = updateService.checkNow();
                messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO(
                        "INFO",
                        UPDATE_SERVICE,
                        status.isUpdateAvailable()
                                ? "Update " + status.getLatestVersion() + " is available"
                                : "Already up to date (" + Constants.VERSION + ")")));
                ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(status));
            } catch (Exception e) {
                log.error("Update check failed", e);
                ctx.status(500).json(ERROR_JSON_PREFIX + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
            }
        });

        // Download the update JAR (if available)
        javalinServer.post("/system/update/download", ctx -> {
            try {
                if (!updateService.getStatus().isUpdateAvailable()) {
                    ctx.status(409).json("{\"error\": \"No update available. Call /system/update/check first.\"}");
                    return;
                }
                java.nio.file.Path downloaded = updateService.downloadUpdate();
                if (downloaded == null) {
                    ctx.status(500).json("{\"error\": \"Download did not produce a file\"}");
                    return;
                }
                messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO(
                        "INFO", UPDATE_SERVICE, "Update downloaded: " + downloaded.getFileName() + ". Restart to apply.")));
                ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"downloaded\", \"path\": \"" + downloaded + "\"}");
            } catch (Exception e) {
                log.error("Update download failed", e);
                ctx.status(500).json(ERROR_JSON_PREFIX + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
            }
        });

        // Apply the update: replaces the JAR and triggers a restart — requires ?confirm=true
        javalinServer.post("/system/update/apply", ctx -> {
            try {
                if (!isConfirmed(ctx)) {
                    ctx.status(400).contentType(ContentType.APPLICATION_JSON)
                            .result("{\"error\": \"Confirmation required. Add ?confirm=true or X-Confirm: true header.\"}");
                    return;
                }

                java.nio.file.Path pending = updateService.consumePendingUpdate();
                if (pending == null) {
                    ctx.status(409).json("{\"error\": \"No pending update to apply. Download first.\"}");
                    return;
                }

                // Guard against overlapping restarts
                if (!restarting.compareAndSet(false, true)) {
                    ctx.status(409).contentType(ContentType.APPLICATION_JSON).result(ALREADY_RESTARTING);
                    return;
                }

                messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO(
                        WARNING_LEVEL, UPDATE_SERVICE, "Applying update and restarting...")));

                ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"applying\", \"pending\": \"" + pending + "\"}");

                Thread updateThread = new Thread(() -> {
                    try {
                        stop();
                        updateService.applyUpdate(pending);
                        updateService.cleanupOldUpdates();
                        ThreadUtil.silentSleep(500);
                        start();
                        messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO(
                                "INFO", UPDATE_SERVICE, "Update applied and server restarted successfully")));
                    } catch (Exception e) {
                        log.error("Failed to apply update", e);
                        try {
                            updateService.rollback();
                        } catch (Exception rollbackEx) {
                            log.error("Rollback also failed", rollbackEx);
                        }
                        try {
                            start();
                        } catch (Exception startEx) {
                            log.error("Restart after failed update also failed", startEx);
                        }
                    } finally {
                        restarting.set(false);
                    }
                }, "update-apply");
                updateThread.setDaemon(false);
                updateThread.start();
            } catch (Exception e) {
                log.error("Failed to apply update", e);
                ctx.status(500).json(ERROR_JSON_PREFIX + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
            }
        });

        // Rollback to the previous version (if a .bak exists) — requires ?confirm=true
        javalinServer.post("/system/update/rollback", ctx -> {
            try {
                if (!isConfirmed(ctx)) {
                    ctx.status(400).contentType(ContentType.APPLICATION_JSON)
                            .result("{\"error\": \"Confirmation required. Add ?confirm=true or X-Confirm: true header.\"}");
                    return;
                }

                if (!restarting.compareAndSet(false, true)) {
                    ctx.status(409).contentType(ContentType.APPLICATION_JSON).result(ALREADY_RESTARTING);
                    return;
                }

                messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO(
                        WARNING_LEVEL, UPDATE_SERVICE, "Rolling back to previous version...")));

                ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"rolling-back\"}");

                Thread rollbackThread = new Thread(() -> {
                    try {
                        stop();
                        updateService.rollback();
                        ThreadUtil.silentSleep(500);
                        start();
                        messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO(
                                "INFO", UPDATE_SERVICE, "Rollback complete and server restarted")));
                    } catch (Exception e) {
                        log.error("Rollback failed", e);
                        try {
                            start();
                        } catch (Exception startEx) {
                            log.error("Restart after failed rollback also failed", startEx);
                        }
                    } finally {
                        restarting.set(false);
                    }
                }, "update-rollback");
                rollbackThread.setDaemon(false);
                rollbackThread.start();
            } catch (Exception e) {
                log.error("Rollback failed", e);
                ctx.status(500).json(ERROR_JSON_PREFIX + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
            }
        });

        // Get/update update config section
        javalinServer.get("/system/update.json", ctx -> {
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getUpdate()));
        });

        javalinServer.put("/system/update.json", ctx -> {
            Config.Update updated = objectMapper.readValue(ctx.body(), Config.Update.class);
            configService.getConfig().setUpdate(updated);
            configService.save();

            // Restart the scheduler if settings changed
            updateService.stopScheduledChecks();
            updateService.startScheduledChecks();

            messageToService(NOTIFICATION_CHANNEL, objectMapper.writeValueAsString(new NotificationDTO("INFO", UPDATE_SERVICE, "Update settings saved")));

            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(configService.getConfig().getUpdate()));
        });
    }
}

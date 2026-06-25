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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
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

    private PrinterWebSocketService printerWebSocketService;

    public static void main(String[] args) {
        try {
            new Server().start();
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
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(CorsPluginConfig.CorsRule::anyHost));

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
                    if (Optional.ofNullable(wsConnectContext.queryParam("token")).orElse("").equals(serverConfig.getAuthentication().getToken())) {
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

        // Add HTTP Auth
        javalinServer.before(ctx -> {
            Config.Security security = configService.getConfig().getSecurity();

            String path = ctx.path();
            Config.EndpointRule rule = security.getEndpoints().get(path);

            // Critical endpoints required for the Web UI must always stay enabled
            if ("/config.json".equals(path) || "/system/health".equals(path)) {
                rule = null; // ignore any disable/password rule
            }

            // Check global token if enabled
            if (serverConfig.getAuthentication().isEnabled()) {
                try {
                    // Bearer Token
                    if (Optional.ofNullable(ctx.header("Authorization")).orElse("").endsWith(serverConfig.getAuthentication().getToken())) {
                        return;
                    }

                    // Basic Auth
                    if (ctx.basicAuthCredentials() != null && Objects.equals(ctx.basicAuthCredentials().getPassword(), serverConfig.getAuthentication().getToken())) {
                        return;
                    }
                } catch (Exception e) {
                    // NOOP
                }

                ctx.header("WWW-Authenticate", "Basic realm=\"Token required\"");
                ctx.res().sendError(401, "Token mismatch");
                return;
            }

            // Check endpoint-specific password if set
            if (rule != null && rule.getPassword() != null && !rule.getPassword().isEmpty()) {
                try {
                    String provided = Optional.ofNullable(ctx.header("Authorization")).orElse("");
                    if (provided.endsWith(rule.getPassword())) {
                        return;
                    }
                    if (ctx.basicAuthCredentials() != null && Objects.equals(ctx.basicAuthCredentials().getPassword(), rule.getPassword())) {
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

        try {
            javalinServer.start(serverConfig.getBind(), serverConfig.getPort());
            log.info("{} {} running on {}", Constants.APP_NAME, Constants.VERSION, serverConfig.getUri());
        } catch (JavalinBindException e) {
            log.info("Unable to bind port, another instance is already running?");
            System.exit(1);
        }
    }

    synchronized public void stop() throws Exception {
        for (Iterator<WebSocketServiceInterface> it = services.iterator(); it.hasNext(); ) {
            WebSocketServiceInterface service = it.next();
            unregisterService(service);
            service.stop();
            it.remove();
        }

        javalinServer.stop();
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
        ConcurrentLinkedQueue<WsContext> connectionList = getSocketsForChannel(channel);
        connectionList.add(socket);
        socketChannelSubscriptions.put(channel, connectionList);
    }

    private void removeSocketFromChannel(String channel, WsContext socket) {
        ConcurrentLinkedQueue<WsContext> connectionList = getSocketsForChannel(channel);
        connectionList.remove(socket);
        socketChannelSubscriptions.put(channel, connectionList);
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
        ConcurrentLinkedQueue<WebSocketServiceInterface> serviceList = serviceChannelSubscriptions.getOrDefault(channel, new ConcurrentLinkedQueue<>());

        serviceList.add(service);
        serviceChannelSubscriptions.put(channel, serviceList);

        if (!services.contains(service)) {
            services.add(service);
        }
    }

    private void removeServiceFromChannel(String channel, WebSocketServiceInterface service) {
        ConcurrentLinkedQueue<WebSocketServiceInterface> serviceList = getServicesForChannel(channel);
        serviceList.remove(service);
        serviceChannelSubscriptions.put(channel, serviceList);

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
                ctx.status(500).json("{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
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

        // Version
        javalinServer.get("/system/version.json", ctx -> {
            VersionDTO dto = new VersionDTO(Constants.APP_NAME, Constants.APP_ID, Constants.VERSION);
            ctx.contentType(ContentType.APPLICATION_JSON).result(objectMapper.writeValueAsString(dto));
        });

        // Restart
        javalinServer.post("/system/restart.json", ctx -> {
            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("WARNING", "Restart", "Server is restarting...")));

            stop();
            ThreadUtil.silentSleep(500);
            start();

            messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Restart", "Server restarted successfully")));

            ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"restarted\"}");
        });

        // Health check
        javalinServer.get("/system/health", ctx -> {
            ObjectNode health = objectMapper.createObjectNode();
            health.put("status", "UP");
            health.put("appName", Constants.APP_NAME);
            health.put("version", Constants.VERSION);

            ObjectNode servicesNode = objectMapper.createObjectNode();
            for (WebSocketServiceInterface svc : services) {
                servicesNode.put(svc.getClass().getSimpleName(), true);
            }
            health.set("services", servicesNode);

            ObjectNode connectionsNode = objectMapper.createObjectNode();
            for (Map.Entry<String, ConcurrentLinkedQueue<WsContext>> entry : socketChannelSubscriptions.entrySet()) {
                connectionsNode.put(entry.getKey(), entry.getValue().size());
            }
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

        // Install systemd service (Linux only)
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
            Path serviceFile = Path.of("/etc/systemd/system/" + serviceName);

            try {
                Files.writeString(serviceFile, serviceContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                ProcessBuilder daemonReload = new ProcessBuilder("systemctl", "daemon-reload");
                daemonReload.redirectErrorStream(true).start().waitFor();

                ProcessBuilder enable = new ProcessBuilder("systemctl", "enable", "--now", serviceName);
                enable.redirectErrorStream(true).start().waitFor();

                messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Service", "Systemd service installed and started")));

                ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"installed\", \"service\": \"" + serviceName + "\"}");
            } catch (Exception e) {
                log.error("Failed to install systemd service", e);
                ctx.status(500).result("{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        });

        // Uninstall systemd service (Linux only)
        javalinServer.post("/system/uninstall-service", ctx -> {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!os.contains("linux")) {
                ctx.status(400).result("{\"error\": \"Service uninstallation is only available on Linux\"}");
                return;
            }

            String serviceName = "local-hardware-bridge.service";

            try {
                ProcessBuilder disable = new ProcessBuilder("systemctl", "disable", "--now", serviceName);
                disable.redirectErrorStream(true).start().waitFor();

                Files.deleteIfExists(Path.of("/etc/systemd/system/" + serviceName));

                ProcessBuilder daemonReload = new ProcessBuilder("systemctl", "daemon-reload");
                daemonReload.redirectErrorStream(true).start().waitFor();

                messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Service", "Systemd service uninstalled")));

                ctx.contentType(ContentType.APPLICATION_JSON).result("{\"status\": \"uninstalled\"}");
            } catch (Exception e) {
                log.error("Failed to uninstall systemd service", e);
                ctx.status(500).result("{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        });
    }
}

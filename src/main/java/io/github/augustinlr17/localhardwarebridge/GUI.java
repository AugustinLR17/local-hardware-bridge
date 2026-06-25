package io.github.augustinlr17.localhardwarebridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.dtos.NotificationDTO;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServerInterface;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServiceInterface;
import io.github.augustinlr17.localhardwarebridge.services.ConfigService;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.util.Objects;
import java.util.Locale;

@Log4j2
public class GUI implements WebSocketServiceInterface {
    private static final ConfigService configService = ConfigService.getInstance();

    private final Server server = new Server();
    private Config config = configService.getConfig();

    Desktop desktop = Desktop.getDesktop();
    TrayIcon trayIcon;
    SystemTray tray;

    public static void main(String[] args) throws Exception {
        GUI gui = new GUI();
        gui.launch();
    }

    public void launch() throws Exception {
        server.start();

        // Create tray icon
        if (!SystemTray.isSupported()) {
            log.warn("SystemTray is not supported. Running in headless mode.");
            log.info("Web UI available at: {}", config.getServer().getUri());

            // Register service as notification listener even without tray
            if (config.getGui().getNotification().isEnabled()) {
                server.registerService(this);
            }

            // Keep process alive
            Thread.currentThread().join();
            return;
        }

        // Register service as notification listener
        if (config.getGui().getNotification().isEnabled()) {
            server.registerService(this);
        }

        MenuItem settingItem = new MenuItem("Web UI");
        settingItem.addActionListener(e -> {
            try {
                if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE)) {
                    throw new Exception("Desktop browse is not supported");
                }

                desktop.browse(new URI(config.getServer().getUri()));
            } catch (Exception ex) {
                log.error("Failed to open Web UI", ex);
            }
        });

        MenuItem appDirectoryItem = new MenuItem("App Directory");
        appDirectoryItem.addActionListener(e -> {
            try {
                if (desktop == null || !desktop.isSupported(Desktop.Action.OPEN)) {
                    throw new Exception("Desktop open is not supported");
                }

                desktop.open(new File("."));
            } catch (Exception ex) {
                log.error("Failed to open log folder", ex);
            }
        });

        MenuItem logDirectoryItem = new MenuItem("Log Directory");
        logDirectoryItem.addActionListener(e -> {
            try {
                if (desktop == null || !desktop.isSupported(Desktop.Action.OPEN)) {
                    throw new Exception("Desktop open is not supported");
                }

                desktop.open(new File("log"));
            } catch (Exception ex) {
                log.error("Failed to open log folder", ex);
            }
        });

        MenuItem restartItem = new MenuItem("Restart");
        restartItem.addActionListener(e -> restart());

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));

        // Add components to pop-up menu
        final PopupMenu popupMenu = new PopupMenu();
        popupMenu.add(settingItem);
        popupMenu.addSeparator();
        popupMenu.add(appDirectoryItem);
        popupMenu.add(logDirectoryItem);
        popupMenu.addSeparator();
        popupMenu.add(restartItem);
        popupMenu.add(exitItem);

        tray = SystemTray.getSystemTray();

        // Set icon
        Dimension trayIconSize = tray.getTrayIconSize();
        final Image image = ImageIO.read(Objects.requireNonNull(getClass().getClassLoader().getResource("icon.png")));
        final Image scaledImage = image.getScaledInstance(trayIconSize.width, trayIconSize.height, Image.SCALE_SMOOTH);

        trayIcon = new TrayIcon(scaledImage, Constants.APP_NAME);
        trayIcon.setPopupMenu(popupMenu);

        tray.add(trayIcon);

        notify(Constants.APP_NAME, " is running in background!", TrayIcon.MessageType.INFO);
    }

    public void notify(String title, String message, TrayIcon.MessageType messageType) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("linux")) {
                String urgency = switch (messageType) {
                    case ERROR -> "critical";
                    case WARNING -> "normal";
                    default -> "low";
                };
                ProcessBuilder pb = new ProcessBuilder("notify-send", "-u", urgency, title, message);
                pb.redirectErrorStream(true).start().waitFor();
            } else if (trayIcon != null) {
                trayIcon.displayMessage(title, message, messageType);
            } else {
                log.info("Notification: {} - {}", title, message);
            }
        } catch (Exception e) {
            log.warn("Notification fallback (display failed): {} - {}", title, message);
        }
    }

    public void restart() {
        try {
            config = configService.getConfig();

            server.stop();
            server.start();

            notify("Restart", "Server restarted successfully", TrayIcon.MessageType.INFO);
        } catch (Exception e) {
            log.error("Failed to restart server", e);
        }
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public void messageToService(String message) {
        try {
            log.debug("GUI Notification: {}", message);

            NotificationDTO notificationDTO = new ObjectMapper().readValue(message, NotificationDTO.class);
            notify(notificationDTO.getTitle(), notificationDTO.getMessage(), TrayIcon.MessageType.valueOf(notificationDTO.getType()));
        } catch (Exception e) {
            log.error("Failed to parse notification message", e);
        }
    }

    @Override
    public void messageToService(byte[] message) {
    }

    @Override
    public void onRegister(WebSocketServerInterface server) {

    }

    @Override
    public void onUnregister() {
    }

    @Override
    public String getChannel() {
        return "/notification";
    }
}

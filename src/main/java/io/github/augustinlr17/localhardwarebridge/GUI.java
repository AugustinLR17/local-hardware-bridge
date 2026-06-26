package io.github.augustinlr17.localhardwarebridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.dtos.NotificationDTO;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServerInterface;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServiceInterface;
import io.github.augustinlr17.localhardwarebridge.services.ConfigService;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;

@Log4j2
public class GUI implements WebSocketServiceInterface {
    private static final ConfigService configService = ConfigService.getInstance();

    private final Server server = new Server();
    private Config config = configService.getConfig();

    Desktop desktop = Desktop.getDesktop();
    TrayIcon trayIcon;
    SystemTray tray;

    public static void main(String[] args) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean forceServer = Boolean.getBoolean("lhb.server");
        boolean forceHeadless = Boolean.getBoolean("lhb.headless");

        if (forceServer) {
            Server.main(args);
            return;
        }

        // On Windows: if launched from a console (java.exe), try to re-spawn under javaw.exe
        // so the console window can close. This is a best-effort — if javaw is not available
        // or we're already under javaw, we just proceed normally.
        if (os.contains("windows") && !forceHeadless) {
            // System.console() returns null when running under javaw.exe (no console window)
            // If it returns non-null, we have a console attached → re-spawn under javaw
            if (System.console() != null) {
                String javawExe = System.getProperty("java.home") + "\\bin\\javaw.exe";
                if (new File(javawExe).exists()) {
                    String classpath = System.getProperty("java.class.path");
                    String workingDir = System.getProperty("user.dir");
                    ProcessBuilder pb = new ProcessBuilder(
                        javawExe,
                        "-cp", classpath,
                        "io.github.augustinlr17.localhardwarebridge.GUI"
                    );
                    pb.directory(new File(workingDir));
                    // Discard all output — javaw process runs silently in background
                    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                    pb.start();
                    // Exit the console process immediately
                    System.exit(0);
                    return; // unreachable but explicit
                }
            }
        }

        GUI gui = new GUI();
        gui.launch();
    }

    public void launch() throws Exception {
        server.start();

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        // On Linux, offer to install systemd service for auto-start
        if (os.contains("linux")) {
            offerLinuxServiceInstall();
            runHeadlessNotificationLoop();
            return;
        }

        // On macOS, offer to install launchd service for auto-start
        if (os.contains("mac")) {
            offerMacOSServiceInstall();
        }

        // Create tray icon (works on Windows and macOS with system tray)
        if (!SystemTray.isSupported()) {
            log.warn("SystemTray is not supported. Running in headless mode.");
            log.info("Web UI available at: {}", config.getServer().getUri());

            if (config.getGui().getNotification().isEnabled()) {
                server.registerService(this);
            }

            Thread.currentThread().join();
            return;
        }

        if (config.getGui().getNotification().isEnabled()) {
            server.registerService(this);
        }

        // On Windows, register auto-start in registry (more reliable than Startup folder shortcut)
        if (os.contains("windows")) {
            registerWindowsAutoStart();
        }

        MenuItem settingItem = new MenuItem("Web UI");
        settingItem.addActionListener(e -> {
            try {
                if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(new URI(config.getServer().getUri()));
                }
            } catch (Exception ex) {
                log.error("Failed to open Web UI", ex);
            }
        });

        MenuItem appDirectoryItem = new MenuItem("App Directory");
        appDirectoryItem.addActionListener(e -> {
            try {
                if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(new File("."));
                }
            } catch (Exception ex) {
                log.error("Failed to open app directory", ex);
            }
        });

        MenuItem logDirectoryItem = new MenuItem("Log Directory");
        logDirectoryItem.addActionListener(e -> {
            try {
                if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(new File("log"));
                }
            } catch (Exception ex) {
                log.error("Failed to open log folder", ex);
            }
        });

        MenuItem restartItem = new MenuItem("Restart");
        restartItem.addActionListener(e -> restart());

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));

        final PopupMenu popupMenu = new PopupMenu();
        popupMenu.add(settingItem);
        popupMenu.addSeparator();
        popupMenu.add(appDirectoryItem);
        popupMenu.add(logDirectoryItem);
        popupMenu.addSeparator();
        popupMenu.add(restartItem);
        popupMenu.add(exitItem);

        tray = SystemTray.getSystemTray();

        Dimension trayIconSize = tray.getTrayIconSize();
        final Image image = ImageIO.read(Objects.requireNonNull(getClass().getClassLoader().getResource("icon.png")));
        final Image scaledImage = image.getScaledInstance(trayIconSize.width, trayIconSize.height, Image.SCALE_SMOOTH);

        trayIcon = new TrayIcon(scaledImage, Constants.APP_NAME);
        trayIcon.setPopupMenu(popupMenu);
        trayIcon.setImageAutoSize(true);

        tray.add(trayIcon);

        notify(Constants.APP_NAME, " is running in background!", TrayIcon.MessageType.INFO);
    }

    /**
     * Register auto-start on Windows via HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Run
     * This is more reliable than a Startup folder shortcut and persists across reboots.
     */
    private void registerWindowsAutoStart() {
        try {
            String appPath = getApplicationPath();
            if (appPath == null) {
                log.warn("Could not determine application path for auto-start registration");
                return;
            }

            // Use reg.exe to add the auto-start entry in HKCU\...\Run
            // This is the standard Windows mechanism for auto-starting applications
            ProcessBuilder pb = new ProcessBuilder(
                "reg", "add",
                "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", Constants.APP_NAME,
                "/d", appPath,
                "/f"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                log.info("Registered auto-start in Windows registry");
            } else {
                log.warn("Failed to register auto-start (reg exit code: {})", exitCode);
            }
        } catch (Exception e) {
            log.warn("Failed to register auto-start: {}", e.getMessage());
        }
    }

    /**
     * Get the command line to launch this application, for auto-start registration.
     */
    private String getApplicationPath() {
        try {
            // If running from a JAR, use javaw -cp jar GUI
            String classpath = System.getProperty("java.class.path");
            String javaHome = System.getProperty("java.home");
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

            String javaExec;
            if (os.contains("windows")) {
                javaExec = javaHome + "\\bin\\javaw.exe";
            } else {
                javaExec = javaHome + "/bin/java";
            }

            // If the classpath is a single JAR file, build a direct command
            if (!classpath.contains(File.pathSeparator)) {
                File jarFile = new File(classpath);
                if (jarFile.exists()) {
                    return "\"" + javaExec + "\" -cp \"" + jarFile.getAbsolutePath() + "\" io.github.augustinlr17.localhardwarebridge.GUI";
                }
            }

            // Otherwise, use the full classpath
            return "\"" + javaExec + "\" -cp \"" + classpath + "\" io.github.augustinlr17.localhardwarebridge.GUI";
        } catch (Exception e) {
            log.warn("Failed to determine application path", e);
            return null;
        }
    }

    private void offerLinuxServiceInstall() {
        try {
            Path serviceFile = Paths.get("/etc/systemd/system/local-hardware-bridge.service");
            Path legacyServiceFile = Paths.get("/etc/systemd/system/webapp-hardware-bridge.service");
            boolean installed = Files.exists(serviceFile);
            boolean legacyInstalled = Files.exists(legacyServiceFile);
            String installedVersion = null;

            if (installed) {
                String content = Files.readString(serviceFile);
                int idx = content.indexOf("# LHB_VERSION=");
                if (idx >= 0) {
                    int end = content.indexOf('\n', idx);
                    if (end < 0) end = content.length();
                    installedVersion = content.substring(idx + 14, end).trim();
                }
            }

            // Offer to migrate from legacy service name
            if (legacyInstalled && !installed) {
                int choice = JOptionPane.showConfirmDialog(
                    null,
                    "An existing \"webapp-hardware-bridge\" service was detected.\n"
                        + "Migrate to the new \"local-hardware-bridge\" service?\n"
                        + "(The old service will be stopped and removed.)",
                    Constants.APP_NAME + " - Service Migration",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );
                if (choice == JOptionPane.YES_OPTION) {
                    // Stop and remove legacy service first
                    new ProcessBuilder("pkexec", "systemctl", "disable", "--now", "webapp-hardware-bridge.service")
                        .redirectErrorStream(true).start().waitFor();
                    Files.deleteIfExists(legacyServiceFile);
                    new ProcessBuilder("pkexec", "systemctl", "daemon-reload")
                        .redirectErrorStream(true).start().waitFor();
                    // Then install new service
                    installLinuxService();
                }
                return;
            }

            int choice;
            if (!installed) {
                choice = JOptionPane.showConfirmDialog(
                    null,
                    "Install Local Hardware Bridge as a systemd service so it starts automatically?\n"
                        + "This requires administrator rights.",
                    Constants.APP_NAME + " - Install Service",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );
            } else if (!Constants.VERSION.equals(installedVersion)) {
                choice = JOptionPane.showConfirmDialog(
                    null,
                    "Service v" + (installedVersion == null ? "?" : installedVersion) + " is installed.\n"
                        + "Update to v" + Constants.VERSION + "?",
                    Constants.APP_NAME + " - Update Service",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );
            } else {
                return;
            }

            if (choice == JOptionPane.YES_OPTION) {
                installLinuxService();
            }
        } catch (Exception e) {
            log.error("Failed to check/install Linux service", e);
        }
    }

    private void offerMacOSServiceInstall() {
        // macOS: register as login item via LaunchAgent plist
        try {
            String home = System.getProperty("user.home");
            Path plistPath = Paths.get(home + "/Library/LaunchAgents/io.github.augustinlr17.localhardwarebridge.plist");
            if (Files.exists(plistPath)) {
                return; // Already installed
            }

            int choice = JOptionPane.showConfirmDialog(
                null,
                "Install Local Hardware Bridge as a login item so it starts automatically?",
                Constants.APP_NAME + " - Install Service",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            );

            if (choice == JOptionPane.YES_OPTION) {
                String classpath = System.getProperty("java.class.path");
                String javaHome = System.getProperty("java.home");
                String workingDir = System.getProperty("user.dir");
                String javaExec = javaHome + "/bin/java";

                String plistContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                    + "<plist version=\"1.0\">\n<dict>\n"
                    + "    <key>Label</key>\n"
                    + "    <string>io.github.augustinlr17.localhardwarebridge</string>\n"
                    + "    <key>ProgramArguments</key>\n"
                    + "    <array>\n"
                    + "        <string>" + javaExec + "</string>\n"
                    + "        <string>-cp</string>\n"
                    + "        <string>" + classpath + "</string>\n"
                    + "        <string>io.github.augustinlr17.localhardwarebridge.GUI</string>\n"
                    + "    </array>\n"
                    + "    <key>WorkingDirectory</key>\n"
                    + "    <string>" + workingDir + "</string>\n"
                    + "    <key>RunAtLoad</key>\n"
                    + "    <true/>\n"
                    + "    <key>KeepAlive</key>\n"
                    + "    <true/>\n"
                    + "</dict>\n</plist>";

                Files.writeString(plistPath, plistContent);
                log.info("Installed macOS LaunchAgent at {}", plistPath);
            }
        } catch (Exception e) {
            log.error("Failed to install macOS service", e);
        }
    }

    private void installLinuxService() {
        try {
            String jarPath = Paths.get(GUI.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().toString();
            String workingDir = System.getProperty("user.dir");
            String javaExec = System.getProperty("java.home") + "/bin/java";

            String serviceContent = "[Unit]\n"
                + "# LHB_VERSION=" + Constants.VERSION + "\n"
                + "Description=Local Hardware Bridge\n"
                + "After=network.target\n\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "ExecStart=" + javaExec + " -cp " + jarPath + " io.github.augustinlr17.localhardwarebridge.GUI\n"
                + "WorkingDirectory=" + workingDir + "\n"
                + "Restart=on-failure\n"
                + "RestartSec=5\n\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n";

            Path tempFile = Files.createTempFile("local-hardware-bridge", ".service");
            Files.writeString(tempFile, serviceContent, StandardOpenOption.TRUNCATE_EXISTING);

            ProcessBuilder copy = new ProcessBuilder("pkexec", "cp", tempFile.toString(), "/etc/systemd/system/local-hardware-bridge.service");
            copy.inheritIO().start().waitFor();

            ProcessBuilder daemonReload = new ProcessBuilder("pkexec", "systemctl", "daemon-reload");
            daemonReload.inheritIO().start().waitFor();

            ProcessBuilder enable = new ProcessBuilder("pkexec", "systemctl", "enable", "--now", "local-hardware-bridge.service");
            enable.inheritIO().start().waitFor();

            Files.deleteIfExists(tempFile);
            notify(Constants.APP_NAME, "Service installed and started", TrayIcon.MessageType.INFO);
        } catch (Exception e) {
            log.error("Failed to install Linux service", e);
            notify(Constants.APP_NAME, "Service installation failed: " + e.getMessage(), TrayIcon.MessageType.ERROR);
        }
    }

    private void runHeadlessNotificationLoop() {
        log.info("SystemTray is not used on Linux. Running with libnotify/osascript notifications.");
        log.info("Web UI available at: {}", config.getServer().getUri());

        if (config.getGui().getNotification().isEnabled()) {
            server.registerService(this);
        }

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void notify(String title, String message, TrayIcon.MessageType messageType) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("linux")) {
                // Use notify-send (libnotify) on Linux
                String urgency = switch (messageType) {
                    case ERROR -> "critical";
                    case WARNING -> "normal";
                    default -> "low";
                };
                ProcessBuilder pb = new ProcessBuilder("notify-send", "-u", urgency, title, message);
                pb.redirectErrorStream(true).start().waitFor();
            } else if (os.contains("mac")) {
                // Use osascript for native macOS notifications
                String escapedMessage = message.replace("\\", "\\\\").replace("\"", "\\\"");
                String escapedTitle = title.replace("\\", "\\\\").replace("\"", "\\\"");
                String script;
                if (messageType == TrayIcon.MessageType.ERROR) {
                    script = "display notification \"" + escapedMessage + "\" with title \"" + escapedTitle + "\" sound name \"Sosumi\"";
                } else {
                    script = "display notification \"" + escapedMessage + "\" with title \"" + escapedTitle + "\"";
                }
                ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
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
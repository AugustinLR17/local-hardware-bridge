package io.github.augustinlr17.localhardwarebridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.dtos.NotificationDTO;
import io.github.augustinlr17.localhardwarebridge.dtos.UpdateStatusDTO;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServerInterface;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServiceInterface;
import io.github.augustinlr17.localhardwarebridge.services.ConfigService;
import io.github.augustinlr17.localhardwarebridge.services.UpdateService;
import io.github.augustinlr17.localhardwarebridge.utils.LaunchdPlistGenerator;
import io.github.augustinlr17.localhardwarebridge.utils.SingleInstanceGuard;
import io.github.augustinlr17.localhardwarebridge.utils.SystemdServiceGenerator;
import io.github.augustinlr17.localhardwarebridge.utils.ThreadUtil;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;

@Log4j2
public class GUI implements WebSocketServiceInterface {
    private static final ConfigService configService = ConfigService.getInstance();

    // System property keys (avoid duplicated literals — java:S1192)
    private static final String OS_NAME_PROP = "os.name";
    private static final String JAVA_HOME_PROP = "java.home";
    private static final String USER_DIR_PROP = "user.dir";
    private static final String PKEXEC = "pkexec";
    private static final String SYSTEMCTL = "systemctl";

    private final Server server = new Server();
    private Config config = configService.getConfig();

    Desktop desktop = Desktop.getDesktop();
    TrayIcon trayIcon;
    SystemTray tray;

    public static void main(String[] args) throws Exception {
        // Defensive: the Launcher already anchors before this class loads, but anchor
        // again in case GUI is used as a direct entry point (e.g. running the JAR with
        // -cp). Anchoring is idempotent and a no-op outside a packaged JAR.
        AppHome.anchor();

        String os = System.getProperty(OS_NAME_PROP, "").toLowerCase(Locale.ROOT);
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
                String javawExe = System.getProperty(JAVA_HOME_PROP) + "\\bin\\javaw.exe";
                if (new File(javawExe).exists()) {
                    String classpath = System.getProperty("java.class.path");
                    String workingDir = System.getProperty(USER_DIR_PROP);
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
        // Check if another instance is already running on the configured port.
        // If it is our app, offer to stop it and take over.
        Config.Server serverConfig = config.getServer();
        String bind = serverConfig.getBind();
        int port = serverConfig.getPort();

        if (SingleInstanceGuard.isAlreadyRunning(bind, port)) {
            if (SingleInstanceGuard.isOurApp(bind, port)) {
                String version = System.getProperty("lhb.server") != null ? "Server" : "GUI";
                int choice = JOptionPane.showConfirmDialog(
                    null,
                    "Another " + Constants.APP_NAME + " instance is already running on port " + port + ".\n"
                        + "Do you want to stop it and start this one instead?",
                    Constants.APP_NAME + " - Instance Already Running",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );
                if (choice == JOptionPane.YES_OPTION) {
                    log.info("Stopping existing instance on {}:{}", bind, port);
                    String token = serverConfig.getAuthentication().isEnabled() ? serverConfig.getAuthentication().getToken() : null;
                    if (!SingleInstanceGuard.stopInstance(bind, port, token)) {
                        log.error("Could not stop existing instance on port {}", port);
                        JOptionPane.showMessageDialog(
                            null,
                            "Could not stop the existing instance on port " + port + ".\n"
                                + "Please close it manually and try again.",
                            Constants.APP_NAME + " - Error",
                            JOptionPane.ERROR_MESSAGE
                        );
                        System.exit(1);
                    }
                    log.info("Port {} is now free, starting new instance", port);
                } else {
                    log.info("User chose not to stop existing instance, exiting");
                    System.exit(0);
                }
            } else {
                // Port is occupied by something that is not our app
                JOptionPane.showMessageDialog(
                    null,
                    "Port " + port + " is already in use by another application.\n"
                        + "Please change the port in the config or close the other application.",
                    Constants.APP_NAME + " - Port In Use",
                    JOptionPane.WARNING_MESSAGE
                );
                System.exit(1);
            }
        }

        server.start();

        String os = System.getProperty(OS_NAME_PROP, "").toLowerCase(Locale.ROOT);

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
                server.registerPersistentService(this);
            }

            Thread.currentThread().join();
            return;
        }

        if (config.getGui().getNotification().isEnabled()) {
            server.registerPersistentService(this);
        }

        // Windows auto-start is handled by the installer (HKCU\...\Run pointing at the
        // bundled launcher). Registering it from the running JVM is unreliable and flashes
        // a console window, so it is intentionally not done here.

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

        MenuItem checkUpdateItem = new MenuItem("Check for Updates");
        checkUpdateItem.addActionListener(e -> checkForUpdatesInteractive());

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));

        final PopupMenu popupMenu = new PopupMenu();
        popupMenu.add(settingItem);
        popupMenu.addSeparator();
        popupMenu.add(appDirectoryItem);
        popupMenu.add(logDirectoryItem);
        popupMenu.addSeparator();
        popupMenu.add(checkUpdateItem);
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

        // Perform a silent background update check after a short delay.
        // The result is shown as a tray notification only if an update is available.
        scheduleSilentUpdateCheck();
    }

    /**
     * Checks for updates in a background thread. If an update is available,
     * displays a tray notification. This is silent (no notification) when
     * the app is already up to date.
     */
    private void scheduleSilentUpdateCheck() {
        Config.Update updateConfig = configService.getConfig().getUpdate();
        if (!updateConfig.isEnabled()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(8000); // Wait 8s after startup before checking
                UpdateStatusDTO status = UpdateService.getInstance().checkNow();
                if (status.isUpdateAvailable()) {
                    notify(Constants.APP_NAME,
                            "Update " + status.getLatestVersion() + " is available! Click \"Check for Updates\" to install.",
                            TrayIcon.MessageType.INFO);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("Silent update check failed: {}", e.getMessage());
            }
        }, "gui-update-check");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Interactive update check: shows a dialog with the result and, if an
     * update is available, offers to download and install it.
     */
    private void checkForUpdatesInteractive() {
        Thread t = new Thread(() -> {
            try {
                notify(Constants.APP_NAME, "Checking for updates...", TrayIcon.MessageType.INFO);

                UpdateStatusDTO status = UpdateService.getInstance().checkNow();

                if (status.getError() != null) {
                    notify(Constants.APP_NAME, "Update check failed: " + status.getError(), TrayIcon.MessageType.ERROR);
                    return;
                }

                if (!status.isUpdateAvailable()) {
                    notify(Constants.APP_NAME, "Already up to date (v" + Constants.VERSION + ")", TrayIcon.MessageType.INFO);
                    return;
                }

                // An update is available
                String message = "A new version is available!\n"
                        + "Current: v" + Constants.VERSION + "\n"
                        + "Latest:  v" + status.getLatestVersion()
                        + (status.isPrerelease() ? " (pre-release)" : "") + "\n\n"
                        + "Download and install now?\n"
                        + "The app will restart automatically after installation.";

                int choice = showConfirmDialog(
                        message,
                        Constants.APP_NAME + " - Update Available",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );

                if (choice == JOptionPane.YES_OPTION) {
                    notify(Constants.APP_NAME, "Downloading update...", TrayIcon.MessageType.INFO);
                    UpdateService.getInstance().downloadUpdate();
                    notify(Constants.APP_NAME, "Update downloaded. Applying and restarting...", TrayIcon.MessageType.WARNING);

                    // Apply via the server's update endpoint (triggers async restart)
                    java.nio.file.Path pending = UpdateService.getInstance().consumePendingUpdate();
                    if (pending != null) {
                        try {
                            server.stop();
                            UpdateService.getInstance().applyUpdate(pending);
                            UpdateService.getInstance().cleanupOldUpdates();
                            ThreadUtil.silentSleep(500);
                            server.start();
                            notify(Constants.APP_NAME, "Update applied successfully! Now running v" + Constants.VERSION, TrayIcon.MessageType.INFO);
                        } catch (Exception e) {
                            log.error("Failed to apply update", e);
                            notify(Constants.APP_NAME, "Update failed: " + e.getMessage() + ". Attempting rollback...", TrayIcon.MessageType.ERROR);
                            try {
                                UpdateService.getInstance().rollback();
                                server.start();
                                notify(Constants.APP_NAME, "Rollback complete. Still running v" + Constants.VERSION, TrayIcon.MessageType.WARNING);
                            } catch (Exception rollbackEx) {
                                log.error("Rollback failed", rollbackEx);
                                notify(Constants.APP_NAME, "Rollback also failed! Manual intervention required.", TrayIcon.MessageType.ERROR);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Interactive update check failed", e);
                notify(Constants.APP_NAME, "Update check failed: " + e.getMessage(), TrayIcon.MessageType.ERROR);
            }
        }, "gui-update-interactive");
        t.setDaemon(true);
        t.start();
    }

    private void offerLinuxServiceInstall() {
        try {
            Path serviceFile = Paths.get("/etc/systemd/system/local-hardware-bridge.service");
            Path legacyServiceFile = Paths.get("/etc/systemd/system/webapp-hardware-bridge.service");
            boolean installed = SystemdServiceGenerator.isServiceInstalled();
            boolean legacyInstalled = Files.exists(legacyServiceFile);
            String installedVersion = null;

            if (installed) {
                try {
                    String content = Files.readString(serviceFile);
                    installedVersion = SystemdServiceGenerator.extractVersionFromUnit(content);
                } catch (java.nio.file.AccessDeniedException ade) {
                    // File is root-owned (installed via pkexec) — can't read it
                    // as a normal user. Treat as "installed, version unknown".
                    log.debug("Cannot read service file (root-owned): {}", ade.getMessage());
                }
            }

            // Offer to migrate from legacy service name
            if (legacyInstalled && !installed) {
                int choice = showConfirmDialog(
                    "An existing \"webapp-hardware-bridge\" service was detected.\n"
                        + "Migrate to the new \"local-hardware-bridge\" service?\n"
                        + "(The old service will be stopped and removed.)",
                    Constants.APP_NAME + " - Service Migration",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );
                if (choice == JOptionPane.YES_OPTION) {
                    // Stop and remove legacy service, then install new one.
                    // Single pkexec for the disable + daemon-reload — installLinuxService does its own.
                    Path legacyScript = Files.createTempFile("lhb-legacy", ".sh");
                    Files.writeString(legacyScript,
                        "#!/bin/sh\nsystemctl disable --now webapp-hardware-bridge.service 2>/dev/null || true\nrm -f /etc/systemd/system/webapp-hardware-bridge.service\nsystemctl daemon-reload\n");
                    legacyScript.toFile().setExecutable(true);
                    new ProcessBuilder(PKEXEC, "sh", legacyScript.toString())
                        .redirectErrorStream(true).start().waitFor();
                    Files.deleteIfExists(legacyScript);
                    installLinuxService();
                }
                return;
            }

            int choice;
            if (!installed) {
                choice = showConfirmDialog(
                    "Install Local Hardware Bridge as a systemd service so it starts automatically?\n"
                        + "This requires administrator rights.",
                    Constants.APP_NAME + " - Install Service",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );
            } else if (!Constants.VERSION.equals(installedVersion)) {
                choice = showConfirmDialog(
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

            int choice = showConfirmDialog(
                "Install Local Hardware Bridge as a login item so it starts automatically?",
                Constants.APP_NAME + " - Install Service",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            );

            if (choice == JOptionPane.YES_OPTION) {
                String classpath = System.getProperty("java.class.path");
                String javaHome = System.getProperty(JAVA_HOME_PROP);
                String workingDir = System.getProperty(USER_DIR_PROP);
                String javaExec = javaHome + "/bin/java";

                String plistContent = LaunchdPlistGenerator.generatePlist(
                    javaExec, classpath, workingDir);

                Files.writeString(plistPath, plistContent);
                log.info("Installed macOS LaunchAgent at {}", plistPath);
            }
        } catch (Exception e) {
            log.error("Failed to install macOS service", e);
        }
    }

    /**
     * Shows a confirm dialog with a parent frame so it has a proper size on
     * Linux (JOptionPane with null parent renders minuscule on some WMs).
     */
    private int showConfirmDialog(String message, String title, int optionType, int messageType) {
        JFrame frame = new JFrame(title);
        frame.setUndecorated(true);
        frame.setSize(0, 0);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        int choice = JOptionPane.showConfirmDialog(frame, message, title, optionType, messageType);
        frame.dispose();
        return choice;
    }

    private void installLinuxService() {
        try {
            String jarPath = Paths.get(GUI.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().toString();
            String javaExec = System.getProperty(JAVA_HOME_PROP) + "/bin/java";

            String installDir = SystemdServiceGenerator.getInstallDir();
            String installedJarPath = SystemdServiceGenerator.getInstalledJarPath();

            String serviceContent = SystemdServiceGenerator.generateServiceUnit(
                javaExec, jarPath, installDir, Constants.VERSION);

            Path tempFile = Files.createTempFile("local-hardware-bridge", ".service");
            restrictTempFilePermissions(tempFile);
            Files.writeString(tempFile, serviceContent, StandardOpenOption.TRUNCATE_EXISTING);

            Path tempJar = Files.createTempFile("local-hardware-bridge-jar", ".jar");
            Files.copy(Path.of(jarPath), tempJar, StandardCopyOption.REPLACE_EXISTING);

            // Single pkexec call that does everything — only ONE password prompt.
            // Writes a temp shell script and runs it via pkexec sh.
            Path script = Files.createTempFile("lhb-install", ".sh");
            String scriptContent = "#!/bin/sh\n"
                + "set -e\n"
                + "mkdir -p " + shellQuote(installDir) + "\n"
                + "cp -f " + shellQuote(tempJar.toString()) + " " + shellQuote(installedJarPath) + "\n"
                + "cp -f " + shellQuote(tempFile.toString()) + " " + shellQuote("/etc/systemd/system/local-hardware-bridge.service") + "\n"
                + "systemctl daemon-reload\n"
                + "systemctl enable --now local-hardware-bridge.service\n"
                + "rm -f " + shellQuote(tempFile.toString()) + " " + shellQuote(tempJar.toString()) + "\n";
            Files.writeString(script, scriptContent);
            script.toFile().setExecutable(true);

            ProcessBuilder pb = new ProcessBuilder(PKEXEC, "sh", script.toString());
            pb.inheritIO().start().waitFor();

            Files.deleteIfExists(script);
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempJar);
            notify(Constants.APP_NAME, "Service installed and started", TrayIcon.MessageType.INFO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notify(Constants.APP_NAME, "Service installation interrupted", TrayIcon.MessageType.WARNING);
        } catch (Exception e) {
            log.error("Failed to install Linux service", e);
            notify(Constants.APP_NAME, "Service installation failed: " + e.getMessage(), TrayIcon.MessageType.ERROR);
        }
    }

    private void runHeadlessNotificationLoop() {
        log.info("SystemTray is not used on Linux. Running with libnotify/osascript notifications.");
        log.info("Web UI available at: {}", config.getServer().getUri());

        if (config.getGui().getNotification().isEnabled()) {
            server.registerPersistentService(this);
        }

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void notify(String title, String message, TrayIcon.MessageType messageType) {
        try {
            String os = System.getProperty(OS_NAME_PROP, "").toLowerCase(Locale.ROOT);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
    public void start() { /* no-op: GUI has no startup beyond launch() */ }

    @Override
    public void stop() { /* no-op: GUI has no shutdown beyond System.exit */ }

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
    public void messageToService(byte[] message) { /* no-op: GUI only handles text notifications */ }

    @Override
    public void onRegister(WebSocketServerInterface server) { /* no-op */ }

    @Override
    public void onUnregister() { /* no-op */ }

    @Override
    public String getChannel() {
        return "/notification";
    }

    private void restrictTempFilePermissions(Path tempFile) {
        try {
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(tempFile,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception e) {
            File f = tempFile.toFile();
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
        }
    }

    /** Single-quotes a path for safe inclusion in a shell script. */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
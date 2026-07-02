package io.github.augustinlr17.localhardwarebridge;

import java.io.File;

/**
 * Anchors the process working directory to the install location.
 *
 * <p>The application reads and writes {@code config.json}, {@code log/} and {@code tls/}
 * using paths relative to {@code user.dir}. When the app is launched from a Start Menu
 * shortcut or auto-started via {@code HKCU\...\Run}, the working directory is typically
 * {@code C:\Windows\system32}, where it can neither load its config nor write logs.
 *
 * <p>The JDK resolves relative {@link File} paths against the {@code user.dir} system
 * property <em>dynamically</em>, so overriding it before any config is read or logger is
 * initialised fixes resolution for every launch method.
 *
 * <p>This class intentionally has no logging or config dependencies, so that
 * {@link #anchor()} can run before those subsystems initialise.
 */
public final class AppHome {

    private AppHome() {
    }

    /**
     * Set {@code user.dir} to the directory containing the running JAR.
     * No-op when running from exploded classes (development / IDE), which keeps
     * the project directory as the working directory.
     *
     * <p>When packaged with jpackage, the JAR lives in an {@code app/}
     * sub-directory of the install folder. The config, logs, and TLS
     * certificates sit in the install folder (parent of {@code app/}),
     * so we walk up one level when the JAR is inside an {@code app/}
     * directory.
     */
    public static void anchor() {
        try {
            // Strategy 1: protection domain code source (works for java -jar)
            File resolved = resolveViaProtectionDomain();
            // Strategy 2: java.class.path (works for jpackage — the launcher
            // sets java.class.path to the app JAR path)
            if (resolved == null) {
                resolved = resolveViaClassPath();
            }
            // Strategy 3: java.home (jpackage bundles a JRE in <install>/runtime;
            // derive <install> from java.home as last resort)
            if (resolved == null) {
                resolved = resolveViaJavaHome();
            }
            // Strategy 4: AppImage mount is read-only — use a persistent home dir.
            // AppImages mount at /tmp/.mount_<name> on Linux; the config/logs/tls
            // can't live inside the squashfs. Fall back to ~/.local/share/<app>.
            if (resolved != null && isAppImageMount(resolved)) {
                File homeConfigDir = getAppDataDir();
                if (homeConfigDir != null) {
                    ensureDir(homeConfigDir);
                    // Migrate: if no config exists in the XDG dir but one exists in
                    // the systemd service dir (/opt/local-hardware-bridge/), copy it.
                    migrateExistingConfig(homeConfigDir);
                    resolved = homeConfigDir;
                }
            }
            if (resolved != null) {
                System.setProperty("user.dir", resolved.getAbsolutePath());
            }
        } catch (Exception e) {
            // Best effort: fall back to the launch working directory.
        }
    }

    static File resolveViaProtectionDomain() {
        try {
            var location = AppHome.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            return resolveInstallDir(new File(location.toURI()));
        } catch (Exception e) {
            return null;
        }
    }

    static File resolveViaClassPath() {
        try {
            String classPath = System.getProperty("java.class.path");
            if (classPath == null || classPath.isBlank()) {
                return null;
            }
            // jpackage sets java.class.path to ONLY the app JAR path, e.g.
            // C:\Users\...\Local Hardware Bridge\app\bridge.jar
            // In other contexts (IDE, gradle test), java.class.path contains
            // many entries (dependencies, test classes, etc.). We only use
            // this strategy when there is exactly one classpath entry, which
            // is the jpackage signature.
            String[] entries = classPath.split(File.pathSeparator);
            if (entries.length != 1) {
                return null;
            }
            return resolveInstallDir(new File(entries[0]));
        } catch (Exception e) {
            // NOOP
        }
        return null;
    }

    static File resolveViaJavaHome() {
        try {
            String javaHome = System.getProperty("java.home");
            if (javaHome == null || javaHome.isBlank()) {
                return null;
            }
            // jpackage layout: <install>/runtime/bin  (or <install>/runtime)
            // We need to find <install>, which is 1-2 levels up from java.home.
            File home = new File(javaHome).getAbsoluteFile();
            // Walk up: runtime/bin → runtime → <install>
            // or:     runtime → <install>
            File runtimeDir = home;
            // If we're in runtime/bin, go up to runtime
            if ("bin".equals(runtimeDir.getName())) {
                runtimeDir = runtimeDir.getParentFile();
            }
            // If we're in runtime, go up to <install>
            if (runtimeDir != null && "runtime".equals(runtimeDir.getName())) {
                File installDir = runtimeDir.getParentFile();
                if (installDir != null && installDir.isDirectory()) {
                    // Verify this looks like the install dir by checking for
                    // the app/ sub-directory or the launcher exe
                    if (new File(installDir, "app").isDirectory()) {
                        return installDir;
                    }
                }
            }
        } catch (Exception e) {
            // NOOP
        }
        return null;
    }

    /**
     * Given the code source file (JAR or exploded classes), returns the
     * directory that should be used as {@code user.dir}.
     *
     * <p>For jpackage layout ({@code <install>/app/<jar>}), returns
     * {@code <install>}. For flat layout ({@code <dir>/<jar>}), returns
     * {@code <dir>}. Returns {@code null} for exploded classes (no-op).
     */
    static File resolveInstallDir(File codeSource) {
        if (codeSource == null || !codeSource.isFile() || !codeSource.getName().endsWith(".jar")) {
            return null;
        }
        File appDir = codeSource.getParentFile();
        if (appDir != null && appDir.isDirectory()) {
            // jpackage layout: <install_dir>/app/<jar> — config.json is in <install_dir>
            if ("app".equals(appDir.getName())) {
                File installDir = appDir.getParentFile();
                if (installDir != null && installDir.isDirectory()) {
                    return installDir;
                }
            }
            // Flat layout (shadow JAR, manual java -jar)
            return appDir;
        }
        return null;
    }

    /**
     * Detects whether the resolved path is inside an AppImage mount point
     * (e.g. {@code /tmp/.mount_local-XXXXX/...} on Linux).
     */
    static boolean isAppImageMount(File dir) {
        if (dir == null) return false;
        String path = dir.getAbsolutePath();
        // AppImages mount at /tmp/.mount_<name> on Linux
        return path.contains("/.mount_") || path.contains("/.AppImage_");
    }

    /**
     * Returns a writable per-user data directory for the application.
     * Uses XDG_DATA_HOME on Linux, APPDATA on Windows, ~/Library/Application Support on macOS.
     */
    static File getAppDataDir() {
        String appName = "local-hardware-bridge";
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("windows")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                return new File(appData, appName);
            }
        } else if (os.contains("mac")) {
            String home = System.getProperty("user.home");
            if (home != null) {
                return new File(home, "Library/Application Support/" + appName);
            }
        } else {
            // Linux / Unix — respect XDG_DATA_HOME
            String xdgData = System.getenv("XDG_DATA_HOME");
            if (xdgData != null && !xdgData.isEmpty()) {
                return new File(xdgData, appName);
            }
            String home = System.getProperty("user.home");
            if (home != null) {
                return new File(home, ".local/share/" + appName);
            }
        }
        return null;
    }

    private static void ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            // Best effort — caller will handle write failures
        }
    }

    /**
     * If no config.json exists in the target dir, tries to copy one from known
     * locations (systemd service dir, CWD). This ensures AppImage users don't
     * lose their existing config on first launch.
     */
    private static void migrateExistingConfig(File targetDir) {
        File targetConfig = new File(targetDir, "config.json");
        if (targetConfig.exists()) {
            return; // Already have a config
        }
        // Try systemd service install location
        File[] candidates = {
            new File("/opt/local-hardware-bridge/config.json"),
            new File("config.json"),
            new File(System.getProperty("user.home", ""), "config.json")
        };
        for (File src : candidates) {
            if (src.exists() && src.isFile() && src.canRead()) {
                try {
                    java.nio.file.Files.copy(src.toPath(), targetConfig.toPath());
                } catch (Exception e) {
                    // Best effort — ConfigService will create defaults if this fails
                }
                return;
            }
        }
    }
}

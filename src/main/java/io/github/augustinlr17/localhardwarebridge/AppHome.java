package io.github.augustinlr17.localhardwarebridge;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the absolute base directory for all mutable application state.
 *
 * <p>The application reads and writes {@code config.json}, {@code log/},
 * {@code tls/}, {@code downloads/} and {@code updates/}. These used to be
 * relative paths resolved against {@code user.dir}, re-pointed at startup by
 * overriding the {@code user.dir} system property. That never actually worked:
 * {@code java.io.WinNTFileSystem}/{@code UnixFileSystem} cache the process
 * working directory at initialisation, so a late {@code user.dir} override is
 * silently ignored. Relative paths therefore kept resolving against the real
 * launch directory — fine for the Start Menu shortcut (whose "Start in" is the
 * install dir) but broken for the {@code HKCU\...\Run} auto-start, which launches
 * with {@code C:\Windows\System32} as the working directory: the app could not
 * find its config (looked "lost") nor create {@code downloads/}.
 *
 * <p>The fix: every mutable path is resolved against the absolute {@link #dir()},
 * which is computed once from the install location (or a per-user data directory
 * as fallback) and is independent of the process working directory.
 *
 * <p>This class intentionally has no logging or config dependencies, so that
 * {@link #anchor()} can run before those subsystems initialise.
 */
public final class AppHome {

    private AppHome() {
    }

    /** Cached absolute app-home directory (resolved once, CWD-independent). */
    private static volatile File homeDir;

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
    /**
     * Warms {@link #dir()} and exposes it to log4j via the {@code lhb.home}
     * system property. Called once at startup, before any config is read or
     * logger initialised. Deliberately does <em>not</em> touch {@code user.dir}
     * (that override is a no-op — see the class javadoc); all mutable state is
     * resolved against the absolute {@link #dir()} instead.
     */
    public static void anchor() {
        try {
            System.setProperty("lhb.home", dir().getAbsolutePath());
        } catch (Exception e) {
            // Best effort — resolve() falls back to the launch working directory.
        }
    }

    /**
     * The absolute, working-directory-independent base directory holding all
     * mutable application state ({@code config.json}, {@code log/}, {@code tls/},
     * {@code downloads/}, {@code updates/}).
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code -Dlhb.home=<dir>} explicit override (ops + deterministic tests);</li>
     *   <li>the install directory when resolvable and writable — the normal
     *       packaged case (per-user installs under {@code %LOCALAPPDATA%});</li>
     *   <li>a per-user data directory ({@code %APPDATA%} / XDG / Library) when the
     *       install directory exists but is not writable (per-machine
     *       {@code Program Files}) or is a read-only AppImage mount;</li>
     *   <li>the launch working directory as a last resort (development / tests,
     *       where the code source is exploded classes rather than a JAR).</li>
     * </ol>
     */
    public static File dir() {
        File d = homeDir;
        if (d == null) {
            synchronized (AppHome.class) {
                d = homeDir;
                if (d == null) {
                    d = resolveHome();
                    homeDir = d;
                    System.setProperty("lhb.home", d.getAbsolutePath());
                }
            }
        }
        return d;
    }

    /**
     * Resolves {@code path} against {@link #dir()}. Absolute paths are returned
     * unchanged; a null/blank path returns {@link #dir()} itself.
     */
    public static File resolve(String path) {
        if (path == null || path.isBlank()) {
            return dir();
        }
        File f = new File(path);
        return f.isAbsolute() ? f : new File(dir(), path);
    }

    /** {@link #resolve(String)} as a {@link Path}. */
    public static Path resolvePath(String path) {
        return resolve(path).toPath();
    }

    static File resolveHome() {
        // 1. Explicit override — ops escape hatch and deterministic tests.
        String override = System.getProperty("lhb.home");
        if (override != null && !override.isBlank()) {
            return new File(override).getAbsoluteFile();
        }
        // 2. Resolve the install directory (absolute — independent of CWD).
        File install = resolveViaProtectionDomain();
        if (install == null) {
            install = resolveViaClassPath();
        }
        if (install == null) {
            install = resolveViaJavaHome();
        }
        if (install != null && !isAppImageMount(install)) {
            // Packaged app: the install dir is the natural home when writable
            // (per-user installs). Otherwise fall back to a per-user data dir
            // (e.g. a per-machine Program Files install running non-elevated).
            if (isWritable(install)) {
                return install;
            }
            File data = appDataHome(install);
            if (data != null) {
                return data;
            }
            return install;
        }
        // 3. Read-only AppImage mount — never usable as home.
        if (install != null) {
            File data = appDataHome(install);
            if (data != null) {
                return data;
            }
        }
        // 4. Development / tests / unknown layout: resolve against the process
        // working directory. Use Path.of("").toAbsolutePath() (the CWD cached by
        // java.io.File / java.nio at FileSystem init) rather than the mutable
        // "user.dir" property, so relative paths here stay consistent with
        // File/NIO relative resolution elsewhere. The packaged app never reaches
        // this branch (the install dir resolves via the protection domain).
        return Path.of("").toAbsolutePath().toFile();
    }

    /**
     * Returns a writable per-user data directory ({@code %APPDATA%} etc.), seeding
     * {@code config.json} from a known location on first use. Returns {@code null}
     * if no writable data directory is available.
     */
    private static File appDataHome(File installForSeed) {
        File data = getAppDataDir();
        if (data == null) {
            return null;
        }
        ensureDir(data);
        if (!data.isDirectory() || !isWritable(data)) {
            return null;
        }
        seedConfig(data, installForSeed);
        return data;
    }

    /**
     * Probes real write access to {@code dir} (creating it if needed). Uses an
     * actual create/delete probe rather than {@link File#canWrite()}, which is
     * unreliable against Windows ACLs and virtualised {@code Program Files}.
     */
    static boolean isWritable(File dir) {
        if (dir == null) {
            return false;
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File probe = File.createTempFile(".lhb-writable", null, dir);
            Files.deleteIfExists(probe.toPath());
            return true;
        } catch (Exception e) {
            return false;
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
     * Seeds {@code config.json} into {@code dataDir} on first use when the app
     * home falls back to a per-user data directory. Copies the first existing
     * candidate: the install-dir config, an enterprise {@code config-template.json}
     * (Intune fleet), a legacy systemd install, the CWD, or the user home. Best
     * effort — {@code ConfigService} creates defaults if this fails.
     */
    private static void seedConfig(File dataDir, File installDir) {
        File targetConfig = new File(dataDir, "config.json");
        if (targetConfig.exists()) {
            return; // Already have a config
        }
        java.util.List<File> candidates = new java.util.ArrayList<>();
        if (installDir != null) {
            candidates.add(new File(installDir, "config.json"));
            candidates.add(new File(installDir, "config-template.json"));
        }
        candidates.add(new File("/opt/local-hardware-bridge/config.json"));
        candidates.add(new File("config.json"));
        candidates.add(new File(System.getProperty("user.home", ""), "config.json"));
        for (File src : candidates) {
            if (src.isFile() && src.canRead()) {
                try {
                    Files.copy(src.toPath(), targetConfig.toPath());
                } catch (Exception e) {
                    // Best effort — ConfigService will create defaults if this fails
                }
                return;
            }
        }
    }
}

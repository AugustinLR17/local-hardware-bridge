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
}

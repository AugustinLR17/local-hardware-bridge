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
            var location = AppHome.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return;
            }
            File codeSource = new File(location.toURI());
            File resolved = resolveInstallDir(codeSource);
            if (resolved != null) {
                System.setProperty("user.dir", resolved.getAbsolutePath());
            }
        } catch (Exception e) {
            // Best effort: fall back to the launch working directory.
        }
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

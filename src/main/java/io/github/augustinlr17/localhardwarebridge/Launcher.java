package io.github.augustinlr17.localhardwarebridge;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.services.ConfigService;
import io.github.augustinlr17.localhardwarebridge.services.UpdateService;

import java.nio.file.Path;

/**
 * Application entry point.
 *
 * <p>This is the {@code Main-Class} of the packaged JAR and the launcher main class
 * configured for the native installers. It has no static dependency on logging or
 * config services, which lets it {@link AppHome#anchor()} the working directory
 * <em>before</em> any other application class is loaded (and therefore before
 * {@code ConfigService} reads {@code config.json} or log4j opens {@code log/}).
 *
 * <p>Dispatch:
 * <ul>
 *   <li>{@code -Dlhb.server=true} &rarr; headless server</li>
 *   <li>{@code -Dlhb.no-update=true} &rarr; skip auto-update apply (emergency bypass)</li>
 *   <li>otherwise &rarr; GUI / system-tray mode</li>
 * </ul>
 *
 * <p>If {@code autoInstall} is enabled in the update config and a pending update
 * JAR was downloaded in a previous run, the Launcher applies it before starting
 * the application. Pass {@code -Dlhb.no-update=true} to skip this (e.g. if a
 * bad update prevents startup).
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        AppHome.anchor();

        // Two-hop update, second hop (Windows): we were relaunched FROM the
        // downloaded JAR with -Dlhb.promote.target=<original jar>. The original
        // JAR is no longer locked by a JVM, so copy ourselves over it and
        // relaunch from the final location. On failure we keep running from
        // the downloaded JAR (still the new version).
        String promoteTarget = System.getProperty("lhb.promote.target");
        if (promoteTarget != null && !Boolean.getBoolean("lhb.no-update")) {
            promoteSelf(promoteTarget); // exits the JVM on success
        }

        // Apply a pending auto-update before starting the app (if enabled).
        // -Dlhb.no-update=true is an emergency bypass for a broken update.
        if (!Boolean.getBoolean("lhb.no-update") && promoteTarget == null) {
            try {
                applyPendingAutoUpdate();
            } catch (Exception e) {
                // Log to stderr — log4j isn't initialized yet (intentional, see javadoc)
                System.err.println("[Launcher] Auto-update apply failed: " + e.getMessage());
            }
        }

        if (Boolean.getBoolean("lhb.server")) {
            Server.main(args);
        } else {
            GUI.main(args);
        }
    }

    /**
     * Second hop of the Windows-safe update: this JVM runs FROM the downloaded
     * JAR; the original JAR (given by {@code -Dlhb.promote.target}) is no
     * longer locked. Copy ourselves over it (with a .bak backup) and relaunch
     * from the final location. On any failure, log to stderr and return — the
     * app then starts normally from the downloaded JAR, which is already the
     * new version, and the promote is retried on the next update cycle.
     */
    private static void promoteSelf(String targetStr) {
        try {
            var location = Launcher.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return;
            }
            Path self = Path.of(location.toURI());
            if (!self.toString().endsWith(".jar")) {
                return; // dev mode — nothing to promote
            }
            Path target = Path.of(targetStr);
            System.err.println("[Launcher] Promoting update: " + self + " -> " + target);
            UpdateService.promoteJar(self, target);
            UpdateService.getInstance().relaunchFromJar(target, null); // exits the JVM
        } catch (Exception e) {
            System.err.println("[Launcher] Promote failed: " + e.getMessage()
                    + " — continuing on the updated version");
        }
    }

    /**
     * If autoInstall is enabled and a pending update JAR exists, applies it
     * and re-launches the new JAR. This only runs when the app starts from a
     * packaged JAR (not from exploded classes in an IDE).
     */
    private static void applyPendingAutoUpdate() throws Exception {
        // Don't trigger ConfigService load if we're in dev mode (no JAR)
        var location = Launcher.class.getProtectionDomain().getCodeSource().getLocation();
        if (location == null) {
            return;
        }
        Path codeSource = Path.of(location.toURI());
        if (!codeSource.toString().endsWith(".jar")) {
            return; // Running from exploded classes (IDE) — skip
        }

        Config.Update updateConfig = ConfigService.getInstance().getConfig().getUpdate();
        if (!updateConfig.isEnabled() || !updateConfig.isAutoInstall()) {
            return;
        }

        Path pending = UpdateService.getInstance().consumePendingUpdate();
        if (pending == null) {
            return;
        }

        // Log4j isn't initialized yet — stderr is the only channel available.
        System.err.println("[Launcher] Applying pending auto-update: " + pending);
        try {
            UpdateService.getInstance().applyUpdate(pending);
        } catch (java.io.IOException e) {
            // Windows: this JVM has the running JAR locked, so it cannot be
            // replaced in-place. Relaunch FROM the downloaded JAR in promote
            // mode: the new process copies itself over the (then unlocked)
            // original JAR and relaunches from there. See Launcher.promoteSelf.
            Path current = UpdateService.getInstance().currentJarPath();
            if (current == null) {
                throw e;
            }
            System.err.println("[Launcher] In-place replace failed (" + e.getMessage()
                    + "), relaunching from the downloaded JAR to complete the update");
            UpdateService.getInstance().relaunchFromJar(pending, current); // exits the JVM
            return;
        }
        UpdateService.getInstance().cleanupOldUpdates();

        // Re-launch the updated JAR with the same args and system properties
        System.err.println("[Launcher] Update applied, re-launching...");
        String javaExec = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");
        String workingDir = System.getProperty("user.dir");

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaExec);
        if (Boolean.getBoolean("lhb.server")) {
            cmd.add("-Dlhb.server=true");
        }
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("io.github.augustinlr17.localhardwarebridge.Launcher");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new java.io.File(workingDir));
        pb.inheritIO();
        pb.start();
        System.exit(0);
    }
}

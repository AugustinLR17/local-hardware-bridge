package io.github.augustinlr17.localhardwarebridge.utils;

import lombok.extern.log4j.Log4j2;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Generates systemd service unit files for Local Hardware Bridge and provides
 * related helpers (install-path constants, version extraction, install-status
 * checks).
 *
 * <p>This class exists so the unit-file content — which must be correct for the
 * service to start headless under systemd — is fully testable without spinning
 * up {@code pkexec}/{@code systemctl} subprocesses.
 *
 * <h2>Bugs this class prevents</h2>
 * <ul>
 *   <li><b>GUI instead of Server</b> — the generated {@code ExecStart} always
 *       references {@code io.github.augustinlr17.localhardwarebridge.Server}
 *       (headless). Launching GUI under systemd crashes because no X/Wayland
 *       display is available.</li>
 *   <li><b>JAR in the download folder</b> — the service always points at
 *       {@code /opt/local-hardware-bridge/local-hardware-bridge.jar}, a stable
 *       FHS location that doesn't break when the user moves the download.</li>
 *   <li><b>AccessDeniedException on root-owned file</b> —
 *       {@link #isServiceInstalled()} and {@link #extractVersionFromUnit(String)}
 *       never throw on unreadable files; they return {@code false}/{@code null}
 *       instead.</li>
 * </ul>
 */
@Log4j2
public final class SystemdServiceGenerator {

    /** FHS install directory for the JAR. */
    static final String INSTALL_DIR = "/opt/local-hardware-bridge";

    /** Stable JAR path inside {@link #INSTALL_DIR}. */
    static final String INSTALLED_JAR_PATH = INSTALL_DIR + "/local-hardware-bridge.jar";

    /** Fully-qualified headless main class used in {@code ExecStart}. */
    private static final String SERVER_MAIN_CLASS =
        "io.github.augustinlr17.localhardwarebridge.Server";

    /** Marker line embedded in the unit file for version tracking. */
    private static final String VERSION_MARKER = "# LHB_VERSION=";

    /** Path to the systemd unit file. */
    private static final String SERVICE_FILE_PATH =
        "/etc/systemd/system/local-hardware-bridge.service";

    private SystemdServiceGenerator() {
    }

    /**
     * Returns the FHS install directory ({@code /opt/local-hardware-bridge}).
     *
     * @return the install directory, never {@code null}
     */
    public static String getInstallDir() {
        return INSTALL_DIR;
    }

    /**
     * Returns the stable JAR path inside the install directory.
     *
     * @return {@code /opt/local-hardware-bridge/local-hardware-bridge.jar}
     */
    public static String getInstalledJarPath() {
        return INSTALLED_JAR_PATH;
    }

    /**
     * Generates a systemd service unit file for Local Hardware Bridge.
     *
     * <p>The unit always launches the <em>headless</em> {@code Server} main
     * class (never {@code GUI}) and always references the JAR at
     * {@link #getInstalledJarPath()}, regardless of where {@code jarPath}
     * currently lives. This prevents two classes of bug:
     * <ol>
     *   <li>Launching GUI under systemd → crash (no display).</li>
     *   <li>Pointing at the download folder → breakage when the user moves
     *       the file.</li>
     * </ol>
     *
     * @param javaExec   absolute path to the {@code java} executable
     * @param jarPath    current location of the JAR (used only for the copy
     *                   step performed by the caller; the unit file itself
     *                   always uses {@link #getInstalledJarPath()})
     * @param workingDir the {@code WorkingDirectory} for the service
     * @param version    the application version to embed in a
     *                   {@code # LHB_VERSION=} comment
     * @return the complete systemd unit file content
     */
    public static String generateServiceUnit(String javaExec, String jarPath,
                                             String workingDir, String version) {
        return "[Unit]\n"
            + VERSION_MARKER + version + "\n"
            + "Description=Local Hardware Bridge\n"
            + "After=network.target\n\n"
            + "[Service]\n"
            + "Type=simple\n"
            + "ExecStart=" + javaExec + " -cp " + INSTALLED_JAR_PATH
            + " " + SERVER_MAIN_CLASS + "\n"
            + "WorkingDirectory=" + workingDir + "\n"
            + "Restart=on-failure\n"
            + "RestartSec=5\n\n"
            + "[Install]\n"
            + "WantedBy=multi-user.target\n";
    }

    /**
     * Extracts the version string from the {@code # LHB_VERSION=} marker line
     * in a systemd unit file.
     *
     * <p>This is a pure string parser — it does not touch the filesystem, so it
     * is safe to call on content read by the caller (who may have already
     * handled {@link AccessDeniedException}). Returns {@code null} when the
     * marker is absent or {@code unitContent} is {@code null}/blank.
     *
     * @param unitContent the full unit file content, or {@code null}
     * @return the version string, or {@code null} if not found
     */
    public static String extractVersionFromUnit(String unitContent) {
        if (unitContent == null || unitContent.isBlank()) {
            return null;
        }
        int idx = unitContent.indexOf(VERSION_MARKER);
        if (idx < 0) {
            return null;
        }
        int start = idx + VERSION_MARKER.length();
        int end = unitContent.indexOf('\n', start);
        if (end < 0) {
            end = unitContent.length();
        }
        String version = unitContent.substring(start, end).trim();
        return version.isEmpty() ? null : version;
    }

    /**
     * Checks whether the systemd service unit file exists <em>and</em> is
     * readable.
     *
     * <p>The unit file is typically root-owned (installed via {@code pkexec}),
     * so a normal user may get {@link AccessDeniedException} when trying to
     * read it. This method catches that and returns {@code false} — the caller
     * treats "cannot read" the same as "not installed" for the purpose of
     * offering a (re)install.
     *
     * @return {@code true} if the file exists and can be read
     */
    public static boolean isServiceInstalled() {
        Path serviceFile = Paths.get(SERVICE_FILE_PATH);
        if (!Files.exists(serviceFile)) {
            return false;
        }
        try {
            Files.readString(serviceFile);
            return true;
        } catch (AccessDeniedException e) {
            log.debug("Cannot read service file (root-owned): {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.debug("Cannot read service file: {}", e.getMessage());
            return false;
        }
    }
}
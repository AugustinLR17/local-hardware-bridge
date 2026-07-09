package io.github.augustinlr17.localhardwarebridge.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.AppHome;
import io.github.augustinlr17.localhardwarebridge.Constants;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.dtos.ReleaseInfo;
import io.github.augustinlr17.localhardwarebridge.dtos.UpdateStatusDTO;
import io.github.augustinlr17.localhardwarebridge.utils.SystemdServiceGenerator;
import io.github.augustinlr17.localhardwarebridge.utils.VersionComparator;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks for, downloads, and applies application updates from GitHub Releases.
 *
 * <h3>Update flow (hybrid / opt-in)</h3>
 * <ol>
 *   <li><b>Check</b> — polls the GitHub Releases API for the latest release.
 *       Compares the tag version with {@link Constants#VERSION} using
 *       {@link VersionComparator}. Pre-releases are skipped unless configured.</li>
 *   <li><b>Download</b> — if a newer version is found and the user triggers the
 *       download (or {@code autoDownload} is enabled), the new fat JAR is
 *       downloaded to {@code updates/local-hardware-bridge-<new>.jar}.
 *       The download is atomic: the file is written to a {@code .part} temp
 *       file and moved into place only after the full transfer completes.</li>
 *   <li><b>Apply</b> — on the next restart, the pending JAR replaces the
 *       current JAR. The old JAR is backed up to {@code *.bak} so a failed
 *       startup can be rolled back. The actual process restart is handled by
 *       the caller (GUI or Server entry point) after {@link #consumePendingUpdate()}
 *       returns the path to the new JAR.</li>
 * </ol>
 *
 * <p>The service is a singleton, like {@link ConfigService}. It uses a
 * single-thread scheduled executor for periodic checks so checks never
 * overlap. A {@code User-Agent} header is sent with every GitHub API request
 * (required by the GitHub API fair-use policy).
 *
 * <p>Thread-safety: the check and download states are guarded by
 * {@link AtomicBoolean} / {@link AtomicReference} so concurrent HTTP
 * requests (manual + scheduled) cannot corrupt each other.
 */
@Log4j2
public class UpdateService {

    @Getter
    private static final UpdateService instance = new UpdateService();

    private static final String JAR_NAME_PREFIX = "local-hardware-bridge-";

    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String UPDATES_DIR = "updates";
    private static final String GITHUB_API_BASE = "https://api.github.com/repos/";
    private static final String USER_AGENT = "Local-Hardware-Bridge/" + Constants.VERSION;

    /** Marker file (next to the app JAR) recording a staged update pending boot verification. */
    private static final String BOOT_MARKER = ".lhb-update-boot";
    /** How many failed boots of a staged update to tolerate before rolling back. */
    private static final int MAX_BOOT_ATTEMPTS = 2;
    /** Versions that failed to boot after a staged update — never auto-applied again. */
    private static final String REJECTED_FILE = "rejected-versions.txt";

    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    /** The latest release info from the last successful check (null if never checked). */
    private final AtomicReference<ReleaseInfo> latestRelease = new AtomicReference<>();

    /** Error from the last check or download (null if none). */
    private final AtomicReference<String> lastError = new AtomicReference<>();

    /** Path to a fully-downloaded JAR awaiting application on next restart. */
    private final AtomicReference<Path> pendingUpdate = new AtomicReference<>();

    private volatile ScheduledExecutorService scheduler;

    private UpdateService() {
        // Detect a previously-downloaded update from a prior run.
        detectPendingUpdate();
    }

    // --- public API ---

    /**
     * Starts the background scheduler if updates are enabled and
     * {@code checkIntervalHours > 0}. Also performs an immediate check.
     * Safe to call multiple times — stops any previous scheduler first.
     */
    public void startScheduledChecks() {
        if (!getConfig().isEnabled()) {
            log.info("Auto-update is disabled in config; skipping scheduled checks");
            return;
        }

        stopScheduledChecks();

        int hours = getConfig().getCheckIntervalHours();
        if (hours <= 0) {
            log.info("Update check interval is 0; only checking on startup");
            checkInBackground();
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "update-checker");
            t.setDaemon(true);
            return t;
        });

        // Immediate check, then periodic.
        scheduler.schedule(this::checkInBackground, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::checkInBackground, hours, hours, TimeUnit.HOURS);
        log.info("Scheduled update checks every {} hour(s)", hours);
    }

    /** Stops the background scheduler if running. */
    public void stopScheduledChecks() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * Performs an update check in a background daemon thread.
     * No-op if a check is already in progress.
     */
    public void checkInBackground() {
        if (!checking.compareAndSet(false, true)) {
            log.debug("Update check already in progress, skipping");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                performCheck();
            } finally {
                checking.set(false);
            }
        }, "update-check-once");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Synchronously checks for an update and returns the status.
     * Used by the HTTP endpoint {@code GET /system/update/check}.
     *
     * @return the current update status after the check completes
     */
    public UpdateStatusDTO checkNow() throws Exception {
        if (!checking.compareAndSet(false, true)) {
            // A check is already running — wait briefly and return current state.
            return getStatus();
        }
        try {
            performCheck();
        } finally {
            checking.set(false);
        }
        return getStatus();
    }

    /**
     * Downloads the new JAR from the latest release (if an update is available).
     * No-op if no update is available or a download is already in progress.
     *
     * @return the path to the downloaded JAR, or null if no download occurred
     */
    public Path downloadUpdate() throws Exception {
        ReleaseInfo release = latestRelease.get();
        if (release == null) {
            // Try checking first
            performCheck();
            release = latestRelease.get();
        }
        if (release == null) {
            throw new IOException("No release info available to download");
        }

        ReleaseInfo.Asset jarAsset = findJarAsset(release);
        if (jarAsset == null) {
            throw new IOException("No JAR asset found in release " + release.getTagName());
        }

        if (!downloading.compareAndSet(false, true)) {
            throw new IOException("A download is already in progress");
        }

        try {
            Path result = downloadAsset(jarAsset, release.getVersionWithoutPrefix());
            pendingUpdate.set(result);
            lastError.set(null);
            log.info("Update downloaded to {}", result);
            return result;
        } finally {
            downloading.set(false);
        }
    }

    /**
     * Downloads and then signals that the update should be applied on the
     * next restart. The caller (GUI/Server) is responsible for the actual
     * process restart.
     *
     * @return the path to the downloaded JAR to apply
     */
    public Path downloadAndPrepare() throws Exception {
        Path downloaded = downloadUpdate();
        if (downloaded == null) {
            throw new IOException("Download did not produce a JAR");
        }
        return downloaded;
    }

    /**
     * Returns the path to a pending update JAR if one exists (from this run
     * or a previous run), and clears the pending state. The caller should:
     * <ol>
     *   <li>Stop the current server</li>
     *   <li>Replace the running JAR with the pending JAR</li>
     *   <li>Restart the process</li>
     * </ol>
     *
     * @return the path to the pending JAR, or null if no update is pending
     */
    public Path consumePendingUpdate() {
        Path pending = pendingUpdate.get();
        if (pending == null) {
            return null;
        }
        if (!Files.isRegularFile(pending)) {
            log.warn("Pending update JAR no longer exists: {}", pending);
            pendingUpdate.set(null);
            return null;
        }
        pendingUpdate.set(null);
        return pending;
    }

    /**
     * Replaces the current JAR with the downloaded JAR, backing up the old one.
     * The caller should stop the server before calling this, then restart.
     *
     * @param newJar the downloaded JAR to install
     * @return the path to the backup of the old JAR (for rollback)
     */
    public Path applyUpdate(Path newJar) throws IOException {
        if (!Files.isRegularFile(newJar)) {
            throw new IOException("Update JAR not found: " + newJar);
        }

        Path currentJar = getCurrentJarPath();

        // If running from a read-only location (e.g. AppImage mount) or from a
        // different JAR than the systemd service, try to update the systemd
        // service JAR at /opt/local-hardware-bridge/ instead.
        Path systemdJar = Path.of(SystemdServiceGenerator.getInstalledJarPath());
        boolean systemdInstalled = SystemdServiceGenerator.isServiceInstalled();

        if (systemdInstalled && Files.isRegularFile(systemdJar)) {
            // If the current JAR is the systemd JAR, replace it directly.
            // Otherwise (AppImage, download folder, etc.), update the systemd JAR
            // so the service picks up the new version on restart.
            Path target = systemdJar.equals(currentJar) ? currentJar : systemdJar;
            Path backup = target.resolveSibling(target.getFileName() + ".bak");
            log.info("Applying update: replacing {} with {} (backup: {})", target, newJar, backup);

            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(newJar, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(newJar, target, StandardCopyOption.REPLACE_EXISTING);
            }

            // If the current JAR is writable and different from the systemd JAR,
            // also update it so the running instance is consistent.
            if (currentJar != null && !currentJar.equals(systemdJar) && Files.isWritable(currentJar)) {
                try {
                    Files.copy(target, currentJar, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    log.warn("Could not sync update to current JAR at {}: {}", currentJar, e.getMessage());
                }
            }

            log.info("Update applied successfully. Old JAR backed up at {}", backup);
            return backup;
        }

        // Fallback: no systemd service installed, just replace the current JAR
        if (currentJar == null || !Files.isRegularFile(currentJar)) {
            throw new IOException("Cannot determine current JAR path for replacement");
        }

        // Check if the current JAR is writable. On Linux AppImage, the JAR lives
        // inside a read-only FUSE mount (/tmp/.mount_*/.../*.jar) and cannot be
        // replaced. In that case, fail with a clear message instead of a
        // confusing FileSystemException.
        if (!Files.isWritable(currentJar)) {
            String appImage = System.getenv("APPIMAGE");
            if (appImage != null && !appImage.isEmpty()) {
                throw new IOException(
                    "Auto-update is not supported inside an AppImage (read-only mount). "
                    + "Download the new AppImage from GitHub: "
                    + "https://github.com/AugustinLR17/local-hardware-bridge/releases/latest"
                );
            }
            throw new IOException(
                "Cannot update: current JAR location is read-only (" + currentJar + "). "
                + "Please update manually."
            );
        }

        Path backup = currentJar.resolveSibling(currentJar.getFileName() + ".bak");
        log.info("Applying update: replacing {} with {} (backup: {})", currentJar, newJar, backup);

        Files.copy(currentJar, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(newJar, currentJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(newJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Update applied successfully. Old JAR backed up at {}", backup);
        return backup;
    }

    /** Exposes the running JAR path (null when running from exploded classes). */
    public Path currentJarPath() {
        return getCurrentJarPath();
    }

    /**
     * Promote step of the two-hop (Windows-safe) update: copies the update JAR
     * over the original JAR, keeping a {@code .bak} backup of the original.
     *
     * <p>On Windows the JVM locks every JAR on its classpath, so
     * {@link #applyUpdate} cannot replace the JAR it is running from. The
     * fallback ({@link #relaunchFromJar}) starts a new JVM <em>from the
     * downloaded JAR</em> with {@code -Dlhb.promote.target=&lt;original&gt;};
     * by the time that process calls this method, the original JAR is no
     * longer locked and the copy succeeds.
     */
    public static void promoteJar(Path source, Path target) throws IOException {
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Builds the relaunch command line (extracted for testability). */
    static java.util.List<String> buildRelaunchCommand(String javaExec, boolean serverMode, Path jar, Path promoteTarget) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaExec);
        if (serverMode) {
            cmd.add("-Dlhb.server=true");
        }
        if (promoteTarget != null) {
            cmd.add("-Dlhb.promote.target=" + promoteTarget);
        }
        cmd.add("-cp");
        cmd.add(jar.toString());
        cmd.add("io.github.augustinlr17.localhardwarebridge.Launcher");
        return cmd;
    }

    /**
     * Relaunches the application from the given JAR and exits this JVM.
     * With a non-null {@code promoteTarget}, the new process performs the
     * promote step (see {@link #promoteJar}) before starting normally.
     * This is the Windows path of the auto-update: the running JAR is locked
     * by this JVM, so the file swap must happen from another process.
     */
    public void relaunchFromJar(Path jar, Path promoteTarget) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String javaExec = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + (windows ? "java.exe" : "java");
        // jpackage runtimes ship NO java launcher (only the native app exe).
        // In that layout, stage the new JAR next to the old one, repoint the
        // launcher .cfg, and restart the native exe instead.
        if (!new File(javaExec).isFile()) {
            if (promoteTarget != null && applyStagedViaCfg(jar, promoteTarget)) {
                return; // unreachable: applyStagedViaCfg exits the JVM
            }
            throw new IOException("No java launcher at " + javaExec
                    + " and launcher cfg staging not applicable — cannot complete update");
        }
        ProcessBuilder pb = new ProcessBuilder(
                buildRelaunchCommand(javaExec, Boolean.getBoolean("lhb.server"), jar, promoteTarget));
        pb.directory(AppHome.dir());
        pb.inheritIO();
        log.info("Relaunching from {} (promoteTarget={})", jar, promoteTarget);
        pb.start();
        System.exit(0);
    }

    /**
     * jpackage staged apply: copies the new JAR next to the (locked) current
     * one inside {@code app/}, rewrites the launcher {@code .cfg} so the
     * native exe loads the new JAR on its next start, then relaunches the exe
     * and exits this JVM. The old JAR stays in place as an implicit backup —
     * repointing the cfg back to it is the rollback.
     *
     * @return false when the layout is not a jpackage app-image (caller
     *         should fall back to another strategy); on success this method
     *         does not return (the JVM exits).
     */
    private boolean applyStagedViaCfg(Path pending, Path currentJar) {
        try {
            Path appDir = currentJar.toAbsolutePath().getParent();
            Path installDir = (appDir == null) ? null : appDir.getParent();
            if (appDir == null || installDir == null) return false;
            Path cfg = appDir.resolve(Constants.APP_NAME + ".cfg");
            Path exe = installDir.resolve(Constants.APP_NAME + ".exe");
            if (!Files.isRegularFile(cfg) || !Files.isRegularFile(exe)) return false;

            Path newJar = appDir.resolve(pending.getFileName().toString());
            Files.copy(pending, newJar, StandardCopyOption.REPLACE_EXISTING);
            String text = Files.readString(cfg);
            String updated = text.replace(currentJar.getFileName().toString(), newJar.getFileName().toString());
            if (updated.equals(text)) {
                // cfg does not reference the running JAR — unknown layout, bail out
                Files.deleteIfExists(newJar);
                return false;
            }
            Files.writeString(cfg, updated);
            // Record the swap so the next boot can verify it (and roll back if the
            // new JAR fails to start). Written BEFORE relaunching, so a crash on the
            // very first boot still leaves a marker for the rollback logic to act on.
            writeBootMarker(cfg, newJar.getFileName().toString(), currentJar.getFileName().toString(), exe, installDir);
            log.info("Staged update: {} installed, launcher cfg repointed, relaunching {}", newJar.getFileName(), exe);

            relaunchExe(exe, installDir); // exits the JVM
            return true; // unreachable
        } catch (Exception e) {
            log.error("Staged cfg update failed", e);
            return false;
        }
    }

    /**
     * Rolls back to the backup JAR (if one exists next to the current JAR).
     *
     * @return true if rollback succeeded
     */
    public boolean rollback() throws IOException {
        // If systemd service is installed, rollback the systemd JAR.
        Path systemdJar = Path.of(SystemdServiceGenerator.getInstalledJarPath());
        if (SystemdServiceGenerator.isServiceInstalled() && Files.isRegularFile(systemdJar)) {
            Path backup = systemdJar.resolveSibling(systemdJar.getFileName() + ".bak");
            if (!Files.isRegularFile(backup)) {
                log.warn("No backup JAR found at {}", backup);
                return false;
            }
            try {
                Files.move(backup, systemdJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(backup, systemdJar, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Rolled back to previous JAR from {}", backup);
            return true;
        }

        // Fallback: rollback the current JAR
        Path currentJar = getCurrentJarPath();
        if (currentJar == null) {
            return false;
        }
        Path backup = currentJar.resolveSibling(currentJar.getFileName() + ".bak");
        if (!Files.isRegularFile(backup)) {
            log.warn("No backup JAR found at {}", backup);
            return false;
        }
        try {
            Files.move(backup, currentJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(backup, currentJar, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Rolled back to previous JAR from {}", backup);
        return true;
    }

    /**
     * Returns the current update status as a DTO for API responses.
     */
    public UpdateStatusDTO getStatus() {
        UpdateStatusDTO dto = new UpdateStatusDTO();
        ReleaseInfo release = latestRelease.get();

        dto.setChecked(release != null);
        dto.setCurrentVersion(Constants.VERSION);
        dto.setDownloading(downloading.get());
        dto.setPendingRestart(pendingUpdate.get() != null && Files.isRegularFile(pendingUpdate.get()));
        Path pending = pendingUpdate.get();
        dto.setDownloadedPath(pending != null ? pending.toString() : null);
        dto.setError(lastError.get());

        if (release != null) {
            dto.setLatestVersion(release.getVersionWithoutPrefix());
            dto.setReleaseName(release.getName());
            dto.setReleaseUrl(release.getHtmlUrl());
            dto.setPrerelease(release.isPreRelease());
            dto.setUpdateAvailable(VersionComparator.isNewer(Constants.VERSION, release.getVersionWithoutPrefix()));
        } else {
            dto.setUpdateAvailable(false);
        }

        return dto;
    }

    /** Returns true if an update check is currently in progress. */
    public boolean isChecking() {
        return checking.get();
    }

    /** Returns true if a download is currently in progress. */
    public boolean isDownloading() {
        return downloading.get();
    }

    // --- internal logic ---

    private void performCheck() {
        try {
            lastError.set(null);
            Config.Update config = getConfig();

            if (!config.isEnabled()) {
                log.debug("Update check skipped (disabled in config)");
                return;
            }

            String repo = config.getRepository();
            String endpoint = config.isIncludePrereleases()
                    ? GITHUB_API_BASE + repo + "/releases"
                    : GITHUB_API_BASE + repo + "/releases/latest";

            ReleaseInfo release = fetchLatestRelease(endpoint, config.isIncludePrereleases());
            if (release == null || release.getTagName() == null) {
                log.warn("No release found from GitHub API");
                return;
            }

            latestRelease.set(release);
            String latestVersion = release.getVersionWithoutPrefix();

            if (VersionComparator.isNewer(Constants.VERSION, latestVersion)) {
                log.info("Update available: {} (current: {})", latestVersion, Constants.VERSION);

                // Auto-download if configured (skip a version quarantined by a failed update boot)
                if (config.isAutoDownload() && pendingUpdate.get() == null && !isRejected(latestVersion)) {
                    try {
                        downloadUpdate();
                    } catch (Exception e) {
                        log.error("Auto-download failed", e);
                        lastError.set("Auto-download failed: " + e.getMessage());
                    }
                }
            } else {
                log.debug("Already up to date (current: {}, latest: {})", Constants.VERSION, latestVersion);
            }
        } catch (Exception e) {
            log.error("Update check failed", e);
            lastError.set(e.getMessage());
        }
    }

    private ReleaseInfo fetchLatestRelease(String url, boolean includePrereleases) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                if (code == 403) {
                    String retryAfter = conn.getHeaderField("X-RateLimit-Reset");
                    String remaining = conn.getHeaderField("X-RateLimit-Remaining");
                    if ("0".equals(remaining)) {
                        long resetEpoch = retryAfter != null ? Long.parseLong(retryAfter) : 0;
                        long waitMinutes = resetEpoch > 0
                            ? Math.max(1, (resetEpoch - System.currentTimeMillis() / 1000) / 60)
                            : 0;
                        throw new IOException("GitHub API rate limit exceeded" + (waitMinutes > 0 ? " — retry in " + waitMinutes + " min" : ""));
                    }
                    throw new IOException("GitHub API returned 403 Forbidden (rate limit or access denied)");
                }
                throw new IOException("GitHub API returned HTTP " + code);
            }

            if (includePrereleases) {
                // The /releases endpoint returns an array; pick the newest
                ReleaseInfo[] releases = mapper.readValue(conn.getInputStream(), ReleaseInfo[].class);
                if (releases == null || releases.length == 0) {
                    return null;
                }
                // Releases are sorted newest-first by the API
                return releases[0];
            } else {
                return mapper.readValue(conn.getInputStream(), ReleaseInfo.class);
            }
        } finally {
            conn.disconnect();
        }
    }

    private ReleaseInfo.Asset findJarAsset(ReleaseInfo release) {
        if (release.getAssets() == null) {
            return null;
        }
        // Prefer the fat JAR (name contains "local-hardware-bridge" and ends with .jar)
        ReleaseInfo.Asset best = null;
        for (ReleaseInfo.Asset a : release.getAssets()) {
            String name = a.getName();
            if (name == null) continue;
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".jar") && lower.contains("local-hardware-bridge")) {
                best = a;
                break;
            }
            if (lower.endsWith(".jar") && best == null) {
                best = a;
            }
        }
        return best;
    }

    private Path downloadAsset(ReleaseInfo.Asset asset, String newVersion) throws Exception {
        Path updatesDir = AppHome.resolvePath(UPDATES_DIR);
        Files.createDirectories(updatesDir);

        String fileName = JAR_NAME_PREFIX + newVersion + ".jar";
        Path target = updatesDir.resolve(fileName);
        Path partFile = updatesDir.resolve(fileName + ".part");

        log.info("Downloading update from {} to {}", asset.getBrowserDownloadUrl(), target);

        URL url = URI.create(asset.getBrowserDownloadUrl()).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);

        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Download failed: HTTP " + code);
            }

            // Stream to .part file
            try (var in = conn.getInputStream()) {
                Files.copy(in, partFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // Verify the file size matches (if GitHub reported it)
            if (asset.getSize() > 0) {
                long actualSize = Files.size(partFile);
                if (actualSize != asset.getSize()) {
                    Files.deleteIfExists(partFile);
                    throw new IOException("Download size mismatch: expected " + asset.getSize() + " got " + actualSize);
                }
            }

            // Atomic move into place
            try {
                Files.move(partFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING);
            }

            return target;
        } finally {
            conn.disconnect();
            Files.deleteIfExists(partFile);
        }
    }

    /**
     * Scans the {@code updates/} directory for a previously-downloaded JAR
     * from a prior run. If found and newer than the current version, it is
     * set as the pending update.
     */
    private void detectPendingUpdate() {
        try {
            Path updatesDir = AppHome.resolvePath(UPDATES_DIR);
            if (!Files.isDirectory(updatesDir)) {
                return;
            }
            File[] jars = updatesDir.toFile().listFiles((dir, name) ->
                    name.startsWith(JAR_NAME_PREFIX) && name.endsWith(".jar"));
            if (jars == null || jars.length == 0) {
                return;
            }
            // Pick the one with the highest version
            Path best = null;
            String bestVersion = null;
            for (File jar : jars) {
                // Extract version from filename: local-hardware-bridge-2.1.0.jar
                String name = jar.getName();
                String version = name
                        .replace(JAR_NAME_PREFIX, "")
                        .replace(".jar", "");
                if (isRejected(version)) {
                    continue; // this version failed to boot after a staged update — never re-apply it
                }
                if (bestVersion == null || VersionComparator.isNewer(bestVersion, version)) {
                    bestVersion = version;
                    best = jar.toPath();
                }
            }
            if (best != null && bestVersion != null) {
                if (VersionComparator.isNewer(Constants.VERSION, bestVersion)) {
                    log.info("Found pending update JAR: {} (version {})", best, bestVersion);
                    pendingUpdate.set(best);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to detect pending update", e);
        }
    }

    /**
     * Returns the path of the currently running JAR, or null if running from
     * exploded classes (development mode).
     */
    private Path getCurrentJarPath() {
        try {
            var location = UpdateService.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            Path path = Path.of(location.toURI());
            if (path.toString().endsWith(".jar")) {
                return path;
            }
        } catch (Exception e) {
            log.warn("Failed to determine current JAR path", e);
        }
        return null;
    }

    // --- staged-update boot verification / auto-rollback ---

    /**
     * Called at the very start of the process (before config/log init) when the
     * app may have just been relaunched from a staged auto-update. If a boot
     * marker is present:
     * <ul>
     *   <li>after {@link #MAX_BOOT_ATTEMPTS} failed boots, repoints the launcher
     *       {@code .cfg} back to the previous (known-good) JAR, quarantines the
     *       bad version so it is never auto-applied again, and relaunches the
     *       native exe (exits the JVM);</li>
     *   <li>otherwise records this boot attempt and lets startup continue. A
     *       successful bind later calls {@link #commitStagedUpdate()} to clear
     *       the marker.</li>
     * </ul>
     * No-op when running from exploded classes or when no marker exists.
     *
     * <p>Uses {@code System.err} (not log4j) because it runs before logging is
     * initialised, matching the Launcher's convention.
     */
    public void verifyOrRollbackStagedUpdate() {
        Path marker = bootMarkerPath();
        if (marker == null || !Files.isRegularFile(marker)) {
            return;
        }
        try {
            Properties p = new Properties();
            try (var in = Files.newInputStream(marker)) {
                p.load(in);
            }
            int attempts = parseIntSafe(p.getProperty("attempts", "0")) + 1;
            Path cfg = pathOrNull(p.getProperty("cfg"));
            String newJar = p.getProperty("newJar", "");
            String prevJar = p.getProperty("prevJar", "");
            Path exe = pathOrNull(p.getProperty("exe"));
            Path installDir = pathOrNull(p.getProperty("installDir"));

            if (attempts > MAX_BOOT_ATTEMPTS) {
                System.err.println("[Launcher] Staged update failed to boot " + (attempts - 1)
                        + "x — rolling back to " + prevJar);
                if (cfg != null && !newJar.isBlank() && !prevJar.isBlank()) {
                    rollbackCfg(cfg, newJar, prevJar);
                    String badVersion = versionFromJarName(newJar);
                    reject(badVersion); // never auto-apply this version again
                    // Remove the bad JAR from app/ and updates/ so nothing re-stages it.
                    if (cfg.getParent() != null) {
                        Files.deleteIfExists(cfg.getParent().resolve(newJar));
                    }
                    Files.deleteIfExists(AppHome.resolvePath(UPDATES_DIR).resolve(newJar));
                }
                Files.deleteIfExists(marker);
                if (exe != null) {
                    relaunchExe(exe, installDir); // exits the JVM on success
                }
                return;
            }

            // Not exhausted yet — persist the attempt count and let this boot proceed.
            p.setProperty("attempts", Integer.toString(attempts));
            try (var out = Files.newOutputStream(marker)) {
                p.store(out, "LHB staged update — boot verification");
            }
            System.err.println("[Launcher] Verifying staged update (boot attempt "
                    + attempts + "/" + MAX_BOOT_ATTEMPTS + ")");
        } catch (Exception e) {
            System.err.println("[Launcher] Staged-update boot verification failed: " + e.getMessage());
        }
    }

    /**
     * Clears the staged-update boot marker once the app has started successfully
     * (e.g. the server bound its port). Safe no-op when no update is pending.
     */
    public void commitStagedUpdate() {
        try {
            Path marker = bootMarkerPath();
            if (marker != null && Files.deleteIfExists(marker)) {
                log.info("Staged update verified — boot marker cleared");
            }
        } catch (Exception e) {
            log.debug("commitStagedUpdate failed: {}", e.getMessage());
        }
    }

    /** Writes the boot marker next to the launcher cfg (jpackage {@code app/} dir). */
    private void writeBootMarker(Path cfg, String newJar, String prevJar, Path exe, Path installDir) {
        try {
            Path appDir = cfg.toAbsolutePath().getParent();
            if (appDir == null) {
                return;
            }
            Properties p = new Properties();
            p.setProperty("cfg", cfg.toAbsolutePath().toString());
            p.setProperty("newJar", newJar);
            p.setProperty("prevJar", prevJar);
            p.setProperty("exe", exe.toAbsolutePath().toString());
            p.setProperty("installDir", installDir.toAbsolutePath().toString());
            p.setProperty("attempts", "0");
            try (var out = Files.newOutputStream(appDir.resolve(BOOT_MARKER))) {
                p.store(out, "LHB staged update — pending boot verification");
            }
        } catch (Exception e) {
            log.warn("Could not write staged-update boot marker: {}", e.getMessage());
        }
    }

    /** Resolves the boot marker from the running JAR's directory (jpackage {@code app/}). */
    private Path bootMarkerPath() {
        Path jar = getCurrentJarPath();
        if (jar == null) {
            return null;
        }
        Path appDir = jar.toAbsolutePath().getParent();
        return appDir == null ? null : appDir.resolve(BOOT_MARKER);
    }

    /** Repoints the launcher cfg from the failed JAR back to the previous one. */
    private void rollbackCfg(Path cfg, String newJar, String prevJar) throws IOException {
        if (!Files.isRegularFile(cfg)) {
            return;
        }
        String text = Files.readString(cfg);
        String reverted = text.replace(newJar, prevJar);
        if (!reverted.equals(text)) {
            Files.writeString(cfg, reverted);
        }
    }

    /** Relaunches the native jpackage exe and exits this JVM. */
    private void relaunchExe(Path exe, Path installDir) throws IOException {
        if (!Files.isRegularFile(exe)) {
            return;
        }
        ProcessBuilder pb = new ProcessBuilder(exe.toString());
        if (installDir != null && Files.isDirectory(installDir)) {
            pb.directory(installDir.toFile());
        }
        pb.inheritIO();
        pb.start();
        System.exit(0);
    }

    /** True if the given version was quarantined after failing to boot. */
    private boolean isRejected(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        try {
            Path f = AppHome.resolvePath(REJECTED_FILE);
            if (!Files.isRegularFile(f)) {
                return false;
            }
            for (String line : Files.readAllLines(f)) {
                if (version.equals(line.trim())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("isRejected({}) failed: {}", version, e.getMessage());
        }
        return false;
    }

    /** Records a version as bad so it is never auto-downloaded/applied again. */
    private void reject(String version) {
        if (version == null || version.isBlank() || isRejected(version)) {
            return;
        }
        try {
            Files.writeString(AppHome.resolvePath(REJECTED_FILE), version + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.warn("Quarantined update version {} — it will not be auto-applied again", version);
        } catch (Exception e) {
            log.warn("Could not quarantine version {}: {}", version, e.getMessage());
        }
    }

    private static String versionFromJarName(String jarName) {
        if (jarName == null || !jarName.startsWith(JAR_NAME_PREFIX) || !jarName.endsWith(".jar")) {
            return null;
        }
        return jarName.substring(JAR_NAME_PREFIX.length(), jarName.length() - ".jar".length());
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static Path pathOrNull(String s) {
        return (s == null || s.isBlank()) ? null : Path.of(s);
    }

    private Config.Update getConfig() {
        return ConfigService.getInstance().getConfig().getUpdate();
    }

    /**
     * Cleans up old update files (anything in {@code updates/} that is not
     * the currently pending update). Safe to call after an update is applied.
     */
    public void cleanupOldUpdates() {
        try {
            Path updatesDir = AppHome.resolvePath(UPDATES_DIR);
            if (!Files.isDirectory(updatesDir)) {
                return;
            }
            Path pending = pendingUpdate.get();
            File[] files = updatesDir.toFile().listFiles();
            if (files == null) return;
            for (File f : files) {
                Path fp = f.toPath();
                if (pending != null && fp.equals(pending)) {
                    continue;
                }
                if (f.getName().endsWith(".part") || f.getName().endsWith(".jar")) {
                    Files.deleteIfExists(fp);
                    log.debug("Cleaned up old update file: {}", f.getName());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clean up old updates", e);
        }
    }
}

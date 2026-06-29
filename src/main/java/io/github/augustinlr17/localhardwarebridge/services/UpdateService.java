package io.github.augustinlr17.localhardwarebridge.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.Constants;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.dtos.ReleaseInfo;
import io.github.augustinlr17.localhardwarebridge.dtos.UpdateStatusDTO;
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

    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    /** The latest release info from the last successful check (null if never checked). */
    private final AtomicReference<ReleaseInfo> latestRelease = new AtomicReference<>();

    /** Error from the last check or download (null if none). */
    private final AtomicReference<String> lastError = new AtomicReference<>();

    /** Path to a fully-downloaded JAR awaiting application on next restart. */
    private final AtomicReference<Path> pendingUpdate = new AtomicReference<>();

    private ScheduledExecutorService scheduler;

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
        if (currentJar == null || !Files.isRegularFile(currentJar)) {
            throw new IOException("Cannot determine current JAR path for replacement");
        }

        Path backup = currentJar.resolveSibling(currentJar.getFileName() + ".bak");
        log.info("Applying update: replacing {} with {} (backup: {})", currentJar, newJar, backup);

        // Back up the current JAR
        Files.copy(currentJar, backup, StandardCopyOption.REPLACE_EXISTING);

        // Replace the current JAR with the new one
        try {
            Files.move(newJar, currentJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(newJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Update applied successfully. Old JAR backed up at {}", backup);
        return backup;
    }

    /**
     * Rolls back to the backup JAR (if one exists next to the current JAR).
     *
     * @return true if rollback succeeded
     */
    public boolean rollback() throws IOException {
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

                // Auto-download if configured
                if (config.isAutoDownload() && pendingUpdate.get() == null) {
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
        Files.createDirectories(Path.of(UPDATES_DIR));

        String fileName = JAR_NAME_PREFIX + newVersion + ".jar";
        Path target = Path.of(UPDATES_DIR, fileName);
        Path partFile = Path.of(UPDATES_DIR, fileName + ".part");

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
            Path updatesDir = Path.of(UPDATES_DIR);
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

    private Config.Update getConfig() {
        return ConfigService.getInstance().getConfig().getUpdate();
    }

    /**
     * Cleans up old update files (anything in {@code updates/} that is not
     * the currently pending update). Safe to call after an update is applied.
     */
    public void cleanupOldUpdates() {
        try {
            Path updatesDir = Path.of(UPDATES_DIR);
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

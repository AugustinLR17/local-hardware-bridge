package io.github.augustinlr17.localhardwarebridge.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Status response for the update-check endpoints.
 *
 * <p>Returned by {@code GET /system/update/check} and {@code GET /system/update/status}.
 * Uses public fields for Jackson serialization (matching {@link VersionDTO} style).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateStatusDTO {
    /** Whether an update check has been performed since startup. */
    public boolean checked;

    /** Whether a newer version is available. */
    public boolean updateAvailable;

    /** The currently running version. */
    public String currentVersion;

    /** The latest version found (null if no check has been done). */
    public String latestVersion;

    /** The release name/title from GitHub (null if no check or error). */
    public String releaseName;

    /** URL to the GitHub release page (null if no check or error). */
    public String releaseUrl;

    /** Whether the latest release is a pre-release. */
    public boolean prerelease;

    /** Whether a download is currently in progress. */
    public boolean downloading;

    /** Whether an update has been downloaded and is pending restart. */
    public boolean pendingRestart;

    /** Path to the downloaded JAR (if pending restart), null otherwise. */
    public String downloadedPath;

    /** Error message if the last check or download failed, null otherwise. */
    public String error;
}

package io.github.augustinlr17.localhardwarebridge.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Status response for the update-check endpoints.
 *
 * <p>Returned by {@code GET /system/update/check} and {@code GET /system/update/status}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateStatusDTO {
    /** Whether an update check has been performed since startup. */
    private boolean checked;

    /** Whether a newer version is available. */
    private boolean updateAvailable;

    /** The currently running version. */
    private String currentVersion;

    /** The latest version found (null if no check has been done). */
    private String latestVersion;

    /** The release name/title from GitHub (null if no check or error). */
    private String releaseName;

    /** URL to the GitHub release page (null if no check or error). */
    private String releaseUrl;

    /** Whether the latest release is a pre-release. */
    private boolean prerelease;

    /** Whether a download is currently in progress. */
    private boolean downloading;

    /** Whether an update has been downloaded and is pending restart. */
    private boolean pendingRestart;

    /** Path to the downloaded JAR (if pending restart), null otherwise. */
    private String downloadedPath;

    /** Error message if the last check or download failed, null otherwise. */
    private String error;
}

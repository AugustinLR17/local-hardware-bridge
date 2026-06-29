package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents the subset of the GitHub Releases API response that the
 * update checker needs.
 *
 * <p>The JSON is fetched from
 * {@code https://api.github.com/repos/AugustinLR17/local-hardware-bridge/releases/latest}.
 * Unknown fields are ignored so future API additions do not break parsing.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReleaseInfo {

    /** The tag name, e.g. {@code "v2.1.0"}. */
    private String tagName;

    /** The release name/title (human-readable). */
    private String name;

    /** Whether the release is a pre-release (alpha/beta/RC). */
    @JsonProperty("prerelease")
    private boolean preRelease;

    /** The HTML URL of the release page on GitHub. */
    @JsonProperty("html_url")
    private String htmlUrl;

    /** Markdown body of the release notes. */
    private String body;

    /** ISO-8601 publication date, e.g. {@code "2026-01-15T10:30:00Z"}. */
    @JsonProperty("published_at")
    private String publishedAt;

    /** Assets attached to the release (JAR, EXE, AppImage, DMG, TUI binaries). */
    private List<Asset> assets;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Asset {
        private String name;
        /** Direct download URL for the asset. */
        @JsonProperty("browser_download_url")
        private String browserDownloadUrl;
        /** File size in bytes. */
        private long size;
        /** SHA-256 digest if GitHub provides one (often null). */
        @JsonProperty("digest")
        private String digest;
    }

    /**
     * Returns the tag name without a leading {@code v} (e.g. {@code "2.1.0"}).
     * Useful for comparison with {@code Constants.VERSION}.
     */
    public String getVersionWithoutPrefix() {
        if (tagName == null) {
            return null;
        }
        if (tagName.startsWith("v") || tagName.startsWith("V")) {
            return tagName.substring(1);
        }
        return tagName;
    }

    /**
     * Finds the asset whose name contains {@code substring} (case-insensitive).
     * Returns {@code null} if no match.
     */
    public Asset findAsset(String substring) {
        if (assets == null || substring == null) {
            return null;
        }
        String lower = substring.toLowerCase(java.util.Locale.ROOT);
        for (Asset a : assets) {
            if (a.getName() != null && a.getName().toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                return a;
            }
        }
        return null;
    }
}

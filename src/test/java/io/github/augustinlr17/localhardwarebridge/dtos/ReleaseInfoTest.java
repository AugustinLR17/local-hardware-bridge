package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ReleaseInfo} DTO: JSON parsing, version prefix
 * stripping, and asset lookup.
 * Fully hermetic — no network.
 */
public class ReleaseInfoTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    public void parseFullGitHubReleaseJson() throws Exception {
        String json = "{"
                + "\"tag_name\":\"v2.1.0\","
                + "\"name\":\"Release 2.1.0\","
                + "\"prerelease\":false,"
                + "\"html_url\":\"https://github.com/AugustinLR17/local-hardware-bridge/releases/tag/v2.1.0\","
                + "\"body\":\"## What's New\\n- Feature A\\n- Bug fix B\","
                + "\"published_at\":\"2026-01-15T10:30:00Z\","
                + "\"assets\":["
                + "  {\"name\":\"local-hardware-bridge-2.1.0.jar\","
                + "   \"browser_download_url\":\"https://github.com/.../local-hardware-bridge-2.1.0.jar\","
                + "   \"size\":15000000,"
                + "   \"digest\":null},"
                + "  {\"name\":\"Local-Hardware-Bridge-2.1.0.exe\","
                + "   \"browser_download_url\":\"https://github.com/.../Local-Hardware-Bridge-2.1.0.exe\","
                + "   \"size\":85000000}"
                + "],"
                + "\"id\":12345,"
                + "\"target_commitish\":\"master\","
                + "\"url\":\"https://api.github.com/...\""
                + "}";

        ReleaseInfo release = mapper().readValue(json, ReleaseInfo.class);

        assertEquals("v2.1.0", release.getTagName());
        assertEquals("Release 2.1.0", release.getName());
        assertFalse(release.isPreRelease());
        assertEquals("https://github.com/AugustinLR17/local-hardware-bridge/releases/tag/v2.1.0", release.getHtmlUrl());
        assertEquals("2026-01-15T10:30:00Z", release.getPublishedAt());
        assertNotNull(release.getBody());
        assertTrue(release.getBody().contains("Feature A"));
        assertNotNull(release.getAssets());
        assertEquals(2, release.getAssets().size());
    }

    @Test
    public void parsePrereleaseRelease() throws Exception {
        String json = "{\"tag_name\":\"v2.2.0-rc.1\",\"name\":\"RC 1\",\"prerelease\":true,\"html_url\":\"http://example.com\"}";
        ReleaseInfo release = mapper().readValue(json, ReleaseInfo.class);
        assertTrue(release.isPreRelease());
        assertEquals("v2.2.0-rc.1", release.getTagName());
    }

    @Test
    public void unknownFieldsAreIgnored() throws Exception {
        String json = "{\"tag_name\":\"v1.0.0\",\"unknown_field\":\"value\",\"another\":[1,2,3]}";
        ReleaseInfo release = mapper().readValue(json, ReleaseInfo.class);
        assertEquals("v1.0.0", release.getTagName());
    }

    @Test
    public void emptyJsonProducesDefaults() throws Exception {
        ReleaseInfo release = mapper().readValue("{}", ReleaseInfo.class);
        assertNull(release.getTagName());
        assertNull(release.getName());
        assertFalse(release.isPreRelease());
        assertNull(release.getAssets());
    }

    @Test
    public void getVersionWithoutPrefixStripsV() {
        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("v2.1.0");
        assertEquals("2.1.0", release.getVersionWithoutPrefix());
    }

    @Test
    public void getVersionWithoutPrefixStripsCapitalV() {
        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("V2.1.0");
        assertEquals("2.1.0", release.getVersionWithoutPrefix());
    }

    @Test
    public void getVersionWithoutPrefixWithoutV() {
        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("2.1.0");
        assertEquals("2.1.0", release.getVersionWithoutPrefix());
    }

    @Test
    public void getVersionWithoutPrefixNullReturnsNull() {
        ReleaseInfo release = new ReleaseInfo();
        assertNull(release.getVersionWithoutPrefix());
    }

    @Test
    public void findAssetBySubstring() {
        ReleaseInfo release = new ReleaseInfo();
        ReleaseInfo.Asset jar = new ReleaseInfo.Asset();
        jar.setName("local-hardware-bridge-2.1.0.jar");
        ReleaseInfo.Asset exe = new ReleaseInfo.Asset();
        exe.setName("Local-Hardware-Bridge-2.1.0.exe");
        release.setAssets(java.util.List.of(jar, exe));

        ReleaseInfo.Asset found = release.findAsset("local-hardware-bridge");
        assertNotNull(found);
        assertEquals("local-hardware-bridge-2.1.0.jar", found.getName());
    }

    @Test
    public void findAssetCaseInsensitive() {
        ReleaseInfo release = new ReleaseInfo();
        ReleaseInfo.Asset exe = new ReleaseInfo.Asset();
        exe.setName("LOCAL-HARDWARE-BRIDGE-2.1.0.EXE");
        release.setAssets(java.util.List.of(exe));

        ReleaseInfo.Asset found = release.findAsset(".exe");
        assertNotNull(found);
        assertEquals("LOCAL-HARDWARE-BRIDGE-2.1.0.EXE", found.getName());
    }

    @Test
    public void findAssetReturnsNullWhenNoMatch() {
        ReleaseInfo release = new ReleaseInfo();
        ReleaseInfo.Asset jar = new ReleaseInfo.Asset();
        jar.setName("local-hardware-bridge-2.1.0.jar");
        release.setAssets(java.util.List.of(jar));

        assertNull(release.findAsset(".dmg"));
    }

    @Test
    public void findAssetReturnsFirstMatch() {
        ReleaseInfo release = new ReleaseInfo();
        ReleaseInfo.Asset jar1 = new ReleaseInfo.Asset();
        jar1.setName("local-hardware-bridge-2.1.0.jar");
        ReleaseInfo.Asset jar2 = new ReleaseInfo.Asset();
        jar2.setName("local-hardware-bridge-2.0.0.jar");
        release.setAssets(java.util.List.of(jar1, jar2));

        ReleaseInfo.Asset found = release.findAsset("local-hardware-bridge");
        assertEquals("local-hardware-bridge-2.1.0.jar", found.getName());
    }

    @Test
    public void findAssetNullAssetsReturnsNull() {
        ReleaseInfo release = new ReleaseInfo();
        assertNull(release.findAsset("test"));
    }

    @Test
    public void findAssetNullSubstringReturnsNull() {
        ReleaseInfo release = new ReleaseInfo();
        release.setAssets(java.util.List.of());
        assertNull(release.findAsset(null));
    }

    @Test
    public void assetFieldsParsedCorrectly() throws Exception {
        String json = "{\"name\":\"test.jar\",\"browser_download_url\":\"http://example.com/test.jar\",\"size\":12345}";
        ReleaseInfo.Asset asset = mapper().readValue(json, ReleaseInfo.Asset.class);
        assertEquals("test.jar", asset.getName());
        assertEquals("http://example.com/test.jar", asset.getBrowserDownloadUrl());
        assertEquals(12345, asset.getSize());
    }

    @Test
    public void roundTripSerialization() throws Exception {
        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("v3.0.0");
        release.setName("Big Release");
        release.setPreRelease(false);
        release.setHtmlUrl("http://example.com/v3.0.0");

        String json = mapper().writeValueAsString(release);
        ReleaseInfo restored = mapper().readValue(json, ReleaseInfo.class);
        assertEquals("v3.0.0", restored.getTagName());
        assertEquals("Big Release", restored.getName());
    }

    // --- Real-world GitHub API JSON fixtures ---

    @Test
    public void parseRealisticLatestReleaseJson() throws Exception {
        // A realistic GitHub /releases/latest response (trimmed to relevant fields)
        String json = "{"
            + "\"url\":\"https://api.github.com/repos/AugustinLR17/local-hardware-bridge/releases/123456\","
            + "\"assets_url\":\"https://api.github.com/repos/AugustinLR17/local-hardware-bridge/releases/123456/assets\","
            + "\"upload_url\":\"https://uploads.github.com/repos/AugustinLR17/local-hardware-bridge/releases/123456/assets{?name,label}\","
            + "\"html_url\":\"https://github.com/AugustinLR17/local-hardware-bridge/releases/tag/v2.1.0\","
            + "\"id\":123456,"
            + "\"author\":{\"login\":\"AugustinLR17\",\"id\":78910,\"type\":\"User\"},"
            + "\"node_id\":\"MDc6UmVsZWFzZTEyMzQ1Ng==\","
            + "\"tag_name\":\"v2.1.0\","
            + "\"target_commitish\":\"master\","
            + "\"name\":\"Local Hardware Bridge 2.1.0\","
            + "\"draft\":false,"
            + "\"prerelease\":false,"
            + "\"created_at\":\"2026-01-10T09:00:00Z\","
            + "\"published_at\":\"2026-01-15T10:30:00Z\","
            + "\"assets\":["
            + "  {\"url\":\"https://api.github.com/repos/AugustinLR17/local-hardware-bridge/releases/assets/111\","
            + "   \"id\":111,"
            + "   \"node_id\":\"MDEyOlJlbGVhc2VBc3NldDExMQ==\","
            + "   \"name\":\"local-hardware-bridge-2.1.0.jar\","
            + "   \"label\":null,"
            + "   \"uploader\":{\"login\":\"AugustinLR17\"},"
            + "   \"content_type\":\"application/java-archive\","
            + "   \"state\":\"uploaded\","
            + "   \"size\":15000000,"
            + "   \"download_count\":523,"
            + "   \"created_at\":\"2026-01-15T10:25:00Z\","
            + "   \"updated_at\":\"2026-01-15T10:30:00Z\","
            + "   \"browser_download_url\":\"https://github.com/AugustinLR17/local-hardware-bridge/releases/download/v2.1.0/local-hardware-bridge-2.1.0.jar\","
            + "   \"digest\":null"
            + "  },"
            + "  {\"url\":\"https://api.github.com/repos/AugustinLR17/local-hardware-bridge/releases/assets/112\","
            + "   \"id\":112,"
            + "   \"name\":\"Local-Hardware-Bridge-2.1.0.exe\","
            + "   \"content_type\":\"application/x-msdownload\","
            + "   \"state\":\"uploaded\","
            + "   \"size\":85000000,"
            + "   \"download_count\":412,"
            + "   \"browser_download_url\":\"https://github.com/AugustinLR17/local-hardware-bridge/releases/download/v2.1.0/Local-Hardware-Bridge-2.1.0.exe\""
            + "  },"
            + "  {\"url\":\"https://api.github.com/repos/AugustinLR17/local-hardware-bridge/releases/assets/113\","
            + "   \"id\":113,"
            + "   \"name\":\"local-hardware-bridge-2.1.0-x86_64.AppImage\","
            + "   \"content_type\":\"application/octet-stream\","
            + "   \"state\":\"uploaded\","
            + "   \"size\":12000000,"
            + "   \"browser_download_url\":\"https://github.com/AugustinLR17/local-hardware-bridge/releases/download/v2.1.0/local-hardware-bridge-2.1.0-x86_64.AppImage\""
            + "  },"
            + "  {\"url\":\"https://api.github.com/repos/AugustinLR17/local-hardware-bridge/releases/assets/114\","
            + "   \"id\":114,"
            + "   \"name\":\"lhb-tui-linux-amd64\","
            + "   \"content_type\":\"application/octet-stream\","
            + "   \"state\":\"uploaded\","
            + "   \"size\":3000000,"
            + "   \"browser_download_url\":\"https://github.com/AugustinLR17/local-hardware-bridge/releases/download/v2.1.0/lhb-tui-linux-amd64\""
            + "  }"
            + "],"
            + "\"tarball_url\":\"https://api.github.com/repos/AugustinLR17/local-hardware-bridge/tarball/v2.1.0\","
            + "\"zipball_url\":\"https://api.github.com/repos/AugustinLR17/local-hardware-bridge/zipball/v2.1.0\","
            + "\"body\":\"## What's New\\n\\n- Auto-update system\\n- Bug fixes\\n\\n**Full Changelog**: https://github.com/AugustinLR17/local-hardware-bridge/compare/v2.0.0...v2.1.0\""
            + "}";

        ReleaseInfo release = mapper().readValue(json, ReleaseInfo.class);

        assertEquals("v2.1.0", release.getTagName());
        assertEquals("Local Hardware Bridge 2.1.0", release.getName());
        assertFalse(release.isPreRelease());
        assertEquals("https://github.com/AugustinLR17/local-hardware-bridge/releases/tag/v2.1.0", release.getHtmlUrl());
        assertEquals("2026-01-15T10:30:00Z", release.getPublishedAt());
        assertNotNull(release.getBody());
        assertTrue(release.getBody().contains("Auto-update system"));
        assertNotNull(release.getAssets());
        assertEquals(4, release.getAssets().size());

        // Check the JAR asset
        ReleaseInfo.Asset jar = release.getAssets().get(0);
        assertEquals("local-hardware-bridge-2.1.0.jar", jar.getName());
        assertEquals(15000000, jar.getSize());
        assertEquals("https://github.com/AugustinLR17/local-hardware-bridge/releases/download/v2.1.0/local-hardware-bridge-2.1.0.jar",
            jar.getBrowserDownloadUrl());

        // findAsset should find the JAR
        ReleaseInfo.Asset found = release.findAsset("local-hardware-bridge");
        assertNotNull(found);
        assertEquals("local-hardware-bridge-2.1.0.jar", found.getName());
    }

    @Test
    public void parseReleasesArrayJson() throws Exception {
        // The /releases endpoint returns an array (for pre-release mode)
        String json = "["
            + "{\"tag_name\":\"v2.2.0-rc.1\",\"name\":\"RC 1\",\"prerelease\":true,\"html_url\":\"http://example.com/v2.2.0-rc.1\",\"assets\":[]},"
            + "{\"tag_name\":\"v2.1.0\",\"name\":\"Stable 2.1.0\",\"prerelease\":false,\"html_url\":\"http://example.com/v2.1.0\",\"assets\":[]}"
            + "]";

        ReleaseInfo[] releases = mapper().readValue(json, ReleaseInfo[].class);
        assertEquals(2, releases.length);
        assertEquals("v2.2.0-rc.1", releases[0].getTagName());
        assertTrue(releases[0].isPreRelease());
        assertEquals("v2.1.0", releases[1].getTagName());
        assertFalse(releases[1].isPreRelease());
    }

    @Test
    public void parseEmptyReleasesArray() throws Exception {
        ReleaseInfo[] releases = mapper().readValue("[]", ReleaseInfo[].class);
        assertEquals(0, releases.length);
    }

    @Test
    public void parseReleaseWithNoAssets() throws Exception {
        String json = "{\"tag_name\":\"v1.0.0\",\"name\":\"Minimal\",\"prerelease\":false,\"assets\":[]}";
        ReleaseInfo release = mapper().readValue(json, ReleaseInfo.class);
        assertNotNull(release.getAssets());
        assertEquals(0, release.getAssets().size());
    }

    @Test
    public void parseReleaseWithNullAssets() throws Exception {
        // Some releases might have null assets
        String json = "{\"tag_name\":\"v1.0.0\",\"name\":\"Null Assets\",\"prerelease\":false}";
        ReleaseInfo release = mapper().readValue(json, ReleaseInfo.class);
        assertNull(release.getAssets());
    }

    @Test
    public void parseDraftRelease() throws Exception {
        // Draft releases should still parse (though the API usually excludes them)
        String json = "{\"tag_name\":\"v3.0.0\",\"name\":\"Draft\",\"prerelease\":false,\"draft\":true}";
        ReleaseInfo release = mapper().readValue(json, ReleaseInfo.class);
        assertEquals("v3.0.0", release.getTagName());
        // draft field is not mapped (unknown field ignored)
    }

    @Test
    public void parseReleaseWithDigestInAsset() throws Exception {
        // If GitHub provides a digest (SHA-256)
        String json = "{\"name\":\"signed.jar\",\"browser_download_url\":\"http://example.com/signed.jar\",\"size\":1000,\"digest\":\"sha256:abc123\"}";
        ReleaseInfo.Asset asset = mapper().readValue(json, ReleaseInfo.Asset.class);
        assertEquals("sha256:abc123", asset.getDigest());
    }

    @Test
    public void parseReleaseWithMarkdownBody() throws Exception {
        String markdown = "## Changes\n\n- Item 1\n- Item 2\n\n```java\ncode block\n```";
        String json = "{\"tag_name\":\"v1.0.0\",\"body\":\"" +
            markdown.replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ReleaseInfo release = mapper().readValue(json, ReleaseInfo.class);
        assertNotNull(release.getBody());
        assertTrue(release.getBody().contains("## Changes"));
        assertTrue(release.getBody().contains("code block"));
    }

    @Test
    public void parseReleaseWithUnicodeInName() throws Exception {
        String json = "{\"tag_name\":\"v1.0.0\",\"name\":\"Release \\u00e9\\u00e8\\u00ea\"}";
        ReleaseInfo release = mapper().readValue(json, ReleaseInfo.class);
        assertNotNull(release.getName());
        assertTrue(release.getName().contains("\u00e9"));
    }

    // --- findAsset edge cases ---

    @Test
    public void findAssetPrefersFirstMatch() {
        ReleaseInfo release = new ReleaseInfo();
        ReleaseInfo.Asset a1 = new ReleaseInfo.Asset();
        a1.setName("local-hardware-bridge-2.1.0.jar");
        ReleaseInfo.Asset a2 = new ReleaseInfo.Asset();
        a2.setName("local-hardware-bridge-2.0.0.jar");
        release.setAssets(java.util.List.of(a1, a2));

        ReleaseInfo.Asset found = release.findAsset("local-hardware-bridge");
        assertEquals("local-hardware-bridge-2.1.0.jar", found.getName());
    }

    @Test
    public void findAssetWithDotJarExtension() {
        ReleaseInfo release = new ReleaseInfo();
        ReleaseInfo.Asset a = new ReleaseInfo.Asset();
        a.setName("app-2.1.0.jar");
        release.setAssets(java.util.List.of(a));

        ReleaseInfo.Asset found = release.findAsset(".jar");
        assertNotNull(found);
    }

    @Test
    public void findAssetWithExeExtension() {
        ReleaseInfo release = new ReleaseInfo();
        ReleaseInfo.Asset a = new ReleaseInfo.Asset();
        a.setName("Local-Hardware-Bridge-2.1.0.exe");
        release.setAssets(java.util.List.of(a));

        ReleaseInfo.Asset found = release.findAsset(".exe");
        assertNotNull(found);
    }

    @Test
    public void findAssetWithAppImageExtension() {
        ReleaseInfo release = new ReleaseInfo();
        ReleaseInfo.Asset a = new ReleaseInfo.Asset();
        a.setName("local-hardware-bridge-2.1.0-x86_64.AppImage");
        release.setAssets(java.util.List.of(a));

        ReleaseInfo.Asset found = release.findAsset("appimage");
        assertNotNull(found);
    }

    @Test
    public void findAssetWithTuiBinary() {
        ReleaseInfo release = new ReleaseInfo();
        ReleaseInfo.Asset a = new ReleaseInfo.Asset();
        a.setName("lhb-tui-linux-amd64");
        release.setAssets(java.util.List.of(a));

        ReleaseInfo.Asset found = release.findAsset("tui");
        assertNotNull(found);
    }

    @Test
    public void findAssetWithEmptyString() {
        ReleaseInfo release = new ReleaseInfo();
        ReleaseInfo.Asset a = new ReleaseInfo.Asset();
        a.setName("anything.jar");
        release.setAssets(java.util.List.of(a));

        // Empty string is a substring of everything → should match
        ReleaseInfo.Asset found = release.findAsset("");
        assertNotNull(found);
    }

    @Test
    public void findAssetMultipleAssetsMatching() {
        ReleaseInfo release = new ReleaseInfo();
        ReleaseInfo.Asset jar = new ReleaseInfo.Asset();
        jar.setName("local-hardware-bridge-2.1.0.jar");
        ReleaseInfo.Asset exe = new ReleaseInfo.Asset();
        exe.setName("Local-Hardware-Bridge-2.1.0.exe");
        release.setAssets(java.util.List.of(jar, exe));

        // "local-hardware-bridge" matches both, returns first (jar)
        ReleaseInfo.Asset found = release.findAsset("local-hardware-bridge");
        assertEquals("local-hardware-bridge-2.1.0.jar", found.getName());
    }

    @Test
    public void findAssetWithEmptyAssetList() {
        ReleaseInfo release = new ReleaseInfo();
        release.setAssets(java.util.List.of());
        assertNull(release.findAsset("test"));
    }

    // --- Asset field edge cases ---

    @Test
    public void assetWithNullDownloadUrl() {
        ReleaseInfo.Asset asset = new ReleaseInfo.Asset();
        asset.setName("test.jar");
        asset.setBrowserDownloadUrl(null);
        assertNull(asset.getBrowserDownloadUrl());
    }

    @Test
    public void assetWithZeroSize() {
        ReleaseInfo.Asset asset = new ReleaseInfo.Asset();
        asset.setSize(0);
        assertEquals(0, asset.getSize());
    }

    @Test
    public void assetWithNegativeSize() {
        ReleaseInfo.Asset asset = new ReleaseInfo.Asset();
        asset.setSize(-1);
        assertEquals(-1, asset.getSize());
    }

    @Test
    public void assetSerializationRoundTrip() throws Exception {
        ReleaseInfo.Asset asset = new ReleaseInfo.Asset();
        asset.setName("test.jar");
        asset.setBrowserDownloadUrl("http://example.com/test.jar");
        asset.setSize(12345);
        asset.setDigest("sha256:abcdef");

        String json = mapper().writeValueAsString(asset);
        ReleaseInfo.Asset restored = mapper().readValue(json, ReleaseInfo.Asset.class);
        assertEquals("test.jar", restored.getName());
        assertEquals("http://example.com/test.jar", restored.getBrowserDownloadUrl());
        assertEquals(12345, restored.getSize());
        assertEquals("sha256:abcdef", restored.getDigest());
    }

    // --- getVersionWithoutPrefix edge cases ---

    @Test
    public void getVersionWithoutPrefixWithDoubleV() {
        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("vv2.0.0"); // Double v — only first is stripped
        assertEquals("v2.0.0", release.getVersionWithoutPrefix());
    }

    @Test
    public void getVersionWithoutPrefixWithPreRelease() {
        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("v2.0.0-rc.1");
        assertEquals("2.0.0-rc.1", release.getVersionWithoutPrefix());
    }

    @Test
    public void getVersionWithoutPrefixEmptyString() {
        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("");
        assertEquals("", release.getVersionWithoutPrefix());
    }

    @Test
    public void getVersionWithoutPrefixOnlyV() {
        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("v");
        assertEquals("", release.getVersionWithoutPrefix());
    }
}

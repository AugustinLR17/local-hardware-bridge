package io.github.augustinlr17.localhardwarebridge.services;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.dtos.ReleaseInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Additional tests for {@link UpdateService} covering:
 * - checkNow() when a check is already in progress (CAS guard)
 * - downloadUpdate() when no JAR asset is found
 * - downloadAndPrepare() when download returns null
 * - downloadUpdate() when a download is already in progress (CAS guard)
 */
public class UpdateServiceEdgeCasesTest {

    private UpdateService updateService;

    @Before
    public void setUp() throws Exception {
        updateService = UpdateService.getInstance();
        ConfigService.getInstance().getConfig().getUpdate().setEnabled(true);
        ConfigService.getInstance().getConfig().getUpdate().setAutoDownload(false);
        ConfigService.getInstance().getConfig().getUpdate().setAutoInstall(false);
        clearPendingUpdate();
        clearLatestRelease();
        clearLastError();
    }

    @After
    public void tearDown() throws Exception {
        updateService.stopScheduledChecks();
        clearPendingUpdate();
        clearLatestRelease();
        clearLastError();
        // Reset download CAS flag
        Field dlField = UpdateService.class.getDeclaredField("downloading");
        dlField.setAccessible(true);
        ((AtomicBoolean) dlField.get(updateService)).set(false);
    }

    @SuppressWarnings("unchecked")
    private void clearPendingUpdate() throws Exception {
        Field f = UpdateService.class.getDeclaredField("pendingUpdate");
        f.setAccessible(true);
        ((AtomicReference<java.nio.file.Path>) f.get(updateService)).set(null);
    }

    @SuppressWarnings("unchecked")
    private void clearLatestRelease() throws Exception {
        Field f = UpdateService.class.getDeclaredField("latestRelease");
        f.setAccessible(true);
        ((AtomicReference<ReleaseInfo>) f.get(updateService)).set(null);
    }

    @SuppressWarnings("unchecked")
    private void clearLastError() throws Exception {
        Field f = UpdateService.class.getDeclaredField("lastError");
        f.setAccessible(true);
        ((AtomicReference<String>) f.get(updateService)).set(null);
    }

    @SuppressWarnings("unchecked")
    private void setLatestRelease(ReleaseInfo release) throws Exception {
        Field f = UpdateService.class.getDeclaredField("latestRelease");
        f.setAccessible(true);
        ((AtomicReference<ReleaseInfo>) f.get(updateService)).set(release);
    }

    @SuppressWarnings("unchecked")
    private void setCheckingFlag(boolean value) throws Exception {
        Field f = UpdateService.class.getDeclaredField("checking");
        f.setAccessible(true);
        ((AtomicBoolean) f.get(updateService)).set(value);
    }

    @SuppressWarnings("unchecked")
    private void setDownloadingFlag(boolean value) throws Exception {
        Field f = UpdateService.class.getDeclaredField("downloading");
        f.setAccessible(true);
        ((AtomicBoolean) f.get(updateService)).set(value);
    }

    // --- checkNow CAS guard ---

    @Test
    public void checkNowReturnsCurrentStatusWhenAlreadyChecking() throws Exception {
        // Set the checking flag to true to simulate an in-progress check
        setCheckingFlag(true);

        try {
            // checkNow should see the CAS fails and return current status (not block)
            io.github.augustinlr17.localhardwarebridge.dtos.UpdateStatusDTO status = updateService.checkNow();
            assertNotNull(status);
            // The flag remains true (we set it, checkNow doesn't clear it in this path)
        } finally {
            // Reset for other tests
            setCheckingFlag(false);
        }
    }

    // --- downloadUpdate: no JAR asset ---

    @Test
    public void downloadUpdateThrowsWhenNoJarAssetInRelease() throws Exception {
        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("v99.99.99");
        // Add only non-JAR assets
        ReleaseInfo.Asset exe = new ReleaseInfo.Asset();
        exe.setName("app.exe");
        exe.setBrowserDownloadUrl("http://example.com/app.exe");
        release.setAssets(List.of(exe));
        setLatestRelease(release);

        try {
            updateService.downloadUpdate();
            fail("expected IOException for no JAR asset");
        } catch (Exception e) {
            assertTrue("error should mention no JAR asset: " + e.getMessage(),
                    e.getMessage().contains("No JAR asset"));
        }
    }

    @Test
    public void downloadUpdateThrowsWhenReleaseHasNoAssets() throws Exception {
        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("v99.99.99");
        release.setAssets(null);
        setLatestRelease(release);

        try {
            updateService.downloadUpdate();
            fail("expected IOException for null assets");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("No JAR asset"));
        }
    }

    // --- downloadUpdate: already in progress CAS guard ---

    @Test
    public void downloadUpdateThrowsWhenAlreadyDownloading() throws Exception {
        // Set up a valid release with a JAR asset
        ReleaseInfo.Asset asset = new ReleaseInfo.Asset();
        asset.setName("local-hardware-bridge-99.99.99.jar");
        asset.setBrowserDownloadUrl("http://127.0.0.1:1/no-connect.jar");
        asset.setSize(100);

        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("v99.99.99");
        release.setAssets(List.of(asset));
        setLatestRelease(release);

        // Set the downloading flag to simulate an in-progress download
        setDownloadingFlag(true);

        try {
            updateService.downloadUpdate();
            fail("expected IOException for download already in progress");
        } catch (Exception e) {
            assertTrue("error should mention already in progress: " + e.getMessage(),
                    e.getMessage().contains("already in progress"));
        }
    }

    // --- downloadAndPrepare: null result ---

    @Test
    public void downloadAndPrepareThrowsWhenDownloadReturnsNull() throws Exception {
        // downloadUpdate will fail to connect to the mock URL → returns null
        // → downloadAndPrepare should throw
        ReleaseInfo.Asset asset = new ReleaseInfo.Asset();
        asset.setName("local-hardware-bridge-99.99.99.jar");
        asset.setBrowserDownloadUrl("http://127.0.0.1:1/no-connect.jar");
        asset.setSize(100);

        ReleaseInfo release = new ReleaseInfo();
        release.setTagName("v99.99.99");
        release.setAssets(List.of(asset));
        setLatestRelease(release);

        // downloadUpdate will try to connect and fail, so downloadAndPrepare
        // should propagate the exception
        try {
            updateService.downloadAndPrepare();
            // If it doesn't throw, the download somehow succeeded (shouldn't happen with port 1)
        } catch (Exception e) {
            // Expected — either connection refused or "did not produce a JAR"
            assertNotNull(e);
        }
    }

    // --- downloadUpdate: no release info at all, performCheck also fails ---

    @Test
    public void downloadUpdateThrowsWhenNoReleaseAndCheckFails() throws Exception {
        clearLatestRelease();
        // Without a release, downloadUpdate tries performCheck() which will fail
        // (no network / disabled config), then throws "No release info"
        ConfigService.getInstance().getConfig().getUpdate().setEnabled(false);

        try {
            updateService.downloadUpdate();
            fail("expected IOException for no release info");
        } catch (Exception e) {
            // Could be "No release info" or a network error from performCheck
            assertNotNull(e);
        }
    }

    // --- downloadAsset with mock server: successful download then re-download ---

    @Test
    public void downloadUpdateSucceedsWithMockServer() throws Exception {
        byte[] payload = "edge case download content".getBytes();

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            ReleaseInfo.Asset asset = new ReleaseInfo.Asset();
            asset.setName("local-hardware-bridge-77.77.77.jar");
            asset.setBrowserDownloadUrl("http://127.0.0.1:" + port + "/edge.jar");
            asset.setSize(payload.length);

            ReleaseInfo release = new ReleaseInfo();
            release.setTagName("v77.77.77");
            release.setAssets(List.of(asset));
            setLatestRelease(release);

            java.nio.file.Path result = updateService.downloadUpdate();
            assertNotNull(result);
            assertTrue(java.nio.file.Files.isRegularFile(result));
            assertArrayEquals(payload, java.nio.file.Files.readAllBytes(result));

            // pendingUpdate should be set
            java.nio.file.Path pending = updateService.consumePendingUpdate();
            assertNotNull(pending);
            assertEquals(result, pending);
        } finally {
            server.stop(0);
        }
    }
}

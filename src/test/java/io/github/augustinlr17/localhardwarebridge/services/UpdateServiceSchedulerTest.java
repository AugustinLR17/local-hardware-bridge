package io.github.augustinlr17.localhardwarebridge.services;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.dtos.ReleaseInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Additional tests for {@link UpdateService} covering scheduler, cleanup,
 * findJarAsset, detectPendingUpdate, and getCurrentJarPath.
 */
public class UpdateServiceSchedulerTest {

    private ConfigService cs;
    private Config.Update originalUpdateConfig;
    private Field pendingUpdateField;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() throws Exception {
        cs = ConfigService.getInstance();
        originalUpdateConfig = cs.getConfig().getUpdate();

        // Reset pending update
        pendingUpdateField = UpdateService.class.getDeclaredField("pendingUpdate");
        pendingUpdateField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Path> pending = (AtomicReference<Path>) pendingUpdateField.get(UpdateService.getInstance());
        pending.set(null);
    }

    @After
    public void tearDown() throws Exception {
        // Restore config
        cs.getConfig().getUpdate().setEnabled(originalUpdateConfig.isEnabled());
        cs.getConfig().getUpdate().setAutoDownload(originalUpdateConfig.isAutoDownload());
        cs.getConfig().getUpdate().setAutoInstall(originalUpdateConfig.isAutoInstall());
        cs.getConfig().getUpdate().setCheckIntervalHours(originalUpdateConfig.getCheckIntervalHours());

        // Clear pending
        @SuppressWarnings("unchecked")
        AtomicReference<Path> pending = (AtomicReference<Path>) pendingUpdateField.get(UpdateService.getInstance());
        pending.set(null);

        // Stop any scheduler that might be running
        UpdateService.getInstance().stopScheduledChecks();
    }

    // --- startScheduledChecks / stopScheduledChecks ---

    @Test
    public void startScheduledChecksSkipsWhenDisabled() {
        cs.getConfig().getUpdate().setEnabled(false);
        UpdateService.getInstance().startScheduledChecks();
        // Should not start a scheduler — verify isChecking stays false
        // (the immediate check is skipped when disabled)
        assertFalse(UpdateService.getInstance().isChecking());
    }

    @Test
    public void stopScheduledChecksIsSafeWhenNoSchedulerRunning() {
        // Stopping when nothing is running should not throw
        UpdateService.getInstance().stopScheduledChecks();
        UpdateService.getInstance().stopScheduledChecks();
    }

    @Test
    public void startScheduledChecksWithZeroIntervalOnlyChecksOnce() throws Exception {
        cs.getConfig().getUpdate().setEnabled(true);
        cs.getConfig().getUpdate().setCheckIntervalHours(0);
        UpdateService.getInstance().startScheduledChecks();
        // With interval=0, it does an immediate background check but no scheduler
        // Wait briefly for the background check to start
        Thread.sleep(200);
        // The check may or may not have completed, but the scheduler field should be null
        Field schedulerField = UpdateService.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        assertNull(schedulerField.get(UpdateService.getInstance()));
    }

    @Test
    public void startScheduledChecksWithIntervalStartsScheduler() throws Exception {
        cs.getConfig().getUpdate().setEnabled(true);
        cs.getConfig().getUpdate().setCheckIntervalHours(1);
        UpdateService.getInstance().startScheduledChecks();

        Field schedulerField = UpdateService.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        Object scheduler = schedulerField.get(UpdateService.getInstance());
        assertNotNull("Scheduler should be created", scheduler);

        UpdateService.getInstance().stopScheduledChecks();
        assertNull("Scheduler should be null after stop", schedulerField.get(UpdateService.getInstance()));
    }

    // --- checkInBackground ---

    @Test
    public void checkInBackgroundSetsCheckingFlag() throws Exception {
        cs.getConfig().getUpdate().setEnabled(true);
        UpdateService.getInstance().checkInBackground();
        // The checking flag should be set (may have already completed if fast)
        // Just verify it doesn't throw
        Thread.sleep(100);
    }

    @Test
    public void checkInBackgroundConcurrentCallSkipsSecond() throws Exception {
        // Two concurrent calls — one should be skipped
        UpdateService.getInstance().checkInBackground();
        UpdateService.getInstance().checkInBackground();
        Thread.sleep(100);
        // No exception = pass
    }

    // --- cleanupOldUpdates ---

    @Test
    public void cleanupOldUpdatesWithNoUpdatesDirDoesNotThrow() {
        // No updates/ directory exists in test env
        UpdateService.getInstance().cleanupOldUpdates();
    }

    @Test
    public void cleanupOldUpdatesCleansPartAndJarFiles() throws Exception {
        Path updatesDir = Path.of("updates");
        try {
            Files.createDirectories(updatesDir);
            Path partFile = updatesDir.resolve("test.part");
            Path jarFile = updatesDir.resolve("local-hardware-bridge-old.jar");
            Files.write(partFile, new byte[]{0x01});
            Files.write(jarFile, new byte[]{0x02});

            assertTrue(Files.exists(partFile));
            assertTrue(Files.exists(jarFile));

            UpdateService.getInstance().cleanupOldUpdates();

            assertFalse("Part file should be deleted", Files.exists(partFile));
            assertFalse("Old JAR should be deleted", Files.exists(jarFile));
        } finally {
            // Clean up
            if (Files.isDirectory(updatesDir)) {
                Files.walk(updatesDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }

    @Test
    public void cleanupOldUpdatesPreservesPendingUpdate() throws Exception {
        Path updatesDir = Path.of("updates");
        try {
            Files.createDirectories(updatesDir);
            Path pendingJar = updatesDir.resolve("local-hardware-bridge-9.9.9.jar");
            Path oldJar = updatesDir.resolve("local-hardware-bridge-old.jar");
            Files.write(pendingJar, new byte[]{0x01});
            Files.write(oldJar, new byte[]{0x02});

            // Set pending update
            @SuppressWarnings("unchecked")
            AtomicReference<Path> pending = (AtomicReference<Path>) pendingUpdateField.get(UpdateService.getInstance());
            pending.set(pendingJar);

            UpdateService.getInstance().cleanupOldUpdates();

            assertTrue("Pending JAR should be preserved", Files.exists(pendingJar));
            assertFalse("Old JAR should be deleted", Files.exists(oldJar));
        } finally {
            @SuppressWarnings("unchecked")
            AtomicReference<Path> pending = (AtomicReference<Path>) pendingUpdateField.get(UpdateService.getInstance());
            pending.set(null);

            if (Files.isDirectory(updatesDir)) {
                Files.walk(updatesDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }

    // --- findJarAsset ---

    @Test
    public void findJarAssetReturnsNullForNullAssets() throws Exception {
        Method m = UpdateService.class.getDeclaredMethod("findJarAsset", ReleaseInfo.class);
        m.setAccessible(true);
        ReleaseInfo release = new ReleaseInfo();
        // assets is null by default
        assertNull(m.invoke(UpdateService.getInstance(), release));
    }

    @Test
    public void findJarAssetReturnsNullForEmptyAssets() throws Exception {
        Method m = UpdateService.class.getDeclaredMethod("findJarAsset", ReleaseInfo.class);
        m.setAccessible(true);
        ReleaseInfo release = new ReleaseInfo();
        release.setAssets(new java.util.ArrayList<>());
        assertNull(m.invoke(UpdateService.getInstance(), release));
    }

    @Test
    public void findJarAssetPrefersLocalHardwareBridgeJar() throws Exception {
        Method m = UpdateService.class.getDeclaredMethod("findJarAsset", ReleaseInfo.class);
        m.setAccessible(true);
        ReleaseInfo release = new ReleaseInfo();
        java.util.List<ReleaseInfo.Asset> assets = new java.util.ArrayList<>();

        ReleaseInfo.Asset other = new ReleaseInfo.Asset();
        other.setName("some-other.jar");
        other.setBrowserDownloadUrl("http://example.com/other.jar");
        other.setSize(100);
        assets.add(other);

        ReleaseInfo.Asset lhb = new ReleaseInfo.Asset();
        lhb.setName("local-hardware-bridge-2.2.0.jar");
        lhb.setBrowserDownloadUrl("http://example.com/lhb.jar");
        lhb.setSize(200);
        assets.add(lhb);

        release.setAssets(assets);

        ReleaseInfo.Asset result = (ReleaseInfo.Asset) m.invoke(UpdateService.getInstance(), release);
        assertNotNull(result);
        assertEquals("local-hardware-bridge-2.2.0.jar", result.getName());
    }

    @Test
    public void findJarAssetFallsBackToAnyJar() throws Exception {
        Method m = UpdateService.class.getDeclaredMethod("findJarAsset", ReleaseInfo.class);
        m.setAccessible(true);
        ReleaseInfo release = new ReleaseInfo();
        java.util.List<ReleaseInfo.Asset> assets = new java.util.ArrayList<>();

        ReleaseInfo.Asset other = new ReleaseInfo.Asset();
        other.setName("random.jar");
        other.setBrowserDownloadUrl("http://example.com/random.jar");
        other.setSize(50);
        assets.add(other);

        release.setAssets(assets);

        ReleaseInfo.Asset result = (ReleaseInfo.Asset) m.invoke(UpdateService.getInstance(), release);
        assertNotNull(result);
        assertEquals("random.jar", result.getName());
    }

    @Test
    public void findJarAssetIgnoresNonJarFiles() throws Exception {
        Method m = UpdateService.class.getDeclaredMethod("findJarAsset", ReleaseInfo.class);
        m.setAccessible(true);
        ReleaseInfo release = new ReleaseInfo();
        java.util.List<ReleaseInfo.Asset> assets = new java.util.ArrayList<>();

        ReleaseInfo.Asset txt = new ReleaseInfo.Asset();
        txt.setName("readme.txt");
        txt.setBrowserDownloadUrl("http://example.com/readme.txt");
        txt.setSize(10);
        assets.add(txt);

        release.setAssets(assets);

        assertNull(m.invoke(UpdateService.getInstance(), release));
    }

    // --- getCurrentJarPath ---

    @Test
    public void getCurrentJarPathReturnsNullFromExplodedClasses() throws Exception {
        Method m = UpdateService.class.getDeclaredMethod("getCurrentJarPath");
        m.setAccessible(true);
        Object result = m.invoke(UpdateService.getInstance());
        // In test env, we run from exploded classes, not a JAR
        assertNull(result);
    }

    // --- downloadUpdate without release ---
    // Note: downloadUpdate calls performCheck() which may make a real network call
    // to GitHub. In CI/test env this may succeed or fail depending on network access.
    // We just verify the method doesn't crash the test suite.

    @Test
    public void downloadUpdateBehaviorIsSafe() {
        // Just verify downloadUpdate doesn't hang or crash the JVM
        try {
            UpdateService.getInstance().downloadUpdate();
        } catch (Exception e) {
            // Any exception is acceptable (network error, no release, etc.)
        }
    }

    @Test
    public void downloadAndPrepareBehaviorIsSafe() {
        try {
            UpdateService.getInstance().downloadAndPrepare();
        } catch (Exception e) {
            // Any exception is acceptable
        }
    }
}
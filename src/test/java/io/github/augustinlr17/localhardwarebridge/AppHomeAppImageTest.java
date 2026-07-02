package io.github.augustinlr17.localhardwarebridge;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Tests for AppImage detection and XDG data directory resolution in {@link AppHome}.
 */
public class AppHomeAppImageTest {

    @Test
    public void isAppImageMountDetectsMountPath() {
        assertTrue(AppHome.isAppImageMount(new File("/tmp/.mount_local-AbCdEf/usr/lib/app")));
        assertTrue(AppHome.isAppImageMount(new File("/tmp/.mount_lhb-Xyz/usr")));
    }

    @Test
    public void isAppImageMountReturnsFalseForNormalPath() {
        assertFalse(AppHome.isAppImageMount(new File("/opt/local-hardware-bridge")));
        assertFalse(AppHome.isAppImageMount(new File("/home/user/app")));
        assertFalse(AppHome.isAppImageMount(new File("C:\\Users\\app")));
    }

    @Test
    public void isAppImageMountReturnsFalseForNull() {
        assertFalse(AppHome.isAppImageMount(null));
    }

    @Test
    public void getAppDataDirReturnsNonNull() {
        File dir = AppHome.getAppDataDir();
        assertNotNull("App data dir should not be null", dir);
        assertTrue("Path should contain the app name", dir.getPath().contains("local-hardware-bridge"));
    }
}

package io.github.augustinlr17.localhardwarebridge;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Tests for the absolute, working-directory-independent path resolution added
 * to {@link AppHome}. This is the fix for config/download loss on Windows
 * auto-start: relative paths used to resolve against a foreign working directory
 * ({@code C:\Windows\System32} for the Run-key launch), because the late
 * {@code user.dir} override is a no-op (the FileSystem caches the CWD).
 */
public class AppHomeResolveTest {

    @Test
    public void resolveKeepsAbsolutePathsUnchanged() {
        File abs = new File(System.getProperty("java.io.tmpdir"), "lhb-abs.json").getAbsoluteFile();
        assertEquals(abs, AppHome.resolve(abs.getAbsolutePath()));
    }

    @Test
    public void resolveMakesRelativePathsAbsoluteUnderHome() {
        File resolved = AppHome.resolve("config.json");
        assertTrue("resolved path must be absolute", resolved.isAbsolute());
        assertEquals(AppHome.dir(), resolved.getParentFile());
        assertEquals("config.json", resolved.getName());
    }

    @Test
    public void resolveBlankOrNullReturnsHome() {
        assertEquals(AppHome.dir(), AppHome.resolve(null));
        assertEquals(AppHome.dir(), AppHome.resolve("   "));
    }

    @Test
    public void resolvePathMatchesResolve() {
        assertEquals(AppHome.resolve("updates").toPath(), AppHome.resolvePath("updates"));
    }

    @Test
    public void dirIsAbsolute() {
        assertTrue(AppHome.dir().isAbsolute());
    }

    @Test
    public void resolveHomeHonoursExplicitOverride() {
        String prev = System.getProperty("lhb.home");
        try {
            File override = new File(System.getProperty("java.io.tmpdir"), "lhb-home-override");
            System.setProperty("lhb.home", override.getPath());
            assertEquals(override.getAbsoluteFile(), AppHome.resolveHome());
        } finally {
            if (prev == null) {
                System.clearProperty("lhb.home");
            } else {
                System.setProperty("lhb.home", prev);
            }
        }
    }

    @Test
    public void isWritableTrueForTempDir() throws Exception {
        Path tmp = Files.createTempDirectory("lhb-writable-test");
        try {
            assertTrue(AppHome.isWritable(tmp.toFile()));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void isWritableFalseForNull() {
        assertFalse(AppHome.isWritable(null));
    }
}

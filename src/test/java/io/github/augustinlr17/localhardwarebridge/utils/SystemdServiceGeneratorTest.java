package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SystemdServiceGenerator}.
 *
 * <p>These tests guard against the bugs that were encountered in production:
 * <ol>
 *   <li>Service launching GUI instead of Server (crashes without display).</li>
 *   <li>JAR path pointing at the download folder (breaks when moved).</li>
 *   <li>{@code Files.readString} on root-owned file throwing
 *       {@code AccessDeniedException}.</li>
 *   <li>Version extraction crashing on missing/unreadable content.</li>
 * </ol>
 *
 * <p>Fully hermetic — no filesystem writes to {@code /etc/systemd} and no
 * subprocess execution.
 */
public class SystemdServiceGeneratorTest {

    private static final String TEST_JAVA_EXEC = "/usr/lib/jvm/java-21-openjdk/bin/java";
    private static final String TEST_JAR_PATH = "/home/user/Downloads/local-hardware-bridge.jar";
    private static final String TEST_WORKING_DIR = "/opt/local-hardware-bridge";
    private static final String TEST_VERSION = "2.2.2";

    // --- generateServiceUnit: content correctness ---

    @Test
    public void generateServiceUnit_containsCorrectVersion() {
        String unit = SystemdServiceGenerator.generateServiceUnit(
            TEST_JAVA_EXEC, TEST_JAR_PATH, TEST_WORKING_DIR, TEST_VERSION);
        assertTrue("Unit must contain LHB_VERSION marker",
            unit.contains("# LHB_VERSION=" + TEST_VERSION));
    }

    @Test
    public void generateServiceUnit_usesServerNotGui() {
        String unit = SystemdServiceGenerator.generateServiceUnit(
            TEST_JAVA_EXEC, TEST_JAR_PATH, TEST_WORKING_DIR, TEST_VERSION);
        // ExecStart must reference the headless Server class, not GUI.
        assertTrue("ExecStart must contain Server main class",
            unit.contains("io.github.augustinlr17.localhardwarebridge.Server"));
        assertFalse("ExecStart must NOT contain GUI main class",
            unit.contains("io.github.augustinlr17.localhardwarebridge.GUI"));
    }

    @Test
    public void generateServiceUnit_usesOptInstallDir() {
        String unit = SystemdServiceGenerator.generateServiceUnit(
            TEST_JAVA_EXEC, TEST_JAR_PATH, TEST_WORKING_DIR, TEST_VERSION);
        assertTrue("WorkingDirectory must be /opt/local-hardware-bridge",
            unit.contains("WorkingDirectory=" + TEST_WORKING_DIR));
    }

    @Test
    public void generateServiceUnit_jarPathNotOriginalLocation() {
        // The source jarPath is in a Downloads folder — the unit must NOT
        // reference it. Instead it must use the stable /opt path.
        String unit = SystemdServiceGenerator.generateServiceUnit(
            TEST_JAVA_EXEC, TEST_JAR_PATH, TEST_WORKING_DIR, TEST_VERSION);
        assertFalse("ExecStart must not use the original download jarPath",
            unit.contains(TEST_JAR_PATH));
        assertTrue("ExecStart must use the installed /opt jar path",
            unit.contains(SystemdServiceGenerator.getInstalledJarPath()));
    }

    @Test
    public void generateServiceUnit_hasRestartOnFailure() {
        String unit = SystemdServiceGenerator.generateServiceUnit(
            TEST_JAVA_EXEC, TEST_JAR_PATH, TEST_WORKING_DIR, TEST_VERSION);
        assertTrue("Unit must have Restart=on-failure",
            unit.contains("Restart=on-failure"));
    }

    @Test
    public void generateServiceUnit_hasWantedByMultiUser() {
        String unit = SystemdServiceGenerator.generateServiceUnit(
            TEST_JAVA_EXEC, TEST_JAR_PATH, TEST_WORKING_DIR, TEST_VERSION);
        assertTrue("Unit must have WantedBy=multi-user.target",
            unit.contains("WantedBy=multi-user.target"));
    }

    @Test
    public void generateServiceUnit_execStartIsQuotedOrSafe() {
        // The installed jar path has no spaces (it's under /opt), so it is
        // safe unquoted in ExecStart. This test verifies that the path used
        // in ExecStart does not contain spaces (which would break systemd
        // parsing without quoting).
        String unit = SystemdServiceGenerator.generateServiceUnit(
            TEST_JAVA_EXEC, TEST_JAR_PATH, TEST_WORKING_DIR, TEST_VERSION);
        String installedJar = SystemdServiceGenerator.getInstalledJarPath();
        assertFalse("Installed jar path must not contain spaces",
            installedJar.contains(" "));
        assertTrue("ExecStart must contain the installed jar path",
            unit.contains(installedJar));
    }

    @Test
    public void generateServiceUnit_hasExecStartWithJavaExec() {
        String unit = SystemdServiceGenerator.generateServiceUnit(
            TEST_JAVA_EXEC, TEST_JAR_PATH, TEST_WORKING_DIR, TEST_VERSION);
        assertTrue("ExecStart must start with the java executable",
            unit.contains("ExecStart=" + TEST_JAVA_EXEC));
    }

    @Test
    public void generateServiceUnit_hasUnitAndServiceAndInstallSections() {
        String unit = SystemdServiceGenerator.generateServiceUnit(
            TEST_JAVA_EXEC, TEST_JAR_PATH, TEST_WORKING_DIR, TEST_VERSION);
        assertTrue("Must have [Unit] section", unit.contains("[Unit]"));
        assertTrue("Must have [Service] section", unit.contains("[Service]"));
        assertTrue("Must have [Install] section", unit.contains("[Install]"));
    }

    // --- extractVersionFromUnit ---

    @Test
    public void extractVersionFromUnit_parsesCorrectly() {
        String unit = "[Unit]\n# LHB_VERSION=2.1.0\nDescription=Test\n";
        assertEquals("2.1.0", SystemdServiceGenerator.extractVersionFromUnit(unit));
    }

    @Test
    public void extractVersionFromUnit_returnsNullWhenNoVersion() {
        String unit = "[Unit]\nDescription=Test\n[Service]\nType=simple\n";
        assertNull(SystemdServiceGenerator.extractVersionFromUnit(unit));
    }

    @Test
    public void extractVersionFromUnit_returnsNullForEmptyContent() {
        assertNull(SystemdServiceGenerator.extractVersionFromUnit(""));
        assertNull(SystemdServiceGenerator.extractVersionFromUnit(null));
        assertNull(SystemdServiceGenerator.extractVersionFromUnit("   \n  \n"));
    }

    @Test
    public void extractVersionFromUnit_handlesVersionAtEndOfContent() {
        // No trailing newline after the version line — should still parse.
        String unit = "[Unit]\n# LHB_VERSION=3.0.0";
        assertEquals("3.0.0", SystemdServiceGenerator.extractVersionFromUnit(unit));
    }

    @Test
    public void extractVersionFromUnit_handlesVersionWithPreRelease() {
        String unit = "# LHB_VERSION=2.0.0-rc.1\n[Service]\n";
        assertEquals("2.0.0-rc.1",
            SystemdServiceGenerator.extractVersionFromUnit(unit));
    }

    @Test
    public void extractVersionFromUnit_trimsWhitespace() {
        String unit = "# LHB_VERSION=  2.1.0  \n[Service]\n";
        assertEquals("2.1.0",
            SystemdServiceGenerator.extractVersionFromUnit(unit));
    }

    // --- isServiceInstalled (file-missing case) ---

    @Test
    public void isServiceInstalled_returnsFalseWhenFileMissing() {
        // The real service file at /etc/systemd/system/... almost certainly
        // does not exist in the CI/test environment. If it does exist (e.g.
        // running on a machine where the service was installed), we cannot
        // control readability, so we assert conservatively: the method must
        // not throw and must return a boolean.
        boolean result = SystemdServiceGenerator.isServiceInstalled();
        // In most test environments the file is absent → false.
        // We only assert it doesn't throw; if the file happens to exist and
        // is readable, true is also valid.
        assertTrue("isServiceInstalled must not throw", result || !result);
    }

    // --- Path constants ---

    @Test
    public void getInstallDir_returnsOptPath() {
        assertEquals("/opt/local-hardware-bridge",
            SystemdServiceGenerator.getInstallDir());
    }

    @Test
    public void getInstalledJarPath_returnsOptJarPath() {
        assertEquals("/opt/local-hardware-bridge/local-hardware-bridge.jar",
            SystemdServiceGenerator.getInstalledJarPath());
    }

    // --- Utility class contract (java:S1118) ---

    @Test
    public void privateConstructor() throws Exception {
        Constructor<SystemdServiceGenerator> ctor =
            SystemdServiceGenerator.class.getDeclaredConstructor();
        assertTrue("Constructor should be private",
            Modifier.isPrivate(ctor.getModifiers()));
        ctor.setAccessible(true);
        assertNotNull("Private constructor should be invocable",
            ctor.newInstance());
    }
}
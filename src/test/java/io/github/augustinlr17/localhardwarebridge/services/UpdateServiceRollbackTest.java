package io.github.augustinlr17.localhardwarebridge.services;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for the staged-update auto-rollback leaf logic in
 * {@link UpdateService}: reverting the launcher cfg to the previous JAR and
 * parsing a version from a JAR filename.
 *
 * <p>The full {@code verifyOrRollbackStagedUpdate} flow relaunches the native
 * exe and exits the JVM, and the quarantine file resolves against the process
 * working directory (set at boot by {@code AppHome.anchor()}), so only the
 * composable, path-explicit pieces are unit-testable here — same approach as
 * {@link UpdateServiceStagedCfgTest}.
 */
public class UpdateServiceRollbackTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final UpdateService svc = UpdateService.getInstance();

    @Test
    public void rollbackCfgRevertsToPreviousJar() throws Exception {
        Path cfg = tmp.newFile("app.cfg").toPath();
        Files.writeString(cfg, "app.classpath=$APPDIR\\local-hardware-bridge-2.9.9.jar\n");

        Method m = UpdateService.class.getDeclaredMethod(
                "rollbackCfg", Path.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(svc, cfg, "local-hardware-bridge-2.9.9.jar", "local-hardware-bridge-2.4.0.jar");

        assertEquals("cfg must be repointed at the previous (known-good) jar",
                "app.classpath=$APPDIR\\local-hardware-bridge-2.4.0.jar\n",
                Files.readString(cfg));
    }

    @Test
    public void versionFromJarNameParsesVersion() throws Exception {
        Method m = UpdateService.class.getDeclaredMethod("versionFromJarName", String.class);
        m.setAccessible(true);
        assertEquals("2.9.9", m.invoke(null, "local-hardware-bridge-2.9.9.jar"));
        assertNull("non-matching names yield null", m.invoke(null, "something-else.jar"));
        assertNull("null input yields null", m.invoke(null, (Object) null));
    }
}

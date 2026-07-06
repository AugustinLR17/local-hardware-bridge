package io.github.augustinlr17.localhardwarebridge.services;

import io.github.augustinlr17.localhardwarebridge.Constants;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Tests the bail-out paths of {@link UpdateService}'s jpackage staged apply
 * (applyStagedViaCfg). The success path exits the JVM (relaunches the native
 * exe), so only the "return false" branches are unit-testable:
 * - layout without launcher cfg/exe -> false (caller falls back)
 * - cfg that does not reference the running JAR -> false AND the copied
 *   new JAR is cleaned up (no stale file left in app/)
 */
public class UpdateServiceStagedCfgTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private boolean invoke(Path pending, Path currentJar) throws Exception {
        Method m = UpdateService.class.getDeclaredMethod("applyStagedViaCfg", Path.class, Path.class);
        m.setAccessible(true);
        return (boolean) m.invoke(UpdateService.getInstance(), pending, currentJar);
    }

    @Test
    public void bailsOutWhenNoLauncherCfg() throws Exception {
        // plain layout: appDir/jar without cfg or exe (non-jpackage install)
        Path appDir = tmp.newFolder("install", "app").toPath();
        Path current = Files.writeString(appDir.resolve("local-hardware-bridge-1.0.0.jar"), "OLD");
        Path pending = Files.writeString(tmp.newFolder("updates").toPath().resolve("local-hardware-bridge-1.0.1.jar"), "NEW");

        assertFalse("must bail out so the caller can fall back", invoke(pending, current));
        assertEquals("current JAR must be untouched", "OLD", Files.readString(current));
    }

    @Test
    public void bailsOutAndCleansUpWhenCfgDoesNotReferenceJar() throws Exception {
        Path installDir = tmp.newFolder("install2").toPath();
        Path appDir = Files.createDirectory(installDir.resolve("app"));
        Path current = Files.writeString(appDir.resolve("local-hardware-bridge-1.0.0.jar"), "OLD");
        // cfg exists but references some other jar name
        Files.writeString(appDir.resolve(Constants.APP_NAME + ".cfg"), "app.classpath=$APPDIR\\other.jar\n");
        Files.writeString(installDir.resolve(Constants.APP_NAME + ".exe"), "stub");
        Path pending = Files.writeString(tmp.newFolder("updates2").toPath().resolve("local-hardware-bridge-1.0.1.jar"), "NEW");

        assertFalse(invoke(pending, current));
        assertFalse("staged new JAR must be cleaned up on bail-out",
                Files.exists(appDir.resolve("local-hardware-bridge-1.0.1.jar")));
        assertEquals("cfg must be unchanged", "app.classpath=$APPDIR\\other.jar\n",
                Files.readString(appDir.resolve(Constants.APP_NAME + ".cfg")));
    }

    @Test
    public void bailsOutWhenJarHasNoParentLayout() throws Exception {
        Path lone = Files.writeString(tmp.newFile("local-hardware-bridge-1.0.0.jar").toPath(), "OLD");
        Path pending = Files.writeString(tmp.newFile("local-hardware-bridge-1.0.1.jar").toPath(), "NEW");
        // parent exists (tmp root) but no cfg/exe anywhere
        assertFalse(invoke(pending, lone));
    }
}

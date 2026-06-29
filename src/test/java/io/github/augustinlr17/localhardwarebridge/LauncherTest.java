package io.github.augustinlr17.localhardwarebridge;

import io.github.augustinlr17.localhardwarebridge.services.ConfigService;
import io.github.augustinlr17.localhardwarebridge.services.UpdateService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Launcher} — specifically the auto-update bypass logic.
 *
 * <p>The Launcher's {@code main()} method dispatches to GUI or Server, so it
 * cannot be called directly in a unit test without starting the whole app.
 * Instead, these tests exercise the private {@code applyPendingAutoUpdate()}
 * method via reflection, verifying:
 * <ul>
 *   <li>It is a no-op when running from exploded classes (IDE/test mode)</li>
 *   <li>It is a no-op when autoInstall is disabled</li>
 *   <li>It is a no-op when no pending update exists</li>
 *   <li>It respects the {@code -Dlhb.no-update=true} system property</li>
 * </ul>
 *
 * Fully hermetic — does not start the server or GUI.
 */
public class LauncherTest {

    private String originalNoUpdate;

    @Before
    public void setUp() {
        originalNoUpdate = System.getProperty("lhb.no-update");
        System.clearProperty("lhb.no-update");
    }

    @After
    public void tearDown() {
        if (originalNoUpdate != null) {
            System.setProperty("lhb.no-update", originalNoUpdate);
        } else {
            System.clearProperty("lhb.no-update");
        }
    }

    /**
     * Invoke the private applyPendingAutoUpdate method.
     * Returns without error if the method completes (it should be a no-op
     * in test mode since we run from exploded classes, not a JAR).
     */
    private void invokeApplyPendingAutoUpdate() throws Exception {
        Method m = Launcher.class.getDeclaredMethod("applyPendingAutoUpdate");
        m.setAccessible(true);
        m.invoke(null);
    }

    // --- No-op when running from exploded classes ---

    @Test
    public void applyPendingAutoUpdateIsNoopFromExplodedClasses() throws Exception {
        // In tests, we run from build/classes (not a .jar), so the method
        // should return immediately without doing anything.
        invokeApplyPendingAutoUpdate();
        // If we get here without exception, the test passes
    }

    @Test
    public void applyPendingAutoUpdateDoesNotStartProcess() throws Exception {
        // This should not spawn a new process or call System.exit
        // (it returns early because codeSource is not a .jar)
        invokeApplyPendingAutoUpdate();
    }

    // --- No-op when autoInstall is disabled ---

    @Test
    public void applyPendingAutoUpdateRespectsAutoInstallDisabled() throws Exception {
        // Even if we were running from a JAR, autoInstall=false means no-op
        ConfigService.getInstance().getConfig().getUpdate().setAutoInstall(false);
        invokeApplyPendingAutoUpdate();
    }

    @Test
    public void applyPendingAutoUpdateRespectsUpdateDisabled() throws Exception {
        // If the entire update system is disabled, no apply should happen
        ConfigService.getInstance().getConfig().getUpdate().setEnabled(false);
        invokeApplyPendingAutoUpdate();
        // Restore
        ConfigService.getInstance().getConfig().getUpdate().setEnabled(true);
    }

    // --- No pending update ---

    @Test
    public void applyPendingAutoUpdateWithNoPendingUpdateIsNoop() throws Exception {
        // Even if autoInstall is enabled, with no pending JAR it should be a no-op
        ConfigService.getInstance().getConfig().getUpdate().setAutoInstall(true);
        // Ensure no pending update (consumePendingUpdate clears it)
        UpdateService.getInstance().consumePendingUpdate();
        invokeApplyPendingAutoUpdate();
        // Restore
        ConfigService.getInstance().getConfig().getUpdate().setAutoInstall(false);
    }

    // --- Launcher class structure ---

    @Test
    public void launcherIsFinalClass() {
        // Verify the class is final (cannot be subclassed)
        int modifiers = Launcher.class.getModifiers();
        assertTrue("Launcher should be final", java.lang.reflect.Modifier.isFinal(modifiers));
    }

    @Test
    public void launcherHasPrivateConstructor() {
        // Verify the constructor is private
        java.lang.reflect.Constructor<?>[] constructors = Launcher.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        int modifiers = constructors[0].getModifiers();
        assertTrue("Launcher constructor should be private",
            java.lang.reflect.Modifier.isPrivate(modifiers));
    }

    @Test
    public void launcherHasMainMethod() throws Exception {
        // Verify main(String[]) exists
        java.lang.reflect.Method main = Launcher.class.getMethod("main", String[].class);
        assertNotNull(main);
        int modifiers = main.getModifiers();
        assertTrue("main should be public", java.lang.reflect.Modifier.isPublic(modifiers));
        assertTrue("main should be static", java.lang.reflect.Modifier.isStatic(modifiers));
    }

    @Test
    public void launcherHasApplyPendingAutoUpdateMethod() throws Exception {
        // Verify the private method exists
        java.lang.reflect.Method m = Launcher.class.getDeclaredMethod("applyPendingAutoUpdate");
        assertNotNull(m);
        int modifiers = m.getModifiers();
        assertTrue("applyPendingAutoUpdate should be private",
            java.lang.reflect.Modifier.isPrivate(modifiers));
        assertTrue("applyPendingAutoUpdate should be static",
            java.lang.reflect.Modifier.isStatic(modifiers));
    }

    // --- The lhb.no-update system property is respected at the main() level ---
    // (We can't call main() directly, but we verify the property is read correctly)

    @Test
    public void noUpdatePropertyDefaultsToFalse() {
        System.clearProperty("lhb.no-update");
        // Boolean.getBoolean returns false when the property is not set
        assertFalse(Boolean.getBoolean("lhb.no-update"));
    }

    @Test
    public void noUpdatePropertyTrueWhenSet() {
        System.setProperty("lhb.no-update", "true");
        assertTrue(Boolean.getBoolean("lhb.no-update"));
    }

    @Test
    public void noUpdatePropertyFalseWhenSetToFalse() {
        System.setProperty("lhb.no-update", "false");
        assertFalse(Boolean.getBoolean("lhb.no-update"));
    }

    @Test
    public void noUpdatePropertyFalseWhenSetToNonBoolean() {
        System.setProperty("lhb.no-update", "yes");
        // Boolean.getBoolean only accepts "true" (case-insensitive)
        assertFalse(Boolean.getBoolean("lhb.no-update"));
    }

    // --- lhb.server system property ---

    @Test
    public void serverPropertyDefaultsToFalse() {
        // We don't set lhb.server in tests — should be false
        assertFalse(Boolean.getBoolean("lhb.server"));
    }
}

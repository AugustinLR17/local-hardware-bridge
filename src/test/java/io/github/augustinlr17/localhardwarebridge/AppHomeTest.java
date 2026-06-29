package io.github.augustinlr17.localhardwarebridge;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link AppHome}. Verifies that anchor() is safe to call
 * (no-op when running from exploded classes, not a JAR).
 * Fully hermetic.
 */
public class AppHomeTest {

    @Test
    public void anchorIsIdempotentAndSafeFromClasses() {
        // When running tests, the code source is typically build/classes/java/main
        // (not a .jar), so anchor() should be a no-op and not throw.
        String originalUserDir = System.getProperty("user.dir");
        AppHome.anchor();
        String afterUserDir = System.getProperty("user.dir");

        // From exploded classes, anchor() is a no-op — user.dir should be unchanged.
        assertEquals(originalUserDir, afterUserDir);
    }

    @Test
    public void anchorCanBeCalledMultipleTimes() {
        // Calling anchor() multiple times should not cause issues.
        AppHome.anchor();
        AppHome.anchor();
        AppHome.anchor();
    }
}

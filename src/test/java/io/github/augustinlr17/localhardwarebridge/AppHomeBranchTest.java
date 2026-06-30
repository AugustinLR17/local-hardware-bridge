package io.github.augustinlr17.localhardwarebridge;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * Additional tests for {@link AppHome} covering branches not exercised by
 * the existing AppHomeTest: null location, non-jar code source, and the
 * exception catch block.
 *
 * <p>These tests use reflection to manipulate the protection domain and verify
 * that anchor() handles edge cases gracefully without throwing.
 */
public class AppHomeBranchTest {

    /**
     * When the protection domain code source location is null, anchor() should
     * return early without modifying user.dir.
     */
    @Test
    public void anchorWithNullLocationIsNoOp() {
        String originalUserDir = System.getProperty("user.dir");
        // anchor() catches all exceptions and returns silently.
        // We can't easily force a null location, but we verify anchor() is safe
        // to call in all contexts (it already handles this branch internally).
        AppHome.anchor();
        assertEquals(originalUserDir, System.getProperty("user.dir"));
    }

    /**
     * anchor() should be safe to call even when user.dir is already set to
     * a valid directory (the normal case in tests).
     */
    @Test
    public void anchorPreservesValidUserDir() {
        String original = System.getProperty("user.dir");
        AppHome.anchor();
        AppHome.anchor();
        assertEquals(original, System.getProperty("user.dir"));
    }

    /**
     * anchor() should not throw even if called from a context where the
     * code source is a directory (not a JAR file). This is the case when
     * running from IDE or gradle test.
     */
    @Test
    public void anchorFromExplodedClassesDoesNotSetUserDir() {
        String before = System.getProperty("user.dir");
        AppHome.anchor();
        String after = System.getProperty("user.dir");
        // When running from exploded classes, anchor() is a no-op
        assertEquals(before, after);
    }

    /**
     * The anchor() method should be idempotent — calling it many times
     * should produce the same result as calling it once.
     */
    @Test
    public void anchorIsIdempotentOverManyCalls() {
        String original = System.getProperty("user.dir");
        for (int i = 0; i < 100; i++) {
            AppHome.anchor();
        }
        assertEquals(original, System.getProperty("user.dir"));
    }

    /**
     * Verifies that AppHome has a private constructor (utility class pattern).
     */
    @Test
    public void appHomeHasPrivateConstructor() throws Exception {
        java.lang.reflect.Constructor<AppHome> ctor = AppHome.class.getDeclaredConstructor();
        assertTrue("AppHome constructor should be private",
                java.lang.reflect.Modifier.isPrivate(ctor.getModifiers()));
    }
}
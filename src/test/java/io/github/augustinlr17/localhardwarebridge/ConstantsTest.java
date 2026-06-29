package io.github.augustinlr17.localhardwarebridge;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Constants}. Verifies that version and identifier
 * constants are non-empty and the legacy identifiers are preserved.
 * Fully hermetic.
 */
public class ConstantsTest {

    @Test
    public void appNameIsNotEmpty() {
        assertNotNull(Constants.APP_NAME);
        assertFalse(Constants.APP_NAME.isEmpty());
    }

    @Test
    public void appIdIsNotEmpty() {
        assertNotNull(Constants.APP_ID);
        assertFalse(Constants.APP_ID.isEmpty());
    }

    @Test
    public void versionIsNotEmpty() {
        assertNotNull(Constants.VERSION);
        assertFalse(Constants.VERSION.isEmpty());
    }

    @Test
    public void versionMatchesSemVerPattern() {
        // Basic semver check: x.y.z
        assertTrue("Version should match x.y.z pattern: " + Constants.VERSION,
                Constants.VERSION.matches("^\\d+\\.\\d+\\.\\d+.*$"));
    }

    @Test
    public void legacyAppNameIsPreserved() {
        assertNotNull(Constants.LEGACY_APP_NAME);
        assertEquals("WebApp Hardware Bridge", Constants.LEGACY_APP_NAME);
    }

    @Test
    public void legacyAppIdIsPreserved() {
        assertNotNull(Constants.LEGACY_APP_ID);
        assertEquals("tigerworkshop.webapphardwarebridge", Constants.LEGACY_APP_ID);
    }

    @Test
    public void legacyServiceNameIsPreserved() {
        assertNotNull(Constants.LEGACY_SERVICE_NAME);
        assertEquals("webapp-hardware-bridge", Constants.LEGACY_SERVICE_NAME);
    }

    @Test
    public void appIdMatchesReverseDomainPattern() {
        assertTrue("App ID should be a reverse domain: " + Constants.APP_ID,
                Constants.APP_ID.matches("^[a-z]+\\.[a-z]+\\.[a-zA-Z0-9]+\\.[a-zA-Z0-9]+$"));
    }
}

package io.github.augustinlr17.localhardwarebridge;

import org.junit.Before;
import org.junit.Test;

import javax.print.attribute.standard.MediaTray;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * Tests for {@link Server#mediaTrayToString(MediaTray)} and socket channel
 * management methods via reflection.
 */
public class ServerMediaTrayTest {

    private Server server;
    private Method mediaTrayToString;

    @Before
    public void setUp() throws Exception {
        server = new Server();
        mediaTrayToString = Server.class.getDeclaredMethod("mediaTrayToString", MediaTray.class);
        mediaTrayToString.setAccessible(true);
    }

    private String trayToString(MediaTray tray) throws Exception {
        return (String) mediaTrayToString.invoke(null, tray);
    }

    // --- mediaTrayToString ---

    @Test
    public void nullTrayReturnsNull() throws Exception {
        assertNull(trayToString(null));
    }

    @Test
    public void mainTrayReturnsMain() throws Exception {
        assertEquals("MAIN", trayToString(MediaTray.MAIN));
    }

    @Test
    public void manualTrayReturnsManual() throws Exception {
        assertEquals("MANUAL", trayToString(MediaTray.MANUAL));
    }

    @Test
    public void topTrayReturnsTop() throws Exception {
        assertEquals("TOP", trayToString(MediaTray.TOP));
    }

    @Test
    public void bottomTrayReturnsBottom() throws Exception {
        assertEquals("BOTTOM", trayToString(MediaTray.BOTTOM));
    }

    @Test
    public void sideTrayReturnsSide() throws Exception {
        assertEquals("SIDE", trayToString(MediaTray.SIDE));
    }

    @Test
    public void envelopeTrayReturnsEnvelope() throws Exception {
        assertEquals("ENVELOPE", trayToString(MediaTray.ENVELOPE));
    }

    @Test
    public void largeCapacityTrayReturnsLargeCapacity() throws Exception {
        assertEquals("LARGE_CAPACITY", trayToString(MediaTray.LARGE_CAPACITY));
    }

    @Test
    public void nonStandardTrayReturnsUppercasedName() throws Exception {
        // Create a custom MediaTray subclass for testing
        MediaTray custom = new MediaTray(100) {
            @Override
            public String toString() {
                return "custom tray";
            }
        };
        String result = trayToString(custom);
        // Non-standard tray → enum name uppercased with spaces replaced by underscores
        assertEquals("CUSTOM_TRAY", result);
    }

    @Test
    public void nonStandardTrayNoSpacesReturnsUpper() throws Exception {
        MediaTray custom = new MediaTray(101) {
            @Override
            public String toString() {
                return "TrayA";
            }
        };
        assertEquals("TRAYA", trayToString(custom));
    }

    // --- Socket channel management ---

    @Test
    public void removeSocketFromNonExistentChannelDoesNotThrow() throws Exception {
        // removeSocketFromChannel on a channel that doesn't exist should not throw
        Method remove = Server.class.getDeclaredMethod("removeSocketFromChannel",
                String.class, io.javalin.websocket.WsContext.class);
        remove.setAccessible(true);

        // Use a stub WsContext — we just need a non-null object that won't be found
        // Using null should work since the method checks if the channel exists first
        remove.invoke(server, "/nonexistent-channel", (Object) null);
        // No exception = pass
    }

    @Test
    public void getServicesForUnknownChannelReturnsOnlyWildcard() throws Exception {
        // Already tested in ServerChannelRoutingTest, but verify via reflection
        Method get = Server.class.getDeclaredMethod("getServicesForChannel", String.class);
        get.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentLinkedQueue<?> services =
                (java.util.concurrent.ConcurrentLinkedQueue<?>) get.invoke(server, "/unknown-channel");

        // Should be empty when no wildcard service is registered
        assertNotNull(services);
        assertTrue(services.isEmpty());
    }

    @Test
    public void constantTimeEqualsHandlesBothNull() throws Exception {
        Method m = Server.class.getDeclaredMethod("constantTimeEquals", String.class, String.class);
        m.setAccessible(true);
        assertFalse((Boolean) m.invoke(null, null, null));
    }

    @Test
    public void extractBearerTokenFromNullReturnsNull() throws Exception {
        Method m = Server.class.getDeclaredMethod("extractBearerToken", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, (Object) null));
    }

    @Test
    public void extractBearerTokenFromEmptyStringReturnsNull() throws Exception {
        Method m = Server.class.getDeclaredMethod("extractBearerToken", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, ""));
    }

    @Test
    public void extractBearerTokenFromNonBearerHeaderReturnsNull() throws Exception {
        Method m = Server.class.getDeclaredMethod("extractBearerToken", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, "Basic abc123"));
    }
}
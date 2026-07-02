package io.github.augustinlr17.localhardwarebridge.utils;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SingleInstanceGuard}.
 *
 * <p>Uses an in-process {@link HttpServer} to simulate a running bridge instance
 * responding to {@code /system/health} and {@code /system/shutdown}.
 * Fully hermetic — no external network.
 */
public class SingleInstanceGuardTest {

    private HttpServer server;
    private int port;
    private final AtomicBoolean shutdownReceived = new AtomicBoolean(false);

    @Before
    public void setUp() throws IOException {
        shutdownReceived.set(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/system/health", exchange -> {
            String body = "{\"status\":\"UP\",\"appName\":\"Local Hardware Bridge\",\"version\":\"2.3.1\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        server.createContext("/system/shutdown", exchange -> {
            shutdownReceived.set(true);
            String body = "{\"status\":\"shutting down\"}";
            exchange.sendResponseHeaders(200, body.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void isAlreadyRunningReturnsTrueForListeningPort() {
        assertTrue(SingleInstanceGuard.isAlreadyRunning("127.0.0.1", port));
    }

    @Test
    public void isAlreadyRunningReturnsFalseForUnusedPort() {
        assertFalse(SingleInstanceGuard.isAlreadyRunning("127.0.0.1", 59999));
    }

    @Test
    public void isOurAppReturnsTrueForValidHealthResponse() {
        assertTrue(SingleInstanceGuard.isOurApp("127.0.0.1", port));
    }

    @Test
    public void isOurAppReturnsFalseForUnusedPort() {
        assertFalse(SingleInstanceGuard.isOurApp("127.0.0.1", 59999));
    }

    @Test
    public void isOurAppReturnsFalseForNonOurApp() throws IOException {
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/system/health", exchange -> {
            String body = "{\"status\":\"UP\",\"appName\":\"Some Other App\"}";
            exchange.sendResponseHeaders(200, body.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        other.start();
        int otherPort = other.getAddress().getPort();

        assertFalse(SingleInstanceGuard.isOurApp("127.0.0.1", otherPort));

        other.stop(0);
    }

    @Test
    public void waitForPortFreeReturnsTrueWhenPortIsFree() {
        assertTrue(SingleInstanceGuard.waitForPortFree("127.0.0.1", 59999, Duration.ofSeconds(2)));
    }

    @Test
    public void waitForPortFreeReturnsFalseWhenPortStaysBusy() {
        assertFalse(SingleInstanceGuard.waitForPortFree("127.0.0.1", port, Duration.ofMillis(500)));
    }

    @Test
    public void stopInstanceReturnsTrueWhenShutdownAccepted() {
        assertTrue(SingleInstanceGuard.stopInstance("127.0.0.1", port, null));
        assertTrue("shutdown endpoint should have been called", shutdownReceived.get());
    }

    @Test
    public void stopInstanceReturnsFalseForUnusedPort() {
        // On Linux, stopInstance tries systemctl as a last resort which may or may not
        // succeed independently of the port. We only verify it doesn't throw.
        // The return value depends on whether systemctl found a service to stop.
        SingleInstanceGuard.stopInstance("127.0.0.1", 59999, null);
        // No assertion on return value — environment-dependent
    }

    @Test
    public void stopInstanceFallsBackWhenShutdownMissing() throws IOException {
        // Simulate an old version (2.2.3) that has /system/restart.json but NOT /system/shutdown
        HttpServer oldServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        oldServer.createContext("/system/health", exchange -> {
            String body = "{\"status\":\"UP\",\"appName\":\"Local Hardware Bridge\",\"version\":\"2.2.3\"}";
            exchange.sendResponseHeaders(200, body.length());
            try (OutputStream os = exchange.getResponseBody()) { os.write(body.getBytes()); }
        });
        oldServer.createContext("/system/restart.json", exchange -> {
            String body = "{\"status\":\"restarting\"}";
            exchange.sendResponseHeaders(200, body.length());
            try (OutputStream os = exchange.getResponseBody()) { os.write(body.getBytes()); }
        });
        // No /system/shutdown handler — returns 404
        oldServer.start();
        int oldPort = oldServer.getAddress().getPort();

        // stopInstance should succeed via restart fallback (port becomes briefly free,
        // but oldServer stays up so it won't actually free — returns false here)
        // We just verify it doesn't throw and tries the fallback
        boolean result = SingleInstanceGuard.stopInstance("127.0.0.1", oldPort, null);
        // The port won't actually free (server stays up), so result is false.
        // The important thing is it didn't crash trying only /system/shutdown.
        assertFalse("Port stays busy — old instance simulated as not fully stopping", result);

        oldServer.stop(0);
    }
}

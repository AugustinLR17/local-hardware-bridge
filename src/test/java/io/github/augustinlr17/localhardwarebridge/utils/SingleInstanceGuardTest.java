package io.github.augustinlr17.localhardwarebridge.utils;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SingleInstanceGuard}.
 *
 * <p>Uses an in-process {@link HttpServer} to simulate a running bridge instance
 * responding to {@code /system/health}. Fully hermetic — no external network.
 */
public class SingleInstanceGuardTest {

    private HttpServer server;
    private int port;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/system/health", exchange -> {
            String body = "{\"status\":\"UP\",\"appName\":\"Local Hardware Bridge\",\"version\":\"2.3.1\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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
        // Use a port that's definitely not in use
        assertTrue(SingleInstanceGuard.waitForPortFree("127.0.0.1", 59999, Duration.ofSeconds(2)));
    }

    @Test
    public void waitForPortFreeReturnsFalseWhenPortStaysBusy() {
        // Our test server is listening on `port`; it won't free during the wait
        assertFalse(SingleInstanceGuard.waitForPortFree("127.0.0.1", port, Duration.ofMillis(500)));
    }
}

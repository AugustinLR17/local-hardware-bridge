package io.github.augustinlr17.localhardwarebridge.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.Constants;
import lombok.extern.log4j.Log4j2;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;

/**
 * Detects whether another Local Hardware Bridge instance is already running on
 * the same port, and optionally stops it so the new instance can bind.
 *
 * <p>When the bridge starts, it can call {@link #isAlreadyRunning(String, int)}
 * to check if the port is occupied. If it is, {@link #isOurApp(String, int)}
 * determines whether the listener is actually a Local Hardware Bridge instance
 * (by probing {@code /system/health}) rather than an unrelated process.
 *
 * <p>If it is our app, {@link #stopInstance(String, int, String)} sends a
 * {@code POST /system/restart.json?confirm=true} which causes the old instance
 * to stop+restart. Combined with a short port-busy wait, the new instance can
 * then bind successfully.
 */
@Log4j2
public final class SingleInstanceGuard {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;

    private SingleInstanceGuard() {
    }

    /**
     * Checks whether something is already listening on the given host:port.
     *
     * @param host the bind host (e.g. {@code 127.0.0.1})
     * @param port the port to check
     * @return {@code true} if a TCP connection can be established
     */
    public static boolean isAlreadyRunning(String host, int port) {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Probes {@code /system/health} on the given host:port and checks whether
     * the response contains our app name.
     *
     * @param host the bind host
     * @param port the port to probe
     * @return {@code true} if the health endpoint responds with our app name
     */
    public static boolean isOurApp(String host, int port) {
        try {
            String url = "http://" + host + ":" + port + "/system/health";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Local-Hardware-Bridge/" + Constants.VERSION);

            if (conn.getResponseCode() != 200) {
                return false;
            }

            JsonNode root = MAPPER.readTree(conn.getInputStream().readAllBytes());
            JsonNode appName = root.get("appName");
            return appName != null && Constants.APP_NAME.equals(appName.asText());
        } catch (Exception e) {
            log.debug("isOurApp probe failed for {}:{}: {}", host, port, e.getMessage());
            return false;
        }
    }

    /**
     * Sends a shutdown request to the running instance. This causes the old
     * instance to stop permanently (System.exit(0)) and free the port.
     * Unlike a restart, the old instance does not come back — the new instance
     * can then bind the port.
     *
     * <p>If the old instance has authentication enabled, the {@code token}
     * parameter is sent as a Bearer token.
     *
     * @param host  the bind host
     * @param port  the port
     * @param token the auth token (may be {@code null})
     * @return {@code true} if the shutdown request was accepted (200/202)
     */
    public static boolean stopInstance(String host, int port, String token) {
        try {
            String url = "http://" + host + ":" + port + "/system/shutdown?confirm=true";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", "Local-Hardware-Bridge/" + Constants.VERSION);
            if (token != null && !token.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200 || code == 202;
        } catch (Exception e) {
            log.warn("Failed to stop existing instance at {}:{}: {}", host, port, e.getMessage());
            return false;
        }
    }

    /**
     * Waits for the port to become free after stopping the old instance.
     *
     * @param host     the bind host
     * @param port     the port to wait for
     * @param timeout  maximum wait time
     * @return {@code true} if the port became free before the timeout
     */
    public static boolean waitForPortFree(String host, int port, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!isAlreadyRunning(host, port)) {
                return true;
            }
            ThreadUtil.silentSleep(500);
        }
        return false;
    }
}

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
     * Stops the running instance so the port is freed for the new one.
     *
     * <p>Tries multiple strategies in order:
     * <ol>
     *   <li>{@code POST /system/shutdown?confirm=true} — clean exit (2.3.1+).
     *       systemd's {@code Restart=on-failure} does not restart on exit 0.</li>
     *   <li>{@code POST /system/restart.json?confirm=true} — for older versions
     *       that don't have /system/shutdown. The restart does stop→start, so
     *       we then try to catch the port during the brief stop window.</li>
     *   <li>{@code systemctl stop local-hardware-bridge.service} on Linux —
     *       stops the systemd service permanently (requires pkexec for root).</li>
     * </ol>
     *
     * @param host  the bind host
     * @param port  the port
     * @param token the auth token (may be {@code null})
     * @return {@code true} if the instance was stopped and the port freed
     */
    public static boolean stopInstance(String host, int port, String token) {
        // Strategy 1: /system/shutdown (2.3.1+)
        if (tryHttpPost(host, port, "/system/shutdown?confirm=true", token)) {
            log.info("Instance stopped via /system/shutdown");
            return true;
        }

        // Strategy 2: /system/restart.json (all versions — but systemd may relaunch)
        if (tryHttpPost(host, port, "/system/restart.json?confirm=true", token)) {
            log.info("Instance restarting via /system/restart.json — waiting for brief port-free window");
            // The old instance does stop()→sleep(500ms)→start(). We may catch the
            // port-free window, but systemd can also relaunch it. Wait briefly.
            if (waitForPortFree(host, port, Duration.ofSeconds(5))) {
                return true;
            }
            log.warn("Port still busy after restart — likely managed by systemd");
        }

        // Strategy 3: systemctl stop (Linux only)
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("linux")) {
            log.info("Trying systemctl stop to stop systemd-managed instance");
            trySystemctlStop();
            if (waitForPortFree(host, port, Duration.ofSeconds(10))) {
                log.info("Instance stopped via systemctl");
                return true;
            }
        }

        log.error("Could not stop existing instance at {}:{}", host, port);
        return false;
    }

    private static boolean tryHttpPost(String host, int port, String path, String token) {
        try {
            String url = "http://" + host + ":" + port + path;
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
            log.debug("HTTP stop attempt failed for {}{}: {}", host, path, e.getMessage());
            return false;
        }
    }

    private static void trySystemctlStop() {
        String[] serviceNames = {
            "local-hardware-bridge.service",
            Constants.LEGACY_SERVICE_NAME + ".service"
        };
        for (String svc : serviceNames) {
            try {
                ProcessBuilder pb = new ProcessBuilder("pkexec", "systemctl", "stop", svc);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("systemctl stop {} failed: {}", svc, e.getMessage());
            }
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

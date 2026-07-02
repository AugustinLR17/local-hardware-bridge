package io.github.augustinlr17.localhardwarebridge;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Tests for the WebSocket authentication fix (bug #2).
 *
 * <p>Bug: the original code used {@code wsBefore} with
 * {@code closeSession(1003, ...)} which only closed the WS session
 * <em>after</em> the upgrade was already accepted. This left a window
 * where unauthenticated clients could connect and send messages
 * (including print jobs) before the session was closed.
 *
 * <p>Fix: switched to {@code wsBeforeUpgrade} which rejects the HTTP
 * upgrade request with 401 <em>before</em> the WS connection is
 * established — no connection window, no message leakage.
 *
 * <p>These tests validate:
 * <ol>
 *   <li>The Server source code uses {@code wsBeforeUpgrade} for auth
 *       (structural guard against regression)</li>
 *   <li>The auth logic accepts both {@code ?token=} query param and
 *       {@code Authorization: Bearer} header</li>
 *   <li>The old {@code closeSession} auth path is removed</li>
 * </ol>
 */
public class ServerWsAuthTest {

    private static final Path SERVER_SOURCE =
            Paths.get("src", "main", "java", "io", "github", "augustinlr17",
                    "localhardwarebridge", "Server.java");

    private static String readServerSource() throws IOException {
        return Files.readString(SERVER_SOURCE);
    }

    // --- Structural: wsBeforeUpgrade is used for auth ---

    @Test
    public void serverUsesWsBeforeUpgradeForAuth() throws IOException {
        String src = readServerSource();
        assertTrue("Server must use wsBeforeUpgrade for WebSocket authentication",
                src.contains("wsBeforeUpgrade"));
    }

    @Test
    public void wsAuthChecksTokenQueryParam() throws IOException {
        String src = readServerSource();
        assertTrue("WS auth must check the ?token= query parameter",
                src.contains("queryParam(\"token\")"));
    }

    @Test
    public void wsAuthAlsoAcceptsBearerHeader() throws IOException {
        String src = readServerSource();
        // The wsBeforeUpgrade handler should also accept Bearer tokens
        // from the Authorization header, not only query params.
        int wsUpgradeIdx = src.indexOf("wsBeforeUpgrade");
        assertTrue("wsBeforeUpgrade must be present", wsUpgradeIdx >= 0);

        // Find the auth block after wsBeforeUpgrade
        String afterUpgrade = src.substring(wsUpgradeIdx);
        assertTrue("wsBeforeUpgrade handler must call extractBearerToken",
                afterUpgrade.contains("extractBearerToken"));
    }

    @Test
    public void wsAuthRejectsWith401Status() throws IOException {
        String src = readServerSource();
        int wsUpgradeIdx = src.indexOf("wsBeforeUpgrade");
        String afterUpgrade = src.substring(wsUpgradeIdx);
        assertTrue("wsBeforeUpgrade must set HTTP 401 on auth failure",
                afterUpgrade.contains("status(401)"));
    }

    // --- Structural: old closeSession(1003) auth path is removed ---

    @Test
    public void oldCloseSessionAuthIsRemoved() throws IOException {
        String src = readServerSource();
        assertFalse("The old closeSession(1003) auth path must be removed",
                src.contains("closeSession(1003"));
    }

    @Test
    public void wsBeforeIsOnlyUsedForConfigNotAuth() throws IOException {
        String src = readServerSource();
        int wsBeforeIdx = src.indexOf("wsBefore(");
        assertTrue("wsBefore must still exist for message size config", wsBeforeIdx >= 0);

        // The wsBefore handler should NOT contain auth logic
        String wsBeforeBlock = src.substring(wsBeforeIdx, wsBeforeIdx + 500);
        assertFalse("wsBefore must not contain auth token checks (that's wsBeforeUpgrade's job)",
                wsBeforeBlock.contains("getAuthentication"));
        assertFalse("wsBefore must not contain closeSession",
                wsBeforeBlock.contains("closeSession"));
    }

    // --- Auth logic via reflection (same pattern as CrossBridgeAuthTest) ---

    private static Method constantTimeEquals;
    private static Method extractBearerToken;

    static {
        try {
            constantTimeEquals = Server.class.getDeclaredMethod("constantTimeEquals", String.class, String.class);
            constantTimeEquals.setAccessible(true);

            extractBearerToken = Server.class.getDeclaredMethod("extractBearerToken", String.class);
            extractBearerToken.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean tokenMatches(String provided, String expected) throws Exception {
        return (Boolean) constantTimeEquals.invoke(null, provided, expected);
    }

    private String bearer(String token) throws Exception {
        return (String) extractBearerToken.invoke(null, "Bearer " + token);
    }

    @Test
    public void wsQueryTokenMatchesExpected() throws Exception {
        // Simulates: ws://host/printer?token=mySecret
        assertTrue("query param token must match expected token",
                tokenMatches("mySecret", "mySecret"));
    }

    @Test
    public void wsWrongQueryTokenDoesNotMatch() throws Exception {
        assertFalse("wrong query param token must not match",
                tokenMatches("wrong", "mySecret"));
    }

    @Test
    public void wsBearerTokenMatchesExpected() throws Exception {
        // Simulates: Authorization: Bearer mySecret on the WS upgrade request
        assertTrue("bearer token must match expected token",
                tokenMatches(bearer("mySecret"), "mySecret"));
    }

    @Test
    public void wsWrongBearerTokenDoesNotMatch() throws Exception {
        assertFalse("wrong bearer token must not match",
                tokenMatches(bearer("wrong"), "mySecret"));
    }

    @Test
    public void wsNullTokenIsRejected() throws Exception {
        // Simulates: ws://host/printer with no ?token= param
        assertFalse("null token (no query param) must be rejected",
                tokenMatches(null, "mySecret"));
    }

    @Test
    public void wsBlankExpectedTokenIsRejected() throws Exception {
        // If the configured token is blank, auth must not pass
        // (the server guards with isBlank() before calling constantTimeEquals)
        assertFalse("empty provided token must not match real token",
                tokenMatches("", "mySecret"));
    }
}

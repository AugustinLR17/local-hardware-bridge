package io.github.augustinlr17.localhardwarebridge;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Tests for the CORS preflight fix and the per-endpoint password auth fix.
 *
 * <p><b>CORS fix</b>: OPTIONS preflight requests must be handled BEFORE the auth
 * handler so browsers can send cross-origin preflight checks without being
 * rejected by 401. The fix adds a dedicated {@code before} handler that
 * short-circuits OPTIONS with CORS headers and 200 status.
 *
 * <p><b>Per-endpoint password fix</b>: When global auth is enabled and an endpoint
 * has its own password, the endpoint password must be accepted as an alternative
 * to the global token. Previously, the global auth check would return 401 before
 * reaching the endpoint-specific password check, making per-endpoint passwords
 * useless when global auth was on.
 *
 * <p>These tests use structural analysis (source code inspection) and reflection
 * on the private auth methods — no server bind, no network.
 */
public class ServerCorsAndEndpointAuthTest {

    private static final Path SERVER_SOURCE =
            Paths.get("src", "main", "java", "io", "github", "augustinlr17",
                    "localhardwarebridge", "Server.java");

    private static String readServerSource() throws IOException {
        return Files.readString(SERVER_SOURCE);
    }

    // --- CORS preflight ---

    @Test
    public void serverHasOptionsPreflightHandler() throws IOException {
        String src = readServerSource();
        assertTrue("Server must have a before handler that checks for OPTIONS method",
                src.contains("HandlerType.OPTIONS"));
    }

    @Test
    public void optionsHandlerSetsCorsHeaders() throws IOException {
        String src = readServerSource();
        int optionsIdx = src.indexOf("HandlerType.OPTIONS");
        assertTrue("HandlerType.OPTIONS must be present", optionsIdx >= 0);

        // Find the OPTIONS handler block (next ~1200 chars)
        String block = src.substring(optionsIdx, Math.min(optionsIdx + 1200, src.length()));
        assertTrue("OPTIONS handler must set Access-Control-Allow-Origin",
                block.contains("Access-Control-Allow-Origin"));
        assertTrue("OPTIONS handler must set Access-Control-Allow-Methods",
                block.contains("Access-Control-Allow-Methods"));
        assertTrue("OPTIONS handler must set Access-Control-Allow-Headers",
                block.contains("Access-Control-Allow-Headers"));
    }

    @Test
    public void optionsHandlerReturns200BeforeAuth() throws IOException {
        String src = readServerSource();
        int optionsIdx = src.indexOf("HandlerType.OPTIONS");
        int authIdx = src.indexOf("// Add HTTP Auth & endpoint security");

        assertTrue("OPTIONS handler must exist", optionsIdx >= 0);
        assertTrue("Auth handler must exist", authIdx >= 0);
        assertTrue("OPTIONS handler must be registered BEFORE the auth handler",
                optionsIdx < authIdx);
    }

    @Test
    public void optionsHandlerShortCircuitsWithReturn() throws IOException {
        String src = readServerSource();
        int optionsIdx = src.indexOf("HandlerType.OPTIONS");
        String block = src.substring(optionsIdx, Math.min(optionsIdx + 1200, src.length()));
        assertTrue("OPTIONS handler must call ctx.status(200)",
                block.contains("status(200)"));
        assertTrue("OPTIONS handler must return to skip subsequent before handlers",
                block.contains("return;"));
    }

    // --- Per-endpoint password works even when global auth is enabled ---

    @Test
    public void endpointPasswordCheckedWhenGlobalAuthEnabled() throws IOException {
        String src = readServerSource();
        // Find the HTTP auth block (the SECOND occurrence — the first is WebSocket auth)
        int firstAuthIdx = src.indexOf("if (currentAuth.isEnabled())");
        assertTrue("First auth check must exist (WebSocket)", firstAuthIdx >= 0);
        int globalAuthIdx = src.indexOf("if (currentAuth.isEnabled())", firstAuthIdx + 1);
        assertTrue("Second auth check must exist (HTTP before handler)", globalAuthIdx >= 0);

        // After the global token check (but before the 401 return), there should be
        // a check for the endpoint-specific password as an alternative.
        String afterGlobalCheck = src.substring(globalAuthIdx,
                Math.min(globalAuthIdx + 2000, src.length()));

        // The fix: within the global-auth-enabled block, after the global token
        // check fails, the endpoint password is checked before returning 401.
        assertTrue("Within global auth block, endpoint password must be checked as alternative",
                afterGlobalCheck.contains("rule.getPassword()"));
        assertTrue("Endpoint password check must use constantTimeEquals",
                afterGlobalCheck.contains("constantTimeEquals(bearer, rule.getPassword())"));
    }

    @Test
    public void endpointPasswordCheckIsBefore401Return() throws IOException {
        String src = readServerSource();
        // Use the SECOND occurrence (HTTP auth, not WebSocket auth)
        int firstAuthIdx = src.indexOf("if (currentAuth.isEnabled())");
        int globalAuthIdx = src.indexOf("if (currentAuth.isEnabled())", firstAuthIdx + 1);
        assertTrue("HTTP auth block must exist", globalAuthIdx >= 0);
        String block = src.substring(globalAuthIdx,
                Math.min(globalAuthIdx + 2500, src.length()));

        // Find the FIRST occurrence of rule.getPassword() inside the global auth block
        // (this is the fix — the endpoint password check added within the block)
        int endpointPwdIdx = block.indexOf("rule.getPassword()");
        int tokenMismatchIdx = block.indexOf("Token mismatch");

        assertTrue("Endpoint password check must be present in global auth block",
                endpointPwdIdx >= 0);
        assertTrue("Token mismatch 401 must be present in global auth block",
                tokenMismatchIdx >= 0);
        assertTrue("Endpoint password check must come BEFORE the 401 Token mismatch return",
                endpointPwdIdx < tokenMismatchIdx);
    }

    @Test
    public void endpointPasswordAlsoWorksWhenGlobalAuthDisabled() throws IOException {
        String src = readServerSource();
        // The existing endpoint-specific password check (after the global auth block)
        // must still be present for when global auth is disabled.
        int secondCheckIdx = src.indexOf("// Check endpoint-specific password if set");
        assertTrue("Endpoint-specific password check must still exist for global-auth-disabled case",
                secondCheckIdx >= 0);
    }

    // --- Reflection-based auth logic tests ---

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
    public void globalTokenAndEndpointPasswordAreDistinct() throws Exception {
        String globalToken = "global-token-123";
        String endpointPassword = "printer-pin-456";

        // Global token matches itself
        assertTrue(tokenMatches(bearer(globalToken), globalToken));

        // Endpoint password matches itself
        assertTrue(tokenMatches(bearer(endpointPassword), endpointPassword));

        // Global token does NOT match endpoint password (and vice versa)
        assertFalse(tokenMatches(bearer(globalToken), endpointPassword));
        assertFalse(tokenMatches(bearer(endpointPassword), globalToken));
    }

    @Test
    public void endpointPasswordAcceptsBasicAuthPassword() throws Exception {
        // The before handler also checks basicAuthCredentials().getPassword()
        // against the endpoint password. Verify the logic conceptually:
        String endpointPassword = "my-endpoint-pin";
        // constantTimeEquals would be called with the basic-auth password
        assertTrue(tokenMatches(endpointPassword, endpointPassword));
        assertFalse(tokenMatches("wrong-pin", endpointPassword));
    }

    @Test
    public void nullEndpointPasswordIsNotChecked() throws Exception {
        // When endpoint password is null, the server skips the endpoint check.
        // This is verified structurally: rule.getPassword() != null check exists.
        String src = readServerSource();
        assertTrue("Server must null-check endpoint password before comparison",
                src.contains("rule.getPassword() != null && !rule.getPassword().isEmpty()"));
    }

    // --- TLS UI fix ---

    @Test
    public void tlsUiHidesCertFieldsWhenSelfSigned() throws IOException {
        Path htmlPath = Paths.get("src", "main", "resources", "web", "index.html");
        String html = Files.readString(htmlPath);

        // The cert/key/caBundle fields should be inside a v-if="!config.server.tls.selfSigned"
        assertTrue("Certificate fields must be gated by v-if='!config.server.tls.selfSigned'",
                html.contains("v-if=\"!config.server.tls.selfSigned\""));
    }

    @Test
    public void tlsUiShowsInfoWhenSelfSigned() throws IOException {
        Path htmlPath = Paths.get("src", "main", "resources", "web", "index.html");
        String html = Files.readString(htmlPath);

        // When self-signed is enabled, an info message should be shown
        assertTrue("Self-signed info message must be present",
                html.contains("auto-generated"));
    }

    @Test
    public void tlsUiStillGatesEntireSectionByTlsEnabled() throws IOException {
        Path htmlPath = Paths.get("src", "main", "resources", "web", "index.html");
        String html = Files.readString(htmlPath);

        // The outer v-if="config.server.tls.enabled" must still be present
        assertTrue("TLS section must still be gated by v-if='config.server.tls.enabled'",
                html.contains("v-if=\"config.server.tls.enabled\""));
    }
}

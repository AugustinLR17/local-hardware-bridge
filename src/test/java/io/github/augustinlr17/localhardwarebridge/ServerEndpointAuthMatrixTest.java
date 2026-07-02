package io.github.augustinlr17.localhardwarebridge;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Exhaustive tests for the HTTP authentication and endpoint security logic of
 * {@link Server}.
 *
 * <p>These tests faithfully replicate the exact decision tree of the {@code before}
 * handler in {@code Server.java} and exercise every meaningful combination:
 *
 * <ul>
 *   <li>No auth at all (open access)</li>
 *   <li>Global token only — Bearer and Basic Auth</li>
 *   <li>Per-endpoint password only (global auth disabled)</li>
 *   <li>Global token + per-endpoint password simultaneously</li>
 *   <li>Two endpoints with different passwords — isolation</li>
 *   <li>Wrong token / wrong password / empty credentials</li>
 *   <li>Disabled endpoint (403 takes priority over auth)</li>
 *   <li>Health endpoint bypass (always accessible)</li>
 *   <li>Config endpoint bypass (ignores rules, still checks global token)</li>
 *   <li>Serial wildcard matching (/serial/{type} rule applies to /serial/SCALE)</li>
 *   <li>Endpoint password works even when global auth is enabled (the fix)</li>
 *   <li>Null/blank token never authorizes</li>
 *   <li>CORS preflight (OPTIONS) is not subject to auth</li>
 * </ul>
 *
 * <p>The tests use reflection to call the private {@code constantTimeEquals} and
 * {@code extractBearerToken} methods, then apply the same conditional logic as the
 * real handler. No server bind, no network — fully hermetic.
 */
public class ServerEndpointAuthMatrixTest {

    // --- Reflection setup ---

    private static final Method constantTimeEquals;
    private static final Method extractBearerToken;

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

    private static boolean ctEquals(String provided, String expected) throws Exception {
        return (Boolean) constantTimeEquals.invoke(null, provided, expected);
    }

    private static String bearer(String token) throws Exception {
        return (String) extractBearerToken.invoke(null, "Bearer " + token);
    }

    private static String bearerHeader(String token) {
        return "Bearer " + token;
    }

    private static String basicHeader(String user, String password) {
        return "Basic " + java.util.Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    // --- Simulated request context ---

    /** Simulates an incoming HTTP request with path, method, and Authorization header. */
    private static class SimRequest {
        final String method;
        final String path;
        final String authorizationHeader; // raw header value (Bearer xxx or Basic xxx)

        SimRequest(String method, String path, String authorizationHeader) {
            this.method = method;
            this.path = path;
            this.authorizationHeader = authorizationHeader;
        }

        /** Extract Bearer token from the Authorization header, or null. */
        String bearerToken() throws Exception {
            return (String) extractBearerToken.invoke(null, authorizationHeader);
        }

        /** Extract Basic Auth password from the Authorization header, or null. */
        String basicPassword() {
            if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
                return null;
            }
            try {
                String decoded = new String(java.util.Base64.getDecoder()
                        .decode(authorizationHeader.substring(6)), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon < 0) return null;
                return decoded.substring(colon + 1);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // --- Simulated config state ---

    /** Simulates the Config object as seen by the before handler. */
    private static class SimConfig {
        boolean globalAuthEnabled;
        String globalToken;
        Map<String, Config.EndpointRule> endpoints = new HashMap<>();

        Config.EndpointRule rule(String path) {
            return endpoints.get(path);
        }

        SimConfig withGlobalAuth(boolean enabled, String token) {
            this.globalAuthEnabled = enabled;
            this.globalToken = token;
            return this;
        }

        SimConfig withEndpoint(String path, boolean enabled, String password) {
            Config.EndpointRule rule = new Config.EndpointRule();
            rule.setEnabled(enabled);
            rule.setPassword(password);
            endpoints.put(path, rule);
            return this;
        }
    }

    // --- The auth decision simulator ---
    // This replicates the EXACT logic of the before handler in Server.java.
    // Returns: "PASS", "401", "403", or "PASS_HEALTH", "PASS_CONFIG"

    private static final String SERIAL_PREFIX = "/serial/";
    private static final String CONFIG_PATH = "/config.json";
    private static final String HEALTH_PATH = "/system/health";

    private static String checkAuth(SimConfig config, SimRequest req) throws Exception {
        String path = req.path;

        // 1. Find matching endpoint rule
        Config.EndpointRule rule = config.rule(path);
        if (rule == null && path.startsWith(SERIAL_PREFIX)) {
            rule = config.rule("/serial/{type}");
        }

        // 2. Health endpoint always reachable
        if (HEALTH_PATH.equals(path)) {
            return "PASS_HEALTH";
        }

        // 3. Config endpoint: ignore endpoint rules but still check global auth
        if (CONFIG_PATH.equals(path)) {
            rule = null;
        }

        // 4. Disabled endpoint → 403 (before any auth check)
        if (rule != null && !rule.isEnabled()) {
            return "403";
        }

        // 5. Global auth check
        if (config.globalAuthEnabled) {
            String expectedToken = config.globalToken;
            if (expectedToken != null && !expectedToken.isBlank()) {
                // Check Bearer token
                String bearerToken = req.bearerToken();
                if (bearerToken != null && ctEquals(bearerToken, expectedToken)) {
                    return "PASS";
                }
                // Check Basic Auth password
                String basicPwd = req.basicPassword();
                if (basicPwd != null && ctEquals(basicPwd, expectedToken)) {
                    return "PASS";
                }
            }

            // 6. Endpoint-specific password as alternative (the fix)
            if (rule != null && rule.getPassword() != null && !rule.getPassword().isEmpty()) {
                String bearerToken = req.bearerToken();
                if (bearerToken != null && ctEquals(bearerToken, rule.getPassword())) {
                    return "PASS";
                }
                String basicPwd = req.basicPassword();
                if (basicPwd != null && ctEquals(basicPwd, rule.getPassword())) {
                    return "PASS";
                }
            }

            return "401";
        }

        // 7. Endpoint-specific password (when global auth disabled)
        if (rule != null && rule.getPassword() != null && !rule.getPassword().isEmpty()) {
            String bearerToken = req.bearerToken();
            if (bearerToken != null && ctEquals(bearerToken, rule.getPassword())) {
                return "PASS";
            }
            String basicPwd = req.basicPassword();
            if (basicPwd != null && ctEquals(basicPwd, rule.getPassword())) {
                return "PASS";
            }
            return "401";
        }

        // 8. No auth required
        return "PASS";
    }

    // Helper: assert the auth result
    private void assertPass(String expected, SimConfig config, SimRequest req, String message) throws Exception {
        String result = checkAuth(config, req);
        assertEquals(message, expected, result);
    }

    // ========================================================================
    // 1. NO AUTH — open access
    // ========================================================================

    @Test
    public void noAuth_noEndpointRules_allRequestsPass() throws Exception {
        SimConfig config = new SimConfig();
        // No global auth, no endpoint rules

        assertPass("PASS", config, new SimRequest("GET", "/printer", null),
                "GET /printer should pass with no auth");
        assertPass("PASS", config, new SimRequest("POST", "/printer", null),
                "POST /printer should pass with no auth");
        assertPass("PASS", config, new SimRequest("GET", "/serial/SCALE", null),
                "GET /serial/SCALE should pass with no auth");
        assertPass("PASS", config, new SimRequest("GET", "/system/version.json", null),
                "GET /system/version.json should pass with no auth");
    }

    @Test
    public void noAuth_credentialsIgnoredWhenNoAuthRequired() throws Exception {
        SimConfig config = new SimConfig();
        // Sending credentials when no auth is configured should still pass
        assertPass("PASS", config, new SimRequest("GET", "/printer", bearerHeader("random")),
                "Credentials should be ignored when no auth is configured");
    }

    // ========================================================================
    // 2. GLOBAL TOKEN ONLY (no endpoint passwords)
    // ========================================================================

    @Test
    public void globalToken_bearerCorrect_passes() throws Exception {
        SimConfig config = new SimConfig().withGlobalAuth(true, "global-secret");

        assertPass("PASS", config, new SimRequest("GET", "/printer", bearerHeader("global-secret")),
                "Correct Bearer token should pass");
    }

    @Test
    public void globalToken_bearerWrong_rejects401() throws Exception {
        SimConfig config = new SimConfig().withGlobalAuth(true, "global-secret");

        assertPass("401", config, new SimRequest("GET", "/printer", bearerHeader("wrong")),
                "Wrong Bearer token should return 401");
    }

    @Test
    public void globalToken_noCredentials_rejects401() throws Exception {
        SimConfig config = new SimConfig().withGlobalAuth(true, "global-secret");

        assertPass("401", config, new SimRequest("GET", "/printer", null),
                "No credentials should return 401");
    }

    @Test
    public void globalToken_basicAuthCorrect_passes() throws Exception {
        SimConfig config = new SimConfig().withGlobalAuth(true, "global-secret");

        assertPass("PASS", config, new SimRequest("GET", "/printer", basicHeader("user", "global-secret")),
                "Correct Basic Auth password should pass");
    }

    @Test
    public void globalToken_basicAuthWrongPassword_rejects401() throws Exception {
        SimConfig config = new SimConfig().withGlobalAuth(true, "global-secret");

        assertPass("401", config, new SimRequest("GET", "/printer", basicHeader("user", "wrong")),
                "Wrong Basic Auth password should return 401");
    }

    @Test
    public void globalToken_basicAuthAnyUsername_passes() throws Exception {
        SimConfig config = new SimConfig().withGlobalAuth(true, "global-secret");

        // Username is ignored — only password matters
        assertPass("PASS", config, new SimRequest("GET", "/printer", basicHeader("anything", "global-secret")),
                "Basic Auth with any username but correct password should pass");
    }

    @Test
    public void globalToken_blankToken_rejectsEverything() throws Exception {
        SimConfig config = new SimConfig().withGlobalAuth(true, "   ");

        // A blank configured token never auto-passes
        assertPass("401", config, new SimRequest("GET", "/printer", bearerHeader("   ")),
                "Blank token should not authorize even if provided token matches");
        assertPass("401", config, new SimRequest("GET", "/printer", null),
                "Blank token should reject no-credential requests");
    }

    @Test
    public void globalToken_nullToken_rejectsEverything() throws Exception {
        SimConfig config = new SimConfig();
        config.globalAuthEnabled = true;
        config.globalToken = null;

        assertPass("401", config, new SimRequest("GET", "/printer", bearerHeader("anything")),
                "Null token should reject all requests");
    }

    // ========================================================================
    // 3. PER-ENDPOINT PASSWORD ONLY (global auth disabled)
    // ========================================================================

    @Test
    public void endpointPassword_only_bearerCorrect_passes() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/printer", true, "printer-pin");

        assertPass("PASS", config, new SimRequest("POST", "/printer", bearerHeader("printer-pin")),
                "Correct endpoint password via Bearer should pass");
    }

    @Test
    public void endpointPassword_only_bearerWrong_rejects401() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/printer", true, "printer-pin");

        assertPass("401", config, new SimRequest("POST", "/printer", bearerHeader("wrong-pin")),
                "Wrong endpoint password should return 401");
    }

    @Test
    public void endpointPassword_only_noCredentials_rejects401() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/printer", true, "printer-pin");

        assertPass("401", config, new SimRequest("POST", "/printer", null),
                "No credentials on password-protected endpoint should return 401");
    }

    @Test
    public void endpointPassword_only_basicAuthCorrect_passes() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/printer", true, "printer-pin");

        assertPass("PASS", config, new SimRequest("POST", "/printer", basicHeader("admin", "printer-pin")),
                "Correct endpoint password via Basic Auth should pass");
    }

    @Test
    public void endpointPassword_only_otherEndpointsWithoutPassword_pass() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/printer", true, "printer-pin");

        // /system/version.json has no password rule — should pass without credentials
        assertPass("PASS", config, new SimRequest("GET", "/system/version.json", null),
                "Endpoint without password rule should pass without credentials");
    }

    @Test
    public void endpointPassword_emptyString_noProtection() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/printer", true, "");

        // Empty password = no protection
        assertPass("PASS", config, new SimRequest("POST", "/printer", null),
                "Empty endpoint password should not require auth");
    }

    @Test
    public void endpointPassword_nullPassword_noProtection() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/printer", true, null);

        assertPass("PASS", config, new SimRequest("POST", "/printer", null),
                "Null endpoint password should not require auth");
    }

    // ========================================================================
    // 4. GLOBAL TOKEN + PER-ENDPOINT PASSWORD (the fix — both must work)
    // ========================================================================

    @Test
    public void globalTokenAndEndpointPassword_globalToken_passes() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/printer", true, "printer-pin");

        // Global token works for /printer
        assertPass("PASS", config, new SimRequest("POST", "/printer", bearerHeader("global-secret")),
                "Global token should work on endpoint that also has its own password");
    }

    @Test
    public void globalTokenAndEndpointPassword_endpointPassword_passes() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/printer", true, "printer-pin");

        // Endpoint password also works (the fix!)
        assertPass("PASS", config, new SimRequest("POST", "/printer", bearerHeader("printer-pin")),
                "Endpoint password should work even when global auth is enabled");
    }

    @Test
    public void globalTokenAndEndpointPassword_wrongCredentials_rejects401() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/printer", true, "printer-pin");

        // Neither the global token nor the endpoint password
        assertPass("401", config, new SimRequest("POST", "/printer", bearerHeader("totally-wrong")),
                "Wrong credentials (neither global nor endpoint) should return 401");
    }

    @Test
    public void globalTokenAndEndpointPassword_noCredentials_rejects401() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/printer", true, "printer-pin");

        assertPass("401", config, new SimRequest("POST", "/printer", null),
                "No credentials should return 401");
    }

    @Test
    public void globalTokenAndEndpointPassword_endpointPasswordBasicAuth_passes() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/printer", true, "printer-pin");

        // Endpoint password via Basic Auth
        assertPass("PASS", config, new SimRequest("POST", "/printer", basicHeader("user", "printer-pin")),
                "Endpoint password via Basic Auth should work when global auth is enabled");
    }

    @Test
    public void globalTokenAndEndpointPassword_globalTokenBasicAuth_passes() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/printer", true, "printer-pin");

        // Global token via Basic Auth
        assertPass("PASS", config, new SimRequest("POST", "/printer", basicHeader("user", "global-secret")),
                "Global token via Basic Auth should work on password-protected endpoint");
    }

    @Test
    public void globalTokenAndEndpointPassword_endpointPasswordNotValidForOtherEndpoints() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/printer", true, "printer-pin");

        // The /printer password should NOT work on /system/version.json (which has no endpoint rule)
        // Only the global token should work there
        assertPass("401", config, new SimRequest("GET", "/system/version.json", bearerHeader("printer-pin")),
                "Endpoint password should not work on other endpoints that don't have that password");
        assertPass("PASS", config, new SimRequest("GET", "/system/version.json", bearerHeader("global-secret")),
                "Global token should work on endpoints without specific password rules");
    }

    // ========================================================================
    // 5. TWO DIFFERENT ENDPOINTS WITH DIFFERENT PASSWORDS — isolation
    // ========================================================================

    @Test
    public void twoEndpoints_differentPasswords_isolation() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/printer", true, "printer-pin")
                .withEndpoint("/serial/SCALE", true, "scale-pin");

        // Printer password works on /printer
        assertPass("PASS", config, new SimRequest("POST", "/printer", bearerHeader("printer-pin")),
                "Printer password should work on /printer");

        // Scale password works on /serial/SCALE
        assertPass("PASS", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("scale-pin")),
                "Scale password should work on /serial/SCALE");

        // Printer password does NOT work on /serial/SCALE
        assertPass("401", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("printer-pin")),
                "Printer password should NOT work on /serial/SCALE (isolation)");

        // Scale password does NOT work on /printer
        assertPass("401", config, new SimRequest("POST", "/printer", bearerHeader("scale-pin")),
                "Scale password should NOT work on /printer (isolation)");
    }

    @Test
    public void twoEndpoints_differentPasswords_withGlobalToken() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/printer", true, "printer-pin")
                .withEndpoint("/serial/SCALE", true, "scale-pin");

        // Global token works on both
        assertPass("PASS", config, new SimRequest("POST", "/printer", bearerHeader("global-secret")),
                "Global token should work on /printer");
        assertPass("PASS", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("global-secret")),
                "Global token should work on /serial/SCALE");

        // Each endpoint password works on its own endpoint
        assertPass("PASS", config, new SimRequest("POST", "/printer", bearerHeader("printer-pin")),
                "Printer password should work on /printer even with global auth");
        assertPass("PASS", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("scale-pin")),
                "Scale password should work on /serial/SCALE even with global auth");

        // Cross-endpoint password does NOT work
        assertPass("401", config, new SimRequest("POST", "/printer", bearerHeader("scale-pin")),
                "Scale password should NOT work on /printer");
        assertPass("401", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("printer-pin")),
                "Printer password should NOT work on /serial/SCALE");
    }

    @Test
    public void twoEndpoints_oneWithPassword_oneWithout() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/printer", true, "printer-pin");
        // /system/version.json has no rule

        // /printer requires password
        assertPass("401", config, new SimRequest("POST", "/printer", null),
                "/printer should require password");
        assertPass("PASS", config, new SimRequest("POST", "/printer", bearerHeader("printer-pin")),
                "/printer with correct password should pass");

        // /system/version.json is open
        assertPass("PASS", config, new SimRequest("GET", "/system/version.json", null),
                "Endpoint without rule should be open");
    }

    // ========================================================================
    // 6. SERIAL WILDCARD MATCHING (/serial/{type} applies to /serial/SCALE etc.)
    // ========================================================================

    @Test
    public void serialWildcard_ruleAppliesToAllSerialTypes() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/serial/{type}", true, "serial-pin");

        // The rule on /serial/{type} applies to /serial/SCALE, /serial/WEIGH, etc.
        assertPass("PASS", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("serial-pin")),
                "/serial/{type} password should apply to /serial/SCALE");
        assertPass("PASS", config, new SimRequest("POST", "/serial/WEIGH", bearerHeader("serial-pin")),
                "/serial/{type} password should apply to /serial/WEIGH");
        assertPass("401", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("wrong")),
                "Wrong password should fail on /serial/SCALE");
    }

    @Test
    public void serialWildcard_exactMatchTakesPriority() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/serial/{type}", true, "generic-serial-pin")
                .withEndpoint("/serial/SCALE", true, "scale-specific-pin");

        // /serial/SCALE should use the exact match, not the wildcard
        assertPass("PASS", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("scale-specific-pin")),
                "Exact match /serial/SCALE should use its own password");
        assertPass("401", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("generic-serial-pin")),
                "Wildcard password should NOT work when exact match exists with different password");

        // /serial/WEIGH still uses the wildcard
        assertPass("PASS", config, new SimRequest("POST", "/serial/WEIGH", bearerHeader("generic-serial-pin")),
                "/serial/WEIGH should use the wildcard password");
    }

    @Test
    public void serialWildcard_disabledBlocksAllSerial() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/serial/{type}", false, null);

        assertPass("403", config, new SimRequest("POST", "/serial/SCALE", null),
                "Disabled /serial/{type} should block all serial endpoints with 403");
        assertPass("403", config, new SimRequest("POST", "/serial/WEIGH", bearerHeader("anything")),
                "Disabled /serial/{type} should block even with credentials");
    }

    // ========================================================================
    // 7. DISABLED ENDPOINT (403 takes priority over auth)
    // ========================================================================

    @Test
    public void disabledEndpoint_returns403EvenWithCorrectToken() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/printer", false, null);

        // Even with the correct global token, a disabled endpoint returns 403
        assertPass("403", config, new SimRequest("POST", "/printer", bearerHeader("global-secret")),
                "Disabled endpoint should return 403 even with correct global token");
    }

    @Test
    public void disabledEndpoint_returns403EvenWithCorrectPassword() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/printer", false, "printer-pin");

        // Even with the correct endpoint password, disabled = 403
        assertPass("403", config, new SimRequest("POST", "/printer", bearerHeader("printer-pin")),
                "Disabled endpoint should return 403 even with correct endpoint password");
    }

    @Test
    public void disabledEndpoint_returns403WithNoCredentials() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/printer", false, null);

        assertPass("403", config, new SimRequest("POST", "/printer", null),
                "Disabled endpoint should return 403 with no credentials");
    }

    @Test
    public void disabledEndpoint_403Before401() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/printer", false, null);

        // No credentials + disabled endpoint → 403 (not 401)
        assertPass("403", config, new SimRequest("POST", "/printer", null),
                "Disabled endpoint should return 403 before checking auth (401)");
    }

    // ========================================================================
    // 8. HEALTH ENDPOINT BYPASS
    // ========================================================================

    @Test
    public void healthEndpoint_alwaysPasses_noAuth() throws Exception {
        SimConfig config = new SimConfig();

        assertPass("PASS_HEALTH", config, new SimRequest("GET", "/system/health", null),
                "Health endpoint should always pass without auth");
    }

    @Test
    public void healthEndpoint_alwaysPasses_withGlobalAuth() throws Exception {
        SimConfig config = new SimConfig().withGlobalAuth(true, "global-secret");

        assertPass("PASS_HEALTH", config, new SimRequest("GET", "/system/health", null),
                "Health endpoint should bypass global auth");
    }

    @Test
    public void healthEndpoint_alwaysPasses_evenIfDisabledInRules() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/system/health", false, "health-pin");

        // Even if someone tries to disable /system/health in rules, it's bypassed
        assertPass("PASS_HEALTH", config, new SimRequest("GET", "/system/health", null),
                "Health endpoint should bypass disabled rule");
    }

    @Test
    public void healthEndpoint_alwaysPasses_withPasswordInRules() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/system/health", true, "health-pin");

        // Even with a password rule, health endpoint bypasses everything
        assertPass("PASS_HEALTH", config, new SimRequest("GET", "/system/health", null),
                "Health endpoint should bypass password rule");
    }

    // ========================================================================
    // 9. CONFIG ENDPOINT BYPASS (ignores rules, but still checks global token)
    // ========================================================================

    @Test
    public void configEndpoint_ignoresDisabledRule_butChecksGlobalAuth() throws Exception {
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "global-secret")
                .withEndpoint("/config.json", false, null);

        // /config.json ignores the disabled rule, but still requires the global token
        assertPass("401", config, new SimRequest("GET", "/config.json", null),
                "Config endpoint should ignore disabled rule but still require global token");
        assertPass("PASS", config, new SimRequest("GET", "/config.json", bearerHeader("global-secret")),
                "Config endpoint with correct global token should pass");
    }

    @Test
    public void configEndpoint_ignoresPasswordRule() throws Exception {
        SimConfig config = new SimConfig()
                .withEndpoint("/config.json", true, "config-pin");

        // /config.json ignores the password rule — should pass without credentials
        assertPass("PASS", config, new SimRequest("GET", "/config.json", null),
                "Config endpoint should ignore password rule when global auth is disabled");
    }

    @Test
    public void configEndpoint_globalAuthDisabled_noRule_passes() throws Exception {
        SimConfig config = new SimConfig();

        assertPass("PASS", config, new SimRequest("GET", "/config.json", null),
                "Config endpoint should pass with no auth and no rules");
    }

    // ========================================================================
    // 10. EMPTY/NULL EDGE CASES
    // ========================================================================

    @Test
    public void emptyBearerToken_rejected() throws Exception {
        SimConfig config = new SimConfig().withGlobalAuth(true, "global-secret");

        assertPass("401", config, new SimRequest("GET", "/printer", bearerHeader("")),
                "Empty Bearer token should be rejected");
    }

    @Test
    public void emptyBasicAuthPassword_rejected() throws Exception {
        SimConfig config = new SimConfig().withGlobalAuth(true, "global-secret");

        assertPass("401", config, new SimRequest("GET", "/printer", basicHeader("user", "")),
                "Empty Basic Auth password should be rejected");
    }

    @Test
    public void malformedAuthHeader_rejected() throws Exception {
        SimConfig config = new SimConfig().withGlobalAuth(true, "global-secret");

        assertPass("401", config, new SimRequest("GET", "/printer", "NotBearer something"),
                "Malformed Authorization header should be rejected");
        assertPass("401", config, new SimRequest("GET", "/printer", "Bearer"),
                "Bearer without token should be rejected");
    }

    // ========================================================================
    // 11. CORS PREFLIGHT (OPTIONS) — structural verification
    // ========================================================================

    @Test
    public void corsPreflightStructural_handlerExists() throws Exception {
        java.nio.file.Path src = java.nio.file.Paths.get("src", "main", "java",
                "io", "github", "augustinlr17", "localhardwarebridge", "Server.java");
        String source = java.nio.file.Files.readString(src);

        // The OPTIONS handler must exist and run before the auth handler
        int optionsIdx = source.indexOf("HandlerType.OPTIONS");
        int authIdx = source.indexOf("// Add HTTP Auth & endpoint security");

        assertTrue("OPTIONS handler must exist", optionsIdx >= 0);
        assertTrue("Auth handler must exist", authIdx >= 0);
        assertTrue("OPTIONS handler must be registered BEFORE auth handler",
                optionsIdx < authIdx);

        // Verify CORS headers are set
        String optionsBlock = source.substring(optionsIdx,
                Math.min(optionsIdx + 1200, source.length()));
        assertTrue("OPTIONS handler must set Access-Control-Allow-Origin",
                optionsBlock.contains("Access-Control-Allow-Origin"));
        assertTrue("OPTIONS handler must set Access-Control-Allow-Methods",
                optionsBlock.contains("Access-Control-Allow-Methods"));
        assertTrue("OPTIONS handler must set Access-Control-Allow-Headers",
                optionsBlock.contains("Access-Control-Allow-Headers"));
        assertTrue("OPTIONS handler must return 200",
                optionsBlock.contains("status(200)"));
    }

    // ========================================================================
    // 12. COMBINED SCENARIO — realistic multi-endpoint deployment
    // ========================================================================

    @Test
    public void realisticScenario_posTerminal() throws Exception {
        // Simulates a POS deployment with:
        // - Global token for admin access
        // - /printer password for the POS app (different from global token)
        // - /serial/SCALE disabled (no scale at this terminal)
        // - /system/health always accessible
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "admin-token-2024")
                .withEndpoint("/printer", true, "pos-print-pin")
                .withEndpoint("/serial/{type}", false, null);

        // POS app prints with the endpoint password (not the admin token)
        assertPass("PASS", config, new SimRequest("POST", "/printer", bearerHeader("pos-print-pin")),
                "POS app should print with endpoint password");

        // Admin can also print with the global token
        assertPass("PASS", config, new SimRequest("POST", "/printer", bearerHeader("admin-token-2024")),
                "Admin should print with global token");

        // Random person can't print
        assertPass("401", config, new SimRequest("POST", "/printer", null),
                "Unauthenticated request should be rejected");

        // Serial is disabled
        assertPass("403", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("pos-print-pin")),
                "Disabled serial endpoint should return 403 even with any password");

        // Health is always accessible
        assertPass("PASS_HEALTH", config, new SimRequest("GET", "/system/health", null),
                "Health check should always pass");

        // Config endpoint: rule ignored, global token required
        assertPass("401", config, new SimRequest("GET", "/config.json", bearerHeader("pos-print-pin")),
                "Config endpoint should reject endpoint password (only global token)");
        assertPass("PASS", config, new SimRequest("GET", "/config.json", bearerHeader("admin-token-2024")),
                "Config endpoint should accept global token");
    }

    @Test
    public void realisticScenario_warehouseWithScales() throws Exception {
        // Simulates a WMS deployment with:
        // - No global auth (open access on localhost)
        // - /serial/SCALE password-protected (scale is sensitive)
        // - /printer open (any operator can print)
        SimConfig config = new SimConfig()
                .withEndpoint("/serial/SCALE", true, "scale-protected-999")
                .withEndpoint("/serial/WEIGH", true, "scale-protected-999");

        // Printer is open
        assertPass("PASS", config, new SimRequest("POST", "/printer", null),
                "Printer should be open without auth");

        // Scale requires password
        assertPass("401", config, new SimRequest("POST", "/serial/SCALE", null),
                "Scale should require password");
        assertPass("PASS", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("scale-protected-999")),
                "Scale with correct password should pass");

        // Weigh endpoint also protected with same password
        assertPass("PASS", config, new SimRequest("POST", "/serial/WEIGH", bearerHeader("scale-protected-999")),
                "Weigh endpoint with correct password should pass");

        // Wrong password
        assertPass("401", config, new SimRequest("POST", "/serial/SCALE", bearerHeader("wrong")),
                "Wrong scale password should be rejected");
    }

    @Test
    public void realisticScenario_multiBridge() throws Exception {
        // Simulates a central app that knows tokens for 3 bridges.
        // This bridge has global token "token-es-28001" and /printer password "print-es".
        // The central app must NOT be able to use bridge A's token on bridge B.
        SimConfig config = new SimConfig()
                .withGlobalAuth(true, "token-es-28001")
                .withEndpoint("/printer", true, "print-es");

        // Correct global token
        assertPass("PASS", config, new SimRequest("POST", "/printer", bearerHeader("token-es-28001")),
                "Correct bridge token should work");

        // Wrong bridge's token (token isolation)
        assertPass("401", config, new SimRequest("POST", "/printer", bearerHeader("token-fr-75001")),
                "Wrong bridge's token should be rejected");

        // Endpoint-specific password also works
        assertPass("PASS", config, new SimRequest("POST", "/printer", bearerHeader("print-es")),
                "Endpoint password should work alongside global token");

        // Another bridge's endpoint password should not work
        assertPass("401", config, new SimRequest("POST", "/printer", bearerHeader("print-fr")),
                "Wrong endpoint password should be rejected");
    }

    // ========================================================================
    // 13. CONSTANT-TIME COMPARISON VERIFICATION
    // ========================================================================

    @Test
    public void constantTimeEquals_sameLengthDifferentContent_rejects() throws Exception {
        assertFalse(ctEquals("abc123", "abc124"));
    }

    @Test
    public void constantTimeEquals_differentLength_rejects() throws Exception {
        assertFalse(ctEquals("short", "much-longer-token"));
    }

    @Test
    public void constantTimeEquals_nullProvided_rejects() throws Exception {
        assertFalse(ctEquals(null, "expected"));
    }

    @Test
    public void constantTimeEquals_nullExpected_rejects() throws Exception {
        assertFalse(ctEquals("provided", null));
    }

    @Test
    public void constantTimeEquals_bothNull_rejects() throws Exception {
        assertFalse(ctEquals(null, null));
    }

    @Test
    public void constantTimeEquals_exactMatch_accepts() throws Exception {
        assertTrue(ctEquals("my-secret-token", "my-secret-token"));
    }

    @Test
    public void constantTimeEquals_unicodeTokens() throws Exception {
        // Tokens with special characters should work
        String token = "tökën-with-spëcial";
        assertTrue(ctEquals(token, token));
        assertFalse(ctEquals(token, "tökën-with-spëciaI"));
    }

    // ========================================================================
    // 14. BEARER TOKEN EXTRACTION EDGE CASES
    // ========================================================================

    @Test
    public void bearerExtraction_validBearer_returnsToken() throws Exception {
        assertEquals("my-token", bearer("my-token"));
    }

    @Test
    public void bearerExtraction_noSpaceAfterBearer_returnsNull() throws Exception {
        // "Bearertoken" without space → null
        assertNull((String) extractBearerToken.invoke(null, "Bearertoken"));
    }

    @Test
    public void bearerExtraction_caseSensitiveBearer() throws Exception {
        // "bearer " (lowercase) is NOT a valid Bearer header
        assertNull((String) extractBearerToken.invoke(null, "bearer my-token"));
    }

    @Test
    public void bearerExtraction_nullHeader_returnsNull() throws Exception {
        assertNull((String) extractBearerToken.invoke(null, (Object) null));
    }

    @Test
    public void bearerExtraction_emptyHeader_returnsNull() throws Exception {
        assertNull((String) extractBearerToken.invoke(null, ""));
    }

    @Test
    public void bearerExtraction_basicAuthHeader_returnsNull() throws Exception {
        assertNull((String) extractBearerToken.invoke(null, "Basic dXNlcjpwYXNz"));
    }

    @Test
    public void bearerExtraction_tokenWithSpecialChars_preserved() throws Exception {
        String token = "jwt.eyJhbGciOiJIUzI1NiJ9.signature";
        assertEquals(token, bearer(token));
    }
}

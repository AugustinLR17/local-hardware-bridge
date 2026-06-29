package io.github.augustinlr17.localhardwarebridge;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.services.ConfigService;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.Assert.*;

/**
 * Tests for the cross-tenant / cross-bridge auth routing scenario.
 *
 * Scenario: a central web app knows the tokens of 3 distinct bridge instances
 * (A/France, B/Spain, C/England). It must be able to:
 * - Authenticate to bridge B with B's token (cross-bridge print routing)
 * - Be rejected when sending A's token to bridge B (token isolation)
 * - Use per-endpoint passwords for fine-grained access control
 *
 * This test exercises the {@code constantTimeEquals} and {@code extractBearerToken}
 * private methods of {@link Server} via reflection, simulating the auth checks
 * that happen in the HTTP {@code before} filter.
 */
public class CrossBridgeAuthTest {

    /** Tokens for 3 distinct bridges, as a central web app would store them. */
    private static final String TOKEN_A_FRANCE = "token-fr-75001";
    private static final String TOKEN_B_SPAIN = "token-es-28001";
    private static final String TOKEN_C_ENGLAND = "token-uk-sw1";

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

    // --- Token isolation between bridges ---

    @Test
    public void bridgeBAcceptsTokenB() throws Exception {
        // Bridge B with B's token → must accept
        assertTrue(tokenMatches(bearer(TOKEN_B_SPAIN), TOKEN_B_SPAIN));
    }

    @Test
    public void bridgeBRejectsTokenA() throws Exception {
        // Bridge B receiving A's token → must reject (A cannot impersonate B)
        assertFalse(tokenMatches(bearer(TOKEN_A_FRANCE), TOKEN_B_SPAIN));
    }

    @Test
    public void bridgeBRejectsTokenC() throws Exception {
        // Bridge B receiving C's token → must reject
        assertFalse(tokenMatches(bearer(TOKEN_C_ENGLAND), TOKEN_B_SPAIN));
    }

    @Test
    public void bridgeCAcceptsTokenC() throws Exception {
        // Bridge C with C's token → must accept
        assertTrue(tokenMatches(bearer(TOKEN_C_ENGLAND), TOKEN_C_ENGLAND));
    }

    @Test
    public void bridgeCRejectsTokenA() throws Exception {
        // Bridge C receiving A's token → must reject
        assertFalse(tokenMatches(bearer(TOKEN_A_FRANCE), TOKEN_C_ENGLAND));
    }

    @Test
    public void bridgeAAcceptsTokenA() throws Exception {
        // Bridge A with A's token → must accept
        assertTrue(tokenMatches(bearer(TOKEN_A_FRANCE), TOKEN_A_FRANCE));
    }

    // --- Cross-bridge routing: A prints via B or C ---

    @Test
    public void userARoutesPrintToBridgeBWithCorrectToken() throws Exception {
        // User A (France) wants to print via Bridge B (Spain).
        // The central web app sends B's token to bridge B → must accept.
        String tokenForBridgeB = TOKEN_B_SPAIN;
        assertTrue("central app must authenticate to bridge B with B's token",
                tokenMatches(bearer(tokenForBridgeB), TOKEN_B_SPAIN));
    }

    @Test
    public void userARoutesPrintToBridgeCWithCorrectToken() throws Exception {
        // User A (France) wants to print via Bridge C (England).
        // The central web app sends C's token to bridge C → must accept.
        String tokenForBridgeC = TOKEN_C_ENGLAND;
        assertTrue("central app must authenticate to bridge C with C's token",
                tokenMatches(bearer(tokenForBridgeC), TOKEN_C_ENGLAND));
    }

    @Test
    public void userCCannotAccidentallySendOwnTokenToBridgeA() throws Exception {
        // User C (England) tries to print via Bridge A (France) but sends C's token.
        // Bridge A must reject — each bridge only accepts its own token.
        assertFalse("bridge A must reject C's token",
                tokenMatches(bearer(TOKEN_C_ENGLAND), TOKEN_A_FRANCE));
    }

    // --- Per-endpoint password for cross-bridge scenarios ---

    @Test
    public void perEndpointPasswordIsIndependentFromGlobalToken() throws Exception {
        // A bridge has global token "token-fr-75001" and a per-endpoint password
        // "print-only-pin" on /printer. The central app can use either:
        // - the global token for any endpoint
        // - the endpoint password specifically for /printer

        String globalToken = TOKEN_A_FRANCE;
        String printerPassword = "print-only-pin";

        // Global token works for global auth check
        assertTrue(tokenMatches(bearer(globalToken), globalToken));

        // Endpoint password is different from global token
        assertFalse(tokenMatches(bearer(globalToken), printerPassword));

        // Endpoint password works for the endpoint-specific check
        assertTrue(tokenMatches(bearer(printerPassword), printerPassword));
    }

    // --- Empty/null token edge cases (critical for multi-tenant safety) ---

    @Test
    public void nullProvidedTokenIsRejected() throws Exception {
        assertFalse(tokenMatches(null, TOKEN_B_SPAIN));
    }

    @Test
    public void nullExpectedTokenIsRejected() throws Exception {
        assertFalse(tokenMatches(bearer(TOKEN_A_FRANCE), null));
    }

    @Test
    public void emptyStringMatchesEmptyStringButServerGuardsAgainstIt() throws Exception {
        // constantTimeEquals("", "") returns true because the byte arrays are equal.
        // The SERVER guards against this at a higher level: it checks
        // `expectedToken != null && !expectedToken.isBlank()` before calling
        // constantTimeEquals, so an empty configured token never authorizes.
        // Here we verify the raw behavior, and document the guard.
        assertTrue("constantTimeEquals('', '') is true — server must guard with isBlank()",
                tokenMatches("", ""));
    }

    @Test
    public void wrongTokenDoesNotMatchEmptyToken() throws Exception {
        // An empty provided token must not match a real configured token
        assertFalse(tokenMatches("", TOKEN_A_FRANCE));
    }

    @Test
    public void realTokenDoesNotMatchEmptyExpectedToken() throws Exception {
        // A real provided token must not match an empty configured token
        assertFalse(tokenMatches(bearer(TOKEN_A_FRANCE), ""));
    }

    // --- Bearer extraction edge cases ---

    @Test
    public void malformedBearerHeaderReturnsNull() throws Exception {
        // "Bearer" without space, or "Basic xxx" → not a Bearer token
        assertNull(bearerFromHeader("Bearer"));
        assertNull(bearerFromHeader("Basic dXNlcjpwYXNz"));
        assertNull(bearerFromHeader(null));
        assertNull(bearerFromHeader(""));
    }

    @Test
    public void bearerExtractionIsCaseSensitive() throws Exception {
        // "bearer " (lowercase) is NOT a valid Bearer header
        assertNull(bearerFromHeader("bearer " + TOKEN_A_FRANCE));
    }

    @Test
    public void bearerWithSpacesInTokenIsPreserved() throws Exception {
        // Tokens with internal spaces should be preserved (edge case)
        String tokenWithSpace = "my token with spaces";
        assertEquals(tokenWithSpace, bearerFromHeader("Bearer " + tokenWithSpace));
    }

    private String bearerFromHeader(String header) throws Exception {
        return (String) extractBearerToken.invoke(null, (Object) header);
    }

    // --- Timing attack resistance (constant-time comparison) ---

    @Test
    public void differentLengthTokensAreRejected() throws Exception {
        // Constant-time comparison must reject tokens of different lengths
        assertFalse(tokenMatches(bearer("short"), TOKEN_A_FRANCE));
        assertFalse(tokenMatches(bearer(TOKEN_A_FRANCE + "extra"), TOKEN_A_FRANCE));
    }

    @Test
    public void similarButDifferentTokensAreRejected() throws Exception {
        // Tokens that differ by one character must be rejected
        String almostB = TOKEN_B_SPAIN.substring(0, TOKEN_B_SPAIN.length() - 1) + "X";
        assertFalse(tokenMatches(bearer(almostB), TOKEN_B_SPAIN));
    }
}

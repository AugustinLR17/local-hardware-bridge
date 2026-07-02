package io.github.augustinlr17.localhardwarebridge;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Tests for the security audit fixes — 6 findings from the v2.2.4 audit.
 *
 * <p>These are structural tests that inspect the Server.java source code to
 * verify that each fix is present. They guard against regressions.
 *
 * <p>Findings tested:
 * <ol>
 *   <li>Token leaked via GET /system/server.json and GET /config.json</li>
 *   <li>POST /system/update/rollback accessible without confirmation</li>
 *   <li>CORS preflight OPTIONS intercepted by auth (already fixed, verify)</li>
 *   <li>POST /system/notification crashes with 500 on bad input</li>
 *   <li>POST /config.json returns 404 (no POST handler)</li>
 *   <li>?token= query param not accepted for REST (only WebSocket)</li>
 * </ol>
 */
public class ServerSecurityAuditFixTest {

    private static final Path SERVER_SOURCE =
            Paths.get("src", "main", "java", "io", "github", "augustinlr17",
                    "localhardwarebridge", "Server.java");

    private static String readServerSource() throws IOException {
        return Files.readString(SERVER_SOURCE);
    }

    // ========================================================================
    // Fix #1 — Token must be masked in API responses
    // ========================================================================

    @Test
    public void tokenMaskedInServerJsonGetResponse() throws IOException {
        String src = readServerSource();
        int getServerIdx = src.indexOf("javalinServer.get(\"/system/server.json\"");
        assertTrue("GET /system/server.json handler must exist", getServerIdx >= 0);

        // The handler must mask the token before returning
        String handlerBlock = src.substring(getServerIdx,
                Math.min(getServerIdx + 800, src.length()));
        assertTrue("GET /system/server.json must mask the token in the response",
                handlerBlock.contains("token") && (
                        handlerBlock.contains("***") ||
                        handlerBlock.contains("mask") ||
                        handlerBlock.contains("redact") ||
                        handlerBlock.contains("setToken")));
    }

    @Test
    public void tokenMaskedInConfigJsonGetResponse() throws IOException {
        String src = readServerSource();
        int getConfigIdx = src.indexOf("javalinServer.get(CONFIG_PATH");
        assertTrue("GET /config.json handler must exist", getConfigIdx >= 0);

        String handlerBlock = src.substring(getConfigIdx,
                Math.min(getConfigIdx + 2000, src.length()));
        assertTrue("GET /config.json must mask the token in the response",
                handlerBlock.contains("maskToken"));
    }

    @Test
    public void tokenMaskedInServerJsonPutResponse() throws IOException {
        String src = readServerSource();
        int putServerIdx = src.indexOf("javalinServer.put(\"/system/server.json\"");
        assertTrue("PUT /system/server.json handler must exist", putServerIdx >= 0);

        String handlerBlock = src.substring(putServerIdx,
                Math.min(putServerIdx + 2000, src.length()));
        assertTrue("PUT /system/server.json must mask the token in the response",
                handlerBlock.contains("maskToken"));
    }

    @Test
    public void tokenMaskingDoesNotBreakAuthCheck() throws IOException {
        String src = readServerSource();
        // The before handler must still use the real token from configService, not a masked one
        int beforeIdx = src.indexOf("// Add HTTP Auth & endpoint security");
        String beforeBlock = src.substring(beforeIdx,
                Math.min(beforeIdx + 6000, src.length()));
        assertTrue("Auth handler must still read the real token from configService",
                beforeBlock.contains("currentAuth.getToken()"));
    }

    // ========================================================================
    // Fix #2 — Rollback endpoint must require confirmation
    // ========================================================================

    @Test
    public void rollbackEndpointRequiresConfirmParam() throws IOException {
        String src = readServerSource();
        int rollbackIdx = src.indexOf("javalinServer.post(\"/system/update/rollback\"");
        assertTrue("POST /system/update/rollback handler must exist", rollbackIdx >= 0);

        String handlerBlock = src.substring(rollbackIdx,
                Math.min(rollbackIdx + 1500, src.length()));
        assertTrue("Rollback must require a confirm parameter or header",
                handlerBlock.contains("confirm") ||
                handlerBlock.contains("force") ||
                handlerBlock.contains("x-confirm") ||
                handlerBlock.contains("queryParam(\"confirm\""));
    }

    @Test
    public void rollbackRejectsWithoutConfirmation() throws IOException {
        String src = readServerSource();
        int rollbackIdx = src.indexOf("javalinServer.post(\"/system/update/rollback\"");
        String handlerBlock = src.substring(rollbackIdx,
                Math.min(rollbackIdx + 1500, src.length()));
        // Must return 400 or 403 when confirm is missing
        assertTrue("Rollback must return 400 or 403 when confirm is not provided",
                handlerBlock.contains("400") || handlerBlock.contains("403"));
    }

    @Test
    public void applyEndpointRequiresConfirmParam() throws IOException {
        String src = readServerSource();
        int applyIdx = src.indexOf("javalinServer.post(\"/system/update/apply\"");
        assertTrue("POST /system/update/apply handler must exist", applyIdx >= 0);

        String handlerBlock = src.substring(applyIdx,
                Math.min(applyIdx + 1500, src.length()));
        assertTrue("Apply must require a confirm parameter or header",
                handlerBlock.contains("confirm") ||
                handlerBlock.contains("force") ||
                handlerBlock.contains("x-confirm") ||
                handlerBlock.contains("queryParam(\"confirm\""));
    }

    // ========================================================================
    // Fix #3 — CORS preflight OPTIONS must bypass auth (verify existing fix)
    // ========================================================================

    @Test
    public void corsPreflightHandlerRunsBeforeAuth() throws IOException {
        String src = readServerSource();
        int optionsIdx = src.indexOf("HandlerType.OPTIONS");
        int authIdx = src.indexOf("// Add HTTP Auth & endpoint security");

        assertTrue("OPTIONS handler must exist", optionsIdx >= 0);
        assertTrue("Auth handler must exist", authIdx >= 0);
        assertTrue("OPTIONS handler must be registered BEFORE auth handler",
                optionsIdx < authIdx);
    }

    @Test
    public void corsPreflightSetsAllowOriginHeader() throws IOException {
        String src = readServerSource();
        int optionsIdx = src.indexOf("HandlerType.OPTIONS");
        String block = src.substring(optionsIdx, Math.min(optionsIdx + 1200, src.length()));
        assertTrue("OPTIONS handler must set Access-Control-Allow-Origin",
                block.contains("Access-Control-Allow-Origin"));
    }

    // ========================================================================
    // Fix #4 — POST /system/notification must not crash on bad input
    // ========================================================================

    @Test
    public void notificationEndpointHasTryCatch() throws IOException {
        String src = readServerSource();
        int notifIdx = src.indexOf("javalinServer.post(\"/system/notification\"");
        assertTrue("POST /system/notification handler must exist", notifIdx >= 0);

        String handlerBlock = src.substring(notifIdx,
                Math.min(notifIdx + 1000, src.length()));
        assertTrue("Notification handler must have try-catch to prevent 500 crash",
                handlerBlock.contains("try") && handlerBlock.contains("catch"));
    }

    @Test
    public void notificationEndpointHandlesEmptyBody() throws IOException {
        String src = readServerSource();
        int notifIdx = src.indexOf("javalinServer.post(\"/system/notification\"");
        String handlerBlock = src.substring(notifIdx,
                Math.min(notifIdx + 1000, src.length()));
        // Must handle empty/null body gracefully (400 or error JSON, not 500)
        assertTrue("Notification handler must handle empty body gracefully",
                handlerBlock.contains("400") ||
                handlerBlock.contains("body") ||
                handlerBlock.contains("isEmpty") ||
                handlerBlock.contains("blank"));
    }

    // ========================================================================
    // Fix #5 — POST /config.json must work (alias to PUT)
    // ========================================================================

    @Test
    public void postConfigJsonHandlerExists() throws IOException {
        String src = readServerSource();
        // There must be a POST handler for /config.json (or CONFIG_PATH)
        assertTrue("Server must have a POST handler for config.json",
                src.contains("javalinServer.post(CONFIG_PATH") ||
                src.contains("javalinServer.post(\"/config.json\""));
    }

    // ========================================================================
    // Fix #6 — ?token= query param must be accepted for REST auth
    // ========================================================================

    @Test
    public void restAuthAcceptsTokenQueryParam() throws IOException {
        String src = readServerSource();
        // The HTTP before handler must check queryParam("token") as a fallback
        int authIdx = src.indexOf("// Add HTTP Auth & endpoint security");
        String authBlock = src.substring(authIdx,
                Math.min(authIdx + 6000, src.length()));
        assertTrue("REST auth handler must accept ?token= query parameter",
                authBlock.contains("queryParam(\"token\")"));
    }

    @Test
    public void restAuthTokenQueryParamCheckedBefore401() throws IOException {
        String src = readServerSource();
        int authIdx = src.indexOf("// Add HTTP Auth & endpoint security");
        String authBlock = src.substring(authIdx,
                Math.min(authIdx + 6000, src.length()));

        int tokenQueryParamIdx = authBlock.indexOf("queryParam(\"token\")");
        int tokenMismatchIdx = authBlock.indexOf("Token mismatch");

        assertTrue("queryParam token check must be present", tokenQueryParamIdx >= 0);
        assertTrue("Token mismatch 401 must be present", tokenMismatchIdx >= 0);
        assertTrue("queryParam token check must come before the 401 return",
                tokenQueryParamIdx < tokenMismatchIdx);
    }

    @Test
    public void restAuthTokenQueryParamUsesConstantTimeComparison() throws IOException {
        String src = readServerSource();
        int authIdx = src.indexOf("// Add HTTP Auth & endpoint security");
        String authBlock = src.substring(authIdx,
                Math.min(authIdx + 6000, src.length()));

        // The query param token must be compared with constantTimeEquals, not ==
        int tokenQueryParamIdx = authBlock.indexOf("queryParam(\"token\")");
        assertTrue("queryParam token must be present", tokenQueryParamIdx >= 0);

        String afterTokenQueryParam = authBlock.substring(tokenQueryParamIdx,
                Math.min(tokenQueryParamIdx + 300, authBlock.length()));
        assertTrue("queryParam token must use constantTimeEquals",
                afterTokenQueryParam.contains("constantTimeEquals"));
    }

    // ========================================================================
    // Fix #2b — restart endpoint should also require confirm
    // ========================================================================

    @Test
    public void restartEndpointRequiresConfirmParam() throws IOException {
        String src = readServerSource();
        int restartIdx = src.indexOf("javalinServer.post(\"/system/restart.json\"");
        assertTrue("POST /system/restart.json handler must exist", restartIdx >= 0);

        String handlerBlock = src.substring(restartIdx,
                Math.min(restartIdx + 1500, src.length()));
        assertTrue("Restart must require a confirm parameter",
                handlerBlock.contains("confirm") ||
                handlerBlock.contains("force") ||
                handlerBlock.contains("x-confirm") ||
                handlerBlock.contains("queryParam(\"confirm\""));
    }

    // ========================================================================
    // Reflection-based token masking verification
    // ========================================================================

    @Test
    public void maskedTokenConstantExists() throws IOException {
        String src = readServerSource();
        // There should be a constant for the masked token value
        assertTrue("Server must define a masked token constant (e.g. \"***\")",
                src.contains("\"***\"") ||
                src.contains("MASKED") ||
                src.contains("REDACTED"));
    }
}

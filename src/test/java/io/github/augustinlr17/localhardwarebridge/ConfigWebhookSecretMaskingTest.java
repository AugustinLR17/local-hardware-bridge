package io.github.augustinlr17.localhardwarebridge;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests that the webhook secret is masked in config GET/PUT responses.
 *
 * <p>The Server's config-masking helper must redact both the authentication
 * token and the webhook secret before the config JSON is returned to any
 * client. This guards against secret leakage through GET /config.json and
 * the masked PUT/POST /config.json response.
 *
 * <p>Uses reflection to invoke the private static masking method directly so
 * the test stays hermetic (no server bind, no network).
 */
public class ConfigWebhookSecretMaskingTest {

    private static String mask(String json) throws Exception {
        Method m = Server.class.getDeclaredMethod("maskToken", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, json);
    }

    private static String configJsonWithWebhookSecret(String secret) throws Exception {
        Config config = new Config();
        config.getWebhook().setEnabled(true);
        config.getWebhook().setUrl("https://example.com/hook");
        config.getWebhook().setSecret(secret);
        return config.toJson();
    }

    @Test
    public void webhookSecretIsMasked() throws Exception {
        String json = configJsonWithWebhookSecret("super-secret-hmac-key");
        String masked = mask(json);

        assertFalse("masked config must not contain the plaintext webhook secret",
                masked.contains("super-secret-hmac-key"));
        assertTrue("masked config must contain a masked secret placeholder",
                masked.contains("\"secret\":\"***\""));
    }

    @Test
    public void nullWebhookSecretRemainsNull() throws Exception {
        Config config = new Config();
        // secret is null by default
        String json = config.toJson();
        String masked = mask(json);

        // null secret stays null (no placeholder needed for null)
        assertTrue(masked.contains("\"secret\":null"));
        assertFalse(masked.contains("\"secret\":\"***\""));
    }

    @Test
    public void authTokenStillMaskedAlongsideWebhookSecret() throws Exception {
        Config config = new Config();
        config.getServer().getAuthentication().setEnabled(true);
        config.getServer().getAuthentication().setToken("auth-token-xyz");
        config.getWebhook().setSecret("webhook-secret-abc");

        String masked = mask(config.toJson());

        assertFalse(masked.contains("auth-token-xyz"));
        assertFalse(masked.contains("webhook-secret-abc"));
        assertTrue(masked.contains("\"token\":\"***\""));
        assertTrue(masked.contains("\"secret\":\"***\""));
    }

    @Test
    public void webhookUrlNotMasked() throws Exception {
        String json = configJsonWithWebhookSecret("the-secret");
        String masked = mask(json);

        // URL is not sensitive and must remain visible.
        assertTrue(masked.contains("https://example.com/hook"));
        assertFalse(masked.contains("the-secret"));
    }
}

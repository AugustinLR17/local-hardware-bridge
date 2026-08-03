package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the {@link Config.Webhook} section: exact default values
 * from the 2.5.0 architecture (VAL-WEBHOOK-004) and Jackson round-trip.
 * Fully hermetic.
 */
public class ConfigWebhookTest {

    private static ObjectMapper appMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    public void defaultsMatchArchitectureSpec() {
        Config.Webhook wh = new Config.Webhook();

        // Webhook is disabled by default.
        assertFalse(wh.isEnabled());
        assertNull(wh.getUrl());
        assertNull(wh.getSecret());

        // blockPrivateNetworks defaults to false, matching the downloader default.
        assertFalse(wh.isBlockPrivateNetworks());

        // Delivery defaults from VAL-WEBHOOK-004.
        assertEquals(10, wh.getConnectTimeoutSeconds());
        assertEquals(30, wh.getReadTimeoutSeconds());
        assertEquals(65536, wh.getMaxResponseBytes());
        assertEquals(10, wh.getMaxAttempts());
        assertEquals(30, wh.getInitialRetryDelaySeconds());
        assertEquals(3600, wh.getMaxRetryDelaySeconds());
        assertEquals(72, wh.getMaxRetryAgeHours());
        assertEquals(2, wh.getDeliveryWorkers());
    }

    @Test
    public void sectionPresentInDefaultConfig() {
        Config config = new Config();
        assertNotNull(config.getWebhook());
        assertFalse(config.getWebhook().isEnabled());
    }

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        ObjectMapper mapper = appMapper();

        Config.Webhook original = new Config.Webhook();
        original.setEnabled(true);
        original.setUrl("https://example.com/webhook");
        original.setSecret("my-hmac-secret");
        original.setBlockPrivateNetworks(true);
        original.setConnectTimeoutSeconds(5);
        original.setReadTimeoutSeconds(15);
        original.setMaxResponseBytes(32768);
        original.setMaxAttempts(5);
        original.setInitialRetryDelaySeconds(10);
        original.setMaxRetryDelaySeconds(600);
        original.setMaxRetryAgeHours(24);
        original.setDeliveryWorkers(1);

        String json = mapper.writeValueAsString(original);
        Config.Webhook restored = mapper.readValue(json, Config.Webhook.class);

        assertEquals(original, restored);
        assertEquals("my-hmac-secret", restored.getSecret());
    }

    @Test
    public void fullConfigRoundTripPreservesWebhook() throws Exception {
        ObjectMapper mapper = appMapper();

        Config original = new Config();
        original.getWebhook().setEnabled(true);
        original.getWebhook().setUrl("https://hook.example.com/event");
        original.getWebhook().setSecret("secret-abc");

        String json = mapper.writeValueAsString(original);
        Config restored = mapper.readValue(json, Config.class);

        assertTrue(restored.getWebhook().isEnabled());
        assertEquals("https://hook.example.com/event", restored.getWebhook().getUrl());
        assertEquals("secret-abc", restored.getWebhook().getSecret());
        // Untouched fields keep defaults.
        assertFalse(restored.getWebhook().isBlockPrivateNetworks());
        assertEquals(10, restored.getWebhook().getConnectTimeoutSeconds());
    }
}

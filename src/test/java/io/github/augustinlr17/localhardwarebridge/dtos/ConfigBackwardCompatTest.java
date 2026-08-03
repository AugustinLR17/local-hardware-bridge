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
 * Backward-compatibility tests: a representative 2.4 configuration (no
 * printJobs or webhook sections, plus unknown future fields) loads without
 * manual migration and the new additive sections receive exact defaults.
 *
 * <p>Fulfills VAL-CROSS-006: "Pre-2.5 configuration upgrades safely."
 * Fully hermetic.
 */
public class ConfigBackwardCompatTest {

    private static ObjectMapper appMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** A representative 2.4 config covering auth, CORS, endpoint rules,
     *  printer/serial mappings, downloader/TLS, GUI, and update. */
    private static final String CONFIG_2_4 = "{\n" +
            "  \"gui\": { \"notification\": { \"enabled\": true } },\n" +
            "  \"server\": {\n" +
            "    \"address\": \"127.0.0.1\",\n" +
            "    \"bind\": \"0.0.0.0\",\n" +
            "    \"port\": 50505,\n" +
            "    \"authentication\": { \"enabled\": true, \"token\": \"legacy-token\" },\n" +
            "    \"tls\": { \"enabled\": false, \"selfSigned\": true, \"cert\": \"tls/default-cert.pem\", \"key\": \"tls/default-key.pem\", \"caBundle\": null },\n" +
            "    \"cors\": { \"allowAllOrigins\": false, \"allowedOrigins\": [\"https://trusted.example\"] }\n" +
            "  },\n" +
            "  \"security\": { \"endpoints\": { \"/printer\": { \"enabled\": true, \"password\": \"print-pass\" } } },\n" +
            "  \"downloader\": { \"ignoreTLSCertificateError\": false, \"blockPrivateNetworks\": true, \"timeout\": 30.0, \"path\": \"downloads\" },\n" +
            "  \"printer\": { \"enabled\": true, \"autoAddUnknownType\": true, \"fallbackToDefault\": false, \"mappings\": [ { \"type\": \"RECEIPT\", \"name\": \"POS-80\", \"autoRotate\": false, \"resetImageableArea\": true, \"forceDPI\": 0 } ] },\n" +
            "  \"serial\": { \"enabled\": true, \"mappings\": [ ] },\n" +
            "  \"update\": { \"enabled\": true, \"autoDownload\": false, \"autoInstall\": false, \"includePrereleases\": false, \"checkIntervalHours\": 24, \"repository\": \"AugustinLR17/local-hardware-bridge\", \"channel\": \"stable\" },\n" +
            "  \"futureUnknownField\": { \"someNewFeature\": true }\n" +
            "}";

    @Test
    public void loadsWithoutManualMigration() throws Exception {
        ObjectMapper mapper = appMapper();
        Config config = mapper.readValue(CONFIG_2_4, Config.class);

        // Existing 2.4 fields preserved.
        assertEquals(50505, config.getServer().getPort());
        assertEquals("0.0.0.0", config.getServer().getBind());
        assertTrue(config.getServer().getAuthentication().isEnabled());
        assertEquals("legacy-token", config.getServer().getAuthentication().getToken());
        assertFalse(config.getServer().getCors().isAllowAllOrigins());
        assertEquals("https://trusted.example", config.getServer().getCors().getAllowedOrigins().get(0));
        assertTrue(config.getDownloader().isBlockPrivateNetworks());
        assertTrue(config.getPrinter().isAutoAddUnknownType());
        assertEquals(1, config.getPrinter().getMappings().size());
        assertEquals("RECEIPT", config.getPrinter().getMappings().get(0).getType());
        assertEquals("print-pass", config.getSecurity().getEndpoints().get("/printer").getPassword());
    }

    @Test
    public void printJobsDefaultsAppliedOnUpgrade() throws Exception {
        ObjectMapper mapper = appMapper();
        Config config = mapper.readValue(CONFIG_2_4, Config.class);

        assertNotNull(config.getPrintJobs());
        // Exact additive defaults from VAL-CROSS-006.
        assertEquals(10485760, config.getPrintJobs().getMaxPayloadBytes());
        assertEquals(1000, config.getPrintJobs().getMaxQueuedJobs());
        assertEquals(524288000, config.getPrintJobs().getMaxPersistentBytes());
        assertEquals(419430400, config.getPrintJobs().getCleanupThresholdBytes());
        assertEquals(367001600, config.getPrintJobs().getCleanupTargetBytes());
        assertEquals(268435456, config.getPrintJobs().getMinFreeBytes());
        assertEquals(5, config.getPrintJobs().getMinFreePercent());
        assertEquals(7, config.getPrintJobs().getSuccessRetentionDays());
        assertEquals(30, config.getPrintJobs().getFailureRetentionDays());
    }

    @Test
    public void webhookDefaultsAppliedOnUpgrade() throws Exception {
        ObjectMapper mapper = appMapper();
        Config config = mapper.readValue(CONFIG_2_4, Config.class);

        assertNotNull(config.getWebhook());
        // Webhook disabled by default, secrets null.
        assertFalse(config.getWebhook().isEnabled());
        assertNull(config.getWebhook().getUrl());
        assertNull(config.getWebhook().getSecret());
        // blockPrivateNetworks defaults to false (independent from downloader).
        assertFalse(config.getWebhook().isBlockPrivateNetworks());
        // Delivery defaults from VAL-WEBHOOK-004.
        assertEquals(10, config.getWebhook().getConnectTimeoutSeconds());
        assertEquals(30, config.getWebhook().getReadTimeoutSeconds());
        assertEquals(65536, config.getWebhook().getMaxResponseBytes());
        assertEquals(10, config.getWebhook().getMaxAttempts());
        assertEquals(30, config.getWebhook().getInitialRetryDelaySeconds());
        assertEquals(3600, config.getWebhook().getMaxRetryDelaySeconds());
        assertEquals(72, config.getWebhook().getMaxRetryAgeHours());
        assertEquals(2, config.getWebhook().getDeliveryWorkers());
    }

    @Test
    public void unknownFieldsTolerated() throws Exception {
        ObjectMapper mapper = appMapper();
        // The CONFIG_2_4 fixture includes "futureUnknownField" which must not break loading.
        Config config = mapper.readValue(CONFIG_2_4, Config.class);
        assertEquals(50505, config.getServer().getPort());
        assertNotNull(config.getPrintJobs());
        assertNotNull(config.getWebhook());
    }

    @Test
    public void upgradedConfigRoundTripsWithNewSections() throws Exception {
        ObjectMapper mapper = appMapper();
        Config config = mapper.readValue(CONFIG_2_4, Config.class);

        // Serialize the upgraded config and reload — new sections must survive.
        String json = mapper.writeValueAsString(config);
        Config restored = mapper.readValue(json, Config.class);

        assertEquals(config.getPrintJobs(), restored.getPrintJobs());
        assertEquals(config.getWebhook(), restored.getWebhook());
        assertEquals(50505, restored.getServer().getPort());
        assertEquals("legacy-token", restored.getServer().getAuthentication().getToken());
    }

    @Test
    public void webhookBlockPrivateNetworksIndependentFromDownloader() throws Exception {
        ObjectMapper mapper = appMapper();
        Config config = mapper.readValue(CONFIG_2_4, Config.class);

        // Downloader has blockPrivateNetworks=true in the 2.4 fixture,
        // but webhook.blockPrivateNetworks must default to false independently.
        assertTrue(config.getDownloader().isBlockPrivateNetworks());
        assertFalse(config.getWebhook().isBlockPrivateNetworks());
    }
}

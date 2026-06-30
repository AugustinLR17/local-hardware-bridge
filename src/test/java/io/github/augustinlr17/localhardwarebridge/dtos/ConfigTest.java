package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the {@link Config} DTO: default values and Jackson round-trip.
 * These are fully hermetic (no I/O, no network, no server).
 */
public class ConfigTest {

    /** Mirror the ObjectMapper settings the application uses (see ConfigService). */
    private static ObjectMapper appMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    public void defaultsMatchSecureExpectations() {
        Config config = new Config();

        // Authentication must be OFF by default (matches e2e harness assumptions).
        assertFalse("auth must be disabled by default", config.getServer().getAuthentication().isEnabled());
        assertEquals(null, config.getServer().getAuthentication().getToken());

        // CORS: allow-all is the default; allowedOrigins starts empty (non-null).
        assertTrue("CORS allowAllOrigins must default to true",
                config.getServer().getCors().isAllowAllOrigins());
        assertNotNull(config.getServer().getCors().getAllowedOrigins());
        assertTrue(config.getServer().getCors().getAllowedOrigins().isEmpty());

        // Downloader: private-network blocking is OFF by default (opt-in SSRF guard).
        assertFalse("blockPrivateNetworks must default to false",
                config.getDownloader().isBlockPrivateNetworks());
        assertFalse(config.getDownloader().isIgnoreTLSCertificateError());
        assertEquals("downloads", config.getDownloader().getPath());

        // TLS defaults.
        assertFalse(config.getServer().getTls().isEnabled());
        assertTrue(config.getServer().getTls().isSelfSigned());
        assertEquals("tls/default-cert.pem", config.getServer().getTls().getCert());
        assertEquals("tls/default-key.pem", config.getServer().getTls().getKey());
        assertEquals(null, config.getServer().getTls().getCaBundle());

        // A few other documented defaults.
        assertEquals("127.0.0.1", config.getServer().getAddress());
        assertEquals("127.0.0.1", config.getServer().getBind());
        assertEquals(57212, config.getServer().getPort());
        assertTrue(config.getPrinter().isEnabled());
        assertTrue(config.getSerial().isEnabled());
    }

    @Test
    public void jacksonRoundTripPreservesKeyFields() throws Exception {
        ObjectMapper mapper = appMapper();

        Config original = new Config();
        // Mutate a representative spread of fields so the round-trip is meaningful.
        original.getServer().setPort(54321);
        original.getServer().getAuthentication().setEnabled(true);
        original.getServer().getAuthentication().setToken("tok-123");
        original.getServer().getCors().setAllowAllOrigins(false);
        original.getServer().getCors().getAllowedOrigins().add("https://example.com");
        original.getDownloader().setBlockPrivateNetworks(true);
        original.getDownloader().setPath("custom-downloads");
        original.getServer().getTls().setEnabled(true);
        original.getServer().getTls().setCert("tls/my-cert.pem");

        String json = mapper.writeValueAsString(original);
        Config restored = mapper.readValue(json, Config.class);

        assertEquals(54321, restored.getServer().getPort());
        assertTrue(restored.getServer().getAuthentication().isEnabled());
        assertEquals("tok-123", restored.getServer().getAuthentication().getToken());
        assertFalse(restored.getServer().getCors().isAllowAllOrigins());
        assertEquals(1, restored.getServer().getCors().getAllowedOrigins().size());
        assertEquals("https://example.com", restored.getServer().getCors().getAllowedOrigins().get(0));
        assertTrue(restored.getDownloader().isBlockPrivateNetworks());
        assertEquals("custom-downloads", restored.getDownloader().getPath());
        assertTrue(restored.getServer().getTls().isEnabled());
        assertEquals("tls/my-cert.pem", restored.getServer().getTls().getCert());

        // Lombok @Data generates equals(); full structural equality should also hold.
        assertEquals(original, restored);
    }

    @Test
    public void deserializeToleratesUnknownProperties() throws Exception {
        ObjectMapper mapper = appMapper();
        // A future/foreign field must not break loading of an older/newer config.
        String json = "{\"server\":{\"port\":11111,\"thisFieldDoesNotExist\":42},"
                + "\"somethingCompletelyNew\":{\"nested\":true}}";

        Config restored = mapper.readValue(json, Config.class);
        assertEquals(11111, restored.getServer().getPort());
        // Untouched fields keep their defaults.
        assertFalse(restored.getServer().getAuthentication().isEnabled());
        assertTrue(restored.getServer().getCors().isAllowAllOrigins());
    }
}

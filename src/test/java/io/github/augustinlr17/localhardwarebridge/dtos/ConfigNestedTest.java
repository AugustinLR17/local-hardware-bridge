package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Additional unit tests for {@link Config} nested DTOs: CORS, Security,
 * EndpointRule, PrinterMapping, SerialMapping, Downloader, TLS.
 * Fully hermetic.
 */
public class ConfigNestedTest {

    private static ObjectMapper appMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // --- CORS ---

    @Test
    public void corsDefaults() {
        Config.Server.Cors cors = new Config.Server.Cors();
        assertTrue(cors.isAllowAllOrigins());
        assertNotNull(cors.getAllowedOrigins());
        assertTrue(cors.getAllowedOrigins().isEmpty());
    }

    @Test
    public void corsRoundTripWithOrigins() throws Exception {
        Config.Server.Cors cors = new Config.Server.Cors();
        cors.setAllowAllOrigins(false);
        cors.getAllowedOrigins().add("https://app1.com");
        cors.getAllowedOrigins().add("http://localhost:3000");

        ObjectMapper mapper = appMapper();
        String json = mapper.writeValueAsString(cors);
        Config.Server.Cors restored = mapper.readValue(json, Config.Server.Cors.class);

        assertFalse(restored.isAllowAllOrigins());
        assertEquals(2, restored.getAllowedOrigins().size());
        assertEquals("https://app1.com", restored.getAllowedOrigins().get(0));
    }

    // --- Security / EndpointRule ---

    @Test
    public void securityDefaultsToEmptyMap() {
        Config.Security security = new Config.Security();
        assertNotNull(security.getEndpoints());
        assertTrue(security.getEndpoints().isEmpty());
    }

    @Test
    public void endpointRuleDefaults() {
        Config.EndpointRule rule = new Config.EndpointRule();
        assertTrue(rule.isEnabled());
        assertNull(rule.getPassword());
    }

    @Test
    public void endpointRuleRoundTrip() throws Exception {
        Config.EndpointRule rule = new Config.EndpointRule();
        rule.setEnabled(false);
        rule.setPassword("secret");

        ObjectMapper mapper = appMapper();
        String json = mapper.writeValueAsString(rule);
        Config.EndpointRule restored = mapper.readValue(json, Config.EndpointRule.class);

        assertFalse(restored.isEnabled());
        assertEquals("secret", restored.getPassword());
    }

    @Test
    public void securityWithMultipleEndpointsRoundTrip() throws Exception {
        Config.Security security = new Config.Security();
        Config.EndpointRule printerRule = new Config.EndpointRule();
        printerRule.setEnabled(true);
        printerRule.setPassword("print-pass");
        security.getEndpoints().put("/printer", printerRule);

        Config.EndpointRule restartRule = new Config.EndpointRule();
        restartRule.setEnabled(false);
        security.getEndpoints().put("/system/restart.json", restartRule);

        ObjectMapper mapper = appMapper();
        String json = mapper.writeValueAsString(security);
        Config.Security restored = mapper.readValue(json, Config.Security.class);

        assertEquals(2, restored.getEndpoints().size());
        assertTrue(restored.getEndpoints().get("/printer").isEnabled());
        assertEquals("print-pass", restored.getEndpoints().get("/printer").getPassword());
        assertFalse(restored.getEndpoints().get("/system/restart.json").isEnabled());
    }

    // --- PrinterMapping ---

    @Test
    public void printerMappingNoArgsLeavesNulls() {
        Config.PrinterMapping mapping = new Config.PrinterMapping();
        assertNull(mapping.getType());
        assertNull(mapping.getName());
        assertFalse(mapping.isAutoRotate());
        assertTrue(mapping.isResetImageableArea());
        assertEquals(0, mapping.getForceDPI());
    }

    @Test
    public void printerMappingAllArgsSetsFields() {
        Config.PrinterMapping mapping = new Config.PrinterMapping("RECEIPT", "POS-80", true, false, 203);

        assertEquals("RECEIPT", mapping.getType());
        assertEquals("POS-80", mapping.getName());
        assertTrue(mapping.isAutoRotate());
        assertFalse(mapping.isResetImageableArea());
        assertEquals(203, mapping.getForceDPI());
    }

    @Test
    public void printerMappingRoundTrip() throws Exception {
        Config.PrinterMapping original = new Config.PrinterMapping("LABEL", "Zebra-ZD420", true, true, 300);
        ObjectMapper mapper = appMapper();
        String json = mapper.writeValueAsString(original);
        Config.PrinterMapping restored = mapper.readValue(json, Config.PrinterMapping.class);

        assertEquals(original, restored);
    }

    // --- SerialMapping ---

    @Test
    public void serialMappingNoArgsLeavesNulls() {
        Config.SerialMapping mapping = new Config.SerialMapping();
        assertNull(mapping.getType());
        assertNull(mapping.getName());
        assertNull(mapping.getBaudRate());
        assertNull(mapping.getNumDataBits());
        assertNull(mapping.getNumStopBits());
        assertNull(mapping.getParity());
        assertFalse(mapping.getReadMultipleBytes());
        assertEquals("ISO-8859-1", mapping.getReadCharset());
    }

    @Test
    public void serialMappingAllArgsSetsFields() {
        Config.SerialMapping mapping = new Config.SerialMapping("WEIGH", "COM3", 9600, 8, 1, 0, true, "UTF-8");

        assertEquals("WEIGH", mapping.getType());
        assertEquals("COM3", mapping.getName());
        assertEquals(Integer.valueOf(9600), mapping.getBaudRate());
        assertEquals(Integer.valueOf(8), mapping.getNumDataBits());
        assertEquals(Integer.valueOf(1), mapping.getNumStopBits());
        assertEquals(Integer.valueOf(0), mapping.getParity());
        assertTrue(mapping.getReadMultipleBytes());
        assertEquals("UTF-8", mapping.getReadCharset());
    }

    @Test
    public void serialMappingRoundTrip() throws Exception {
        Config.SerialMapping original = new Config.SerialMapping("SCALE", "/dev/ttyUSB0", 4800, 7, 2, 1, false, "BINARY");
        ObjectMapper mapper = appMapper();
        String json = mapper.writeValueAsString(original);
        Config.SerialMapping restored = mapper.readValue(json, Config.SerialMapping.class);

        assertEquals(original, restored);
    }

    @Test
    public void serialMappingBinaryCharsetRoundTrip() throws Exception {
        Config.SerialMapping mapping = new Config.SerialMapping();
        mapping.setType("RAW");
        mapping.setName("COM5");
        mapping.setReadCharset("BINARY");

        ObjectMapper mapper = appMapper();
        String json = mapper.writeValueAsString(mapping);
        Config.SerialMapping restored = mapper.readValue(json, Config.SerialMapping.class);

        assertEquals("BINARY", restored.getReadCharset());
    }

    // --- Downloader ---

    @Test
    public void downloaderDefaults() {
        Config.Downloader dl = new Config.Downloader();
        assertFalse(dl.isIgnoreTLSCertificateError());
        assertFalse(dl.isBlockPrivateNetworks());
        assertEquals(30.0, dl.getTimeout(), 0.001);
        assertEquals("downloads", dl.getPath());
    }

    @Test
    public void downloaderRoundTrip() throws Exception {
        Config.Downloader original = new Config.Downloader();
        original.setBlockPrivateNetworks(true);
        original.setTimeout(60);
        original.setPath("/tmp/dl");

        ObjectMapper mapper = appMapper();
        String json = mapper.writeValueAsString(original);
        Config.Downloader restored = mapper.readValue(json, Config.Downloader.class);

        assertEquals(original, restored);
    }

    // --- TLS ---

    @Test
    public void tlsDefaults() {
        var tls = new Config().getServer().getTls();
        assertFalse(tls.isEnabled());
        assertTrue(tls.isSelfSigned());
        assertEquals("tls/default-cert.pem", tls.getCert());
        assertEquals("tls/default-key.pem", tls.getKey());
        assertNull(tls.getCaBundle());
    }

    @Test
    public void tlsRoundTripWithCustomCert() throws Exception {
        Config config = new Config();
        var original = config.getServer().getTls();
        original.setEnabled(true);
        original.setSelfSigned(false);
        original.setCert("tls/my-cert.pem");
        original.setKey("tls/my-key.pem");
        original.setCaBundle("tls/ca-bundle.pem");

        ObjectMapper mapper = appMapper();
        String json = mapper.writeValueAsString(config);
        Config restored = mapper.readValue(json, Config.class);

        assertEquals(true, restored.getServer().getTls().isEnabled());
        assertEquals(false, restored.getServer().getTls().isSelfSigned());
        assertEquals("tls/my-cert.pem", restored.getServer().getTls().getCert());
        assertEquals("tls/my-key.pem", restored.getServer().getTls().getKey());
        assertEquals("tls/ca-bundle.pem", restored.getServer().getTls().getCaBundle());
    }

    // --- Server URI ---

    @Test
    public void serverUriIsHttpWhenTlsDisabled() {
        Config.Server server = new Config.Server();
        assertEquals("http://127.0.0.1:12212", server.getUri());
    }

    @Test
    public void serverUriIsHttpsWhenTlsEnabled() {
        Config.Server server = new Config.Server();
        server.getTls().setEnabled(true);
        assertEquals("https://127.0.0.1:12212", server.getUri());
    }

    // --- Printer/Serial sections ---

    @Test
    public void printerDefaults() {
        Config.Printer printer = new Config.Printer();
        assertTrue(printer.isEnabled());
        assertFalse(printer.isAutoAddUnknownType());
        assertFalse(printer.isFallbackToDefault());
        assertNotNull(printer.getMappings());
        assertTrue(printer.getMappings().isEmpty());
    }

    @Test
    public void serialDefaults() {
        Config.Serial serial = new Config.Serial();
        assertTrue(serial.isEnabled());
        assertNotNull(serial.getMappings());
        assertTrue(serial.getMappings().isEmpty());
    }
}

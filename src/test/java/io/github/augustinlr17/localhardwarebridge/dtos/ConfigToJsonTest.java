package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Config#toJson()} and {@link Config.Server#getUri()}
 * with various address/port/TLS combinations.
 */
public class ConfigToJsonTest {

    @Test
    public void toJsonProducesValidParseableJson() throws Exception {
        Config config = new Config();
        String json = config.toJson();

        assertNotNull(json);
        assertFalse(json.isEmpty());

        // Must be parseable by a fresh ObjectMapper
        ObjectMapper mapper = new ObjectMapper();
        Config restored = mapper.readValue(json, Config.class);
        assertNotNull(restored);
    }

    @Test
    public void toJsonContainsExpectedTopLevelKeys() throws Exception {
        Config config = new Config();
        String json = config.toJson();

        assertTrue(json.contains("\"server\""));
        assertTrue(json.contains("\"security\""));
        assertTrue(json.contains("\"downloader\""));
        assertTrue(json.contains("\"printer\""));
        assertTrue(json.contains("\"serial\""));
        assertTrue(json.contains("\"gui\""));
    }

    @Test
    public void toJsonReflectsCustomValues() throws Exception {
        Config config = new Config();
        config.getServer().setPort(9999);
        config.getServer().getAuthentication().setEnabled(true);
        config.getServer().getAuthentication().setToken("my-token");

        String json = config.toJson();
        ObjectMapper mapper = new ObjectMapper();
        Config restored = mapper.readValue(json, Config.class);

        assertEquals(9999, restored.getServer().getPort());
        assertTrue(restored.getServer().getAuthentication().isEnabled());
        assertEquals("my-token", restored.getServer().getAuthentication().getToken());
    }

    @Test
    public void toJsonRoundTripWithMappings() throws Exception {
        Config config = new Config();
        config.getPrinter().getMappings().add(
                new Config.PrinterMapping("RECEIPT", "POS-80", true, false, 203));
        config.getSerial().getMappings().add(
                new Config.SerialMapping("SCALE", "COM3", 9600, 8, 1, 0, true, "BINARY"));

        String json = config.toJson();
        ObjectMapper mapper = new ObjectMapper();
        Config restored = mapper.readValue(json, Config.class);

        assertEquals(1, restored.getPrinter().getMappings().size());
        assertEquals("RECEIPT", restored.getPrinter().getMappings().get(0).getType());
        assertEquals(203, restored.getPrinter().getMappings().get(0).getForceDPI());

        assertEquals(1, restored.getSerial().getMappings().size());
        assertEquals("SCALE", restored.getSerial().getMappings().get(0).getType());
        assertEquals("BINARY", restored.getSerial().getMappings().get(0).getReadCharset());
    }

    // --- getUri() combinations ---

    @Test
    public void getUriHttpDefault() {
        Config.Server server = new Config.Server();
        assertEquals("http://127.0.0.1:57212", server.getUri());
    }

    @Test
    public void getUriHttpsDefault() {
        Config.Server server = new Config.Server();
        server.getTls().setEnabled(true);
        assertEquals("https://127.0.0.1:57212", server.getUri());
    }

    @Test
    public void getUriCustomAddressAndPort() {
        Config.Server server = new Config.Server();
        server.setAddress("192.168.1.100");
        server.setPort(8443);
        assertEquals("http://192.168.1.100:8443", server.getUri());
    }

    @Test
    public void getUriCustomDomainWithTls() {
        Config.Server server = new Config.Server();
        server.setAddress("local.example.com");
        server.setPort(443);
        server.getTls().setEnabled(true);
        assertEquals("https://local.example.com:443", server.getUri());
    }

    @Test
    public void getUriWithZeroDotZeroDotZeroDotZero() {
        Config.Server server = new Config.Server();
        server.setAddress("0.0.0.0");
        server.setPort(8080);
        assertEquals("http://0.0.0.0:8080", server.getUri());
    }
}

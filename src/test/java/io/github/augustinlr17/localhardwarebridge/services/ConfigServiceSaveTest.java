package io.github.augustinlr17.localhardwarebridge.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class ConfigServiceSaveTest {

    private static ObjectMapper appMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static Config.EndpointRule rule(boolean enabled, String password) {
        Config.EndpointRule r = new Config.EndpointRule();
        r.setEnabled(enabled);
        r.setPassword(password);
        return r;
    }

    @Test
    public void saveWritesValidJsonReadableByLoadFromFile() throws Exception {
        ConfigService service = ConfigService.getInstance();
        Config source = new Config();
        source.getServer().setPort(70707);
        source.getDownloader().setPath("roundtrip-dir");
        service.loadFromJson(appMapper().writeValueAsString(source));
        service.save();

        File written = new File("config.json");
        assertTrue(written.exists());

        service.loadFromFile("config.json");
        Config reloaded = service.getConfig();
        assertEquals(70707, reloaded.getServer().getPort());
        assertEquals("roundtrip-dir", reloaded.getDownloader().getPath());
    }

    @Test
    public void saveIsAtomicNoLeftoverTempFiles() {
        ConfigService service = ConfigService.getInstance();
        File dir = new File("config.json").getAbsoluteFile().getParentFile();
        File[] existing = dir.listFiles((d, name) ->
                name.startsWith("config") && name.endsWith(".tmp"));
        if (existing != null) {
            for (File f : existing) {
                f.delete();
            }
        }

        service.save();

        File[] tempFiles = dir.listFiles((d, name) ->
                name.startsWith("config") && name.endsWith(".tmp"));
        assertNotNull(tempFiles);
        assertEquals(0, tempFiles.length);
    }

    @Test
    public void loadFromJsonPreservesComplexConfig() throws Exception {
        Config source = new Config();
        source.getServer().setPort(8080);
        source.getServer().setBind("0.0.0.0");
        source.getServer().getAuthentication().setEnabled(true);
        source.getServer().getAuthentication().setToken("secret-token");
        source.getServer().getCors().setAllowAllOrigins(false);
        source.getServer().getCors().getAllowedOrigins().add("https://app.example.com");
        source.getServer().getCors().getAllowedOrigins().add("https://admin.example.com");
        source.getSecurity().getEndpoints().put("/print", rule(true, "pass1"));
        source.getSecurity().getEndpoints().put("/serial", rule(false, null));
        source.getPrinter().setEnabled(true);
        source.getPrinter().setAutoAddUnknownType(true);
        source.getPrinter().getMappings().add(
                new Config.PrinterMapping("label", "Printer1", true, false, 300));
        source.getPrinter().getMappings().add(
                new Config.PrinterMapping("receipt", "Printer2", false, true, 0));
        source.getSerial().setEnabled(false);
        source.getSerial().getMappings().add(
                new Config.SerialMapping("scale", "COM1", 9600, 8, 1, 0, true, "UTF-8"));

        String json = appMapper().writeValueAsString(source);
        ConfigService service = ConfigService.getInstance();
        service.loadFromJson(json);
        Config loaded = service.getConfig();

        assertEquals(8080, loaded.getServer().getPort());
        assertEquals("0.0.0.0", loaded.getServer().getBind());
        assertTrue(loaded.getServer().getAuthentication().isEnabled());
        assertEquals("secret-token", loaded.getServer().getAuthentication().getToken());
        assertFalse(loaded.getServer().getCors().isAllowAllOrigins());
        assertEquals(2, loaded.getServer().getCors().getAllowedOrigins().size());
        assertEquals("https://app.example.com", loaded.getServer().getCors().getAllowedOrigins().get(0));
        assertEquals("https://admin.example.com", loaded.getServer().getCors().getAllowedOrigins().get(1));

        assertEquals(2, loaded.getSecurity().getEndpoints().size());
        Config.EndpointRule printRule = loaded.getSecurity().getEndpoints().get("/print");
        assertTrue(printRule.isEnabled());
        assertEquals("pass1", printRule.getPassword());
        Config.EndpointRule serialRule = loaded.getSecurity().getEndpoints().get("/serial");
        assertFalse(serialRule.isEnabled());
        assertNull(serialRule.getPassword());

        assertTrue(loaded.getPrinter().isEnabled());
        assertTrue(loaded.getPrinter().isAutoAddUnknownType());
        assertEquals(2, loaded.getPrinter().getMappings().size());
        Config.PrinterMapping m0 = loaded.getPrinter().getMappings().get(0);
        assertEquals("label", m0.getType());
        assertEquals("Printer1", m0.getName());
        assertTrue(m0.isAutoRotate());
        assertFalse(m0.isResetImageableArea());
        assertEquals(300, m0.getForceDPI());
        Config.PrinterMapping m1 = loaded.getPrinter().getMappings().get(1);
        assertEquals("receipt", m1.getType());
        assertEquals("Printer2", m1.getName());
        assertFalse(m1.isAutoRotate());
        assertTrue(m1.isResetImageableArea());
        assertEquals(0, m1.getForceDPI());

        assertFalse(loaded.getSerial().isEnabled());
        assertEquals(1, loaded.getSerial().getMappings().size());
        Config.SerialMapping sm = loaded.getSerial().getMappings().get(0);
        assertEquals("scale", sm.getType());
        assertEquals("COM1", sm.getName());
        assertEquals(Integer.valueOf(9600), sm.getBaudRate());
        assertEquals(Integer.valueOf(8), sm.getNumDataBits());
        assertEquals(Integer.valueOf(1), sm.getNumStopBits());
        assertTrue(sm.getReadMultipleBytes());
        assertEquals("UTF-8", sm.getReadCharset());
    }

    @Test
    public void loadFromJsonEmptyProducesDefaults() throws Exception {
        ConfigService service = ConfigService.getInstance();
        service.loadFromJson("{}");
        Config loaded = service.getConfig();

        assertEquals("127.0.0.1", loaded.getServer().getAddress());
        assertEquals(12212, loaded.getServer().getPort());
        assertFalse(loaded.getServer().getAuthentication().isEnabled());
        assertTrue(loaded.getServer().getCors().isAllowAllOrigins());
        assertTrue(loaded.getPrinter().isEnabled());
        assertFalse(loaded.getPrinter().isAutoAddUnknownType());
        assertTrue(loaded.getGui().getNotification().isEnabled());
        assertEquals(0, loaded.getPrinter().getMappings().size());
        assertEquals(0, loaded.getSecurity().getEndpoints().size());
        assertEquals(0, loaded.getSerial().getMappings().size());
        assertEquals(new Config(), loaded);
    }

    @Test
    public void loadFromJsonNullThrows() {
        ConfigService service = ConfigService.getInstance();
        assertThrows(Exception.class, () -> service.loadFromJson(null));
    }

    @Test
    public void getConfigReturnsStableReferenceBeforeAndAfterLoad() throws Exception {
        ConfigService service = ConfigService.getInstance();
        Config before = service.getConfig();
        assertSame(before, service.getConfig());

        service.loadFromJson("{}");
        Config after = service.getConfig();
        assertSame(after, service.getConfig());
        assertNotSame(before, after);
    }

    @Test
    public void addPrintTypeToListAddsMappingAndTriggersSave() throws Exception {
        ConfigService service = ConfigService.getInstance();
        Config config = service.getConfig();
        config.getPrinter().getMappings().removeIf(m -> "SAVETEST".equals(m.getType()));
        service.save();

        File configFile = new File("config.json");
        assertTrue(configFile.exists());

        String contentBefore = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
        Config parsedBefore = appMapper().readValue(contentBefore, Config.class);
        long countBefore = parsedBefore.getPrinter().getMappings().stream()
                .filter(m -> "SAVETEST".equals(m.getType())).count();
        assertEquals(0, countBefore);

        service.addPrintTypeToList("SAVETEST");

        String contentAfter = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
        assertNotEquals(contentBefore, contentAfter);
        Config parsedAfter = appMapper().readValue(contentAfter, Config.class);
        long countAfter = parsedAfter.getPrinter().getMappings().stream()
                .filter(m -> "SAVETEST".equals(m.getType())).count();
        assertEquals(1, countAfter);

        Config.PrinterMapping saved = parsedAfter.getPrinter().getMappings().stream()
                .filter(m -> "SAVETEST".equals(m.getType()))
                .findFirst().orElse(null);
        assertNotNull(saved);
        assertEquals("", saved.getName());
        assertFalse(saved.isAutoRotate());
        assertTrue(saved.isResetImageableArea());
        assertEquals(0, saved.getForceDPI());

        config.getPrinter().getMappings().removeIf(m -> "SAVETEST".equals(m.getType()));
        service.save();
    }
}

package io.github.augustinlr17.localhardwarebridge.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Tests for {@link ConfigService} focusing on {@code loadFromJson}, {@code save},
 * {@code addPrintTypeToList}, {@code getConfig}, and singleton behavior.
 *
 * <p>Because {@link ConfigService} is a singleton shared across all tests, each test
 * snapshots the current config in {@link #setUp()} and restores it in {@link #tearDown()}.
 * Tests that invoke {@code save()} create a {@code config.json} file in the CWD; the
 * teardown deletes it to keep the workspace clean.
 */
public class ConfigServiceLoadFromJsonTest {

    private static final String CONFIG_FILE = "config.json";

    private ConfigService service;
    private Config originalConfig;
    private boolean configExistedBefore;

    private static ObjectMapper appMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Before
    public void setUp() {
        service = ConfigService.getInstance();
        // Snapshot the current singleton state so tests don't leak into each other.
        originalConfig = appMapper().convertValue(service.getConfig(), Config.class);
        configExistedBefore = new File(CONFIG_FILE).exists();
    }

    @After
    public void tearDown() throws Exception {
        // Restore the singleton config to whatever it was before the test ran.
        service.loadFromJson(appMapper().writeValueAsString(originalConfig));
        // Remove the config.json file if this test created it.
        File file = new File(CONFIG_FILE);
        if (!configExistedBefore && file.exists()) {
            file.delete();
        }
    }

    // ------------------------------------------------------------------
    // 1. loadFromJson(String json)
    // ------------------------------------------------------------------

    @Test
    public void loadFromJsonValidConfigWithAllSections() throws Exception {
        Config source = new Config();
        source.getServer().setPort(8181);
        source.getServer().setBind("0.0.0.0");
        source.getServer().getAuthentication().setEnabled(true);
        source.getServer().getAuthentication().setToken("tok-123");
        source.getServer().getCors().setAllowAllOrigins(false);
        source.getServer().getCors().getAllowedOrigins().add("https://shop.example.com");
        source.getSecurity().getEndpoints().put("/print", new Config.EndpointRule());
        source.getSecurity().getEndpoints().get("/print").setEnabled(true);
        source.getSecurity().getEndpoints().get("/print").setPassword("pw");
        source.getDownloader().setPath("dl");
        source.getDownloader().setTimeout(45.0);
        source.getPrinter().setEnabled(true);
        source.getPrinter().setAutoAddUnknownType(true);
        source.getPrinter().getMappings().add(
                new Config.PrinterMapping("label", "LP-1", true, false, 300));
        source.getSerial().setEnabled(false);
        source.getSerial().getMappings().add(
                new Config.SerialMapping("scale", "COM3", 9600, 8, 1, 0, true, "UTF-8"));
        source.getUpdate().setEnabled(true);
        source.getUpdate().setAutoDownload(true);

        String json = appMapper().writeValueAsString(source);
        service.loadFromJson(json);
        Config loaded = service.getConfig();

        assertEquals(8181, loaded.getServer().getPort());
        assertEquals("0.0.0.0", loaded.getServer().getBind());
        assertTrue(loaded.getServer().getAuthentication().isEnabled());
        assertEquals("tok-123", loaded.getServer().getAuthentication().getToken());
        assertFalse(loaded.getServer().getCors().isAllowAllOrigins());
        assertEquals(1, loaded.getServer().getCors().getAllowedOrigins().size());
        assertEquals("https://shop.example.com", loaded.getServer().getCors().getAllowedOrigins().get(0));
        assertEquals(1, loaded.getSecurity().getEndpoints().size());
        assertTrue(loaded.getSecurity().getEndpoints().get("/print").isEnabled());
        assertEquals("pw", loaded.getSecurity().getEndpoints().get("/print").getPassword());
        assertEquals("dl", loaded.getDownloader().getPath());
        assertEquals(45.0, loaded.getDownloader().getTimeout(), 0.0001);
        assertTrue(loaded.getPrinter().isEnabled());
        assertTrue(loaded.getPrinter().isAutoAddUnknownType());
        assertEquals(1, loaded.getPrinter().getMappings().size());
        assertEquals("label", loaded.getPrinter().getMappings().get(0).getType());
        assertEquals("LP-1", loaded.getPrinter().getMappings().get(0).getName());
        assertTrue(loaded.getPrinter().getMappings().get(0).isAutoRotate());
        assertFalse(loaded.getPrinter().getMappings().get(0).isResetImageableArea());
        assertEquals(300, loaded.getPrinter().getMappings().get(0).getForceDPI());
        assertFalse(loaded.getSerial().isEnabled());
        assertEquals(1, loaded.getSerial().getMappings().size());
        assertEquals("scale", loaded.getSerial().getMappings().get(0).getType());
        assertEquals("COM3", loaded.getSerial().getMappings().get(0).getName());
        assertTrue(loaded.getUpdate().isEnabled());
        assertTrue(loaded.getUpdate().isAutoDownload());
    }

    @Test
    public void loadFromJsonToleratesUnknownProperties() throws Exception {
        // FAIL_ON_UNKNOWN_PROPERTIES is disabled, so unknown keys are silently ignored.
        String json = "{"
                + "\"server\":{\"port\":4242},"
                + "\"unknownTopLevel\":{\"a\":1,\"b\":[2,3]},"
                + "\"printer\":{\"enabled\":false,\"unknownNested\":\"x\"}"
                + "}";
        service.loadFromJson(json);
        Config loaded = service.getConfig();

        assertEquals(4242, loaded.getServer().getPort());
        assertFalse(loaded.getPrinter().isEnabled());
        // Defaults for unmentioned sections are preserved.
        assertEquals("127.0.0.1", loaded.getServer().getAddress());
        assertTrue(loaded.getGui().getNotification().isEnabled());
    }

    @Test
    public void loadFromJsonMinimalEmptyObjectProducesDefaults() throws Exception {
        service.loadFromJson("{}");
        Config loaded = service.getConfig();

        assertNotNull(loaded);
        assertEquals("127.0.0.1", loaded.getServer().getAddress());
        assertEquals("127.0.0.1", loaded.getServer().getBind());
        assertEquals(57212, loaded.getServer().getPort());
        assertFalse(loaded.getServer().getAuthentication().isEnabled());
        assertTrue(loaded.getServer().getCors().isAllowAllOrigins());
        assertTrue(loaded.getPrinter().isEnabled());
        assertTrue(loaded.getSerial().isEnabled());
        assertTrue(loaded.getUpdate().isEnabled());
        assertEquals(0, loaded.getPrinter().getMappings().size());
        assertEquals(0, loaded.getSerial().getMappings().size());
        assertEquals(0, loaded.getSecurity().getEndpoints().size());
        assertEquals(new Config(), loaded);
    }

    @Test
    public void loadFromJsonChangesServerPort() throws Exception {
        Config before = service.getConfig();
        int originalPort = before.getServer().getPort();

        String json = "{\"server\":{\"port\":65500}}";
        service.loadFromJson(json);

        Config loaded = service.getConfig();
        assertNotEquals(originalPort, loaded.getServer().getPort());
        assertEquals(65500, loaded.getServer().getPort());
    }

    @Test
    public void loadFromJsonChangesPrinterEnabledState() throws Exception {
        // Ensure a known starting state.
        service.loadFromJson("{\"printer\":{\"enabled\":true}}");
        assertTrue(service.getConfig().getPrinter().isEnabled());

        service.loadFromJson("{\"printer\":{\"enabled\":false}}");
        assertFalse(service.getConfig().getPrinter().isEnabled());

        service.loadFromJson("{\"printer\":{\"enabled\":true}}");
        assertTrue(service.getConfig().getPrinter().isEnabled());
    }

    @Test
    public void loadFromJsonReplacesPreviousConfig() throws Exception {
        service.loadFromJson("{\"server\":{\"port\":11111}}");
        assertEquals(11111, service.getConfig().getServer().getPort());

        service.loadFromJson("{\"server\":{\"port\":22222}}");
        assertEquals(22222, service.getConfig().getServer().getPort());
    }

    @Test
    public void loadFromJsonInvalidJsonThrows() {
        assertThrows(Exception.class, () -> service.loadFromJson("not-json"));
    }

    // ------------------------------------------------------------------
    // 2. save()
    // ------------------------------------------------------------------

    @Test
    public void saveCreatesConfigFile() throws Exception {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            file.delete();
        }

        Config source = new Config();
        source.getServer().setPort(40404);
        service.loadFromJson(appMapper().writeValueAsString(source));
        service.save();

        assertTrue("save() must create config.json", file.exists());

        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Config reparsed = appMapper().readValue(content, Config.class);
        assertEquals(40404, reparsed.getServer().getPort());
    }

    @Test
    public void saveIsIdempotent() throws Exception {
        Config source = new Config();
        source.getServer().setPort(30303);
        service.loadFromJson(appMapper().writeValueAsString(source));

        service.save();
        File file = new File(CONFIG_FILE);
        assertTrue(file.exists());
        String first = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        service.save();
        assertTrue(file.exists());
        String second = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        // Content should be identical after a repeated save with no state change.
        assertEquals(first, second);

        Config reparsed = appMapper().readValue(second, Config.class);
        assertEquals(30303, reparsed.getServer().getPort());
    }

    @Test
    public void saveAfterLoadFromJsonPersistsNewConfig() throws Exception {
        Config source = new Config();
        source.getServer().setPort(50505);
        source.getPrinter().setEnabled(false);
        source.getDownloader().setPath("persisted-dir");

        service.loadFromJson(appMapper().writeValueAsString(source));
        service.save();

        File file = new File(CONFIG_FILE);
        assertTrue(file.exists());

        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Config reparsed = appMapper().readValue(content, Config.class);
        assertEquals(50505, reparsed.getServer().getPort());
        assertFalse(reparsed.getPrinter().isEnabled());
        assertEquals("persisted-dir", reparsed.getDownloader().getPath());
    }

    // ------------------------------------------------------------------
    // 3. addPrintTypeToList(String type)
    // ------------------------------------------------------------------

    @Test
    public void addPrintTypeToListCreatesMapping() {
        Config config = service.getConfig();
        config.getPrinter().getMappings().removeIf(m -> "LFJ_NEW".equals(m.getType()));

        int before = config.getPrinter().getMappings().size();
        service.addPrintTypeToList("LFJ_NEW");

        assertEquals(before + 1, config.getPrinter().getMappings().size());
        Config.PrinterMapping added = config.getPrinter().getMappings().stream()
                .filter(m -> "LFJ_NEW".equals(m.getType()))
                .findFirst().orElse(null);
        assertNotNull(added);
        assertEquals("LFJ_NEW", added.getType());
        assertEquals("", added.getName());
        assertFalse(added.isAutoRotate());
        assertTrue(added.isResetImageableArea());
        assertEquals(0, added.getForceDPI());

        // Clean up the added mapping so it isn't persisted to config.json.
        config.getPrinter().getMappings().removeIf(m -> "LFJ_NEW".equals(m.getType()));
    }

    @Test
    public void addPrintTypeToListDoesNotDeduplicate() {
        // ConfigService.addPrintTypeToList always appends; deduplication is the
        // caller's responsibility. We assert the observable behavior: a second add
        // appends another entry rather than silently no-op'ing.
        Config config = service.getConfig();
        config.getPrinter().getMappings().removeIf(m -> "LFJ_DUP".equals(m.getType()));

        service.addPrintTypeToList("LFJ_DUP");
        int countAfterFirst = (int) config.getPrinter().getMappings().stream()
                .filter(m -> "LFJ_DUP".equals(m.getType())).count();
        assertEquals(1, countAfterFirst);

        service.addPrintTypeToList("LFJ_DUP");
        int countAfterSecond = (int) config.getPrinter().getMappings().stream()
                .filter(m -> "LFJ_DUP".equals(m.getType())).count();
        assertEquals(2, countAfterSecond);

        config.getPrinter().getMappings().removeIf(m -> "LFJ_DUP".equals(m.getType()));
    }

    @Test
    public void addPrintTypeToListWithEmptyStringIsHandledGracefully() {
        Config config = service.getConfig();
        config.getPrinter().getMappings().removeIf(m -> "".equals(m.getType()));

        int before = config.getPrinter().getMappings().size();
        // Should not throw.
        service.addPrintTypeToList("");

        assertEquals(before + 1, config.getPrinter().getMappings().size());
        Config.PrinterMapping added = config.getPrinter().getMappings().stream()
                .filter(m -> "".equals(m.getType()))
                .findFirst().orElse(null);
        assertNotNull(added);
        assertEquals("", added.getType());

        config.getPrinter().getMappings().removeIf(m -> "".equals(m.getType()));
    }

    @Test
    public void addPrintTypeToListWithNullIsHandledGracefully() {
        Config config = service.getConfig();
        int before = config.getPrinter().getMappings().size();

        // Should not throw — a mapping with a null type is added.
        service.addPrintTypeToList(null);

        assertEquals(before + 1, config.getPrinter().getMappings().size());
        Config.PrinterMapping added = config.getPrinter().getMappings().stream()
                .filter(m -> m.getType() == null)
                .findFirst().orElse(null);
        assertNotNull(added);
        assertNull(added.getType());

        // Clean up the null-typed mapping.
        config.getPrinter().getMappings().removeIf(m -> m.getType() == null);
    }

    // ------------------------------------------------------------------
    // 4. getConfig()
    // ------------------------------------------------------------------

    @Test
    public void getConfigReturnsNonNull() {
        Config config = service.getConfig();
        assertNotNull(config);
        assertNotNull(config.getServer());
        assertNotNull(config.getPrinter());
        assertNotNull(config.getSerial());
    }

    @Test
    public void getConfigHasExpectedDefaultsWhenNoFile() throws Exception {
        // Load an empty object to simulate "no config.json" defaults.
        service.loadFromJson("{}");
        Config config = service.getConfig();

        assertNotNull(config.getServer());
        assertEquals("127.0.0.1", config.getServer().getAddress());
        assertEquals("127.0.0.1", config.getServer().getBind());
        assertEquals(57212, config.getServer().getPort());
        assertFalse(config.getServer().getAuthentication().isEnabled());
        assertFalse(config.getServer().getTls().isEnabled());
        assertTrue(config.getServer().getCors().isAllowAllOrigins());

        assertNotNull(config.getPrinter());
        assertTrue(config.getPrinter().isEnabled());
        assertFalse(config.getPrinter().isAutoAddUnknownType());
        assertFalse(config.getPrinter().isFallbackToDefault());
        assertTrue(config.getPrinter().getMappings().isEmpty());

        assertNotNull(config.getSerial());
        assertTrue(config.getSerial().isEnabled());
        assertTrue(config.getSerial().getMappings().isEmpty());

        assertNotNull(config.getDownloader());
        assertEquals("downloads", config.getDownloader().getPath());
        assertEquals(30.0, config.getDownloader().getTimeout(), 0.0001);

        assertNotNull(config.getUpdate());
        assertTrue(config.getUpdate().isEnabled());
        assertEquals("AugustinLR17/local-hardware-bridge", config.getUpdate().getRepository());

        assertNotNull(config.getGui());
        assertTrue(config.getGui().getNotification().isEnabled());
    }

    // ------------------------------------------------------------------
    // 5. Singleton / constructor behavior
    // ------------------------------------------------------------------

    @Test
    public void getInstanceReturnsSameInstance() {
        ConfigService a = ConfigService.getInstance();
        ConfigService b = ConfigService.getInstance();
        assertSame(a, b);
    }

    @Test
    public void configIsLoadedOnFirstAccess() {
        // The singleton is already initialized (it's a static field), so just
        // verify that the first accessible config is non-null and well-formed.
        Config config = ConfigService.getInstance().getConfig();
        assertNotNull(config);
        assertNotNull(config.getServer());
        // The constructor either loads config.json or falls back to a fresh Config
        // and saves it — either way getConfig() must return a usable object.
        assertTrue(config.getServer().getPort() > 0);
    }

    @Test
    public void getConfigReturnsConsistentReferenceUntilReload() throws Exception {
        Config first = service.getConfig();
        assertSame(first, service.getConfig());

        service.loadFromJson("{\"server\":{\"port\":7777}}");
        Config reloaded = service.getConfig();
        assertNotSame(first, reloaded);
        assertEquals(7777, reloaded.getServer().getPort());
        assertSame(reloaded, service.getConfig());
    }
}
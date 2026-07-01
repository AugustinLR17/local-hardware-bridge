package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Integration test that validates the Intune enterprise config template
 * ({@code packaging/intune/config-template.json}) can actually be loaded by
 * the application's own {@link Config} class via Jackson.
 *
 * <p>This proves the template is structurally compatible with the Config DTO —
 * no missing required fields, no unknown properties that would break, and the
 * enterprise values (auth token, serial disabled, printer enabled, update
 * disabled) are correctly mapped.
 *
 * <p>The test reads the template from the project root (Gradle's working
 * directory during test execution).
 */
public class ConfigIntuneTemplateTest {

    private static final String TEMPLATE_PATH = "packaging/intune/config-template.json";

    private File findTemplateFile() {
        // Gradle runs tests with CWD = project root, so the path should resolve directly.
        File direct = new File(TEMPLATE_PATH);
        if (direct.exists()) {
            return direct;
        }
        // Fallback: search upward in case CWD is a subdirectory.
        File parent = new File(System.getProperty("user.dir"));
        while (parent != null && parent.exists()) {
            File candidate = new File(parent, TEMPLATE_PATH);
            if (candidate.exists()) {
                return candidate;
            }
            parent = parent.getParentFile();
        }
        return null;
    }

    @Test
    public void templateFileExists() {
        File template = findTemplateFile();
        assertNotNull(
            "Intune config template not found at " + TEMPLATE_PATH
            + " (CWD: " + System.getProperty("user.dir") + ")",
            template
        );
    }

    @Test
    public void templateIsParseableByJacksonIntoConfig() throws Exception {
        File template = findTemplateFile();
        assumeTemplateExists(template);

        String json = new String(Files.readAllBytes(template.toPath()), StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        // FAIL_ON_UNKNOWN_PROPERTIES is false in the app's ConfigService, but
        // the template should NOT have unknown properties anyway — test with
        // strict mode to catch drift.
        mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        Config config = mapper.readValue(json, Config.class);
        assertNotNull("Config deserialized from template must not be null", config);
    }

    @Test
    public void templateHasEnterpriseAuthSettings() throws Exception {
        File template = findTemplateFile();
        assumeTemplateExists(template);

        String json = new String(Files.readAllBytes(template.toPath()), StandardCharsets.UTF_8);
        Config config = new ObjectMapper().readValue(json, Config.class);

        assertTrue("Auth must be enabled in enterprise template",
            config.getServer().getAuthentication().isEnabled());
        assertEquals("Auth token must be 'lhb002' in enterprise template",
            "lhb002", config.getServer().getAuthentication().getToken());
    }

    @Test
    public void templateHasSerialDisabled() throws Exception {
        File template = findTemplateFile();
        assumeTemplateExists(template);

        String json = new String(Files.readAllBytes(template.toPath()), StandardCharsets.UTF_8);
        Config config = new ObjectMapper().readValue(json, Config.class);

        assertFalse("Serial must be disabled in enterprise template",
            config.getSerial().isEnabled());
        assertTrue("Serial mappings must be empty in enterprise template",
            config.getSerial().getMappings().isEmpty());
    }

    @Test
    public void templateHasPrinterEnabled() throws Exception {
        File template = findTemplateFile();
        assumeTemplateExists(template);

        String json = new String(Files.readAllBytes(template.toPath()), StandardCharsets.UTF_8);
        Config config = new ObjectMapper().readValue(json, Config.class);

        assertTrue("Printer must be enabled in enterprise template",
            config.getPrinter().isEnabled());
    }

    @Test
    public void templateHasUpdateDisabled() throws Exception {
        File template = findTemplateFile();
        assumeTemplateExists(template);

        String json = new String(Files.readAllBytes(template.toPath()), StandardCharsets.UTF_8);
        Config config = new ObjectMapper().readValue(json, Config.class);

        assertFalse("Auto-update must be disabled in enterprise template (managed via Intune)",
            config.getUpdate().isEnabled());
    }

    @Test
    public void templateBindsToLocalhost() throws Exception {
        File template = findTemplateFile();
        assumeTemplateExists(template);

        String json = new String(Files.readAllBytes(template.toPath()), StandardCharsets.UTF_8);
        Config config = new ObjectMapper().readValue(json, Config.class);

        assertEquals("Bind address must be 127.0.0.1 in enterprise template",
            "127.0.0.1", config.getServer().getBind());
        assertEquals("Address must be 127.0.0.1 in enterprise template",
            "127.0.0.1", config.getServer().getAddress());
    }

    @Test
    public void templateRoundTripsThroughJackson() throws Exception {
        File template = findTemplateFile();
        assumeTemplateExists(template);

        String json = new String(Files.readAllBytes(template.toPath()), StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();

        // Deserialize -> reserialize -> re-deserialize must be stable
        Config config1 = mapper.readValue(json, Config.class);
        String json2 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config1);
        Config config2 = mapper.readValue(json2, Config.class);

        assertEquals("Auth token round-trip", "lhb002", config2.getServer().getAuthentication().getToken());
        assertTrue("Auth enabled round-trip", config2.getServer().getAuthentication().isEnabled());
        assertFalse("Serial disabled round-trip", config2.getSerial().isEnabled());
        assertTrue("Printer enabled round-trip", config2.getPrinter().isEnabled());
        assertFalse("Update disabled round-trip", config2.getUpdate().isEnabled());
    }

    @Test
    public void templateHasAllTopLevelSections() throws Exception {
        File template = findTemplateFile();
        assumeTemplateExists(template);

        String json = new String(Files.readAllBytes(template.toPath()), StandardCharsets.UTF_8);
        Config config = new ObjectMapper().readValue(json, Config.class);

        assertNotNull("gui section", config.getGui());
        assertNotNull("server section", config.getServer());
        assertNotNull("security section", config.getSecurity());
        assertNotNull("downloader section", config.getDownloader());
        assertNotNull("printer section", config.getPrinter());
        assertNotNull("serial section", config.getSerial());
        assertNotNull("update section", config.getUpdate());
    }

    @Test
    public void templatePreservesDefaultDownloaderValues() throws Exception {
        File template = findTemplateFile();
        assumeTemplateExists(template);

        String json = new String(Files.readAllBytes(template.toPath()), StandardCharsets.UTF_8);
        Config config = new ObjectMapper().readValue(json, Config.class);

        assertFalse("TLS ignore must be false", config.getDownloader().isIgnoreTLSCertificateError());
        assertFalse("blockPrivateNetworks must be false", config.getDownloader().isBlockPrivateNetworks());
        assertEquals("timeout must be 30", 30.0, config.getDownloader().getTimeout(), 0.001);
    }

    /**
     * Skips the test if the template file is not found (e.g. running outside
     * the project root). Uses JUnit 4 assumeTrue via a manual check — we throw
     * a special exception only if the file is truly missing AND we're in a
     * context where it should exist.
     */
    private void assumeTemplateExists(File template) {
        if (template == null || !template.exists()) {
            // Don't fail — skip silently if the template isn't on disk
            // (e.g. running in an environment without the packaging/ dir)
            org.junit.Assume.assumeTrue(
                "Intune config template not found — skipping (CWD: "
                + System.getProperty("user.dir") + ")",
                false
            );
        }
    }
}
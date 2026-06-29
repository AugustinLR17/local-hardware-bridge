package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the {@link Config.Update} nested class and its
 * integration with the parent {@link Config} DTO.
 * Fully hermetic.
 */
public class ConfigUpdateTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // --- Defaults ---

    @Test
    public void updateDefaultsAreCorrect() {
        Config.Update update = new Config.Update();
        assertTrue(update.isEnabled());
        assertFalse(update.isAutoDownload());
        assertFalse(update.isAutoInstall());
        assertFalse(update.isIncludePrereleases());
        assertEquals(24, update.getCheckIntervalHours());
        assertEquals("AugustinLR17/local-hardware-bridge", update.getRepository());
        assertEquals("stable", update.getChannel());
    }

    // --- Config integration ---

    @Test
    public void configIncludesUpdateSectionByDefault() {
        Config config = new Config();
        assertNotNull(config.getUpdate());
        assertTrue(config.getUpdate().isEnabled());
    }

    @Test
    public void configSerializationIncludesUpdateSection() throws Exception {
        Config config = new Config();
        String json = mapper().writeValueAsString(config);
        assertTrue(json.contains("\"update\""));
        assertTrue(json.contains("\"enabled\":true"));
        assertTrue(json.contains("\"checkIntervalHours\":24"));
    }

    @Test
    public void configDeserializationParsesUpdateSection() throws Exception {
        String json = "{"
                + "\"update\":{"
                + "  \"enabled\":false,"
                + "  \"autoDownload\":true,"
                + "  \"autoInstall\":false,"
                + "  \"includePrereleases\":true,"
                + "  \"checkIntervalHours\":12,"
                + "  \"repository\":\"myorg/myrepo\","
                + "  \"channel\":\"prerelease\""
                + "}"
                + "}";

        Config config = mapper().readValue(json, Config.class);
        assertNotNull(config.getUpdate());
        assertFalse(config.getUpdate().isEnabled());
        assertTrue(config.getUpdate().isAutoDownload());
        assertFalse(config.getUpdate().isAutoInstall());
        assertTrue(config.getUpdate().isIncludePrereleases());
        assertEquals(12, config.getUpdate().getCheckIntervalHours());
        assertEquals("myorg/myrepo", config.getUpdate().getRepository());
        assertEquals("prerelease", config.getUpdate().getChannel());
    }

    @Test
    public void configRoundTripWithUpdateSettings() throws Exception {
        Config config = new Config();
        config.getUpdate().setEnabled(true);
        config.getUpdate().setAutoDownload(true);
        config.getUpdate().setAutoInstall(true);
        config.getUpdate().setIncludePrereleases(true);
        config.getUpdate().setCheckIntervalHours(6);
        config.getUpdate().setRepository("test/repo");

        String json = mapper().writeValueAsString(config);
        Config restored = mapper().readValue(json, Config.class);

        assertTrue(restored.getUpdate().isEnabled());
        assertTrue(restored.getUpdate().isAutoDownload());
        assertTrue(restored.getUpdate().isAutoInstall());
        assertTrue(restored.getUpdate().isIncludePrereleases());
        assertEquals(6, restored.getUpdate().getCheckIntervalHours());
        assertEquals("test/repo", restored.getUpdate().getRepository());
    }

    @Test
    public void emptyJsonProducesDefaultUpdate() throws Exception {
        Config config = mapper().readValue("{}", Config.class);
        assertNotNull(config.getUpdate());
        assertTrue(config.getUpdate().isEnabled());
        assertEquals(24, config.getUpdate().getCheckIntervalHours());
    }

    @Test
    public void partialUpdateJsonKeepsDefaultsForMissing() throws Exception {
        String json = "{\"update\":{\"enabled\":false}}";
        Config config = mapper().readValue(json, Config.class);
        assertFalse(config.getUpdate().isEnabled());
        // These should keep defaults
        assertFalse(config.getUpdate().isAutoDownload());
        assertEquals(24, config.getUpdate().getCheckIntervalHours());
        assertEquals("AugustinLR17/local-hardware-bridge", config.getUpdate().getRepository());
    }

    @Test
    public void unknownFieldsInUpdateAreIgnored() throws Exception {
        String json = "{\"update\":{\"enabled\":true,\"futureField\":\"value\",\"unknown\":123}}";
        Config config = mapper().readValue(json, Config.class);
        assertTrue(config.getUpdate().isEnabled());
    }

    @Test
    public void checkIntervalZeroMeansStartupOnly() {
        Config.Update update = new Config.Update();
        update.setCheckIntervalHours(0);
        assertEquals(0, update.getCheckIntervalHours());
    }

    @Test
    public void customRepositoryIsAccepted() {
        Config.Update update = new Config.Update();
        update.setRepository("myorg/local-hardware-bridge-fork");
        assertEquals("myorg/local-hardware-bridge-fork", update.getRepository());
    }

    @Test
    public void allBooleansCanBeToggled() {
        Config.Update update = new Config.Update();
        update.setEnabled(false);
        update.setAutoDownload(true);
        update.setAutoInstall(true);
        update.setIncludePrereleases(true);

        assertFalse(update.isEnabled());
        assertTrue(update.isAutoDownload());
        assertTrue(update.isAutoInstall());
        assertTrue(update.isIncludePrereleases());
    }

    @Test
    public void channelCanBePrerelease() {
        Config.Update update = new Config.Update();
        update.setChannel("prerelease");
        assertEquals("prerelease", update.getChannel());
    }

    @Test
    public void updateSectionDoesNotBreakExistingConfigFields() throws Exception {
        String json = "{"
                + "\"server\":{\"port\":9999,\"address\":\"0.0.0.0\"},"
                + "\"update\":{\"enabled\":false},"
                + "\"printer\":{\"enabled\":true}"
                + "}";

        Config config = mapper().readValue(json, Config.class);
        assertEquals(9999, config.getServer().getPort());
        assertEquals("0.0.0.0", config.getServer().getAddress());
        assertFalse(config.getUpdate().isEnabled());
        assertTrue(config.getPrinter().isEnabled());
    }

    // --- All fields individually ---

    @Test
    public void enabledFieldRoundTrip() throws Exception {
        Config.Update update = new Config.Update();
        update.setEnabled(false);
        String json = mapper().writeValueAsString(update);
        Config.Update restored = mapper().readValue(json, Config.Update.class);
        assertFalse(restored.isEnabled());
    }

    @Test
    public void autoDownloadFieldRoundTrip() throws Exception {
        Config.Update update = new Config.Update();
        update.setAutoDownload(true);
        String json = mapper().writeValueAsString(update);
        Config.Update restored = mapper().readValue(json, Config.Update.class);
        assertTrue(restored.isAutoDownload());
    }

    @Test
    public void autoInstallFieldRoundTrip() throws Exception {
        Config.Update update = new Config.Update();
        update.setAutoInstall(true);
        String json = mapper().writeValueAsString(update);
        Config.Update restored = mapper().readValue(json, Config.Update.class);
        assertTrue(restored.isAutoInstall());
    }

    @Test
    public void includePrereleasesFieldRoundTrip() throws Exception {
        Config.Update update = new Config.Update();
        update.setIncludePrereleases(true);
        String json = mapper().writeValueAsString(update);
        Config.Update restored = mapper().readValue(json, Config.Update.class);
        assertTrue(restored.isIncludePrereleases());
    }

    @Test
    public void checkIntervalHoursFieldRoundTrip() throws Exception {
        Config.Update update = new Config.Update();
        update.setCheckIntervalHours(48);
        String json = mapper().writeValueAsString(update);
        Config.Update restored = mapper().readValue(json, Config.Update.class);
        assertEquals(48, restored.getCheckIntervalHours());
    }

    @Test
    public void repositoryFieldRoundTrip() throws Exception {
        Config.Update update = new Config.Update();
        update.setRepository("org/repo");
        String json = mapper().writeValueAsString(update);
        Config.Update restored = mapper().readValue(json, Config.Update.class);
        assertEquals("org/repo", restored.getRepository());
    }

    @Test
    public void channelFieldRoundTrip() throws Exception {
        Config.Update update = new Config.Update();
        update.setChannel("prerelease");
        String json = mapper().writeValueAsString(update);
        Config.Update restored = mapper().readValue(json, Config.Update.class);
        assertEquals("prerelease", restored.getChannel());
    }

    // --- Negative/edge values ---

    @Test
    public void negativeCheckIntervalIsAccepted() {
        Config.Update update = new Config.Update();
        update.setCheckIntervalHours(-1);
        assertEquals(-1, update.getCheckIntervalHours());
    }

    @Test
    public void largeCheckIntervalIsAccepted() {
        Config.Update update = new Config.Update();
        update.setCheckIntervalHours(999999);
        assertEquals(999999, update.getCheckIntervalHours());
    }

    // --- Null/empty values ---

    @Test
    public void nullRepositoryIsAccepted() {
        Config.Update update = new Config.Update();
        update.setRepository(null);
        assertNull(update.getRepository());
    }

    @Test
    public void nullChannelIsAccepted() {
        Config.Update update = new Config.Update();
        update.setChannel(null);
        assertNull(update.getChannel());
    }

    @Test
    public void emptyRepositoryIsAccepted() {
        Config.Update update = new Config.Update();
        update.setRepository("");
        assertEquals("", update.getRepository());
    }

    @Test
    public void emptyChannelIsAccepted() {
        Config.Update update = new Config.Update();
        update.setChannel("");
        assertEquals("", update.getChannel());
    }

    // --- Full config with all sections + update ---

    @Test
    public void fullConfigWithAllSectionsAndUpdateRoundTrip() throws Exception {
        Config config = new Config();
        config.getServer().setPort(8080);
        config.getServer().setBind("0.0.0.0");
        config.getServer().getAuthentication().setEnabled(true);
        config.getServer().getAuthentication().setToken("secret");
        config.getPrinter().setEnabled(true);
        config.getPrinter().getMappings().add(
                new Config.PrinterMapping("RECEIPT", "POS-80", false, true, 0));
        config.getSerial().setEnabled(false);
        config.getUpdate().setEnabled(true);
        config.getUpdate().setAutoDownload(true);
        config.getUpdate().setAutoInstall(true);
        config.getUpdate().setIncludePrereleases(true);
        config.getUpdate().setCheckIntervalHours(12);
        config.getUpdate().setRepository("test/repo");
        config.getUpdate().setChannel("prerelease");

        String json = mapper().writeValueAsString(config);
        Config restored = mapper().readValue(json, Config.class);

        // Verify server
        assertEquals(8080, restored.getServer().getPort());
        assertTrue(restored.getServer().getAuthentication().isEnabled());
        // Verify printer
        assertEquals(1, restored.getPrinter().getMappings().size());
        assertEquals("RECEIPT", restored.getPrinter().getMappings().get(0).getType());
        // Verify update
        assertTrue(restored.getUpdate().isEnabled());
        assertTrue(restored.getUpdate().isAutoDownload());
        assertTrue(restored.getUpdate().isAutoInstall());
        assertTrue(restored.getUpdate().isIncludePrereleases());
        assertEquals(12, restored.getUpdate().getCheckIntervalHours());
        assertEquals("test/repo", restored.getUpdate().getRepository());
        assertEquals("prerelease", restored.getUpdate().getChannel());
    }

    // --- Serialization field names ---

    @Test
    public void serializationUsesCorrectFieldNames() throws Exception {
        Config.Update update = new Config.Update();
        update.setAutoDownload(true);
        update.setAutoInstall(true);
        update.setIncludePrereleases(true);
        update.setCheckIntervalHours(6);

        String json = mapper().writeValueAsString(update);
        assertTrue(json.contains("\"autoDownload\":true"));
        assertTrue(json.contains("\"autoInstall\":true"));
        assertTrue(json.contains("\"includePrereleases\":true"));
        assertTrue(json.contains("\"checkIntervalHours\":6"));
    }

    // --- Deserialization with string values for booleans (tolerance) ---

    @Test
    public void deserializationWithExtraFieldsIsIgnored() throws Exception {
        String json = "{\"update\":{\"enabled\":true,\"futureOption\":\"value\",\"nested\":{\"a\":1}}}";
        Config config = mapper().readValue(json, Config.class);
        assertTrue(config.getUpdate().isEnabled());
    }

    // --- Update section with all other config sections present ---

    @Test
    public void updateCoexistsWithAllOtherSections() throws Exception {
        String json = "{"
            + "\"gui\":{\"notification\":{\"enabled\":true}},"
            + "\"server\":{\"port\":12212},"
            + "\"security\":{\"endpoints\":{\"/printer\":{\"enabled\":true,\"password\":\"\"}}},"
            + "\"downloader\":{\"path\":\"downloads\"},"
            + "\"printer\":{\"enabled\":true},"
            + "\"serial\":{\"enabled\":true},"
            + "\"update\":{\"enabled\":true,\"checkIntervalHours\":24}"
            + "}";

        Config config = mapper().readValue(json, Config.class);
        assertNotNull(config.getGui());
        assertNotNull(config.getServer());
        assertNotNull(config.getSecurity());
        assertNotNull(config.getDownloader());
        assertNotNull(config.getPrinter());
        assertNotNull(config.getSerial());
        assertNotNull(config.getUpdate());
        assertTrue(config.getUpdate().isEnabled());
        assertEquals(12212, config.getServer().getPort());
    }

    // --- Update defaults persist through save/load ---

    @Test
    public void updateDefaultsPersistThroughRoundTrip() throws Exception {
        Config original = new Config();
        // Don't touch update — leave defaults
        String json = mapper().writeValueAsString(original);
        Config restored = mapper().readValue(json, Config.class);

        assertEquals(original.getUpdate().isEnabled(), restored.getUpdate().isEnabled());
        assertEquals(original.getUpdate().isAutoDownload(), restored.getUpdate().isAutoDownload());
        assertEquals(original.getUpdate().isAutoInstall(), restored.getUpdate().isAutoInstall());
        assertEquals(original.getUpdate().isIncludePrereleases(), restored.getUpdate().isIncludePrereleases());
        assertEquals(original.getUpdate().getCheckIntervalHours(), restored.getUpdate().getCheckIntervalHours());
        assertEquals(original.getUpdate().getRepository(), restored.getUpdate().getRepository());
        assertEquals(original.getUpdate().getChannel(), restored.getUpdate().getChannel());
    }

    // --- Verify Lombok @Data generates equals/hashCode ---

    @Test
    public void twoUpdateObjectsWithSameValuesAreEqual() {
        Config.Update u1 = new Config.Update();
        Config.Update u2 = new Config.Update();
        assertEquals(u1, u2);
        assertEquals(u1.hashCode(), u2.hashCode());
    }

    @Test
    public void twoUpdateObjectsWithDifferentValuesAreNotEqual() {
        Config.Update u1 = new Config.Update();
        Config.Update u2 = new Config.Update();
        u2.setEnabled(false);
        assertNotEquals(u1, u2);
    }

    @Test
    public void updateToStringContainsFieldName() {
        Config.Update update = new Config.Update();
        String str = update.toString();
        // Lombok @Data generates toString — should contain field names
        assertTrue(str.contains("enabled") || str.contains("update"));
    }
}

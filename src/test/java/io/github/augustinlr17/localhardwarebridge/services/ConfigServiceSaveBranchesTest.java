package io.github.augustinlr17.localhardwarebridge.services;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Tests for {@link ConfigService#save()} focusing on branches not covered
 * by existing tests: file creation, content validity, and idempotency.
 */
public class ConfigServiceSaveBranchesTest {

    private ConfigService cs;
    private File configFile;
    private String originalJson;

    @Before
    public void setUp() throws Exception {
        cs = ConfigService.getInstance();
        configFile = new File("config.json");
        // Save original config state
        originalJson = cs.getConfig().toJson();
    }

    @After
    public void tearDown() throws Exception {
        // Restore original config
        try {
            cs.loadFromJson(originalJson);
        } catch (Exception e) {
            // If restore fails, just continue
        }
        // Clean up config.json if we created it
        if (configFile.exists()) {
            configFile.delete();
        }
    }

    @Test
    public void saveCreatesConfigJsonFile() throws Exception {
        // Ensure file doesn't exist before save
        if (configFile.exists()) {
            configFile.delete();
        }
        assertFalse("config.json should not exist before save", configFile.exists());

        cs.save();

        assertTrue("config.json should exist after save", configFile.exists());
        assertTrue("config.json should not be empty", configFile.length() > 0);
    }

    @Test
    public void saveProducesValidJsonContent() throws Exception {
        cs.save();

        String content = new String(Files.readAllBytes(configFile.toPath()));
        // Should be valid JSON (starts with { and ends with })
        assertTrue("config.json should start with {", content.trim().startsWith("{"));
        assertTrue("config.json should end with }", content.trim().endsWith("}"));

        // Should contain expected sections
        assertTrue("should contain server section", content.contains("\"server\""));
        assertTrue("should contain printer section", content.contains("\"printer\""));
        assertTrue("should contain serial section", content.contains("\"serial\""));
    }

    @Test
    public void saveIsIdempotent() throws Exception {
        cs.save();
        long size1 = configFile.length();
        String content1 = new String(Files.readAllBytes(configFile.toPath()));

        cs.save();
        long size2 = configFile.length();
        String content2 = new String(Files.readAllBytes(configFile.toPath()));

        assertEquals("File size should be identical after second save", size1, size2);
        assertEquals("File content should be identical after second save", content1, content2);
    }

    @Test
    public void savePersistsChangedConfig() throws Exception {
        // Change the server port
        int originalPort = cs.getConfig().getServer().getPort();
        cs.getConfig().getServer().setPort(9999);

        cs.save();

        // Read back and verify
        String content = new String(Files.readAllBytes(configFile.toPath()));
        assertTrue("Saved config should contain port 9999", content.contains("9999"));

        // Restore
        cs.getConfig().getServer().setPort(originalPort);
    }

    @Test
    public void saveAfterLoadFromJsonPersistsNewConfig() throws Exception {
        String json = "{\"server\":{\"port\":12345,\"bind\":\"0.0.0.0\"}}";
        cs.loadFromJson(json);

        cs.save();

        String content = new String(Files.readAllBytes(configFile.toPath()));
        assertTrue("Saved config should contain port 12345", content.contains("12345"));
        assertTrue("Saved config should contain bind 0.0.0.0", content.contains("0.0.0.0"));
    }

    @Test
    public void saveDoesNotLeaveTempFiles() throws Exception {
        File dir = configFile.getAbsoluteFile().getParentFile();
        File[] tempsBefore = dir.listFiles((d, name) -> name.startsWith("config") && name.endsWith(".tmp"));

        cs.save();

        File[] tempsAfter = dir.listFiles((d, name) -> name.startsWith("config") && name.endsWith(".tmp"));
        // The temp file should be cleaned up (moved or deleted)
        // We can't assert exact counts because other tests might create temps,
        // but there should be no NEW temp files left after save completes
        int afterCount = tempsAfter != null ? tempsAfter.length : 0;
        int beforeCount = tempsBefore != null ? tempsBefore.length : 0;
        assertTrue("No new temp files should remain after save", afterCount <= beforeCount);
    }

    @Test
    public void getConfigReturnsNonNullWithDefaults() {
        Config config = cs.getConfig();
        assertNotNull(config);
        assertNotNull(config.getServer());
        assertNotNull(config.getPrinter());
        assertNotNull(config.getSerial());
        assertNotNull(config.getDownloader());
        assertNotNull(config.getUpdate());
        assertNotNull(config.getGui());
        assertNotNull(config.getSecurity());
    }

    @Test
    public void getInstanceReturnsSameInstance() {
        ConfigService instance1 = ConfigService.getInstance();
        ConfigService instance2 = ConfigService.getInstance();
        assertSame(instance1, instance2);
    }
}
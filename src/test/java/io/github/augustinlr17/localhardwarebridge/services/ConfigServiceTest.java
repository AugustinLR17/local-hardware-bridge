package io.github.augustinlr17.localhardwarebridge.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ConfigService}. ConfigService is a singleton that reads/writes
 * {@code config.json} from {@code user.dir}; rather than clobber the repo config we drive
 * the public load/save API with temp files and known in-memory configs. The on-disk
 * config.json is git-ignored, so save() side effects are harmless in CI.
 */
public class ConfigServiceTest {

    private static ObjectMapper appMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    public void loadFromFileRoundTrip() throws Exception {
        Config source = new Config();
        source.getServer().setPort(40404);
        source.getServer().getAuthentication().setEnabled(true);
        source.getServer().getAuthentication().setToken("file-token");
        source.getDownloader().setBlockPrivateNetworks(true);

        File temp = File.createTempFile("lhb-config-load", ".json");
        temp.deleteOnExit();
        Files.write(temp.toPath(), appMapper().writeValueAsBytes(source));

        ConfigService service = ConfigService.getInstance();
        service.loadFromFile(temp.getAbsolutePath());

        Config loaded = service.getConfig();
        assertEquals(40404, loaded.getServer().getPort());
        assertTrue(loaded.getServer().getAuthentication().isEnabled());
        assertEquals("file-token", loaded.getServer().getAuthentication().getToken());
        assertTrue(loaded.getDownloader().isBlockPrivateNetworks());
    }

    @Test
    public void loadFromJsonRoundTrip() throws Exception {
        Config source = new Config();
        source.getServer().setPort(50505);
        source.getServer().getCors().setAllowAllOrigins(false);
        source.getServer().getCors().getAllowedOrigins().add("https://trusted.example");

        String json = appMapper().writeValueAsString(source);

        ConfigService service = ConfigService.getInstance();
        service.loadFromJson(json);

        Config loaded = service.getConfig();
        assertEquals(50505, loaded.getServer().getPort());
        assertFalse(loaded.getServer().getCors().isAllowAllOrigins());
        assertEquals("https://trusted.example",
                loaded.getServer().getCors().getAllowedOrigins().get(0));
    }

    @Test
    public void saveProducesValidJsonReadableBack() throws Exception {
        ConfigService service = ConfigService.getInstance();

        // Set a distinctive in-memory state, persist it, then read the file back raw.
        Config source = new Config();
        source.getServer().setPort(60606);
        source.getDownloader().setPath("saved-downloads");
        service.loadFromJson(appMapper().writeValueAsString(source));

        service.save();

        // save() writes config.json into the current working directory.
        File written = new File("config.json");
        assertTrue("save() must produce config.json", written.exists());

        String content = new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8);
        // Must be valid, parseable JSON reflecting what we saved.
        Config reparsed = appMapper().readValue(content, Config.class);
        assertEquals(60606, reparsed.getServer().getPort());
        assertEquals("saved-downloads", reparsed.getDownloader().getPath());
    }
}

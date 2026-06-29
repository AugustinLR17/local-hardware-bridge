package io.github.augustinlr17.localhardwarebridge.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

@Log4j2
public class ConfigService {
    @Getter
    private static final ConfigService instance = new ConfigService();

    private static final String CONFIG_FILENAME = "config.json";
    private static final String PRINTER_PLACEHOLDER = "";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Getter
    private final AtomicReference<Config> config = new AtomicReference<>(new Config());

    public Config getConfig() {
        return config.get();
    }

    private ConfigService() {
        try {
            loadFromFile(CONFIG_FILENAME);
        } catch (Exception e) {
            log.warn("Failed loading config, creating new file");
            save();
        }
    }

    public synchronized void loadFromJson(String json) throws JsonProcessingException {
        log.info("Loading config from JSON: {}", json);
        config.set(objectMapper.readValue(json, Config.class));
    }

    public synchronized void loadFromFile(String filename) throws IOException {
        log.info("Loading config from file: {}", filename);
        config.set(objectMapper.readValue(new File(filename), Config.class));
    }

    public synchronized void save() {
        File target = new File(CONFIG_FILENAME);
        try {
            // Write to a temp file in the same directory, then atomically move into place
            // so a crash mid-write cannot corrupt the existing config.
            File dir = target.getAbsoluteFile().getParentFile();
            File temp = File.createTempFile("config", ".tmp", dir);
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp, config);
                Path tempPath = temp.toPath();
                Path targetPath = target.toPath();
                try {
                    Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                // Best-effort cleanup if the move did not consume the temp file.
                try {
                    Files.deleteIfExists(temp.toPath());
                } catch (IOException deleteEx) {
                    log.warn("Could not delete temp config file: {}", temp, deleteEx);
                }
            }
        } catch (Exception e) {
            log.error("Failed to save config file atomically, falling back to direct write", e);
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(target, config);
            } catch (Exception ex) {
                log.error("Failed to save config file", ex);
            }
        }
    }

    public synchronized void addPrintTypeToList(String printType) {
        config.get().getPrinter().getMappings().add(new Config.PrinterMapping(printType, PRINTER_PLACEHOLDER, false, true, 0));
        save();
    }
}

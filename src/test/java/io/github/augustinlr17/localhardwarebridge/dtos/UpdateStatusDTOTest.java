package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link UpdateStatusDTO}.
 * Fully hermetic.
 */
public class UpdateStatusDTOTest {

    @Test
    public void defaultConstructorProducesDefaults() {
        UpdateStatusDTO dto = new UpdateStatusDTO();
        assertFalse(dto.isChecked());
        assertFalse(dto.isUpdateAvailable());
        assertNull(dto.getCurrentVersion());
        assertNull(dto.getLatestVersion());
        assertFalse(dto.isDownloading());
        assertFalse(dto.isPendingRestart());
        assertNull(dto.getError());
    }

    @Test
    public void allArgsConstructorSetsAllFields() {
        UpdateStatusDTO dto = new UpdateStatusDTO(
                true, true, "2.0.0", "2.1.0",
                "Release 2.1.0", "http://example.com/release",
                false, false, true, "/path/to/jar", null);
        assertTrue(dto.isChecked());
        assertTrue(dto.isUpdateAvailable());
        assertEquals("2.0.0", dto.getCurrentVersion());
        assertEquals("2.1.0", dto.getLatestVersion());
        assertEquals("Release 2.1.0", dto.getReleaseName());
        assertEquals("http://example.com/release", dto.getReleaseUrl());
        assertFalse(dto.isPrerelease());
        assertFalse(dto.isDownloading());
        assertTrue(dto.isPendingRestart());
        assertEquals("/path/to/jar", dto.getDownloadedPath());
        assertNull(dto.getError());
    }

    @Test
    public void serializationRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UpdateStatusDTO original = new UpdateStatusDTO(
                true, true, "2.0.0", "2.1.0",
                "Release 2.1.0", "http://example.com",
                true, true, false, "/updates/jar.jar", "network error");

        String json = mapper.writeValueAsString(original);
        UpdateStatusDTO restored = mapper.readValue(json, UpdateStatusDTO.class);

        assertTrue(restored.isChecked());
        assertTrue(restored.isUpdateAvailable());
        assertEquals("2.0.0", restored.getCurrentVersion());
        assertEquals("2.1.0", restored.getLatestVersion());
        assertEquals("Release 2.1.0", restored.getReleaseName());
        assertEquals("http://example.com", restored.getReleaseUrl());
        assertTrue(restored.isPrerelease());
        assertTrue(restored.isDownloading());
        assertFalse(restored.isPendingRestart());
        assertEquals("/updates/jar.jar", restored.getDownloadedPath());
        assertEquals("network error", restored.getError());
    }

    @Test
    public void serializationContainsExpectedFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UpdateStatusDTO dto = new UpdateStatusDTO();
        dto.setChecked(true);
        dto.setUpdateAvailable(true);
        dto.setCurrentVersion("2.0.0");
        dto.setLatestVersion("2.1.0");
        dto.setReleaseUrl("http://example.com");

        String json = mapper.writeValueAsString(dto);
        assertTrue(json.contains("\"checked\":true"));
        assertTrue(json.contains("\"updateAvailable\":true"));
        assertTrue(json.contains("\"currentVersion\":\"2.0.0\""));
        assertTrue(json.contains("\"latestVersion\":\"2.1.0\""));
        assertTrue(json.contains("\"releaseUrl\":\"http://example.com\""));
    }

    @Test
    public void nullFieldsSerializeAsNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UpdateStatusDTO dto = new UpdateStatusDTO();
        dto.setChecked(false);

        String json = mapper.writeValueAsString(dto);
        assertTrue(json.contains("\"currentVersion\":null"));
        assertTrue(json.contains("\"latestVersion\":null"));
        assertTrue(json.contains("\"error\":null"));
    }

    @Test
    public void errorFieldIsPreserved() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UpdateStatusDTO dto = new UpdateStatusDTO();
        dto.setError("GitHub API returned HTTP 403");

        String json = mapper.writeValueAsString(dto);
        UpdateStatusDTO restored = mapper.readValue(json, UpdateStatusDTO.class);
        assertEquals("GitHub API returned HTTP 403", restored.getError());
    }
}

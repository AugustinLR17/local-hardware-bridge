package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.Constants;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link VersionDTO}.
 * Verifies the 3-arg constructor populates legacy fields from {@link Constants}.
 * Fully hermetic.
 */
public class VersionDTOTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void threeArgConstructorSetsLegacyFields() {
        VersionDTO dto = new VersionDTO("App", "com.app", "1.0");

        assertEquals("App", dto.appName);
        assertEquals("com.app", dto.appId);
        assertEquals("1.0", dto.version);
        assertEquals(Constants.LEGACY_APP_NAME, dto.legacyAppName);
        assertEquals(Constants.LEGACY_APP_ID, dto.legacyAppId);
    }

    @Test
    public void allArgsConstructorSetsAllFields() {
        VersionDTO dto = new VersionDTO("App", "com.app", "1.0", "Legacy", "com.legacy");

        assertEquals("App", dto.appName);
        assertEquals("com.app", dto.appId);
        assertEquals("1.0", dto.version);
        assertEquals("Legacy", dto.legacyAppName);
        assertEquals("com.legacy", dto.legacyAppId);
    }

    @Test
    public void noArgsConstructorLeavesNulls() {
        VersionDTO dto = new VersionDTO();

        assertNull(dto.appName);
        assertNull(dto.appId);
        assertNull(dto.version);
        assertNull(dto.legacyAppName);
        assertNull(dto.legacyAppId);
    }

    @Test
    public void serializationIncludesAllFields() throws Exception {
        VersionDTO dto = new VersionDTO(Constants.APP_NAME, Constants.APP_ID, Constants.VERSION);
        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"appName\":\"" + Constants.APP_NAME + "\""));
        assertTrue(json.contains("\"appId\":\"" + Constants.APP_ID + "\""));
        assertTrue(json.contains("\"version\":\"" + Constants.VERSION + "\""));
        assertTrue(json.contains("\"legacyAppName\":\"" + Constants.LEGACY_APP_NAME + "\""));
        assertTrue(json.contains("\"legacyAppId\":\"" + Constants.LEGACY_APP_ID + "\""));
    }

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        VersionDTO original = new VersionDTO("App", "com.app", "2.0", "Legacy", "com.legacy");
        String json = mapper.writeValueAsString(original);
        VersionDTO restored = mapper.readValue(json, VersionDTO.class);

        assertEquals(original.appName, restored.appName);
        assertEquals(original.appId, restored.appId);
        assertEquals(original.version, restored.version);
        assertEquals(original.legacyAppName, restored.legacyAppName);
        assertEquals(original.legacyAppId, restored.legacyAppId);
    }
}

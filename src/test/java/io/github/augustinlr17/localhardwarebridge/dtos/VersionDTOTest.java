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

        assertEquals("App", dto.getAppName());
        assertEquals("com.app", dto.getAppId());
        assertEquals("1.0", dto.getVersion());
        assertEquals(Constants.LEGACY_APP_NAME, dto.getLegacyAppName());
        assertEquals(Constants.LEGACY_APP_ID, dto.getLegacyAppId());
    }

    @Test
    public void allArgsConstructorSetsAllFields() {
        VersionDTO dto = new VersionDTO("App", "com.app", "1.0", "Legacy", "com.legacy");

        assertEquals("App", dto.getAppName());
        assertEquals("com.app", dto.getAppId());
        assertEquals("1.0", dto.getVersion());
        assertEquals("Legacy", dto.getLegacyAppName());
        assertEquals("com.legacy", dto.getLegacyAppId());
    }

    @Test
    public void noArgsConstructorLeavesNulls() {
        VersionDTO dto = new VersionDTO();

        assertNull(dto.getAppName());
        assertNull(dto.getAppId());
        assertNull(dto.getVersion());
        assertNull(dto.getLegacyAppName());
        assertNull(dto.getLegacyAppId());
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

        assertEquals(original.getAppName(), restored.getAppName());
        assertEquals(original.getAppId(), restored.getAppId());
        assertEquals(original.getVersion(), restored.getVersion());
        assertEquals(original.getLegacyAppName(), restored.getLegacyAppName());
        assertEquals(original.getLegacyAppId(), restored.getLegacyAppId());
    }
}

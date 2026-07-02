package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link PrintServiceDTO} state/acceptingJobs fields and serialization.
 */
public class PrintServiceDTOStateTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void twoArgConstructorDefaultsToIdleAndAccepting() {
        PrintServiceDTO dto = new PrintServiceDTO("HP", "Office");
        assertEquals("HP", dto.getName());
        assertEquals("Office", dto.getDescription());
        assertTrue("Should be accepting by default", dto.isAcceptingJobs());
        assertEquals("idle", dto.getState());
    }

    @Test
    public void fourArgConstructorSetsAllFields() {
        PrintServiceDTO dto = new PrintServiceDTO("TS7400", "", false, "stopped");
        assertEquals("TS7400", dto.getName());
        assertFalse(dto.isAcceptingJobs());
        assertEquals("stopped", dto.getState());
    }

    @Test
    public void serializesAllFields() throws Exception {
        PrintServiceDTO dto = new PrintServiceDTO("PRN", "desc", false, "stopped");
        String json = mapper.writeValueAsString(dto);
        assertTrue(json.contains("\"acceptingJobs\":false"));
        assertTrue(json.contains("\"state\":\"stopped\""));
    }

    @Test
    public void deserializesAllFields() throws Exception {
        String json = "{\"name\":\"X\",\"description\":\"\",\"acceptingJobs\":true,\"state\":\"processing\"}";
        PrintServiceDTO dto = mapper.readValue(json, PrintServiceDTO.class);
        assertTrue(dto.isAcceptingJobs());
        assertEquals("processing", dto.getState());
    }

    @Test
    public void toleratesMissingStateFields() throws Exception {
        // Old clients may not send acceptingJobs/state
        String json = "{\"name\":\"X\",\"description\":\"\"}";
        PrintServiceDTO dto = mapper.readValue(json, PrintServiceDTO.class);
        assertEquals("X", dto.getName());
        assertFalse(dto.isAcceptingJobs()); // default boolean false
        assertNull(dto.getState());
    }
}

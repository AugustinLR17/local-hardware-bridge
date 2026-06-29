package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link NotificationDTO}.
 * Fully hermetic.
 */
public class NotificationDTOTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void noArgsConstructorLeavesNulls() {
        NotificationDTO dto = new NotificationDTO();
        assertNull(dto.type);
        assertNull(dto.title);
        assertNull(dto.message);
    }

    @Test
    public void allArgsConstructorSetsAllFields() {
        NotificationDTO dto = new NotificationDTO("INFO", "Title", "Message body");

        assertEquals("INFO", dto.type);
        assertEquals("Title", dto.title);
        assertEquals("Message body", dto.message);
    }

    @Test
    public void serializationProducesExpectedJson() throws Exception {
        NotificationDTO dto = new NotificationDTO("WARNING", "Serial Port", "COM3 unplugged");
        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"type\":\"WARNING\""));
        assertTrue(json.contains("\"title\":\"Serial Port\""));
        assertTrue(json.contains("\"message\":\"COM3 unplugged\""));
    }

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        NotificationDTO original = new NotificationDTO("ERROR", "Print Error", "Printer not found");
        String json = mapper.writeValueAsString(original);
        NotificationDTO restored = mapper.readValue(json, NotificationDTO.class);

        assertEquals(original.type, restored.type);
        assertEquals(original.title, restored.title);
        assertEquals(original.message, restored.message);
    }
}

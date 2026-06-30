package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link PrintServiceDTO}.
 * Covers Lombok-generated accessors plus the JSON contract exposed by the
 * {@code /system/printers.json} REST endpoint. Fully hermetic.
 */
public class PrintServiceDTOTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void noArgsConstructorLeavesNulls() {
        PrintServiceDTO dto = new PrintServiceDTO();
        assertNull(dto.getName());
        assertNull(dto.getDescription());
    }

    @Test
    public void allArgsConstructorSetsAllFields() {
        PrintServiceDTO dto = new PrintServiceDTO("POS-80", "Thermal receipt printer");

        assertEquals("POS-80", dto.getName());
        assertEquals("Thermal receipt printer", dto.getDescription());
    }

    @Test
    public void settersMutateFields() {
        PrintServiceDTO dto = new PrintServiceDTO();
        dto.setName("Zebra ZD420");
        dto.setDescription("Label printer");

        assertEquals("Zebra ZD420", dto.getName());
        assertEquals("Label printer", dto.getDescription());
    }

    @Test
    public void serializationProducesExpectedJson() throws Exception {
        // Mirrors how Server builds the list for /system/printers.json
        PrintServiceDTO dto = new PrintServiceDTO("POS-80", "");
        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"name\":\"POS-80\""));
        assertTrue(json.contains("\"description\":\"\""));
    }

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        PrintServiceDTO original = new PrintServiceDTO("HP LaserJet", "Office printer");
        String json = mapper.writeValueAsString(original);
        PrintServiceDTO restored = mapper.readValue(json, PrintServiceDTO.class);

        assertEquals(original.getName(), restored.getName());
        assertEquals(original.getDescription(), restored.getDescription());
    }

    @Test
    public void deserializationIgnoresMissingFieldsGracefully() throws Exception {
        PrintServiceDTO restored = mapper.readValue("{\"name\":\"OnlyName\"}", PrintServiceDTO.class);

        assertEquals("OnlyName", restored.getName());
        assertNull(restored.getDescription());
    }

    @Test
    public void serializationHandlesNullFields() throws Exception {
        PrintServiceDTO dto = new PrintServiceDTO();
        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"name\":null"));
        assertTrue(json.contains("\"description\":null"));
    }
}

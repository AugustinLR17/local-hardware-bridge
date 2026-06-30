package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SerialPortDTO}.
 * Covers Lombok-generated accessors plus the JSON contract exposed by the
 * {@code /system/serials.json} REST endpoint. Fully hermetic.
 */
public class SerialPortDTOTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void noArgsConstructorLeavesNulls() {
        SerialPortDTO dto = new SerialPortDTO();
        assertNull(dto.getName());
        assertNull(dto.getDescription());
        assertNull(dto.getManufacturer());
    }

    @Test
    public void allArgsConstructorSetsAllFields() {
        SerialPortDTO dto = new SerialPortDTO("/dev/ttyUSB0", "USB Serial", "FTDI");

        assertEquals("/dev/ttyUSB0", dto.getName());
        assertEquals("USB Serial", dto.getDescription());
        assertEquals("FTDI", dto.getManufacturer());
    }

    @Test
    public void settersMutateFields() {
        SerialPortDTO dto = new SerialPortDTO();
        dto.setName("COM3");
        dto.setDescription("Prolific USB-to-Serial");
        dto.setManufacturer("Prolific");

        assertEquals("COM3", dto.getName());
        assertEquals("Prolific USB-to-Serial", dto.getDescription());
        assertEquals("Prolific", dto.getManufacturer());
    }

    @Test
    public void serializationProducesExpectedJson() throws Exception {
        // Mirrors how Server builds the list for /system/serials.json
        SerialPortDTO dto = new SerialPortDTO("/dev/ttyUSB0", "USB Serial", "FTDI");
        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"name\":\"/dev/ttyUSB0\""));
        assertTrue(json.contains("\"description\":\"USB Serial\""));
        assertTrue(json.contains("\"manufacturer\":\"FTDI\""));
    }

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        SerialPortDTO original = new SerialPortDTO("COM1", "Communications Port", "Microsoft");
        String json = mapper.writeValueAsString(original);
        SerialPortDTO restored = mapper.readValue(json, SerialPortDTO.class);

        assertEquals(original.getName(), restored.getName());
        assertEquals(original.getDescription(), restored.getDescription());
        assertEquals(original.getManufacturer(), restored.getManufacturer());
    }

    @Test
    public void deserializationIgnoresMissingFieldsGracefully() throws Exception {
        SerialPortDTO restored = mapper.readValue("{\"name\":\"/dev/ttyS0\"}", SerialPortDTO.class);

        assertEquals("/dev/ttyS0", restored.getName());
        assertNull(restored.getDescription());
        assertNull(restored.getManufacturer());
    }

    @Test
    public void serializationHandlesNullManufacturer() throws Exception {
        // Some OS serial ports report no manufacturer; the field must still serialize.
        SerialPortDTO dto = new SerialPortDTO("/dev/ttyAMA0", "Built-in UART", null);
        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"name\":\"/dev/ttyAMA0\""));
        assertTrue(json.contains("\"manufacturer\":null"));
    }
}

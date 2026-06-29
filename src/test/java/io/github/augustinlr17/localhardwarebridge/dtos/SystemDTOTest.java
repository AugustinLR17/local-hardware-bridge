package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link PrintServiceDTO} and {@link SerialPortDTO}.
 * These are simple data carriers — verify construction and JSON round-trip.
 * Fully hermetic.
 */
public class SystemDTOTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // --- PrintServiceDTO ---

    @Test
    public void printServiceDtoNoArgsLeavesNulls() {
        PrintServiceDTO dto = new PrintServiceDTO();
        assertNull(dto.name);
        assertNull(dto.description);
    }

    @Test
    public void printServiceDtoAllArgsSetsFields() {
        PrintServiceDTO dto = new PrintServiceDTO("HP LaserJet", "Office printer");
        assertEquals("HP LaserJet", dto.name);
        assertEquals("Office printer", dto.description);
    }

    @Test
    public void printServiceDtoRoundTrip() throws Exception {
        PrintServiceDTO original = new PrintServiceDTO("POS-80", "Thermal");
        String json = mapper.writeValueAsString(original);
        PrintServiceDTO restored = mapper.readValue(json, PrintServiceDTO.class);

        assertEquals(original.name, restored.name);
        assertEquals(original.description, restored.description);
    }

    // --- SerialPortDTO ---

    @Test
    public void serialPortDtoNoArgsLeavesNulls() {
        SerialPortDTO dto = new SerialPortDTO();
        assertNull(dto.name);
        assertNull(dto.description);
        assertNull(dto.manufacturer);
    }

    @Test
    public void serialPortDtoAllArgsSetsFields() {
        SerialPortDTO dto = new SerialPortDTO("COM3", "USB Serial", "FTDI");
        assertEquals("COM3", dto.name);
        assertEquals("USB Serial", dto.description);
        assertEquals("FTDI", dto.manufacturer);
    }

    @Test
    public void serialPortDtoRoundTrip() throws Exception {
        SerialPortDTO original = new SerialPortDTO("/dev/ttyUSB0", "USB Adapter", "Prolific");
        String json = mapper.writeValueAsString(original);
        SerialPortDTO restored = mapper.readValue(json, SerialPortDTO.class);

        assertEquals(original.name, restored.name);
        assertEquals(original.description, restored.description);
        assertEquals(original.manufacturer, restored.manufacturer);
    }
}

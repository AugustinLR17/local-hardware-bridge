package io.github.augustinlr17.localhardwarebridge.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link PrintResult} construction and Jackson serialization.
 * Fully hermetic.
 */
public class PrintResultTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void noArgsConstructorProducesNulls() {
        PrintResult result = new PrintResult();
        assertNull(result.getSuccess());
        assertNull(result.getMessage());
        assertNull(result.getId());
        assertNull(result.getPrinterName());
    }

    @Test
    public void allArgsConstructorSetsAllFields() {
        PrintResult result = new PrintResult(true, "Success", "job-1", "POS-80");

        assertTrue(result.getSuccess());
        assertEquals("Success", result.getMessage());
        assertEquals("job-1", result.getId());
        assertEquals("POS-80", result.getPrinterName());
    }

    @Test
    public void serializationProducesExpectedJson() throws Exception {
        PrintResult result = new PrintResult(true, "Success", "job-1", "POS-80");
        String json = mapper.writeValueAsString(result);

        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"message\":\"Success\""));
        assertTrue(json.contains("\"id\":\"job-1\""));
        assertTrue(json.contains("\"printerName\":\"POS-80\""));
    }

    @Test
    public void serializationHandlesNullFields() throws Exception {
        PrintResult result = new PrintResult(false, "No matched printer: RECEIPT", null, null);
        String json = mapper.writeValueAsString(result);

        assertTrue(json.contains("\"success\":false"));
        assertTrue(json.contains("\"message\":\"No matched printer: RECEIPT\""));
        assertTrue(json.contains("\"id\":null"));
        assertTrue(json.contains("\"printerName\":null"));
    }

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        PrintResult original = new PrintResult(true, "Done", "id-42", "HP LaserJet");
        String json = mapper.writeValueAsString(original);
        PrintResult restored = mapper.readValue(json, PrintResult.class);

        assertEquals(original.getSuccess(), restored.getSuccess());
        assertEquals(original.getMessage(), restored.getMessage());
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getPrinterName(), restored.getPrinterName());
    }
}

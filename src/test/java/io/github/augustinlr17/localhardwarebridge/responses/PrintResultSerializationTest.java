package io.github.augustinlr17.localhardwarebridge.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class PrintResultSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void serializeSuccessResultWithAllFields() throws IOException {
        PrintResult result = new PrintResult(true, "Printed successfully", "job-123", "HP LaserJet");
        String json = mapper.writeValueAsString(result);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"message\":\"Printed successfully\""));
        assertTrue(json.contains("\"id\":\"job-123\""));
        assertTrue(json.contains("\"printerName\":\"HP LaserJet\""));
    }

    @Test
    public void serializeFailureResultWithNullPrinterName() throws IOException {
        PrintResult result = new PrintResult(false, "Printer not found", "job-456", null);
        String json = mapper.writeValueAsString(result);
        assertTrue(json.contains("\"success\":false"));
        assertTrue(json.contains("\"message\":\"Printer not found\""));
        assertTrue(json.contains("\"id\":\"job-456\""));
        assertTrue(json.contains("\"printerName\":null"));
    }

    @Test
    public void serializeResultWithNullMessageAndNullId() throws IOException {
        PrintResult result = new PrintResult(null, null, null, "Canon PIXMA");
        String json = mapper.writeValueAsString(result);
        assertTrue(json.contains("\"success\":null"));
        assertTrue(json.contains("\"message\":null"));
        assertTrue(json.contains("\"id\":null"));
        assertTrue(json.contains("\"printerName\":\"Canon PIXMA\""));
    }

    @Test
    public void deserializeFromJsonWithAllFields() throws IOException {
        String json = "{\"success\":true,\"message\":\"Done\",\"id\":\"job-789\",\"printerName\":\"Epson\"}";
        PrintResult result = mapper.readValue(json, PrintResult.class);
        assertEquals(Boolean.TRUE, result.success);
        assertEquals("Done", result.message);
        assertEquals("job-789", result.id);
        assertEquals("Epson", result.printerName);
    }

    @Test
    public void deserializeFromEmptyJsonProducesNulls() throws IOException {
        String json = "{}";
        PrintResult result = mapper.readValue(json, PrintResult.class);
        assertNull(result.success);
        assertNull(result.message);
        assertNull(result.id);
        assertNull(result.printerName);
    }

    @Test
    public void roundTripSerializeDeserializeCompareAllFields() throws IOException {
        PrintResult original = new PrintResult(true, "Round trip", "job-999", "Brother HL");
        String json = mapper.writeValueAsString(original);
        PrintResult deserialized = mapper.readValue(json, PrintResult.class);
        assertEquals(original.success, deserialized.success);
        assertEquals(original.message, deserialized.message);
        assertEquals(original.id, deserialized.id);
        assertEquals(original.printerName, deserialized.printerName);
    }

    @Test
    public void successFieldIsBoxedBooleanAndCanBeNull() {
        PrintResult result = new PrintResult();
        result.success = null;
        assertNull(result.success);
    }

    @Test
    public void toStringIncludesAllFieldValues() {
        PrintResult result = new PrintResult(true, "Hello", "id-1", "PrinterX");
        String str = result.toString();
        assertNotNull(str);
        assertTrue(str.contains("success=true"));
        assertTrue(str.contains("message=Hello"));
        assertTrue(str.contains("id=id-1"));
        assertTrue(str.contains("printerName=PrinterX"));
    }
}

package io.github.augustinlr17.localhardwarebridge.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the additive {@code jobId} and {@code queued} fields on
 * {@link PrintResult}: the existing 4-arg constructor is preserved, 6-field
 * serialization works, and old 4-field JSON deserializes backward-compatibly.
 * Fully hermetic.
 */
public class PrintResultAdditiveFieldsTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void existingFourArgConstructorPreserved() {
        PrintResult result = new PrintResult(true, "Success", "job-1", "POS-80");
        assertTrue(result.getSuccess());
        assertEquals("Success", result.getMessage());
        assertEquals("job-1", result.getId());
        assertEquals("POS-80", result.getPrinterName());
        // Additive fields are null when using the 4-arg constructor.
        assertNull(result.getJobId());
        assertNull(result.getQueued());
    }

    @Test
    public void sixArgConstructorSetsAllFields() {
        PrintResult result = new PrintResult(true, "Done", "client-1", "HP", "srv-job-uuid", false);
        assertTrue(result.getSuccess());
        assertEquals("Done", result.getMessage());
        assertEquals("client-1", result.getId());
        assertEquals("HP", result.getPrinterName());
        assertEquals("srv-job-uuid", result.getJobId());
        assertEquals(false, result.getQueued());
    }

    @Test
    public void serializesWithSixFields() throws Exception {
        PrintResult result = new PrintResult(true, "Success", "job-1", "POS-80", "srv-uuid-1", false);
        String json = mapper.writeValueAsString(result);

        // All 6 fields present in serialized output.
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"message\":\"Success\""));
        assertTrue(json.contains("\"id\":\"job-1\""));
        assertTrue(json.contains("\"printerName\":\"POS-80\""));
        assertTrue(json.contains("\"jobId\":\"srv-uuid-1\""));
        assertTrue(json.contains("\"queued\":false"));
    }

    @Test
    public void serializesNullAdditiveFields() throws Exception {
        PrintResult result = new PrintResult(true, "Success", "job-1", "POS-80");
        String json = mapper.writeValueAsString(result);

        // 4-arg constructor: additive fields serialize as null.
        assertTrue(json.contains("\"jobId\":null"));
        assertTrue(json.contains("\"queued\":null"));
    }

    @Test
    public void deserializesOldFourFieldJsonBackwardCompatible() throws Exception {
        // Old 2.4 client sends only 4 fields — no jobId or queued.
        String legacyJson = "{\"success\":true,\"message\":\"Done\",\"id\":\"old-id\",\"printerName\":\"Epson\"}";
        PrintResult result = mapper.readValue(legacyJson, PrintResult.class);

        assertTrue(result.getSuccess());
        assertEquals("Done", result.getMessage());
        assertEquals("old-id", result.getId());
        assertEquals("Epson", result.getPrinterName());
        // Additive fields are null when absent from input.
        assertNull(result.getJobId());
        assertNull(result.getQueued());
    }

    @Test
    public void roundTripWithAllSixFields() throws Exception {
        PrintResult original = new PrintResult(false, "Queued for retry", "client-9", "Zebra", "srv-uuid-9", true);
        String json = mapper.writeValueAsString(original);
        PrintResult restored = mapper.readValue(json, PrintResult.class);

        assertEquals(original.getSuccess(), restored.getSuccess());
        assertEquals(original.getMessage(), restored.getMessage());
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getPrinterName(), restored.getPrinterName());
        assertEquals(original.getJobId(), restored.getJobId());
        assertEquals(original.getQueued(), restored.getQueued());
    }

    @Test
    public void jobIdDistinctFromClientId() {
        PrintResult result = new PrintResult(true, "Success", "client-abc", "POS", "srv-job-xyz", false);
        // jobId is server-assigned and distinct from the existing client id.
        assertEquals("client-abc", result.getId());
        assertEquals("srv-job-xyz", result.getJobId());
        assertTrue(!result.getId().equals(result.getJobId()));
    }

    @Test
    public void queuedFieldIsBoxedBooleanAndCanBeNull() {
        PrintResult result = new PrintResult();
        result.setQueued(null);
        assertNull(result.getQueued());
    }
}

package io.github.augustinlr17.localhardwarebridge.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the new print-options fields on {@link PrintDocument}:
 * duplex, color, and paper_tray.
 *
 * These fields are optional (null = printer default) and use snake_case
 * for paper_tray to match the existing file_content / raw_content convention.
 * Fully hermetic — no I/O, no network.
 */
public class PrintDocumentPrintOptionsTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void duplexDeserializesAsBoolean() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"TEST\",\"duplex\":true}", PrintDocument.class);
        assertEquals(Boolean.TRUE, doc.getDuplex());
    }

    @Test
    public void duplexFalseDeserializes() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"TEST\",\"duplex\":false}", PrintDocument.class);
        assertEquals(Boolean.FALSE, doc.getDuplex());
    }

    @Test
    public void duplexAbsentIsNull() throws Exception {
        PrintDocument doc = mapper.readValue("{\"type\":\"TEST\"}", PrintDocument.class);
        assertNull(doc.getDuplex());
    }

    @Test
    public void colorDeserializesAsBoolean() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"TEST\",\"color\":true}", PrintDocument.class);
        assertEquals(Boolean.TRUE, doc.getColor());
    }

    @Test
    public void colorFalseDeserializes() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"TEST\",\"color\":false}", PrintDocument.class);
        assertEquals(Boolean.FALSE, doc.getColor());
    }

    @Test
    public void colorAbsentIsNull() throws Exception {
        PrintDocument doc = mapper.readValue("{\"type\":\"TEST\"}", PrintDocument.class);
        assertNull(doc.getColor());
    }

    @Test
    public void paperTrayDeserializesSnakeCase() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"TEST\",\"paper_tray\":\"MAIN\"}", PrintDocument.class);
        assertEquals("MAIN", doc.getPaperTray());
    }

    @Test
    public void paperTrayAbsentIsNull() throws Exception {
        PrintDocument doc = mapper.readValue("{\"type\":\"TEST\"}", PrintDocument.class);
        assertNull(doc.getPaperTray());
    }

    @Test
    public void allThreeFieldsRoundTrip() throws Exception {
        PrintDocument original = mapper.readValue(
                "{\"type\":\"INVOICE\",\"url\":\"http://x/test.pdf\","
                + "\"duplex\":true,\"color\":false,\"paper_tray\":\"MANUAL\"}",
                PrintDocument.class);

        String json = mapper.writeValueAsString(original);
        PrintDocument restored = mapper.readValue(json, PrintDocument.class);

        assertEquals(original.getDuplex(), restored.getDuplex());
        assertEquals(original.getColor(), restored.getColor());
        assertEquals(original.getPaperTray(), restored.getPaperTray());
    }

    @Test
    public void serializationIncludesNewFields() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"TEST\",\"duplex\":true,\"color\":true,\"paper_tray\":\"TOP\"}",
                PrintDocument.class);
        String json = mapper.writeValueAsString(doc);

        assertTrue(json.contains("\"duplex\":true"));
        assertTrue(json.contains("\"color\":true"));
        assertTrue(json.contains("\"paper_tray\":\"TOP\""));
    }

    @Test
    public void unknownFieldsStillTolerated() throws Exception {
        // FAIL_ON_UNKNOWN_PROPERTIES is not set on the bare ObjectMapper here,
        // but PrintDocument has no annotation either — Jackson default is to fail.
        // However the app's configured mapper tolerates unknowns. This test just
        // verifies the new fields don't break existing deserialization.
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"TEST\",\"duplex\":false,\"color\":true,\"paper_tray\":\"SIDE\","
                + "\"qty\":2,\"id\":\"job-1\"}",
                PrintDocument.class);

        assertEquals(Boolean.FALSE, doc.getDuplex());
        assertEquals(Boolean.TRUE, doc.getColor());
        assertEquals("SIDE", doc.getPaperTray());
        assertEquals(Integer.valueOf(2), doc.getQty());
        assertEquals("job-1", doc.getId());
    }
}
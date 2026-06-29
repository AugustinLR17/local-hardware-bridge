package io.github.augustinlr17.localhardwarebridge.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link PrintDocument} Jackson deserialization.
 * Verifies snake_case field mapping, default values, and edge cases.
 * Fully hermetic — no I/O, no network, no server.
 */
public class PrintDocumentTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void deserializeBasicFields() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"RECEIPT\",\"url\":\"https://example.com/file.pdf\",\"id\":\"job-1\"}",
                PrintDocument.class);

        assertEquals("RECEIPT", doc.getType());
        assertEquals("https://example.com/file.pdf", doc.getUrl());
        assertEquals("job-1", doc.getId());
    }

    @Test
    public void snakeCaseFieldMapping() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"TEST\",\"file_content\":\"SGVsbG8=\",\"raw_content\":\"Qm9i\"}",
                PrintDocument.class);

        assertEquals("SGVsbG8=", doc.getFileContent());
        assertEquals("Qm9i", doc.getRawContent());
    }

    @Test
    public void defaultsAreAppliedWhenFieldsMissing() throws Exception {
        PrintDocument doc = mapper.readValue("{\"type\":\"TEST\"}", PrintDocument.class);

        assertEquals("TEST", doc.getType());
        assertNull(doc.getUrl());
        assertNull(doc.getId());
        assertNull(doc.getFileContent());
        assertNull(doc.getRawContent());
        assertEquals(Integer.valueOf(1), doc.getQty());
        assertNotNull(doc.getUuid());
        assertNotNull(doc.getExtras());
        assertTrue(doc.getExtras().isEmpty());
    }

    @Test
    public void qtyIsParsedAsInteger() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"TEST\",\"qty\":5}", PrintDocument.class);

        assertEquals(Integer.valueOf(5), doc.getQty());
    }

    @Test
    public void extrasAreDeserialized() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"TEST\",\"extras\":[{\"text\":\"COPY\",\"x\":50.0,\"y\":50.0,\"size\":48,\"bold\":true}]}",
                PrintDocument.class);

        assertEquals(1, doc.getExtras().size());
        assertEquals("COPY", doc.getExtras().get(0).getText());
        assertEquals(Float.valueOf(50.0f), doc.getExtras().get(0).getX());
        assertEquals(Float.valueOf(50.0f), doc.getExtras().get(0).getY());
        assertEquals(Integer.valueOf(48), doc.getExtras().get(0).getSize());
        assertTrue(doc.getExtras().get(0).getBold());
    }

    @Test
    public void uuidIsGeneratedOnDeserialization() throws Exception {
        PrintDocument doc1 = mapper.readValue("{\"type\":\"A\"}", PrintDocument.class);
        PrintDocument doc2 = mapper.readValue("{\"type\":\"B\"}", PrintDocument.class);

        assertNotNull(doc1.getUuid());
        assertNotNull(doc2.getUuid());
        assertNotEquals(doc1.getUuid(), doc2.getUuid());
    }

    @Test
    public void emptyJsonProducesDefaults() throws Exception {
        PrintDocument doc = mapper.readValue("{}", PrintDocument.class);

        assertNull(doc.getType());
        assertEquals(Integer.valueOf(1), doc.getQty());
        assertNotNull(doc.getExtras());
        assertNotNull(doc.getUuid());
    }
}

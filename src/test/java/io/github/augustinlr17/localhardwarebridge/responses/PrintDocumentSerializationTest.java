package io.github.augustinlr17.localhardwarebridge.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link PrintDocument} serialization and toString().
 * Complements PrintDocumentTest which focuses on deserialization.
 */
public class PrintDocumentSerializationTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void serializationProducesJsonWithAllFields() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"RECEIPT\",\"url\":\"http://x/test.pdf\",\"id\":\"job-1\","
                + "\"qty\":3,\"file_content\":\"SGVsbG8=\",\"raw_content\":\"Qm9i\"}",
                PrintDocument.class);

        String json = mapper.writeValueAsString(doc);

        assertTrue(json.contains("\"type\":\"RECEIPT\""));
        assertTrue(json.contains("\"url\":\"http://x/test.pdf\""));
        assertTrue(json.contains("\"id\":\"job-1\""));
        assertTrue(json.contains("\"qty\":3"));
        assertTrue(json.contains("\"file_content\":\"SGVsbG8=\""));
        assertTrue(json.contains("\"raw_content\":\"Qm9i\""));
        assertTrue(json.contains("\"uuid\""));
        assertTrue(json.contains("\"extras\""));
    }

    @Test
    public void serializationRoundTripPreservesAllFields() throws Exception {
        PrintDocument original = mapper.readValue(
                "{\"type\":\"INVOICE\",\"url\":\"http://x/invoice.pdf\",\"id\":\"rt-1\",\"qty\":2,"
                + "\"extras\":[{\"text\":\"COPY\",\"x\":1.0,\"y\":2.0,\"size\":24,\"bold\":true}]}",
                PrintDocument.class);

        String json = mapper.writeValueAsString(original);
        PrintDocument restored = mapper.readValue(json, PrintDocument.class);

        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getUrl(), restored.getUrl());
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getQty(), restored.getQty());
        assertEquals(original.getFileContent(), restored.getFileContent());
        assertEquals(original.getRawContent(), restored.getRawContent());
        assertEquals(original.getExtras().size(), restored.getExtras().size());
        assertEquals(original.getExtras().get(0).getText(), restored.getExtras().get(0).getText());
        // UUID is generated on deserialization, so it won't match — but it must be non-null
        assertNotNull(restored.getUuid());
    }

    @Test
    public void toStringContainsFieldNames() throws Exception {
        PrintDocument doc = mapper.readValue(
                "{\"type\":\"RECEIPT\",\"url\":\"http://x/test.pdf\",\"id\":\"ts-1\"}",
                PrintDocument.class);

        String str = doc.toString();

        // Lombok @ToString includes all fields
        assertTrue(str.contains("RECEIPT"));
        assertTrue(str.contains("http://x/test.pdf"));
        assertTrue(str.contains("ts-1"));
    }

    @Test
    public void toStringContainsNullFieldsWhenAbsent() throws Exception {
        PrintDocument doc = mapper.readValue("{\"type\":\"X\"}", PrintDocument.class);

        String str = doc.toString();
        assertTrue(str.contains("type=X"));
        assertTrue(str.contains("url=null"));
        assertTrue(str.contains("id=null"));
    }

    @Test
    public void serializationIncludesSnakeCaseFields() throws Exception {
        // Can't set fields directly (no setters, package-private), so use Jackson
        PrintDocument doc = mapper.readValue("{\"file_content\":\"ABC\",\"raw_content\":\"XYZ\"}", PrintDocument.class);

        String json = mapper.writeValueAsString(doc);
        // Jackson must use @JsonProperty snake_case names on output too
        assertTrue(json.contains("\"file_content\":\"ABC\""));
        assertTrue(json.contains("\"raw_content\":\"XYZ\""));
    }

    @Test
    public void serializationOfEmptyDocument() throws Exception {
        PrintDocument doc = mapper.readValue("{}", PrintDocument.class);
        String json = mapper.writeValueAsString(doc);

        // Even an empty doc must serialize (defaults included)
        assertNotNull(json);
        assertTrue(json.contains("\"qty\":1")); // default qty
        assertTrue(json.contains("\"extras\":[]")); // default empty extras
    }
}

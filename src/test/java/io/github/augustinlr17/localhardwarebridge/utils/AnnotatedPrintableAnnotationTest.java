package io.github.augustinlr17.localhardwarebridge.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link AnnotatedPrintable.AnnotatedPrintableAnnotation}.
 * Verifies Jackson deserialization of the annotation fields used in print
 * job `extras`. Fully hermetic.
 */
public class AnnotatedPrintableAnnotationTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void deserializeAllFields() throws Exception {
        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = mapper.readValue(
                "{\"text\":\"COPY\",\"x\":50.0,\"y\":50.0,\"size\":48,\"bold\":true}",
                AnnotatedPrintable.AnnotatedPrintableAnnotation.class);

        assertEquals("COPY", ann.getText());
        assertEquals(Float.valueOf(50.0f), ann.getX());
        assertEquals(Float.valueOf(50.0f), ann.getY());
        assertEquals(Integer.valueOf(48), ann.getSize());
        assertTrue(ann.getBold());
    }

    @Test
    public void defaultsAreNullWhenMissing() throws Exception {
        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = mapper.readValue("{}", AnnotatedPrintable.AnnotatedPrintableAnnotation.class);

        assertNull(ann.getText());
        assertNull(ann.getX());
        assertNull(ann.getY());
        assertNull(ann.getSize());
        assertNull(ann.getBold());
    }

    @Test
    public void boldDefaultsToNullWhenOmitted() throws Exception {
        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = mapper.readValue(
                "{\"text\":\"A\",\"x\":1.0,\"y\":2.0,\"size\":10}",
                AnnotatedPrintable.AnnotatedPrintableAnnotation.class);

        assertNull(ann.getBold());
    }

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        AnnotatedPrintable.AnnotatedPrintableAnnotation original = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        original.setText("WATERMARK");
        original.setX(10.5f);
        original.setY(20.5f);
        original.setSize(36);
        original.setBold(false);

        String json = mapper.writeValueAsString(original);
        AnnotatedPrintable.AnnotatedPrintableAnnotation restored = mapper.readValue(json, AnnotatedPrintable.AnnotatedPrintableAnnotation.class);

        assertEquals(original.getText(), restored.getText());
        assertEquals(original.getX(), restored.getX());
        assertEquals(original.getY(), restored.getY());
        assertEquals(original.getSize(), restored.getSize());
        assertEquals(original.getBold(), restored.getBold());
    }

    @Test
    public void emptyTextIsAllowed() throws Exception {
        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = mapper.readValue(
                "{\"text\":\"\",\"x\":0.0,\"y\":0.0,\"size\":1}",
                AnnotatedPrintable.AnnotatedPrintableAnnotation.class);

        assertEquals("", ann.getText());
    }
}

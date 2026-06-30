package io.github.augustinlr17.localhardwarebridge.websocketservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.responses.PrintDocument;
import org.junit.Before;
import org.junit.Test;

import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Chromaticity;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.MediaTray;
import javax.print.attribute.standard.Sides;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link PrinterWebSocketService} print-attribute building:
 * the private {@code buildPrintAttributes} and {@code mapPaperTray} methods
 * that convert the new PrintDocument fields (duplex, color, paper_tray)
 * into Javax PrintRequestAttributeSet.
 *
 * Tested via reflection — no printing, no server bind, no I/O.
 */
public class PrinterWebSocketServicePrintAttributesTest {

    private PrinterWebSocketService service;
    private Method buildPrintAttributes;
    private Method mapPaperTray;

    @Before
    public void setUp() throws Exception {
        service = new PrinterWebSocketService();
        buildPrintAttributes = PrinterWebSocketService.class
                .getDeclaredMethod("buildPrintAttributes", PrintDocument.class);
        buildPrintAttributes.setAccessible(true);

        mapPaperTray = PrinterWebSocketService.class
                .getDeclaredMethod("mapPaperTray", String.class);
        mapPaperTray.setAccessible(true);
    }

    private PrintDocument doc(String json) throws Exception {
        return new ObjectMapper().readValue(json, PrintDocument.class);
    }

    private PrintRequestAttributeSet buildAttrs(String json) throws Exception {
        return (PrintRequestAttributeSet) buildPrintAttributes.invoke(service, doc(json));
    }

    // --- buildPrintAttributes: empty / null fields ---

    @Test
    public void emptyAttributesWhenNoOptionsSet() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\"}");
        assertNotNull(attrs);
        // No copies, no sides, no chromaticity, no media
        assertFalse(attrs.containsValue(Sides.DUPLEX));
        assertFalse(attrs.containsValue(Sides.ONE_SIDED));
        assertFalse(attrs.containsValue(Chromaticity.COLOR));
        assertFalse(attrs.containsValue(Chromaticity.MONOCHROME));
    }

    // --- duplex / Sides ---

    @Test
    public void duplexTrueProducesDuplexSides() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"duplex\":true}");
        assertTrue(attrs.containsValue(Sides.DUPLEX));
    }

    @Test
    public void duplexFalseProducesOneSided() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"duplex\":false}");
        assertTrue(attrs.containsValue(Sides.ONE_SIDED));
    }

    @Test
    public void duplexNullProducesNoSidesAttribute() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\"}");
        assertFalse(attrs.containsValue(Sides.DUPLEX));
        assertFalse(attrs.containsValue(Sides.ONE_SIDED));
    }

    // --- color / Chromaticity ---

    @Test
    public void colorTrueProducesColorChromaticity() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"color\":true}");
        assertTrue(attrs.containsValue(Chromaticity.COLOR));
    }

    @Test
    public void colorFalseProducesMonochrome() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"color\":false}");
        assertTrue(attrs.containsValue(Chromaticity.MONOCHROME));
    }

    @Test
    public void colorNullProducesNoChromaticityAttribute() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\"}");
        assertFalse(attrs.containsValue(Chromaticity.COLOR));
        assertFalse(attrs.containsValue(Chromaticity.MONOCHROME));
    }

    // --- paper_tray / MediaTray ---

    @Test
    public void paperTrayMainProducesMainMediaTray() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"MAIN\"}");
        assertTrue(attrs.containsValue(MediaTray.MAIN));
    }

    @Test
    public void paperTrayManualProducesManualMediaTray() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"MANUAL\"}");
        assertTrue(attrs.containsValue(MediaTray.MANUAL));
    }

    @Test
    public void paperTrayTopProducesTopMediaTray() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"TOP\"}");
        assertTrue(attrs.containsValue(MediaTray.TOP));
    }

    @Test
    public void paperTrayBottomProducesBottomMediaTray() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"BOTTOM\"}");
        assertTrue(attrs.containsValue(MediaTray.BOTTOM));
    }

    @Test
    public void paperTraySideProducesSideMediaTray() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"SIDE\"}");
        assertTrue(attrs.containsValue(MediaTray.SIDE));
    }

    @Test
    public void paperTrayEnvelopeProducesEnvelopeMediaTray() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"ENVELOPE\"}");
        assertTrue(attrs.containsValue(MediaTray.ENVELOPE));
    }

    @Test
    public void paperTrayLargeCapacityProducesLargeCapacityMediaTray() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"LARGE_CAPACITY\"}");
        assertTrue(attrs.containsValue(MediaTray.LARGE_CAPACITY));
    }

    @Test
    public void paperTrayCaseInsensitive() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"main\"}");
        assertTrue(attrs.containsValue(MediaTray.MAIN));
    }

    @Test
    public void paperTrayUnknownProducesNoMediaTray() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"TRAY_42\"}");
        // Unknown tray name should not add any MediaTray attribute
        assertFalse(attrs.containsValue(MediaTray.MAIN));
        assertFalse(attrs.containsValue(MediaTray.MANUAL));
        assertFalse(attrs.containsValue(MediaTray.TOP));
    }

    @Test
    public void paperTrayNullProducesNoMediaTray() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\"}");
        assertFalse(attrs.containsValue(MediaTray.MAIN));
    }

    // --- combined ---

    @Test
    public void allOptionsCombined() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs(
                "{\"type\":\"TEST\",\"duplex\":true,\"color\":false,\"paper_tray\":\"MANUAL\",\"qty\":3}");

        assertTrue(attrs.containsValue(Sides.DUPLEX));
        assertTrue(attrs.containsValue(Chromaticity.MONOCHROME));
        assertTrue(attrs.containsValue(MediaTray.MANUAL));
        assertTrue(attrs.containsValue(new Copies(3)));
    }

    // --- mapPaperTray direct tests ---

    @Test
    public void mapPaperTrayKnownValues() throws Exception {
        assertEquals(MediaTray.MAIN, mapPaperTray.invoke(service, "MAIN"));
        assertEquals(MediaTray.MANUAL, mapPaperTray.invoke(service, "MANUAL"));
        assertEquals(MediaTray.TOP, mapPaperTray.invoke(service, "TOP"));
        assertEquals(MediaTray.BOTTOM, mapPaperTray.invoke(service, "BOTTOM"));
        assertEquals(MediaTray.SIDE, mapPaperTray.invoke(service, "SIDE"));
        assertEquals(MediaTray.ENVELOPE, mapPaperTray.invoke(service, "ENVELOPE"));
        assertEquals(MediaTray.LARGE_CAPACITY, mapPaperTray.invoke(service, "LARGE_CAPACITY"));
    }

    @Test
    public void mapPaperTrayCaseInsensitive() throws Exception {
        assertEquals(MediaTray.MAIN, mapPaperTray.invoke(service, "main"));
        assertEquals(MediaTray.TOP, mapPaperTray.invoke(service, "Top"));
        assertEquals(MediaTray.MANUAL, mapPaperTray.invoke(service, "manual"));
    }

    @Test
    public void mapPaperTrayUnknownReturnsNull() throws Exception {
        assertNull(mapPaperTray.invoke(service, "UNKNOWN_TRAY"));
        assertNull(mapPaperTray.invoke(service, "tray-42"));
    }

    @Test
    public void mapPaperTrayNullReturnsNull() throws Exception {
        assertNull(mapPaperTray.invoke(service, (Object) null));
    }

    @Test
    public void mapPaperTrayEmptyReturnsNull() throws Exception {
        assertNull(mapPaperTray.invoke(service, ""));
    }
}
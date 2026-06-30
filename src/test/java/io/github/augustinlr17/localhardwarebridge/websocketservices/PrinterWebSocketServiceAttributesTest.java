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
 * Unit tests for the private helpers of {@link PrinterWebSocketService}:
 * {@code buildPrintAttributes}, {@code mapPaperTray}, {@code isRaw},
 * {@code urlFilename}, {@code isImage}, {@code isPDF}, plus the no-op
 * {@code start()} / {@code stop()} and the binary {@code messageToService(byte[])}.
 *
 * <p>All private methods are exercised via reflection. No printing, no
 * server bind, no I/O — fully hermetic.
 */
public class PrinterWebSocketServiceAttributesTest {

    private PrinterWebSocketService service;
    private Method buildPrintAttributes;
    private Method mapPaperTray;
    private Method isRaw;
    private Method urlFilename;
    private Method isImage;
    private Method isPDF;

    private static final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() throws Exception {
        service = new PrinterWebSocketService();

        buildPrintAttributes = PrinterWebSocketService.class
                .getDeclaredMethod("buildPrintAttributes", PrintDocument.class);
        buildPrintAttributes.setAccessible(true);

        mapPaperTray = PrinterWebSocketService.class
                .getDeclaredMethod("mapPaperTray", String.class);
        mapPaperTray.setAccessible(true);

        isRaw = PrinterWebSocketService.class
                .getDeclaredMethod("isRaw", PrintDocument.class);
        isRaw.setAccessible(true);

        urlFilename = PrinterWebSocketService.class
                .getDeclaredMethod("urlFilename", PrintDocument.class);
        urlFilename.setAccessible(true);

        isImage = PrinterWebSocketService.class
                .getDeclaredMethod("isImage", PrintDocument.class);
        isImage.setAccessible(true);

        isPDF = PrinterWebSocketService.class
                .getDeclaredMethod("isPDF", PrintDocument.class);
        isPDF.setAccessible(true);
    }

    /** Build a PrintDocument from JSON so the @JsonProperty snake_case fields map correctly. */
    private PrintDocument doc(String json) throws Exception {
        return mapper.readValue(json, PrintDocument.class);
    }

    private PrintRequestAttributeSet buildAttrs(String json) throws Exception {
        return (PrintRequestAttributeSet) buildPrintAttributes.invoke(service, doc(json));
    }

    /** True if the attribute set contains any attribute of the given class. */
    private boolean containsAttributeClass(PrintRequestAttributeSet attrs, Class<?> clazz) {
        for (javax.print.attribute.Attribute a : attrs.toArray()) {
            if (clazz.isInstance(a)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // buildPrintAttributes
    // =========================================================================

    // --- duplex / Sides ---

    @Test
    public void buildPrintAttributesDuplexTrueProducesDuplexSides() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"duplex\":true}");
        assertTrue(attrs.containsValue(Sides.DUPLEX));
        assertFalse(attrs.containsValue(Sides.ONE_SIDED));
    }

    @Test
    public void buildPrintAttributesDuplexFalseProducesOneSided() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"duplex\":false}");
        assertTrue(attrs.containsValue(Sides.ONE_SIDED));
        assertFalse(attrs.containsValue(Sides.DUPLEX));
    }

    @Test
    public void buildPrintAttributesDuplexNullProducesNoSides() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\"}");
        assertNotNull(attrs);
        assertFalse(attrs.containsValue(Sides.DUPLEX));
        assertFalse(attrs.containsValue(Sides.ONE_SIDED));
    }

    // --- color / Chromaticity ---

    @Test
    public void buildPrintAttributesColorTrueProducesColor() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"color\":true}");
        assertTrue(attrs.containsValue(Chromaticity.COLOR));
        assertFalse(attrs.containsValue(Chromaticity.MONOCHROME));
    }

    @Test
    public void buildPrintAttributesColorFalseProducesMonochrome() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"color\":false}");
        assertTrue(attrs.containsValue(Chromaticity.MONOCHROME));
        assertFalse(attrs.containsValue(Chromaticity.COLOR));
    }

    @Test
    public void buildPrintAttributesColorNullProducesNoChromaticity() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\"}");
        assertNotNull(attrs);
        assertFalse(attrs.containsValue(Chromaticity.COLOR));
        assertFalse(attrs.containsValue(Chromaticity.MONOCHROME));
    }

    // --- paper_tray / MediaTray ---

    @Test
    public void buildPrintAttributesPaperTrayMain() throws Exception {
        assertTrue(buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"MAIN\"}").containsValue(MediaTray.MAIN));
    }

    @Test
    public void buildPrintAttributesPaperTrayManualLowercase() throws Exception {
        assertTrue(buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"manual\"}").containsValue(MediaTray.MANUAL));
    }

    @Test
    public void buildPrintAttributesPaperTrayTop() throws Exception {
        assertTrue(buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"TOP\"}").containsValue(MediaTray.TOP));
    }

    @Test
    public void buildPrintAttributesPaperTrayBottom() throws Exception {
        assertTrue(buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"BOTTOM\"}").containsValue(MediaTray.BOTTOM));
    }

    @Test
    public void buildPrintAttributesPaperTraySide() throws Exception {
        assertTrue(buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"SIDE\"}").containsValue(MediaTray.SIDE));
    }

    @Test
    public void buildPrintAttributesPaperTrayEnvelope() throws Exception {
        assertTrue(buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"ENVELOPE\"}").containsValue(MediaTray.ENVELOPE));
    }

    @Test
    public void buildPrintAttributesPaperTrayLargeCapacity() throws Exception {
        assertTrue(buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"LARGE_CAPACITY\"}").containsValue(MediaTray.LARGE_CAPACITY));
    }

    @Test
    public void buildPrintAttributesPaperTrayUnknownIgnored() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"UNKNOWN\"}");
        assertNotNull(attrs);
        assertFalse(attrs.containsValue(MediaTray.MAIN));
        assertFalse(attrs.containsValue(MediaTray.MANUAL));
        assertFalse(attrs.containsValue(MediaTray.TOP));
        assertFalse(attrs.containsValue(MediaTray.BOTTOM));
        assertFalse(attrs.containsValue(MediaTray.SIDE));
        assertFalse(attrs.containsValue(MediaTray.ENVELOPE));
        assertFalse(attrs.containsValue(MediaTray.LARGE_CAPACITY));
    }

    @Test
    public void buildPrintAttributesPaperTrayNullIgnored() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\"}");
        assertFalse(attrs.containsValue(MediaTray.MAIN));
    }

    @Test
    public void buildPrintAttributesPaperTrayEmptyIgnored() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"paper_tray\":\"\"}");
        assertNotNull(attrs);
        assertFalse(attrs.containsValue(MediaTray.MAIN));
        assertFalse(attrs.containsValue(MediaTray.MANUAL));
    }

    // --- qty / Copies ---

    @Test
    public void buildPrintAttributesQtyPositiveProducesCopies() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"qty\":3}");
        assertTrue(attrs.containsValue(new Copies(3)));
    }

    @Test
    public void buildPrintAttributesQtyZeroProducesNoCopies() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\",\"qty\":0}");
        // qty <= 0 must not add a Copies attribute. Copies requires value >= 1,
        // so we check by class rather than constructing a zero-valued Copies.
        assertFalse(containsAttributeClass(attrs, Copies.class));
    }

    @Test
    public void buildPrintAttributesQtyNullProducesNoCopies() throws Exception {
        // Explicit null qty in JSON overrides the field's default of 1.
        PrintDocument d = doc("{\"type\":\"TEST\",\"qty\":null}");
        assertNull(d.getQty());
        PrintRequestAttributeSet attrs = (PrintRequestAttributeSet) buildPrintAttributes.invoke(service, d);
        assertNotNull(attrs);
        assertFalse(containsAttributeClass(attrs, Copies.class));
    }

    @Test
    public void buildPrintAttributesAllOptionsCombined() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs(
                "{\"type\":\"TEST\",\"duplex\":true,\"color\":false,\"paper_tray\":\"MANUAL\",\"qty\":2}");
        assertTrue(attrs.containsValue(Sides.DUPLEX));
        assertTrue(attrs.containsValue(Chromaticity.MONOCHROME));
        assertTrue(attrs.containsValue(MediaTray.MANUAL));
        assertTrue(attrs.containsValue(new Copies(2)));
    }

    @Test
    public void buildPrintAttributesNeverReturnsNull() throws Exception {
        PrintRequestAttributeSet attrs = buildAttrs("{\"type\":\"TEST\"}");
        assertNotNull(attrs);
    }

    // =========================================================================
    // mapPaperTray
    // =========================================================================

    @Test
    public void mapPaperTrayAllKnownValues() throws Exception {
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
        assertEquals(MediaTray.MANUAL, mapPaperTray.invoke(service, "manual"));
        assertEquals(MediaTray.TOP, mapPaperTray.invoke(service, "Top"));
        assertEquals(MediaTray.BOTTOM, mapPaperTray.invoke(service, "bottom"));
        assertEquals(MediaTray.SIDE, mapPaperTray.invoke(service, "SiDe"));
        assertEquals(MediaTray.ENVELOPE, mapPaperTray.invoke(service, "envelope"));
        assertEquals(MediaTray.LARGE_CAPACITY, mapPaperTray.invoke(service, "large_capacity"));
    }

    @Test
    public void mapPaperTrayTrimsWhitespace() throws Exception {
        assertEquals(MediaTray.MAIN, mapPaperTray.invoke(service, "  MAIN  "));
        assertEquals(MediaTray.TOP, mapPaperTray.invoke(service, "\tTOP\t"));
    }

    @Test
    public void mapPaperTrayUnknownReturnsNull() throws Exception {
        assertNull(mapPaperTray.invoke(service, "UNKNOWN"));
        assertNull(mapPaperTray.invoke(service, "tray-42"));
        assertNull(mapPaperTray.invoke(service, "MAIN_MANUAL"));
    }

    @Test
    public void mapPaperTrayNullReturnsNull() throws Exception {
        assertNull(mapPaperTray.invoke(service, (Object) null));
    }

    @Test
    public void mapPaperTrayEmptyReturnsNull() throws Exception {
        assertNull(mapPaperTray.invoke(service, ""));
        assertNull(mapPaperTray.invoke(service, "   "));
    }

    // =========================================================================
    // isRaw
    // =========================================================================

    @Test
    public void isRawTrueWhenNonEmptyRawContent() throws Exception {
        assertTrue((boolean) isRaw.invoke(service, doc("{\"type\":\"TEST\",\"raw_content\":\"SGVsbG8=\"}")));
    }

    @Test
    public void isRawFalseWhenEmptyRawContent() throws Exception {
        assertFalse((boolean) isRaw.invoke(service, doc("{\"type\":\"TEST\",\"raw_content\":\"\"}")));
    }

    @Test
    public void isRawFalseWhenRawContentNull() throws Exception {
        assertFalse((boolean) isRaw.invoke(service, doc("{\"type\":\"TEST\"}")));
    }

    // =========================================================================
    // urlFilename
    // =========================================================================

    @Test
    public void urlFilenameNormalUrl() throws Exception {
        assertEquals("file.pdf",
                urlFilename.invoke(service, doc("{\"type\":\"TEST\",\"url\":\"http://example.com/file.pdf\"}")));
    }

    @Test
    public void urlFilenameStripsQueryString() throws Exception {
        assertEquals("file.pdf",
                urlFilename.invoke(service,
                        doc("{\"type\":\"TEST\",\"url\":\"http://example.com/file.pdf?token=abc\"}")));
    }

    @Test
    public void urlFilenameStripsFragment() throws Exception {
        // http://host/file.exe#x.pdf -> path is /file.exe, fragment stripped
        assertEquals("file.exe",
                urlFilename.invoke(service,
                        doc("{\"type\":\"TEST\",\"url\":\"http://example.com/file.exe#x.pdf\"}")));
    }

    @Test
    public void urlFilenameNullUrlReturnsEmpty() throws Exception {
        assertEquals("",
                urlFilename.invoke(service, doc("{\"type\":\"TEST\"}")));
    }

    @Test
    public void urlFilenameMalformedUrlStillReturnsFilename() throws Exception {
        // Not a valid URL (no scheme/host) -> MalformedURLException path falls back
        // to FilenameUtils.getName on the raw string.
        String result = (String) urlFilename.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"not-a-url/file.txt\"}"));
        assertEquals("file.txt", result);
    }

    @Test
    public void urlFilenameUrlWithSubdirectory() throws Exception {
        assertEquals("doc.pdf",
                urlFilename.invoke(service,
                        doc("{\"type\":\"TEST\",\"url\":\"http://example.com/path/to/doc.pdf\"}")));
    }

    // =========================================================================
    // isImage
    // =========================================================================

    @Test
    public void isImageTrueForJpg() throws Exception {
        assertTrue((boolean) isImage.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/pic.jpg\"}")));
    }

    @Test
    public void isImageTrueForJpeg() throws Exception {
        assertTrue((boolean) isImage.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/pic.jpeg\"}")));
    }

    @Test
    public void isImageTrueForPng() throws Exception {
        assertTrue((boolean) isImage.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/pic.png\"}")));
    }

    @Test
    public void isImageTrueForGif() throws Exception {
        assertTrue((boolean) isImage.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/pic.gif\"}")));
    }

    @Test
    public void isImageFalseForPdf() throws Exception {
        assertFalse((boolean) isImage.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/doc.pdf\"}")));
    }

    @Test
    public void isImageFalseForUnknownExtension() throws Exception {
        assertFalse((boolean) isImage.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/doc.txt\"}")));
    }

    @Test
    public void isImageFalseForNullUrl() throws Exception {
        assertFalse((boolean) isImage.invoke(service, doc("{\"type\":\"TEST\"}")));
    }

    @Test
    public void isImageTrueWithQueryString() throws Exception {
        assertTrue((boolean) isImage.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/pic.png?w=100\"}")));
    }

    // =========================================================================
    // isPDF
    // =========================================================================

    @Test
    public void isPdfTrueForPdf() throws Exception {
        assertTrue((boolean) isPDF.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/doc.pdf\"}")));
    }

    @Test
    public void isPdfTrueForPdfWithQueryString() throws Exception {
        assertTrue((boolean) isPDF.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/doc.pdf?token=abc\"}")));
    }

    @Test
    public void isPdfFalseForImage() throws Exception {
        assertFalse((boolean) isPDF.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/pic.png\"}")));
    }

    @Test
    public void isPdfFalseForUnknownExtension() throws Exception {
        assertFalse((boolean) isPDF.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/doc.txt\"}")));
    }

    @Test
    public void isPdfFalseForNullUrl() throws Exception {
        assertFalse((boolean) isPDF.invoke(service, doc("{\"type\":\"TEST\"}")));
    }

    @Test
    public void isPdfFalseForFragmentSpoofing() throws Exception {
        // http://host/file.exe#x.pdf -> filename is file.exe, not a PDF
        assertFalse((boolean) isPDF.invoke(service,
                doc("{\"type\":\"TEST\",\"url\":\"http://example.com/file.exe#x.pdf\"}")));
    }

    // =========================================================================
    // start() / stop() — no-op methods, must not throw
    // =========================================================================

    @Test
    public void startDoesNotThrow() {
        service.start();
    }

    @Test
    public void stopDoesNotThrow() {
        service.stop();
    }

    @Test
    public void startStopRepeatedDoesNotThrow() {
        service.start();
        service.start();
        service.stop();
        service.stop();
    }

    // =========================================================================
    // messageToService(byte[]) — logs error, must not throw
    // =========================================================================

    @Test
    public void messageToServiceBinaryDoesNotThrow() {
        service.messageToService(new byte[]{0x01, 0x02, 0x03});
    }

    @Test
    public void messageToServiceEmptyByteArrayDoesNotThrow() {
        service.messageToService(new byte[]{});
    }

    @Test
    public void messageToServiceNullByteArrayDoesNotThrow() {
        service.messageToService((byte[]) null);
    }
}
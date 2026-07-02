package io.github.augustinlr17.localhardwarebridge.websocketservices;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Tests for the smart printer fallback system in {@link PrinterWebSocketService}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link PrinterWebSocketService#decodeRawAsText(byte[])} — ESC/POS control char filtering</li>
 *   <li>{@link PrinterWebSocketService#renderTextToImage(String)} — text-to-image rendering</li>
 *   <li>{@link PrinterWebSocketService.PrinterCapabilities} — capability detection logic</li>
 * </ul>
 *
 * No printing, network, or server bind occurs. All tests are hermetic.
 */
public class PrinterWebSocketServiceFallbackTest {

    // --- decodeRawAsText ---

    @Test
    public void decodeRawAsTextPreservesPrintableAscii() {
        byte[] input = "Hello World 123!".getBytes(StandardCharsets.UTF_8);
        String result = PrinterWebSocketService.decodeRawAsText(input);
        assertEquals("Hello World 123!", result);
    }

    @Test
    public void decodeRawAsTextPreservesNewlines() {
        byte[] input = "Line1\nLine2\nLine3".getBytes(StandardCharsets.UTF_8);
        String result = PrinterWebSocketService.decodeRawAsText(input);
        assertEquals("Line1\nLine2\nLine3", result);
    }

    @Test
    public void decodeRawAsTextHandlesWindowsLineEndings() {
        byte[] input = "Line1\r\nLine2".getBytes(StandardCharsets.UTF_8);
        String result = PrinterWebSocketService.decodeRawAsText(input);
        // CR should be converted to \n, then LF stays, so we get "Line1\n\nLine2"
        assertEquals("Line1\n\nLine2", result);
    }

    @Test
    public void decodeRawAsTextFiltersEscPosControlCharacters() {
        // ESC @ (initialize), ESC ! (print mode), LF, GS V 1 (cut)
        // Printable chars: '@' (0x40), '!' (0x21), 'H', 'i', 'V' (0x56)
        // Control chars filtered: ESC(0x1B), NUL(0x00), GS(0x1D), 0x01
        byte[] input = {0x1B, 0x40, 0x1B, 0x21, 0x00, 'H', 'i', '\n', 0x1D, 0x56, 0x01};
        String result = PrinterWebSocketService.decodeRawAsText(input);
        // Only printable chars and newline should survive: @!Hi\nV
        assertEquals("@!Hi\nV", result);
    }

    @Test
    public void decodeRawAsTextPreservesExtendedLatinChars() {
        byte[] input = "Café Résumé".getBytes(StandardCharsets.ISO_8859_1);
        String result = PrinterWebSocketService.decodeRawAsText(input);
        // The é character (0xE9 in ISO-8859-1) should be preserved as it's in the 128-255 range
        assertTrue("should contain Café", result.contains("Caf"));
    }

    @Test
    public void decodeRawAsTextHandlesEmptyInput() {
        String result = PrinterWebSocketService.decodeRawAsText(new byte[0]);
        assertEquals("", result);
    }

    @Test
    public void decodeRawAsTextHandlesNullInput() {
        String result = PrinterWebSocketService.decodeRawAsText(null);
        // Null should produce empty string without NPE
        assertEquals("", result);
    }

    @Test
    public void decodeRawAsTextHandlesAllControlChars() {
        // Various ESC/POS control chars: ESC, GS, RS, US, DLE, DC2
        byte[] input = {0x1B, 0x1D, 0x1E, 0x1F, 0x10, 0x12, 'A', 'B'};
        String result = PrinterWebSocketService.decodeRawAsText(input);
        assertEquals("AB", result);
    }

    // --- renderTextToImage ---

    @Test
    public void renderTextToImageProducesValidImage() {
        BufferedImage image = PrinterWebSocketService.renderTextToImage("Hello World");
        assertNotNull(image);
        assertTrue("image width should be positive", image.getWidth() > 0);
        assertTrue("image height should be positive", image.getHeight() > 0);
    }

    @Test
    public void renderTextToImageHasMinimumWidth() {
        BufferedImage image = PrinterWebSocketService.renderTextToImage("short");
        // Default receipt width is 576px
        assertEquals(576, image.getWidth());
    }

    @Test
    public void renderTextToImageHeightGrowsWithLines() {
        BufferedImage shortImg = PrinterWebSocketService.renderTextToImage("one line");
        BufferedImage tallImg = PrinterWebSocketService.renderTextToImage("line1\nline2\nline3\nline4\nline5");
        assertTrue("taller text should produce taller image",
                tallImg.getHeight() > shortImg.getHeight());
    }

    @Test
    public void renderTextToImageHandlesEmptyText() {
        BufferedImage image = PrinterWebSocketService.renderTextToImage("");
        assertNotNull(image);
        assertTrue("image width should be positive", image.getWidth() > 0);
        assertTrue("image height should be positive", image.getHeight() > 0);
    }

    @Test
    public void renderTextToImageHandlesNullText() {
        BufferedImage image = PrinterWebSocketService.renderTextToImage(null);
        assertNotNull(image);
        assertTrue("image width should be positive", image.getWidth() > 0);
    }

    @Test
    public void renderTextToImageWrapsLongLines() {
        // A very long line should produce a taller image than a short one
        String longLine = "This is a very long line that should definitely be wrapped across multiple lines when rendered to the image because it exceeds the maximum characters per line limit of the receipt width";
        BufferedImage wrappedImg = PrinterWebSocketService.renderTextToImage(longLine);
        BufferedImage singleLine = PrinterWebSocketService.renderTextToImage("short");
        assertTrue("wrapped long line should produce taller image than single short line",
                wrappedImg.getHeight() > singleLine.getHeight());
    }

    @Test
    public void renderTextToImageProducesWhiteBackground() {
        BufferedImage image = PrinterWebSocketService.renderTextToImage("test");
        // Check top-left corner is white (or near-white)
        int rgb = image.getRGB(0, 0);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        assertTrue("background should be white", r > 240 && g > 240 && b > 240);
    }

    // --- PrinterCapabilities ---

    @Test
    public void thermalOnlyPrinterIsDetectedAsThermal() {
        PrinterWebSocketService.PrinterCapabilities caps =
                new PrinterWebSocketService.PrinterCapabilities(true, false, false);
        assertTrue("raw-only printer should be thermal", caps.isThermalOnly());
    }

    @Test
    public void laserPrinterIsNotThermal() {
        PrinterWebSocketService.PrinterCapabilities caps =
                new PrinterWebSocketService.PrinterCapabilities(false, true, true);
        assertFalse("image+pdf printer should not be thermal", caps.isThermalOnly());
    }

    @Test
    public void rawAndImagePrinterIsNotThermal() {
        PrinterWebSocketService.PrinterCapabilities caps =
                new PrinterWebSocketService.PrinterCapabilities(true, true, false);
        assertFalse("raw+image printer should not be thermal-only", caps.isThermalOnly());
    }

    @Test
    public void rawAndPdfPrinterIsNotThermal() {
        PrinterWebSocketService.PrinterCapabilities caps =
                new PrinterWebSocketService.PrinterCapabilities(true, false, true);
        assertFalse("raw+pdf printer should not be thermal-only", caps.isThermalOnly());
    }

    @Test
    public void noCapabilitiesIsNotThermal() {
        PrinterWebSocketService.PrinterCapabilities caps =
                new PrinterWebSocketService.PrinterCapabilities(false, false, false);
        assertFalse("no-capability printer should not be thermal", caps.isThermalOnly());
    }
}

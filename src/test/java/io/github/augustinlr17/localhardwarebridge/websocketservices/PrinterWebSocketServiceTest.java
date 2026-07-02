package io.github.augustinlr17.localhardwarebridge.websocketservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.responses.PrintDocument;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the file-type detection used by {@link PrinterWebSocketService}. The fix makes
 * isPDF/isImage inspect only the URL *path* so a query string or fragment cannot spoof the
 * type (e.g. {@code http://host/file.exe#x.pdf}). Both methods are private instance methods,
 * invoked here via reflection. No printing, network, or server bind occurs.
 */
public class PrinterWebSocketServiceTest {

    private static PrinterWebSocketService service;
    private static Method isPDF;
    private static Method isImage;
    private static Method isRaw;
    private static Method urlFilename;

    @BeforeClass
    public static void setUp() throws Exception {
        service = new PrinterWebSocketService();
        isPDF = PrinterWebSocketService.class.getDeclaredMethod("isPDF", PrintDocument.class);
        isPDF.setAccessible(true);
        isImage = PrinterWebSocketService.class.getDeclaredMethod("isImage", PrintDocument.class);
        isImage.setAccessible(true);
        isRaw = PrinterWebSocketService.class.getDeclaredMethod("isRaw", PrintDocument.class);
        isRaw.setAccessible(true);
        urlFilename = PrinterWebSocketService.class.getDeclaredMethod("urlFilename", PrintDocument.class);
        urlFilename.setAccessible(true);
    }

    private static PrintDocument doc(String url) throws Exception {
        return new ObjectMapper().readValue("{\"url\":\"" + url + "\"}", PrintDocument.class);
    }

    private static PrintDocument docRaw(String rawContent) throws Exception {
        return new ObjectMapper().readValue("{\"raw_content\":\"" + rawContent + "\"}", PrintDocument.class);
    }

    private boolean isPdf(String url) throws Exception {
        return (Boolean) isPDF.invoke(service, doc(url));
    }

    private boolean isImg(String url) throws Exception {
        return (Boolean) isImage.invoke(service, doc(url));
    }

    private boolean isRawDoc(String rawContent) throws Exception {
        return (Boolean) isRaw.invoke(service, docRaw(rawContent));
    }

    private String filename(String url) throws Exception {
        return (String) urlFilename.invoke(service, doc(url));
    }

    // --- isPDF ---

    @Test
    public void genuinePdfUrlIsPdf() throws Exception {
        assertTrue(isPdf("http://x/file.pdf"));
        assertTrue(isPdf("http://x/a/b/document.PDF"));
    }

    @Test
    public void fragmentCannotSpoofPdf() throws Exception {
        // Path is "/y"; the "#a.pdf" fragment must be ignored.
        assertFalse(isPdf("http://x/y#a.pdf"));
    }

    @Test
    public void queryCannotSpoofPdf() throws Exception {
        // Path is "/y"; the "?z=.pdf" query must be ignored.
        assertFalse(isPdf("http://x/y?z=.pdf"));
    }

    @Test
    public void nonPdfUrlIsNotPdf() throws Exception {
        assertFalse(isPdf("http://x/file.docx"));
        assertFalse(isPdf("http://x/file.png"));
        assertFalse(isPdf("http://x/file"));
    }

    @Test
    public void pdfWithQueryStringIsStillPdf() throws Exception {
        assertTrue(isPdf("http://x/report.pdf?token=abc"));
    }

    @Test
    public void pdfWithFragmentIsStillPdf() throws Exception {
        assertTrue(isPdf("http://x/report.pdf#page=1"));
    }

    // --- isImage ---

    @Test
    public void genuineImageUrlIsImage() throws Exception {
        assertTrue(isImg("http://x/picture.png"));
        assertTrue(isImg("http://x/photo.JPEG"));
        assertTrue(isImg("http://x/pic.jpg"));
        assertTrue(isImg("http://x/anim.gif"));
    }

    @Test
    public void queryCannotSpoofImage() throws Exception {
        assertFalse(isImg("http://x/y?z=.png"));
        assertFalse(isImg("http://x/y#a.png"));
    }

    @Test
    public void nonImageUrlIsNotImage() throws Exception {
        assertFalse(isImg("http://x/file.bmp"));
        assertFalse(isImg("http://x/file.tiff"));
        assertFalse(isImg("http://x/file.pdf"));
    }

    @Test
    public void imageWithQueryStringIsStillImage() throws Exception {
        assertTrue(isImg("http://x/photo.jpg?v=2"));
    }

    // --- isRaw ---

    @Test
    public void documentWithRawContentIsRaw() throws Exception {
        assertTrue(isRawDoc("SGVsbG8="));
        assertTrue(isRawDoc("AA=="));
    }

    @Test
    public void documentWithEmptyRawContentIsNotRaw() throws Exception {
        assertFalse(isRawDoc(""));
    }

    @Test
    public void documentWithoutRawContentIsNotRaw() throws Exception {
        PrintDocument noRaw = new ObjectMapper().readValue("{\"type\":\"TEST\"}", PrintDocument.class);
        assertFalse((Boolean) isRaw.invoke(service, noRaw));
    }

    // --- urlFilename ---

    @Test
    public void urlFilenameExtractsBasename() throws Exception {
        assertEquals("file.pdf", filename("http://x/a/b/file.pdf"));
        assertEquals("photo.jpg", filename("http://example.com/path/photo.jpg"));
    }

    @Test
    public void urlFilenameIgnoresQueryAndFragment() throws Exception {
        assertEquals("report.pdf", filename("http://x/report.pdf?token=abc"));
        assertEquals("report.pdf", filename("http://x/report.pdf#page=1"));
    }

    @Test
    public void urlFilenameHandlesRootPath() throws Exception {
        // Root path has no filename; getName returns ""
        assertEquals("", filename("http://x/"));
    }

    @Test
    public void urlFilenameHandlesMalformedUrl() throws Exception {
        // A non-URL string falls back to FilenameUtils.getName
        assertEquals("file.pdf", filename("file.pdf"));
    }

    @Test
    public void urlFilenameHandlesNullUrl() throws Exception {
        PrintDocument nullUrl = new ObjectMapper().readValue("{}", PrintDocument.class);
        assertEquals("", (String) urlFilename.invoke(service, nullUrl));
    }

    // --- Content-based detection (file_content without URL or with URL without extension) ---

    /** Minimal valid PDF: %PDF-1.4 header. */
    private static final byte[] PDF_MAGIC = "%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    /** Minimal PNG signature: 89 50 4E 47 0D 0A 1A 0A. */
    private static final byte[] PNG_MAGIC = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    /** Minimal JPEG SOI marker: FF D8 FF E0. */
    private static final byte[] JPEG_MAGIC = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    /** GIF87a header. */
    private static final byte[] GIF_MAGIC = "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private PrintDocument docWithContent(String fileContent) throws Exception {
        String json = new ObjectMapper().writeValueAsString(new java.util.HashMap<String, String>() {{
            put("file_content", fileContent);
        }});
        return new ObjectMapper().readValue(json, PrintDocument.class);
    }

    private PrintDocument docWithContentAndUrl(String fileContent, String url) throws Exception {
        String json = new ObjectMapper().writeValueAsString(new java.util.HashMap<String, String>() {{
            put("file_content", fileContent);
            put("url", url);
        }});
        return new ObjectMapper().readValue(json, PrintDocument.class);
    }

    @Test
    public void pdfByContentWithoutUrl() throws Exception {
        assertTrue((Boolean) isPDF.invoke(service, docWithContent(b64(PDF_MAGIC))));
    }

    @Test
    public void pdfByContentWithUrlNoExtension() throws Exception {
        // URL has no .pdf extension; content should be sniffed.
        assertTrue((Boolean) isPDF.invoke(service, docWithContentAndUrl(b64(PDF_MAGIC), "http://x/document")));
    }

    @Test
    public void pngByContentWithoutUrl() throws Exception {
        assertTrue((Boolean) isImage.invoke(service, docWithContent(b64(PNG_MAGIC))));
    }

    @Test
    public void jpegByContentWithoutUrl() throws Exception {
        assertTrue((Boolean) isImage.invoke(service, docWithContent(b64(JPEG_MAGIC))));
    }

    @Test
    public void gifByContentWithoutUrl() throws Exception {
        assertTrue((Boolean) isImage.invoke(service, docWithContent(b64(GIF_MAGIC))));
    }

    @Test
    public void imageByContentWithUrlNoExtension() throws Exception {
        assertTrue((Boolean) isImage.invoke(service, docWithContentAndUrl(b64(PNG_MAGIC), "http://x/photo")));
    }

    @Test
    public void nonImageNonPdfContentIsNotDetected() throws Exception {
        // Random bytes that don't match any known magic
        byte[] random = "Hello World this is not a file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse((Boolean) isImage.invoke(service, docWithContent(b64(random))));
        assertFalse((Boolean) isPDF.invoke(service, docWithContent(b64(random))));
    }

    @Test
    public void urlExtensionStillPrioritizedOverContent() throws Exception {
        // URL says .pdf but content is PNG — URL wins (security: query/fragment already stripped)
        assertTrue((Boolean) isPDF.invoke(service, docWithContentAndUrl(b64(PNG_MAGIC), "http://x/file.pdf")));
        assertFalse((Boolean) isImage.invoke(service, docWithContentAndUrl(b64(PNG_MAGIC), "http://x/file.pdf")));
    }

    @Test
    public void emptyFileContentIsNotDetected() throws Exception {
        assertFalse((Boolean) isPDF.invoke(service, docWithContent("")));
        assertFalse((Boolean) isImage.invoke(service, docWithContent("")));
    }

    @Test
    public void invalidBase64ContentIsNotDetected() throws Exception {
        assertFalse((Boolean) isPDF.invoke(service, docWithContent("!!!notbase64!!!")));
        assertFalse((Boolean) isImage.invoke(service, docWithContent("!!!notbase64!!!")));
    }
}

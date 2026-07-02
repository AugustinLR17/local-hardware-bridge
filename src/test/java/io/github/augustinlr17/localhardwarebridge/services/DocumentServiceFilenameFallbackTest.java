package io.github.augustinlr17.localhardwarebridge.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import static org.junit.Assert.*;

/**
 * Additional tests for {@link DocumentService#getOutputFile} edge cases:
 * empty/null filename fallback to UUID, and URL with no path component.
 * Private method invoked via reflection.
 * Fully hermetic: no network, no real downloads.
 */
public class DocumentServiceFilenameFallbackTest {

    private static ObjectMapper appMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private File baseDir;
    private java.lang.reflect.Method getOutputFile;

    @org.junit.Before
    public void setUp() throws Exception {
        baseDir = Files.createTempDirectory("lhb-fallback").toFile();
        baseDir.deleteOnExit();
        ConfigService.getInstance().getConfig().getDownloader().setPath(baseDir.getAbsolutePath());

        getOutputFile = DocumentService.class.getDeclaredMethod("getOutputFile", io.github.augustinlr17.localhardwarebridge.responses.PrintDocument.class);
        getOutputFile.setAccessible(true);
    }

    private io.github.augustinlr17.localhardwarebridge.responses.PrintDocument doc(String json) throws Exception {
        return appMapper().readValue(json, io.github.augustinlr17.localhardwarebridge.responses.PrintDocument.class);
    }

    private File invokeGetOutputFile(io.github.augustinlr17.localhardwarebridge.responses.PrintDocument document) throws Throwable {
        try {
            return (File) getOutputFile.invoke(DocumentService.getInstance(), document);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    public void urlWithRootPathFallsBackToUuid() throws Throwable {
        // URL path is "/" → FilenameUtils.getName returns "" → uuid fallback
        File out = invokeGetOutputFile(doc("{\"url\":\"http://example.com/\"}"));
        assertNotNull(out);
        String basePath = baseDir.getCanonicalPath() + File.separator;
        assertTrue("resolved path must stay inside downloads base: " + out.getCanonicalPath(),
                out.getCanonicalPath().startsWith(basePath));
        // The filename should contain the uuid (not empty)
        String name = out.getName();
        assertFalse("filename must not be empty when url path is root", name.isEmpty());
        // Format is "<uuid>-" since rawName was empty
        assertTrue("filename should start with uuid prefix", name.length() > 36);
    }

    @Test
    public void urlWithNoPathFallsBackToUuid() throws Throwable {
        // URL path has no filename (e.g. "http://example.com")
        File out = invokeGetOutputFile(doc("{\"url\":\"http://example.com\"}"));
        assertNotNull(out);
        String basePath = baseDir.getCanonicalPath() + File.separator;
        assertTrue(out.getCanonicalPath().startsWith(basePath));
        String name = out.getName();
        assertFalse("filename must not be empty when url has no path", name.isEmpty());
    }

    @Test
    public void inlineContentWithNullUrlFallsBackToUuid() throws Throwable {
        // file_content is set but url is null → FilenameUtils.getName(null) returns ""
        File out = invokeGetOutputFile(doc("{\"file_content\":\"SGVsbG8=\"}"));
        assertNotNull(out);
        String basePath = baseDir.getCanonicalPath() + File.separator;
        assertTrue(out.getCanonicalPath().startsWith(basePath));
        String name = out.getName();
        assertFalse("filename must not be empty when url is null", name.isEmpty());
    }

    // --- sniffExtension tests ---

    @Test
    public void sniffExtensionDetectsPdf() {
        byte[] pdf = "%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(".pdf", DocumentService.sniffExtension(Base64.getEncoder().encodeToString(pdf)));
    }

    @Test
    public void sniffExtensionDetectsPng() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertEquals(".png", DocumentService.sniffExtension(Base64.getEncoder().encodeToString(png)));
    }

    @Test
    public void sniffExtensionDetectsJpeg() {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        assertEquals(".jpg", DocumentService.sniffExtension(Base64.getEncoder().encodeToString(jpeg)));
    }

    @Test
    public void sniffExtensionDetectsGif() {
        byte[] gif = "GIF87a".getBytes(StandardCharsets.US_ASCII);
        assertEquals(".gif", DocumentService.sniffExtension(Base64.getEncoder().encodeToString(gif)));
    }

    @Test
    public void sniffExtensionReturnsEmptyForUnknownContent() {
        byte[] random = "Hello World".getBytes(StandardCharsets.UTF_8);
        assertEquals("", DocumentService.sniffExtension(Base64.getEncoder().encodeToString(random)));
    }

    @Test
    public void sniffExtensionReturnsEmptyForNullOrEmptyInput() {
        assertEquals("", DocumentService.sniffExtension(null));
        assertEquals("", DocumentService.sniffExtension(""));
    }

    @Test
    public void sniffExtensionReturnsEmptyForInvalidBase64() {
        assertEquals("", DocumentService.sniffExtension("!!!notbase64!!!"));
    }

    // --- Extension added to inline content filename ---

    @Test
    public void inlinePdfContentGetsPdfExtension() throws Throwable {
        byte[] pdf = "%PDF-1.4 test".getBytes(StandardCharsets.ISO_8859_1);
        String b64 = Base64.getEncoder().encodeToString(pdf);
        File out = invokeGetOutputFile(doc("{\"file_content\":\"" + b64 + "\"}"));
        assertNotNull(out);
        assertTrue("filename should have .pdf extension: " + out.getName(), out.getName().endsWith(".pdf"));
    }

    @Test
    public void inlinePngContentGetsPngExtension() throws Throwable {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        String b64 = Base64.getEncoder().encodeToString(png);
        File out = invokeGetOutputFile(doc("{\"file_content\":\"" + b64 + "\"}"));
        assertNotNull(out);
        assertTrue("filename should have .png extension: " + out.getName(), out.getName().endsWith(".png"));
    }

    @Test
    public void inlineContentWithUrlExtensionKeepsUrlExtension() throws Throwable {
        // URL says .pdf — the URL extension should be used, not content sniffing
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        String b64 = Base64.getEncoder().encodeToString(png);
        File out = invokeGetOutputFile(doc("{\"file_content\":\"" + b64 + "\",\"url\":\"http://x/doc.pdf\"}"));
        assertNotNull(out);
        assertTrue("filename should have .pdf from URL: " + out.getName(), out.getName().endsWith(".pdf"));
    }
}

package io.github.augustinlr17.localhardwarebridge.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
}

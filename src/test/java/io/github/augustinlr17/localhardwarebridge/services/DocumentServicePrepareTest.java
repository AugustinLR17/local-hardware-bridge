package io.github.augustinlr17.localhardwarebridge.services;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.responses.PrintDocument;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DocumentService#prepareDocument} and
 * {@link DocumentService#deleteDocument}.
 * Uses a temp directory as the downloads path.
 * Fully hermetic: no network, no real downloads.
 */
public class DocumentServicePrepareTest {

    private File baseDir;
    private DocumentService documentService;

    @Before
    public void setUp() throws Exception {
        baseDir = Files.createTempDirectory("lhb-prepare").toFile();
        baseDir.deleteOnExit();

        ConfigService.getInstance().getConfig().getDownloader().setPath(baseDir.getAbsolutePath());
        documentService = DocumentService.getInstance();
    }

    private PrintDocument doc(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, PrintDocument.class);
    }

    // --- prepareDocument ---

    @Test
    public void prepareDocumentWithFileContentWritesFile() throws Exception {
        PrintDocument printDoc = doc("{\"file_content\":\"SGVsbG8gV29ybGQ=\",\"url\":\"test.txt\"}");

        File result = documentService.prepareDocument(printDoc);

        assertNotNull(result);
        assertTrue("file must exist after prepare", result.exists());
        assertTrue("file must be inside base dir",
                result.getCanonicalPath().startsWith(baseDir.getCanonicalPath() + File.separator));
        byte[] content = Files.readAllBytes(result.toPath());
        assertEquals("Hello World", new String(content));
    }

    @Test
    public void prepareDocumentThrowsWhenBothUrlAndFileContentAreNull() throws Exception {
        PrintDocument printDoc = doc("{\"type\":\"TEST\"}");

        try {
            documentService.prepareDocument(printDoc);
            fail("expected exception when both URL and fileContent are null");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Both URL and File Content are null"));
        }
    }

    @Test
    public void prepareDocumentCreatesDownloadsDirIfMissing() throws Exception {
        // Point to a non-existent subdirectory
        File nestedDir = new File(baseDir, "nested/downloads");
        ConfigService.getInstance().getConfig().getDownloader().setPath(nestedDir.getAbsolutePath());

        PrintDocument printDoc = doc("{\"file_content\":\"QQ==\",\"url\":\"x.txt\"}");
        File result = documentService.prepareDocument(printDoc);

        assertTrue("nested downloads dir must be created", nestedDir.exists());
        assertTrue(result.exists());

        // Reset for other tests
        ConfigService.getInstance().getConfig().getDownloader().setPath(baseDir.getAbsolutePath());
    }

    // --- deleteDocument ---

    @Test
    public void deleteDocumentRemovesTheFile() throws Exception {
        PrintDocument printDoc = doc("{\"file_content\":\"SGVsbG8=\",\"url\":\"delete-me.txt\"}");
        File created = documentService.prepareDocument(printDoc);
        assertTrue(created.exists());

        documentService.deleteDocument(printDoc);

        assertFalse("file must be deleted", created.exists());
    }

    @Test
    public void deleteDocumentIsSafeWhenFileAlreadyGone() throws Exception {
        PrintDocument printDoc = doc("{\"file_content\":\"SGVsbG8=\",\"url\":\"already-gone.txt\"}");
        File created = documentService.prepareDocument(printDoc);
        created.delete();
        assertFalse(created.exists());

        // Should not throw
        documentService.deleteDocument(printDoc);
    }

    @Test
    public void prepareThenDeleteCleansUpCompletely() throws Exception {
        PrintDocument printDoc = doc("{\"file_content\":\"SGVsbG8=\",\"url\":\"cleanup.txt\"}");
        File created = documentService.prepareDocument(printDoc);

        assertTrue(created.exists());
        documentService.deleteDocument(printDoc);
        assertFalse(created.exists());

        // The base dir should still exist (only the file is deleted)
        assertTrue(baseDir.exists());
    }
}

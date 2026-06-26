package io.github.augustinlr17.localhardwarebridge.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.responses.PrintDocument;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Path-traversal hardening tests for {@link DocumentService#getOutputFile}. The method is
 * private, so we invoke it via reflection. The downloads base directory is redirected to a
 * temp dir through the (singleton) config so no real files leak. Fully hermetic: no network
 * and no actual downloads occur (getOutputFile only computes/validates the target path).
 */
public class DocumentServiceTest {

    private File baseDir;
    private String basePrefix;
    private Method getOutputFile;

    @Before
    public void setUp() throws Exception {
        baseDir = Files.createTempDirectory("lhb-downloads").toFile();
        baseDir.deleteOnExit();
        basePrefix = baseDir.getCanonicalPath() + File.separator;

        // Redirect the downloads directory used by getOutputFile().
        ConfigService.getInstance().getConfig().getDownloader().setPath(baseDir.getAbsolutePath());

        getOutputFile = DocumentService.class.getDeclaredMethod("getOutputFile", PrintDocument.class);
        getOutputFile.setAccessible(true);
    }

    /** Build a PrintDocument via Jackson (its fields are package-private with no setters). */
    private PrintDocument doc(String json) throws Exception {
        return new ObjectMapper().readValue(json, PrintDocument.class);
    }

    private File invoke(PrintDocument document) throws Throwable {
        try {
            return (File) getOutputFile.invoke(DocumentService.getInstance(), document);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void assertInsideBase(File output) throws IOException {
        String resolved = output.getCanonicalPath();
        assertTrue("resolved path must stay inside downloads base: " + resolved,
                resolved.startsWith(basePrefix));
    }

    @Test
    public void normalInlineFilenameStaysInBase() throws Throwable {
        // fileContent present + a plain suggested filename.
        File out = invoke(doc("{\"file_content\":\"SGVsbG8=\",\"url\":\"report.pdf\"}"));
        assertInsideBase(out);
        assertTrue(out.getName().endsWith("-report.pdf"));
    }

    @Test
    public void inlineTraversalFilenameIsSanitizedIntoBase() throws Throwable {
        // Classic ../../ traversal as a *suggested* filename for inline content: must be
        // stripped to its basename and contained, never escaping the base dir.
        File out = invoke(doc("{\"file_content\":\"SGVsbG8=\",\"url\":\"../../etc/passwd\"}"));
        assertInsideBase(out);
        assertTrue(out.getName().endsWith("-passwd"));
    }

    @Test
    public void inlineAbsolutePathIsSanitizedIntoBase() throws Throwable {
        File out = invoke(doc("{\"file_content\":\"SGVsbG8=\",\"url\":\"/etc/passwd\"}"));
        assertInsideBase(out);
        assertTrue(out.getName().endsWith("-passwd"));
    }

    @Test
    public void urlWithDirectoriesUsesBasenameInBase() throws Throwable {
        // Download branch (no fileContent): directories in the URL path are dropped.
        File out = invoke(doc("{\"url\":\"http://example.com/a/b/c.pdf\"}"));
        assertInsideBase(out);
        assertTrue(out.getName().endsWith("-c.pdf"));
    }

    @Test
    public void malformedTraversalUrlIsRejected() throws Throwable {
        // Download branch with a non-URL traversal string -> new URL() fails -> IOException.
        try {
            invoke(doc("{\"url\":\"../../etc/passwd\"}"));
            fail("expected an IOException for a malformed/traversal URL");
        } catch (IOException expected) {
            // good: rejected rather than producing an out-of-base path
        }
    }
}

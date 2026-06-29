package io.github.augustinlr17.localhardwarebridge.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
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
 * Path-traversal and URL scheme hardening tests for {@link DocumentService#getOutputFile}
 * and {@link DocumentService#download}. Private methods are invoked via reflection.
 * The downloads base directory is redirected to a temp dir through the (singleton) config
 * so no real files leak. Fully hermetic: no network and no actual downloads occur.
 */
public class DocumentServiceTest {

    private File baseDir;
    private String basePrefix;
    private Method getOutputFile;
    private Method download;
    private Method verifyPublicHost;

    @Before
    public void setUp() throws Exception {
        baseDir = Files.createTempDirectory("lhb-downloads").toFile();
        baseDir.deleteOnExit();
        basePrefix = baseDir.getCanonicalPath() + File.separator;

        // Redirect the downloads directory used by getOutputFile().
        ConfigService.getInstance().getConfig().getDownloader().setPath(baseDir.getAbsolutePath());

        getOutputFile = DocumentService.class.getDeclaredMethod("getOutputFile", PrintDocument.class);
        getOutputFile.setAccessible(true);

        download = DocumentService.class.getDeclaredMethod("download", java.net.URL.class, File.class);
        download.setAccessible(true);

        verifyPublicHost = DocumentService.class.getDeclaredMethod("verifyPublicHost", String.class);
        verifyPublicHost.setAccessible(true);
    }

    /** Build a PrintDocument via Jackson (its fields are package-private with no setters). */
    private PrintDocument doc(String json) throws Exception {
        return new ObjectMapper().readValue(json, PrintDocument.class);
    }

    private File invokeGetOutputFile(PrintDocument document) throws Throwable {
        try {
            return (File) getOutputFile.invoke(DocumentService.getInstance(), document);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void invokeDownload(java.net.URL url, File output) throws Throwable {
        try {
            download.invoke(DocumentService.getInstance(), url, output);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void invokeVerifyPublicHost(String host) throws Throwable {
        try {
            verifyPublicHost.invoke(DocumentService.getInstance(), (Object) host);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void assertInsideBase(File output) throws IOException {
        String resolved = output.getCanonicalPath();
        assertTrue("resolved path must stay inside downloads base: " + resolved,
                resolved.startsWith(basePrefix));
    }

    // --- Path traversal (getOutputFile) ---

    @Test
    public void normalInlineFilenameStaysInBase() throws Throwable {
        File out = invokeGetOutputFile(doc("{\"file_content\":\"SGVsbG8=\",\"url\":\"report.pdf\"}"));
        assertInsideBase(out);
        assertTrue(out.getName().endsWith("-report.pdf"));
    }

    @Test
    public void inlineTraversalFilenameIsSanitizedIntoBase() throws Throwable {
        File out = invokeGetOutputFile(doc("{\"file_content\":\"SGVsbG8=\",\"url\":\"../../etc/passwd\"}"));
        assertInsideBase(out);
        assertTrue(out.getName().endsWith("-passwd"));
    }

    @Test
    public void inlineAbsolutePathIsSanitizedIntoBase() throws Throwable {
        File out = invokeGetOutputFile(doc("{\"file_content\":\"SGVsbG8=\",\"url\":\"/etc/passwd\"}"));
        assertInsideBase(out);
        assertTrue(out.getName().endsWith("-passwd"));
    }

    @Test
    public void urlWithDirectoriesUsesBasenameInBase() throws Throwable {
        File out = invokeGetOutputFile(doc("{\"url\":\"http://example.com/a/b/c.pdf\"}"));
        assertInsideBase(out);
        assertTrue(out.getName().endsWith("-c.pdf"));
    }

    @Test
    public void malformedTraversalUrlIsRejected() throws Throwable {
        try {
            invokeGetOutputFile(doc("{\"url\":\"../../etc/passwd\"}"));
            fail("expected an IOException for a malformed/traversal URL");
        } catch (IOException expected) {
            // good: rejected rather than producing an out-of-base path
        }
    }

    // --- URL scheme validation (download) ---

    @Test
    public void fileSchemeIsRejected() throws Throwable {
        File outFile = new File(baseDir, "test-scheme.out");
        try {
            invokeDownload(new java.net.URL("file:///etc/passwd"), outFile);
            fail("expected IOException for file:// scheme");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unsupported URL scheme"));
        }
    }

    @Test
    public void ftpSchemeIsRejected() throws Throwable {
        File outFile = new File(baseDir, "test-ftp.out");
        try {
            invokeDownload(new java.net.URL("ftp://example.com/file.pdf"), outFile);
            fail("expected IOException for ftp:// scheme");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unsupported URL scheme"));
        }
    }

    @Test
    public void jarSchemeIsRejected() throws Throwable {
        File outFile = new File(baseDir, "test-jar.out");
        try {
            invokeDownload(new java.net.URL("jar:file:/test.jar!/file.pdf"), outFile);
            fail("expected IOException for jar: scheme");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unsupported URL scheme"));
        }
    }

    // --- SSRF host verification (verifyPublicHost) ---

    @Test
    public void loopbackHostIsRejected() throws Throwable {
        ConfigService.getInstance().getConfig().getDownloader().setBlockPrivateNetworks(true);
        try {
            invokeVerifyPublicHost("127.0.0.1");
            fail("expected IOException for loopback host");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("private/reserved"));
        }
    }

    @Test
    public void localhostIsRejected() throws Throwable {
        ConfigService.getInstance().getConfig().getDownloader().setBlockPrivateNetworks(true);
        try {
            invokeVerifyPublicHost("localhost");
            fail("expected IOException for localhost");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("private/reserved"));
        }
    }

    @Test
    public void privateNetworkIsRejected() throws Throwable {
        ConfigService.getInstance().getConfig().getDownloader().setBlockPrivateNetworks(true);
        try {
            invokeVerifyPublicHost("192.168.1.1");
            fail("expected IOException for private network");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("private/reserved"));
        }
    }

    @Test
    public void tenNetworkIsRejected() throws Throwable {
        ConfigService.getInstance().getConfig().getDownloader().setBlockPrivateNetworks(true);
        try {
            invokeVerifyPublicHost("10.0.0.1");
            fail("expected IOException for 10.x network");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("private/reserved"));
        }
    }

    @Test
    public void emptyHostIsRejected() throws Throwable {
        ConfigService.getInstance().getConfig().getDownloader().setBlockPrivateNetworks(true);
        try {
            invokeVerifyPublicHost("");
            fail("expected IOException for empty host");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("empty host"));
        }
    }

    @Test
    public void nullHostIsRejected() throws Throwable {
        ConfigService.getInstance().getConfig().getDownloader().setBlockPrivateNetworks(true);
        try {
            invokeVerifyPublicHost(null);
            fail("expected IOException for null host");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("empty host"));
        }
    }

    @Test
    public void unresolvableHostIsRejected() throws Throwable {
        ConfigService.getInstance().getConfig().getDownloader().setBlockPrivateNetworks(true);
        try {
            invokeVerifyPublicHost("this-domain-does-not-exist.invalid");
            fail("expected IOException for unresolvable host");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unable to resolve"));
        }
    }
}

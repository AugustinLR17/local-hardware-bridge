package io.github.augustinlr17.localhardwarebridge.websocketservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.responses.PrintDocument;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Method;

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

    @BeforeClass
    public static void setUp() throws Exception {
        service = new PrinterWebSocketService();
        isPDF = PrinterWebSocketService.class.getDeclaredMethod("isPDF", PrintDocument.class);
        isPDF.setAccessible(true);
        isImage = PrinterWebSocketService.class.getDeclaredMethod("isImage", PrintDocument.class);
        isImage.setAccessible(true);
    }

    private static PrintDocument doc(String url) throws Exception {
        return new ObjectMapper().readValue("{\"url\":\"" + url + "\"}", PrintDocument.class);
    }

    private boolean isPdf(String url) throws Exception {
        return (Boolean) isPDF.invoke(service, doc(url));
    }

    private boolean isImg(String url) throws Exception {
        return (Boolean) isImage.invoke(service, doc(url));
    }

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
    public void genuineImageUrlIsImage() throws Exception {
        assertTrue(isImg("http://x/picture.png"));
        assertTrue(isImg("http://x/photo.JPEG"));
    }

    @Test
    public void queryCannotSpoofImage() throws Exception {
        assertFalse(isImg("http://x/y?z=.png"));
        assertFalse(isImg("http://x/y#a.png"));
    }
}

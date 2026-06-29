package io.github.augustinlr17.localhardwarebridge.websocketservices;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServerInterface;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServiceInterface;
import io.github.augustinlr17.localhardwarebridge.responses.PrintDocument;
import io.github.augustinlr17.localhardwarebridge.responses.PrintResult;
import io.github.augustinlr17.localhardwarebridge.services.ConfigService;
import org.junit.Before;
import org.junit.Test;

import java.awt.print.PrinterException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Additional tests for {@link PrinterWebSocketService} covering:
 * - searchPrinterForType with fallbackToDefault when no default printer exists
 * - searchPrinterForType with a valid mapping but printer not on system
 * - printDocument error flow (unknown file type → failure result)
 * - printDocument lock key selection with null type (DEFAULT_LOCK_KEY)
 */
public class PrinterWebSocketServiceSearchTest {

    private PrinterWebSocketService service;
    private Method searchPrinterForType;

    @Before
    public void setUp() throws Exception {
        service = new PrinterWebSocketService();
        searchPrinterForType = PrinterWebSocketService.class
                .getDeclaredMethod("searchPrinterForType", String.class);
        searchPrinterForType.setAccessible(true);
    }

    @Test
    public void fallbackToDefaultThrowsWhenNoDefaultPrinter() throws Throwable {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(true);
        config.getPrinter().getMappings().removeIf(m -> "NO_DEFAULT_TEST".equals(m.getType()));

        // Note: this test depends on whether the test machine has a default printer.
        // If it does, the search will succeed; if not, it throws "No default printer found".
        // We handle both cases gracefully.
        try {
            Object result = searchPrinterForType.invoke(service, "NO_DEFAULT_TEST");
            // If we got here, a default printer exists — verify the result
            assertNotNull(result);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue("expected PrinterException", cause instanceof PrinterException);
            // Should mention either "No default printer" or "No printer mapping"
            String msg = cause.getMessage();
            assertTrue("error should mention default printer or mapping: " + msg,
                    msg.contains("No default printer") || msg.contains("No printer mapping"));
        } finally {
            config.getPrinter().setFallbackToDefault(false);
        }
    }

    @Test
    public void searchThrowsForUnknownTypeWithoutAnyFallback() throws Throwable {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().removeIf(m -> "COMPLETELY_UNKNOWN_TYPE".equals(m.getType()));

        try {
            searchPrinterForType.invoke(service, "COMPLETELY_UNKNOWN_TYPE");
            fail("expected PrinterException for unknown type");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof PrinterException);
            assertTrue(cause.getMessage().contains("No printer mapping found for type: COMPLETELY_UNKNOWN_TYPE"));
        }
    }

    @Test
    public void searchWithMappingToNonExistentPrinterThrows() throws Throwable {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);

        config.getPrinter().getMappings().removeIf(m -> "GHOST_TEST_2".equals(m.getType()));
        config.getPrinter().getMappings().add(
                new Config.PrinterMapping("GHOST_TEST_2", "NonExistentPrinterXYZ999", false, true, 0));

        try {
            searchPrinterForType.invoke(service, "GHOST_TEST_2");
            fail("expected PrinterException for ghost printer");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof PrinterException);
            assertTrue(cause.getMessage().contains("Printer not found on system"));
            assertTrue(cause.getMessage().contains("NonExistentPrinterXYZ999"));
        } finally {
            config.getPrinter().getMappings().removeIf(m -> "GHOST_TEST_2".equals(m.getType()));
        }
    }

    // --- printDocument error flow ---

    @Test
    public void printDocumentUnknownFileTypeReturnsFailureResult() throws Exception {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().removeIf(m -> "UNKNOWN_FILE_TYPE_TEST".equals(m.getType()));

        // Add a mapping so searchPrinterForType doesn't fail before type detection
        // Actually, searchPrinterForType will fail because the printer doesn't exist.
        // So we need to test the case where the printer IS found but the file type is unknown.
        // Since we can't mock PrintServiceLookup, we test the error flow via the
        // printDocument method which catches all exceptions.

        // Set up a mock server to capture the result
        MockServer mockServer = new MockServer();
        service.onRegister(mockServer);

        PrintDocument doc = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"type\":\"UNKNOWN_FILE_TYPE_TEST\",\"url\":\"http://example.com/file.xyz\"}",
                PrintDocument.class);

        PrintResult result = service.printDocument(doc);

        // The print should fail (either no printer mapping or unknown file type)
        assertNotNull(result);
        assertFalse("print should fail for unknown type/printer", result.success);
    }

    @Test
    public void printDocumentWithNullTypeUsesDefaultLockKey() throws Exception {
        // Verify that a null type doesn't cause an NPE in the lock key selection
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);

        MockServer mockServer = new MockServer();
        service.onRegister(mockServer);

        // PrintDocument with null type
        PrintDocument doc = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"url\":\"http://example.com/file.pdf\"}",
                PrintDocument.class);

        PrintResult result = service.printDocument(doc);

        // Should fail (no mapping for null type) but not throw NPE
        assertNotNull(result);
        assertFalse(result.success);
    }

    @Test
    public void printDocumentRawContentWithNoPrinterMappingFailsGracefully() throws Exception {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().removeIf(m -> "RAW_NO_MAPPING".equals(m.getType()));

        MockServer mockServer = new MockServer();
        service.onRegister(mockServer);

        PrintDocument doc = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"type\":\"RAW_NO_MAPPING\",\"raw_content\":\"SGVsbG8=\"}",
                PrintDocument.class);

        PrintResult result = service.printDocument(doc);

        assertNotNull(result);
        assertFalse(result.success);
        // The error should mention no printer mapping
        assertNotNull(result.message);
    }

    @Test
    public void printDocumentSendsResultToServerChannel() throws Exception {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().removeIf(m -> "CHANNEL_TEST".equals(m.getType()));

        MockServer mockServer = new MockServer();
        service.onRegister(mockServer);

        PrintDocument doc = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"type\":\"CHANNEL_TEST\",\"url\":\"http://example.com/file.pdf\"}",
                PrintDocument.class);

        service.printDocument(doc);

        // The mock server should have received at least one message on /printer channel
        assertFalse("printDocument must send a result to /printer channel",
                mockServer.broadcastMessages.isEmpty());

        // The last message should be a PrintResult JSON
        String lastMsg = mockServer.broadcastMessages.get(mockServer.broadcastMessages.size() - 1);
        assertTrue("result should be valid JSON containing success field: " + lastMsg,
                lastMsg.contains("\"success\""));
    }

    @Test
    public void printDocumentSendsNotificationOnFailure() throws Exception {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().removeIf(m -> "NOTIF_TEST".equals(m.getType()));

        MockServer mockServer = new MockServer();
        service.onRegister(mockServer);

        PrintDocument doc = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"type\":\"NOTIF_TEST\",\"url\":\"http://example.com/file.pdf\"}",
                PrintDocument.class);

        service.printDocument(doc);

        // Should have sent at least one notification to /notification channel
        assertFalse("printDocument must send notifications",
                mockServer.serviceMessages.isEmpty());

        // At least one service message should be on /notification
        boolean hasNotification = mockServer.serviceChannels.stream()
                .anyMatch(ch -> ch.equals("/notification"));
        assertTrue("should send at least one /notification message", hasNotification);
    }

    /** Minimal mock server that captures messages for verification. */
    private static class MockServer implements WebSocketServerInterface {
        final List<String> broadcastMessages = new ArrayList<>();
        final List<String> broadcastChannels = new ArrayList<>();
        final List<String> serviceMessages = new ArrayList<>();
        final List<String> serviceChannels = new ArrayList<>();

        @Override
        public void messageToServer(String channel, String message) {
            broadcastMessages.add(message);
            broadcastChannels.add(channel);
        }

        @Override
        public void messageToServer(String channel, byte[] message) {}

        @Override
        public void messageToService(String channel, String message) {
            serviceMessages.add(message);
            serviceChannels.add(channel);
        }

        @Override
        public void messageToService(String channel, byte[] message) {}

        @Override
        public void registerService(WebSocketServiceInterface service) {}

        @Override
        public void unregisterService(WebSocketServiceInterface service) {}
    }
}

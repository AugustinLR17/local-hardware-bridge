package io.github.augustinlr17.localhardwarebridge.websocketservices;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServerInterface;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServiceInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.responses.PrintDocument;
import io.github.augustinlr17.localhardwarebridge.responses.PrintResult;
import io.github.augustinlr17.localhardwarebridge.services.ConfigService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.awt.print.PrinterException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link PrinterWebSocketService#printDocument(PrintDocument)} and its
 * private sub-method {@code searchPrinterForType(String)}.
 *
 * <p>These tests focus on the error / dispatch paths that do NOT require a real
 * PrintService to be installed on the test machine. Reflection is used to invoke
 * the private {@code searchPrinterForType} directly so each branch can be asserted.
 *
 * <p>ConfigService is a singleton backed by an {@code AtomicReference<Config>}.
 * We snapshot and restore the config around every test to avoid cross-test
 * contamination (especially because {@code autoAddUnknownType} mutates the config).
 */
public class PrinterWebSocketServicePrintDocumentTest {

    private PrinterWebSocketService service;
    private Method searchPrinterForType;
    private MockServer mockServer;

    private Config savedConfig;

    @Before
    public void setUp() throws Exception {
        // Snapshot the singleton config so each test starts clean and mutations
        // (e.g. autoAddUnknownType adding mappings) don't leak into other tests.
        ConfigService configService = ConfigService.getInstance();
        savedConfig = configService.getConfig();

        // Build a fresh Config with deterministic printer settings for each test.
        Config fresh = new Config();
        fresh.getPrinter().setEnabled(true);
        fresh.getPrinter().setAutoAddUnknownType(false);
        fresh.getPrinter().setFallbackToDefault(false);
        fresh.getPrinter().getMappings().clear();
        configService.getConfig().getPrinter().getMappings().clear();
        configService.getConfig().getPrinter().setAutoAddUnknownType(false);
        configService.getConfig().getPrinter().setFallbackToDefault(false);

        service = new PrinterWebSocketService();
        mockServer = new MockServer();
        service.onRegister(mockServer);

        searchPrinterForType = PrinterWebSocketService.class
                .getDeclaredMethod("searchPrinterForType", String.class);
        searchPrinterForType.setAccessible(true);
    }

    @After
    public void tearDown() {
        // Restore the original config state to avoid leaking test mappings.
        ConfigService configService = ConfigService.getInstance();
        configService.getConfig().getPrinter().getMappings().clear();
        configService.getConfig().getPrinter().setAutoAddUnknownType(false);
        configService.getConfig().getPrinter().setFallbackToDefault(false);
        if (savedConfig != null) {
            configService.getConfig().getPrinter().getMappings().addAll(savedConfig.getPrinter().getMappings());
            configService.getConfig().getPrinter().setAutoAddUnknownType(savedConfig.getPrinter().isAutoAddUnknownType());
            configService.getConfig().getPrinter().setFallbackToDefault(savedConfig.getPrinter().isFallbackToDefault());
        }
    }

    // -------------------------------------------------------------------------
    // searchPrinterForType (private, invoked via reflection)
    // -------------------------------------------------------------------------

    /**
     * Case 1: no mappings, no auto-add, no fallback -> PrinterException
     * "No printer mapping found for type".
     */
    @Test
    public void searchPrinterForType_noMappingsNoFallback_throwsNoMapping() throws Throwable {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().clear();

        try {
            searchPrinterForType.invoke(service, "UNMAPPED_TYPE_1");
            fail("expected PrinterException for unmapped type");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertNotNull("expected a cause", cause);
            assertTrue("expected PrinterException, got " + cause.getClass().getName(),
                    cause instanceof PrinterException);
            assertTrue("message should mention no printer mapping: " + cause.getMessage(),
                    cause.getMessage().contains("No printer mapping found for type"));
            assertTrue("message should include the type name: " + cause.getMessage(),
                    cause.getMessage().contains("UNMAPPED_TYPE_1"));
        }
    }

    /**
     * Case 2: autoAddUnknownType=true -> type is added to config, then still
     * throws because no printer is found on the system.
     */
    @Test
    public void searchPrinterForType_autoAddUnknownType_addsThenThrowsNoMapping() throws Throwable {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(true);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().clear();

        String type = "AUTO_ADD_TYPE_2";
        assertFalse("type should not be present before call",
                config.getPrinter().getMappings().stream().anyMatch(m -> type.equals(m.getType())));

        try {
            searchPrinterForType.invoke(service, type);
            fail("expected PrinterException after auto-add (no printer configured)");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertNotNull(cause);
            assertTrue("expected PrinterException, got " + cause.getClass().getName(),
                    cause instanceof PrinterException);
            // After auto-add, the mapping exists (with empty printer name) but no
            // printer matches on the system, so we either get "No printer mapping"
            // (fallback not enabled) — the auto-add only inserts the mapping, it does
            // not make the lookup succeed.
            String msg = cause.getMessage();
            assertTrue("unexpected message after auto-add: " + msg,
                    msg.contains("No printer mapping") || msg.contains("Printer not found"));
        }

        // The auto-add path should have inserted a mapping for the type.
        assertTrue("autoAddUnknownType should have added the type to the mappings",
                config.getPrinter().getMappings().stream().anyMatch(m -> type.equals(m.getType())));
    }

    /**
     * Case 3: fallbackToDefault=true, no default printer on system ->
     * PrinterException "No default printer found" (or success if a default
     * printer exists on the test machine; we handle both).
     */
    @Test
    public void searchPrinterForType_fallbackToDefault_noDefaultThrows() throws Throwable {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(true);
        config.getPrinter().getMappings().clear();

        try {
            Object result = searchPrinterForType.invoke(service, "FALLBACK_TYPE_3");
            // A default printer exists on this machine — accept the success.
            assertNotNull("if a default printer exists, result should be returned", result);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertNotNull(cause);
            assertTrue("expected PrinterException, got " + cause.getClass().getName(),
                    cause instanceof PrinterException);
            String msg = cause.getMessage();
            assertTrue("should mention default printer: " + msg,
                    msg.contains("No default printer") || msg.contains("No printer mapping"));
        }
    }

    /**
     * Case 4: a mapping exists but the printer name doesn't match any OS
     * printer -> PrinterException "Printer not found on system".
     */
    @Test
    public void searchPrinterForType_mappingExistsPrinterNotOnSystem_throws() throws Throwable {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().clear();
        config.getPrinter().getMappings().add(
                new Config.PrinterMapping("GHOST_TYPE_4", "NonExistentPrinterXYZ999", false, true, 0));

        try {
            searchPrinterForType.invoke(service, "GHOST_TYPE_4");
            fail("expected PrinterException for ghost printer");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertNotNull(cause);
            assertTrue("expected PrinterException, got " + cause.getClass().getName(),
                    cause instanceof PrinterException);
            assertTrue("should mention printer not found: " + cause.getMessage(),
                    cause.getMessage().contains("Printer not found on system"));
            assertTrue("should mention the printer name: " + cause.getMessage(),
                    cause.getMessage().contains("NonExistentPrinterXYZ999"));
        }
    }

    // -------------------------------------------------------------------------
    // printDocument (public, error/dispatch paths)
    // -------------------------------------------------------------------------

    /**
     * Case 5: Unknown file type (.txt) -> PrintResult success=false.
     *
     * <p>Because no printer mapping exists for the type, searchPrinterForType
     * throws before the type detection even runs; printDocument catches it and
     * returns a failure result. We assert the result is a failure.
     */
    @Test
    public void printDocument_unknownFileType_returnsFailureResult() throws Exception {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().removeIf(m -> "UNKNOWN_FILE_TYPE_TEST".equals(m.getType()));

        PrintDocument doc = new ObjectMapper().readValue(
                "{\"type\":\"UNKNOWN_FILE_TYPE_TEST\",\"url\":\"http://example.com/file.txt\"}",
                PrintDocument.class);

        PrintResult result = service.printDocument(doc);

        assertNotNull(result);
        assertFalse("print should fail for unknown type", result.getSuccess());
        assertNotNull("failure result should carry a message", result.getMessage());
    }

    /**
     * Case 6: rawContent set -> isRaw returns true -> printRaw attempted, but
     * no printer mapping exists -> PrintResult success=false.
     */
    @Test
    public void printDocument_rawContentNoMapping_returnsFailure() throws Exception {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().removeIf(m -> "RAW_NO_MAPPING".equals(m.getType()));

        PrintDocument doc = new ObjectMapper().readValue(
                "{\"type\":\"RAW_NO_MAPPING\",\"raw_content\":\"SGVsbG8gV29ybGQ=\"}",
                PrintDocument.class);

        PrintResult result = service.printDocument(doc);

        assertNotNull(result);
        assertFalse("raw print with no mapping must fail", result.getSuccess());
        assertNotNull(result.getMessage());
    }

    /**
     * Case 7: null type, null url, null fileContent -> handled gracefully,
     * returns a failure PrintResult (no NPE leaks out).
     */
    @Test
    public void printDocument_nullTypeNullUrlNullContent_handledGracefully() throws Exception {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().clear();

        PrintDocument doc = new ObjectMapper().readValue("{}", PrintDocument.class);
        // type, url, fileContent all null by default

        PrintResult result = service.printDocument(doc);

        assertNotNull("printDocument must not return null", result);
        assertFalse("print must fail for all-null document", result.getSuccess());
    }

    /**
     * Case 8: messageToService with invalid JSON -> caught internally, no
     * exception thrown to caller.
     */
    @Test
    public void messageToService_invalidJson_doesNotThrow() {
        // messageToService parses JSON; invalid JSON should be caught and logged.
        try {
            service.messageToService("this is not valid json {{{");
        } catch (Exception e) {
            fail("messageToService must not propagate exceptions for invalid JSON: " + e);
        }
    }

    // -------------------------------------------------------------------------
    // Mock WebSocketServerInterface
    // -------------------------------------------------------------------------

    /**
     * Minimal recording implementation of {@link WebSocketServerInterface}.
     * Captures every String message routed to/from the service so tests can
     * assert that printDocument broadcasts a result and notifications.
     */
    static final class MockServer implements WebSocketServerInterface {
        final List<String> serviceMessages = new ArrayList<>();
        final List<String> serviceChannels = new ArrayList<>();
        final List<String> serverMessages = new ArrayList<>();
        final List<String> serverChannels = new ArrayList<>();

        @Override
        public void messageToServer(String channel, String message) {
            serverChannels.add(channel);
            serverMessages.add(message);
        }

        @Override
        public void messageToServer(String channel, byte[] message) { /* no-op */ }

        @Override
        public void messageToService(String channel, String message) {
            serviceChannels.add(channel);
            serviceMessages.add(channel + ":" + message);
        }

        @Override
        public void messageToService(String channel, byte[] message) { /* no-op */ }

        @Override
        public void registerService(WebSocketServiceInterface service) { /* no-op */ }

        @Override
        public void unregisterService(WebSocketServiceInterface service) { /* no-op */ }
    }
}
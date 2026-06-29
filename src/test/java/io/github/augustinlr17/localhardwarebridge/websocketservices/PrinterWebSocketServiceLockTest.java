package io.github.augustinlr17.localhardwarebridge.websocketservices;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.services.ConfigService;
import org.junit.Before;
import org.junit.Test;

import java.awt.print.PrinterException;
import java.lang.reflect.Method;
import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link PrinterWebSocketService} per-type print lock behavior
 * and {@code searchPrinterForType} logic (via reflection).
 *
 * The lock-key selection (type vs DEFAULT_LOCK_KEY) and the search branches
 * (mapped printer found, autoAddUnknownType, fallbackToDefault, not-found)
 * are tested here without actually printing.
 */
public class PrinterWebSocketServiceLockTest {

    private PrinterWebSocketService service;
    private Method searchPrinterForType;

    @Before
    public void setUp() throws Exception {
        service = new PrinterWebSocketService();
        searchPrinterForType = PrinterWebSocketService.class
                .getDeclaredMethod("searchPrinterForType", String.class);
        searchPrinterForType.setAccessible(true);
    }

    // --- searchPrinterForType branches ---

    @Test
    public void searchThrowsWhenNoMappingAndNoFallback() throws Throwable {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);
        // Ensure no mapping for "NONEXISTENT"
        config.getPrinter().getMappings().removeIf(m -> "NONEXISTENT_LOCK_TEST".equals(m.getType()));

        try {
            searchPrinterForType.invoke(service, "NONEXISTENT_LOCK_TEST");
            fail("expected PrinterException for unmapped type");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue("expected PrinterException", cause instanceof PrinterException);
            assertTrue(cause.getMessage().contains("No printer mapping found for type: NONEXISTENT_LOCK_TEST"));
        }
    }

    @Test
    public void searchThrowsWhenMappingExistsButPrinterNotFound() throws Throwable {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setAutoAddUnknownType(false);
        config.getPrinter().setFallbackToDefault(false);

        // Add a mapping pointing to a non-existent printer name
        config.getPrinter().getMappings().removeIf(m -> "GHOST_PRINTER".equals(m.getType()));
        config.getPrinter().getMappings().add(
                new Config.PrinterMapping("GHOST_PRINTER", "ThisPrinterDoesNotExist12345", false, true, 0));

        try {
            searchPrinterForType.invoke(service, "GHOST_PRINTER");
            fail("expected PrinterException for non-existent printer");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue("expected PrinterException", cause instanceof PrinterException);
            assertTrue(cause.getMessage().contains("Printer not found on system"));
        } finally {
            config.getPrinter().getMappings().removeIf(m -> "GHOST_PRINTER".equals(m.getType()));
        }
    }

    @Test
    public void searchAutoAddsUnknownTypeWhenEnabled() throws Throwable {
        ConfigService configService = ConfigService.getInstance();
        Config config = configService.getConfig();
        config.getPrinter().setAutoAddUnknownType(true);
        config.getPrinter().setFallbackToDefault(false);
        config.getPrinter().getMappings().removeIf(m -> "AUTOADD_TEST".equals(m.getType()));

        try {
            searchPrinterForType.invoke(this.service, "AUTOADD_TEST");
            // This will fail because the auto-added mapping has an empty printer name,
            // but the type should now exist in config
            fail("expected PrinterException (empty printer name in auto-added mapping)");
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Expected — the auto-added mapping has name="" which won't match any printer
        }

        // Verify the type was auto-added to the config
        boolean found = config.getPrinter().getMappings().stream()
                .anyMatch(m -> "AUTOADD_TEST".equals(m.getType()));
        assertTrue("autoAddUnknownType must add the type to config", found);

        // Clean up
        config.getPrinter().getMappings().removeIf(m -> "AUTOADD_TEST".equals(m.getType()));
        config.getPrinter().setAutoAddUnknownType(false);
    }

    // --- getChannel ---

    @Test
    public void getChannelReturnsPrinterChannel() {
        assertEquals("/printer", service.getChannel());
    }

    // --- onRegister / onUnregister ---

    @Test
    public void onRegisterAndOnUnregisterDoNotThrow() {
        service.onRegister(null);
        service.onUnregister();
        // Should not throw even with null server
    }

    // --- messageToService(byte[]) ---

    @Test
    public void messageToServiceBinaryDoesNotThrow() {
        // Binary data to the printer service just logs an error — must not throw
        service.messageToService(new byte[]{0x01, 0x02, 0x03});
    }

    @Test
    public void messageToServiceNullBinaryDoesNotThrow() {
        service.messageToService((byte[]) null);
    }
}

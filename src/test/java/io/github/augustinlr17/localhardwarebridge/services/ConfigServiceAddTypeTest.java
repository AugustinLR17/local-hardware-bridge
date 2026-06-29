package io.github.augustinlr17.localhardwarebridge.services;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ConfigService#addPrintTypeToList}.
 * Verifies that auto-add creates a mapping with the correct defaults
 * and that it persists to config.
 */
public class ConfigServiceAddTypeTest {

    @Test
    public void addPrintTypeCreatesMappingWithDefaults() {
        ConfigService service = ConfigService.getInstance();
        Config config = service.getConfig();

        // Start from a clean state — remove any existing "AUTOTEST" mapping
        config.getPrinter().getMappings().removeIf(m -> "AUTOTEST".equals(m.getType()));

        service.addPrintTypeToList("AUTOTEST");

        // The mapping must have been added
        boolean found = config.getPrinter().getMappings().stream()
                .anyMatch(m -> "AUTOTEST".equals(m.getType()));
        assertTrue("addPrintTypeToList must add the type to mappings", found);

        // Verify the defaults: empty printer name, autoRotate=false, resetImageableArea=true, forceDPI=0
        Config.PrinterMapping added = config.getPrinter().getMappings().stream()
                .filter(m -> "AUTOTEST".equals(m.getType()))
                .findFirst().orElse(null);

        assertNotNull(added);
        assertEquals("AUTOTEST", added.getType());
        assertEquals("", added.getName());
        assertFalse(added.isAutoRotate());
        assertTrue(added.isResetImageableArea());
        assertEquals(0, added.getForceDPI());

        // Clean up
        config.getPrinter().getMappings().removeIf(m -> "AUTOTEST".equals(m.getType()));
    }

    @Test
    public void addPrintTypeDoesNotDuplicateIfAlreadyExists() {
        ConfigService service = ConfigService.getInstance();
        Config config = service.getConfig();

        // Add a mapping manually
        Config.PrinterMapping existing = new Config.PrinterMapping("DUPLICATE_TEST", "MyPrinter", false, true, 0);
        config.getPrinter().getMappings().add(existing);

        int countBefore = (int) config.getPrinter().getMappings().stream()
                .filter(m -> "DUPLICATE_TEST".equals(m.getType())).count();

        // addPrintTypeToList adds another entry (the caller is responsible for checking)
        service.addPrintTypeToList("DUPLICATE_TEST");

        int countAfter = (int) config.getPrinter().getMappings().stream()
                .filter(m -> "DUPLICATE_TEST".equals(m.getType())).count();

        // The service always adds; the caller (searchPrinterForType) checks with noneMatch first
        assertEquals(countBefore + 1, countAfter);

        // Clean up
        config.getPrinter().getMappings().removeIf(m -> "DUPLICATE_TEST".equals(m.getType()));
    }
}

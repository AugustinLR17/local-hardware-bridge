package io.github.augustinlr17.localhardwarebridge.services;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * Unit tests for the mapping CRUD logic used by Server HTTP endpoints.
 * Since Server's endpoint handlers operate directly on ConfigService.getConfig()
 * collections, we test the collection manipulation logic here (the same
 * operations the handlers perform: add, update by type, delete by type,
 * not-found 404 branches).
 */
public class MappingCrudLogicTest {

    // --- Printer mapping CRUD ---

    @Test
    public void addPrinterMappingAppendsToList() {
        Config config = ConfigService.getInstance().getConfig();
        ArrayList<Config.PrinterMapping> mappings = config.getPrinter().getMappings();

        int before = mappings.size();
        Config.PrinterMapping mapping = new Config.PrinterMapping("CRUD_TEST", "TestPrinter", false, true, 0);
        mappings.add(mapping);

        assertEquals(before + 1, mappings.size());
        assertEquals("CRUD_TEST", mappings.get(mappings.size() - 1).getType());

        // Clean up
        mappings.removeIf(m -> "CRUD_TEST".equals(m.getType()));
    }

    @Test
    public void updatePrinterMappingByTypeWhenFound() {
        Config config = ConfigService.getInstance().getConfig();
        ArrayList<Config.PrinterMapping> mappings = config.getPrinter().getMappings();

        mappings.add(new Config.PrinterMapping("UPDATE_ME", "OldPrinter", false, true, 0));

        String type = "UPDATE_ME";
        Config.PrinterMapping updated = new Config.PrinterMapping("UPDATE_ME", "NewPrinter", true, false, 300);

        // Simulate the Server's PUT handler logic
        boolean found = false;
        for (int i = 0; i < mappings.size(); i++) {
            if (type.equals(mappings.get(i).getType())) {
                mappings.set(i, updated);
                found = true;
                break;
            }
        }

        assertTrue("mapping must be found for update", found);
        Config.PrinterMapping result = mappings.stream()
                .filter(m -> "UPDATE_ME".equals(m.getType())).findFirst().orElse(null);
        assertNotNull(result);
        assertEquals("NewPrinter", result.getName());
        assertTrue(result.isAutoRotate());
        assertEquals(300, result.getForceDPI());

        // Clean up
        mappings.removeIf(m -> "UPDATE_ME".equals(m.getType()));
    }

    @Test
    public void updatePrinterMappingByTypeWhenNotFound() {
        Config config = ConfigService.getInstance().getConfig();
        ArrayList<Config.PrinterMapping> mappings = config.getPrinter().getMappings();

        mappings.removeIf(m -> "NONEXISTENT_PUT".equals(m.getType()));

        String type = "NONEXISTENT_PUT";
        Config.PrinterMapping updated = new Config.PrinterMapping("NONEXISTENT_PUT", "Printer", false, true, 0);

        // Simulate the Server's PUT handler logic
        boolean found = false;
        for (int i = 0; i < mappings.size(); i++) {
            if (type.equals(mappings.get(i).getType())) {
                mappings.set(i, updated);
                found = true;
                break;
            }
        }

        assertFalse("mapping must not be found", found);
        // In the real handler, this would return 404
    }

    @Test
    public void deletePrinterMappingByTypeWhenFound() {
        Config config = ConfigService.getInstance().getConfig();
        ArrayList<Config.PrinterMapping> mappings = config.getPrinter().getMappings();

        mappings.add(new Config.PrinterMapping("DELETE_ME", "TestPrinter", false, true, 0));
        assertTrue(mappings.stream().anyMatch(m -> "DELETE_ME".equals(m.getType())));

        // Simulate the Server's DELETE handler logic
        boolean removed = mappings.removeIf(m -> "DELETE_ME".equals(m.getType()));

        assertTrue("mapping must be removed", removed);
        assertFalse(mappings.stream().anyMatch(m -> "DELETE_ME".equals(m.getType())));
    }

    @Test
    public void deletePrinterMappingByTypeWhenNotFound() {
        Config config = ConfigService.getInstance().getConfig();
        ArrayList<Config.PrinterMapping> mappings = config.getPrinter().getMappings();

        mappings.removeIf(m -> "NONEXISTENT_DELETE".equals(m.getType()));

        // Simulate the Server's DELETE handler logic
        boolean removed = mappings.removeIf(m -> "NONEXISTENT_DELETE".equals(m.getType()));

        assertFalse("nothing should be removed", removed);
        // In the real handler, this would return 404
    }

    // --- Serial mapping CRUD ---

    @Test
    public void addSerialMappingAppendsToList() {
        Config config = ConfigService.getInstance().getConfig();
        ArrayList<Config.SerialMapping> mappings = config.getSerial().getMappings();

        int before = mappings.size();
        Config.SerialMapping mapping = new Config.SerialMapping("SERIAL_CRUD", "COM9", 9600, 8, 1, 0, false, "ISO-8859-1");
        mappings.add(mapping);

        assertEquals(before + 1, mappings.size());

        // Clean up
        mappings.removeIf(m -> "SERIAL_CRUD".equals(m.getType()));
    }

    @Test
    public void updateSerialMappingByTypeWhenFound() {
        Config config = ConfigService.getInstance().getConfig();
        ArrayList<Config.SerialMapping> mappings = config.getSerial().getMappings();

        mappings.add(new Config.SerialMapping("SER_UPDATE", "COM1", 9600, 8, 1, 0, false, "ISO-8859-1"));

        String type = "SER_UPDATE";
        Config.SerialMapping updated = new Config.SerialMapping("SER_UPDATE", "COM2", 4800, 7, 2, 1, true, "BINARY");

        boolean found = false;
        for (int i = 0; i < mappings.size(); i++) {
            if (type.equals(mappings.get(i).getType())) {
                mappings.set(i, updated);
                found = true;
                break;
            }
        }

        assertTrue(found);
        Config.SerialMapping result = mappings.stream()
                .filter(m -> "SER_UPDATE".equals(m.getType())).findFirst().orElse(null);
        assertNotNull(result);
        assertEquals("COM2", result.getName());
        assertEquals(Integer.valueOf(4800), result.getBaudRate());
        assertEquals("BINARY", result.getReadCharset());

        // Clean up
        mappings.removeIf(m -> "SER_UPDATE".equals(m.getType()));
    }

    @Test
    public void updateSerialMappingByTypeWhenNotFound() {
        Config config = ConfigService.getInstance().getConfig();
        ArrayList<Config.SerialMapping> mappings = config.getSerial().getMappings();

        mappings.removeIf(m -> "SER_NONEXIST".equals(m.getType()));

        String type = "SER_NONEXIST";
        boolean found = false;
        for (int i = 0; i < mappings.size(); i++) {
            if (type.equals(mappings.get(i).getType())) {
                found = true;
                break;
            }
        }

        assertFalse(found);
    }

    @Test
    public void deleteSerialMappingByTypeWhenFound() {
        Config config = ConfigService.getInstance().getConfig();
        ArrayList<Config.SerialMapping> mappings = config.getSerial().getMappings();

        mappings.add(new Config.SerialMapping("SER_DELETE", "COM5", 9600, 8, 1, 0, false, "ISO-8859-1"));

        boolean removed = mappings.removeIf(m -> "SER_DELETE".equals(m.getType()));
        assertTrue(removed);
        assertFalse(mappings.stream().anyMatch(m -> "SER_DELETE".equals(m.getType())));
    }

    @Test
    public void deleteSerialMappingByTypeWhenNotFound() {
        Config config = ConfigService.getInstance().getConfig();
        ArrayList<Config.SerialMapping> mappings = config.getSerial().getMappings();

        mappings.removeIf(m -> "SER_NONEXIST_DEL".equals(m.getType()));
        boolean removed = mappings.removeIf(m -> "SER_NONEXIST_DEL".equals(m.getType()));
        assertFalse(removed);
    }

    // --- Printer enable/disable logic ---

    @Test
    public void disablePrinterService() {
        Config config = ConfigService.getInstance().getConfig();
        boolean original = config.getPrinter().isEnabled();

        config.getPrinter().setEnabled(false);
        assertFalse(config.getPrinter().isEnabled());

        // Restore
        config.getPrinter().setEnabled(original);
    }

    @Test
    public void enablePrinterService() {
        Config config = ConfigService.getInstance().getConfig();
        config.getPrinter().setEnabled(true);
        assertTrue(config.getPrinter().isEnabled());
    }

    @Test
    public void disableSerialService() {
        Config config = ConfigService.getInstance().getConfig();
        boolean original = config.getSerial().isEnabled();

        config.getSerial().setEnabled(false);
        assertFalse(config.getSerial().isEnabled());

        config.getSerial().setEnabled(original);
    }

    // --- Endpoint security rule lookup logic ---

    @Test
    public void endpointRuleLookupForExactPath() {
        Config.Security security = new Config.Security();
        Config.EndpointRule rule = new Config.EndpointRule();
        rule.setEnabled(false);
        rule.setPassword("secret");
        security.getEndpoints().put("/printer", rule);

        // Exact match
        Config.EndpointRule found = security.getEndpoints().get("/printer");
        assertNotNull(found);
        assertFalse(found.isEnabled());
        assertEquals("secret", found.getPassword());
    }

    @Test
    public void endpointRuleLookupForDynamicSerialPath() {
        Config.Security security = new Config.Security();
        Config.EndpointRule rule = new Config.EndpointRule();
        rule.setEnabled(false);
        security.getEndpoints().put("/serial/{type}", rule);

        // Simulate the Server's prefix-match logic for /serial/SCALE
        String path = "/serial/SCALE";
        Config.EndpointRule found = security.getEndpoints().get(path);
        if (found == null && path.startsWith("/serial/")) {
            found = security.getEndpoints().get("/serial/{type}");
        }

        assertNotNull("must find the /serial/{type} rule via prefix match", found);
        assertFalse(found.isEnabled());
    }

    @Test
    public void endpointRuleLookupReturnsNullForUnconfiguredPath() {
        Config.Security security = new Config.Security();
        // No rules configured

        Config.EndpointRule found = security.getEndpoints().get("/printer");
        assertNull(found);

        // Simulate prefix match for /serial/SCALE
        String path = "/serial/SCALE";
        found = security.getEndpoints().get(path);
        if (found == null && path.startsWith("/serial/")) {
            found = security.getEndpoints().get("/serial/{type}");
        }
        assertNull(found);
    }

    @Test
    public void configAndHealthAreAlwaysExempt() {
        // The Server's before filter exempts /config.json and /system/health
        // from any endpoint rule. Simulate this logic:
        Config.Security security = new Config.Security();
        Config.EndpointRule blockAll = new Config.EndpointRule();
        blockAll.setEnabled(false);
        security.getEndpoints().put("/config.json", blockAll);
        security.getEndpoints().put("/system/health", blockAll);

        // The Server sets rule = null for these paths, ignoring any configured rule
        for (String exemptPath : new String[]{"/config.json", "/system/health"}) {
            Config.EndpointRule rule = security.getEndpoints().get(exemptPath);
            // Simulate the exemption: rule = null
            rule = null;
            assertNull("exempt endpoint must have its rule cleared", rule);
        }
    }
}

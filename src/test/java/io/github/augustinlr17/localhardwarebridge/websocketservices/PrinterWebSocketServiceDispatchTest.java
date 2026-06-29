package io.github.augustinlr17.localhardwarebridge.websocketservices;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PrinterWebSocketServiceDispatchTest {

    private PrinterWebSocketService service;

    @Before
    public void setUp() {
        service = new PrinterWebSocketService();
    }

    @Test
    public void getChannelReturnsPrinterChannel() {
        assertEquals("/printer", service.getChannel());
    }

    @Test
    public void onRegisterNullDoesNotThrow() {
        service.onRegister(null);
    }

    @Test
    public void onUnregisterDoesNotThrow() {
        service.onUnregister();
    }

    @Test
    public void messageToServiceBinaryDoesNotThrow() {
        service.messageToService(new byte[]{0x01, 0x02, 0x03});
    }

    @Test
    public void messageToServiceInvalidJsonDoesNotThrow() {
        service.messageToService("not valid json {{{");
    }

    @Test
    public void messageToServiceNullStringDoesNotThrow() {
        service.messageToService((String) null);
    }

    @Test
    public void printLocksInitiallyEmpty() throws Exception {
        Field field = PrinterWebSocketService.class.getDeclaredField("printLocks");
        field.setAccessible(true);
        ConcurrentHashMap<String, Object> map =
                (ConcurrentHashMap<String, Object>) field.get(service);
        assertTrue("printLocks should be empty on a fresh instance", map.isEmpty());
    }

    @Test
    public void defaultLockKeyConstantIsCorrect() throws Exception {
        Field field = PrinterWebSocketService.class.getDeclaredField("DEFAULT_LOCK_KEY");
        field.setAccessible(true);
        String value = (String) field.get(null);
        assertEquals("__default__", value);
    }

    @Test
    public void messageToServiceValidJsonNoServerDoesNotThrow() {
        service.onRegister(null);
        service.messageToService("{\"type\":\"TEST\",\"url\":\"http://example.com/file.pdf\"}");
    }
}

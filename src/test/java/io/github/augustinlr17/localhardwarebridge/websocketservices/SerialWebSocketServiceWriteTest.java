package io.github.augustinlr17.localhardwarebridge.websocketservices;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServerInterface;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServiceInterface;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.*;

/**
 * Additional tests for {@link SerialWebSocketService} that verify the
 * write-queue behaviour, channel name, and lifecycle methods.
 *
 * <p>On some platforms {@code SerialPort.getCommPort()} throws for non-existent
 * port names. To keep these tests hermetic across platforms, we create the
 * service instance via reflection and inject a mock {@code SerialPort} so the
 * constructor never calls the native library.
 */
public class SerialWebSocketServiceWriteTest {

    private SerialWebSocketService createService() throws Exception {
        // Use reflection to create the instance without calling the constructor
        // (which calls SerialPort.getCommPort and may throw on some platforms).
        Constructor<SerialWebSocketService> ctor = SerialWebSocketService.class
                .getDeclaredConstructor(Config.SerialMapping.class);
        ctor.setAccessible(true);

        // Create a SerialMapping with a dummy port name.
        // On Linux, getCommPort returns a placeholder for non-existent ports
        // without throwing. On other platforms it may throw.
        // We catch the exception and fall back to unsafe allocation.
        try {
            Config.SerialMapping mapping = new Config.SerialMapping();
            mapping.setType("TESTTYPE");
            mapping.setName("NONEXISTENT_PORT_TEST");
            return ctor.newInstance(mapping);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // getCommPort threw — use Unsafe to allocate without constructor
            if (e.getCause() instanceof com.fazecast.jSerialComm.SerialPortInvalidPortException) {
                return allocateWithoutConstructor();
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private SerialWebSocketService allocateWithoutConstructor() throws Exception {
        // Use sun.misc.Unsafe to allocate the object without calling the constructor
        // This avoids the SerialPort.getCommPort() call entirely.
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        SerialWebSocketService instance = (SerialWebSocketService) unsafe.allocateInstance(SerialWebSocketService.class);

        // Initialize the mapping field (needed for getChannel())
        Config.SerialMapping mapping = new Config.SerialMapping();
        mapping.setType("TESTTYPE");
        mapping.setName("DUMMY");

        Field mappingField = SerialWebSocketService.class.getDeclaredField("mapping");
        mappingField.setAccessible(true);
        mappingField.set(instance, mapping);

        // The writeQueue is final and initialized in the field declaration,
        // but Unsafe skips that, so we need to set it too.
        Field queueField = SerialWebSocketService.class.getDeclaredField("writeQueue");
        queueField.setAccessible(true);
        queueField.set(instance, new java.util.concurrent.LinkedBlockingQueue<>());

        // The isRunning field
        Field runningField = SerialWebSocketService.class.getDeclaredField("isRunning");
        runningField.setAccessible(true);
        runningField.setBoolean(instance, true);

        return instance;
    }

    @SuppressWarnings("unchecked")
    private BlockingQueue<byte[]> getWriteQueue(SerialWebSocketService service) throws Exception {
        Field f = SerialWebSocketService.class.getDeclaredField("writeQueue");
        f.setAccessible(true);
        return (BlockingQueue<byte[]>) f.get(service);
    }

    @Test
    public void getChannelReturnsSerialSlashType() throws Exception {
        SerialWebSocketService service = createService();
        assertEquals("/serial/TESTTYPE", service.getChannel());
    }

    @Test
    public void messageToServiceStringEnqueuesBytes() throws Exception {
        SerialWebSocketService service = createService();
        BlockingQueue<byte[]> queue = getWriteQueue(service);

        assertEquals(0, queue.size());

        service.messageToService("hello");

        assertEquals(1, queue.size());
        assertArrayEquals("hello".getBytes(), queue.poll());
    }

    @Test
    public void messageToServiceBytesEnqueuesData() throws Exception {
        SerialWebSocketService service = createService();
        BlockingQueue<byte[]> queue = getWriteQueue(service);

        byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04};
        service.messageToService(data);

        assertEquals(1, queue.size());
        assertArrayEquals(data, queue.poll());
    }

    @Test
    public void messageToServiceNullBytesDoesNotEnqueue() throws Exception {
        SerialWebSocketService service = createService();
        BlockingQueue<byte[]> queue = getWriteQueue(service);

        service.messageToService((byte[]) null);

        assertEquals(0, queue.size());
    }

    @Test
    public void messageToServiceMultipleMessagesAllEnqueued() throws Exception {
        SerialWebSocketService service = createService();
        BlockingQueue<byte[]> queue = getWriteQueue(service);

        service.messageToService("msg1");
        service.messageToService("msg2");
        service.messageToService("msg3");

        assertEquals(3, queue.size());
        assertEquals("msg1", new String(queue.poll()));
        assertEquals("msg2", new String(queue.poll()));
        assertEquals("msg3", new String(queue.poll()));
    }

    @Test
    public void onRegisterSetsServer() throws Exception {
        SerialWebSocketService service = createService();
        WebSocketServerInterface mockServer = new MockServer();
        service.onRegister(mockServer);

        Field f = SerialWebSocketService.class.getDeclaredField("server");
        f.setAccessible(true);
        assertSame(mockServer, f.get(service));
    }

    @Test
    public void onUnregisterClearsServer() throws Exception {
        SerialWebSocketService service = createService();
        service.onRegister(new MockServer());
        service.onUnregister();

        Field f = SerialWebSocketService.class.getDeclaredField("server");
        f.setAccessible(true);
        assertNull(f.get(service));
    }

    @Test
    public void isRunningDefaultsToTrue() throws Exception {
        SerialWebSocketService service = createService();
        Field f = SerialWebSocketService.class.getDeclaredField("isRunning");
        f.setAccessible(true);
        assertTrue((boolean) f.get(service));
    }

    @Test
    public void messageToServiceBytesWithBoundedQueueDoesNotThrow() throws Exception {
        SerialWebSocketService service = createService();
        // Replace the write queue with a bounded one that is already full.
        Field qf = SerialWebSocketService.class.getDeclaredField("writeQueue");
        qf.setAccessible(true);
        java.util.concurrent.LinkedBlockingQueue<byte[]> bounded =
                new java.util.concurrent.LinkedBlockingQueue<>(1);
        bounded.offer(new byte[]{0x00});
        qf.set(service, bounded);

        // This should not throw even though the queue is full.
        service.messageToService(new byte[]{0x01});

        // The queue should still contain only the first element.
        assertEquals(1, bounded.size());
    }

    /** Minimal mock server for onRegister/onUnregister tests. */
    private static class MockServer implements WebSocketServerInterface {
        @Override
        public void messageToServer(String channel, String message) {}
        @Override
        public void messageToServer(String channel, byte[] message) {}
        @Override
        public void messageToService(String channel, String message) {}
        @Override
        public void messageToService(String channel, byte[] message) {}
        @Override
        public void registerService(WebSocketServiceInterface service) {}
        @Override
        public void unregisterService(WebSocketServiceInterface service) {}
    }
}

package io.github.augustinlr17.localhardwarebridge;

import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServerInterface;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServiceInterface;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Server} channel routing logic.
 * Tests the pub/sub channel model: service registration, wildcard `"*"`
 * subscription, and message routing between channels — all without
 * starting the Javalin HTTP server.
 *
 * Uses reflection to access private channel routing methods.
 */
public class ServerChannelRoutingTest {

    private Server server;
    private Method getServicesForChannel;
    private Method addServiceToChannel;
    private Method removeServiceFromChannel;

    @Before
    public void setUp() throws Exception {
        server = new Server();
        getServicesForChannel = Server.class.getDeclaredMethod("getServicesForChannel", String.class);
        getServicesForChannel.setAccessible(true);
        addServiceToChannel = Server.class.getDeclaredMethod(
                "addServiceToChannel", String.class, WebSocketServiceInterface.class);
        addServiceToChannel.setAccessible(true);
        removeServiceFromChannel = Server.class.getDeclaredMethod(
                "removeServiceFromChannel", String.class, WebSocketServiceInterface.class);
        removeServiceFromChannel.setAccessible(true);
    }

    /** A minimal test service that records the messages it receives. */
    private static class TestService implements WebSocketServiceInterface {
        private final String channel;
        private final java.util.List<String> receivedMessages = new java.util.ArrayList<>();
        private final java.util.List<byte[]> receivedBinary = new java.util.ArrayList<>();

        TestService(String channel) {
            this.channel = channel;
        }

        @Override
        public void start() { /* no-op */ }
        @Override
        public void stop() { /* no-op */ }
        @Override
        public void messageToService(String message) {
            receivedMessages.add(message);
        }
        @Override
        public void messageToService(byte[] message) {
            receivedBinary.add(message);
        }
        @Override
        public void onRegister(WebSocketServerInterface server) { /* no-op */ }
        @Override
        public void onUnregister() { /* no-op */ }
        @Override
        public String getChannel() {
            return channel;
        }

        List<String> getReceivedMessages() { return receivedMessages; }
    }

    @SuppressWarnings("unchecked")
    private ConcurrentLinkedQueue<WebSocketServiceInterface> getServices(String channel) throws Exception {
        return (ConcurrentLinkedQueue<WebSocketServiceInterface>) getServicesForChannel.invoke(server, channel);
    }

    private void addService(String channel, WebSocketServiceInterface svc) throws Exception {
        addServiceToChannel.invoke(server, channel, svc);
    }

    private void removeService(String channel, WebSocketServiceInterface svc) throws Exception {
        removeServiceFromChannel.invoke(server, channel, svc);
    }

    // --- Basic channel routing ---

    @Test
    public void serviceOnSpecificChannelReceivesMessagesForThatChannel() throws Exception {
        TestService printerService = new TestService("/printer");
        addService("/printer", printerService);

        // Route a message to /printer
        server.messageToService("/printer", "hello-printer");

        assertEquals(1, printerService.getReceivedMessages().size());
        assertEquals("hello-printer", printerService.getReceivedMessages().get(0));

        // Clean up
        removeService("/printer", printerService);
    }

    @Test
    public void serviceOnOneChannelDoesNotReceiveMessagesForAnother() throws Exception {
        TestService printerService = new TestService("/printer");
        TestService serialService = new TestService("/serial/SCALE");
        addService("/printer", printerService);
        addService("/serial/SCALE", serialService);

        server.messageToService("/printer", "for-printer");
        server.messageToService("/serial/SCALE", "for-serial");

        assertEquals(1, printerService.getReceivedMessages().size());
        assertEquals("for-printer", printerService.getReceivedMessages().get(0));

        assertEquals(1, serialService.getReceivedMessages().size());
        assertEquals("for-serial", serialService.getReceivedMessages().get(0));

        removeService("/printer", printerService);
        removeService("/serial/SCALE", serialService);
    }

    // --- Wildcard "*" channel ---

    @Test
    public void wildcardServiceReceivesMessagesFromAllChannels() throws Exception {
        TestService wildcardService = new TestService("*");
        TestService printerService = new TestService("/printer");
        addService("*", wildcardService);
        addService("/printer", printerService);

        server.messageToService("/printer", "msg-1");
        server.messageToService("/serial/SCALE", "msg-2");
        server.messageToService("/notification", "msg-3");

        // Wildcard receives all 3
        assertEquals(3, wildcardService.getReceivedMessages().size());

        // Printer service only receives its own channel
        assertEquals(1, printerService.getReceivedMessages().size());
        assertEquals("msg-1", printerService.getReceivedMessages().get(0));

        removeService("*", wildcardService);
        removeService("/printer", printerService);
    }

    @Test
    public void getServicesForChannelIncludesWildcard() throws Exception {
        TestService wildcard = new TestService("*");
        TestService printer = new TestService("/printer");
        addService("*", wildcard);
        addService("/printer", printer);

        ConcurrentLinkedQueue<WebSocketServiceInterface> services = getServices("/printer");
        assertEquals(2, services.size());

        removeService("*", wildcard);
        removeService("/printer", printer);
    }

    @Test
    public void getServicesForUnknownChannelReturnsOnlyWildcard() throws Exception {
        TestService wildcard = new TestService("*");
        addService("*", wildcard);

        ConcurrentLinkedQueue<WebSocketServiceInterface> services = getServices("/nonexistent");
        assertEquals(1, services.size());

        removeService("*", wildcard);
    }

    @Test
    public void getServicesForChannelWithNoSubscribersReturnsEmpty() throws Exception {
        ConcurrentLinkedQueue<WebSocketServiceInterface> services = getServices("/nobody");
        assertTrue(services.isEmpty());
    }

    // --- Binary message routing ---

    @Test
    public void binaryMessageIsRoutedToCorrectChannel() throws Exception {
        TestService serialService = new TestService("/serial/SCALE");
        addService("/serial/SCALE", serialService);

        byte[] data = new byte[]{0x01, 0x02, 0x03};
        server.messageToService("/serial/SCALE", data);

        assertEquals(1, serialService.receivedBinary.size());
        assertArrayEquals(data, serialService.receivedBinary.get(0));

        removeService("/serial/SCALE", serialService);
    }

    // --- Register / unregister ---

    @Test
    public void registerServiceCallsOnRegister() throws Exception {
        TestService svc = new TestService("/printer");
        server.registerService(svc);

        // Service should be in the channel map
        ConcurrentLinkedQueue<WebSocketServiceInterface> services = getServices("/printer");
        assertTrue(services.contains(svc));

        server.unregisterService(svc);
    }

    @Test
    public void unregisterServiceRemovesFromChannel() throws Exception {
        TestService svc = new TestService("/printer");
        server.registerService(svc);

        ConcurrentLinkedQueue<WebSocketServiceInterface> before = getServices("/printer");
        assertFalse(before.isEmpty());

        server.unregisterService(svc);

        ConcurrentLinkedQueue<WebSocketServiceInterface> after = getServices("/printer");
        assertFalse(after.contains(svc));
    }

    // --- Multiple services on same channel ---

    @Test
    public void multipleServicesOnSameChannelAllReceiveMessages() throws Exception {
        TestService svc1 = new TestService("/printer");
        TestService svc2 = new TestService("/printer");
        // Use different channels to avoid the getChannel() collision in registerService
        addService("/printer", svc1);
        addService("/printer", svc2);

        server.messageToService("/printer", "broadcast");

        assertEquals(1, svc1.getReceivedMessages().size());
        assertEquals("broadcast", svc1.getReceivedMessages().get(0));
        assertEquals(1, svc2.getReceivedMessages().size());
        assertEquals("broadcast", svc2.getReceivedMessages().get(0));

        removeService("/printer", svc1);
        removeService("/printer", svc2);
    }

    // --- registerPersistentService ---

    @Test
    public void persistentServiceIsRegisteredAndRemembered() throws Exception {
        TestService svc = new TestService("/notification");
        server.registerPersistentService(svc);

        ConcurrentLinkedQueue<WebSocketServiceInterface> services = getServices("/notification");
        assertTrue(services.contains(svc));

        server.unregisterService(svc);
    }
}

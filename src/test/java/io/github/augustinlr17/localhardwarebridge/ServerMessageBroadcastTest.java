package io.github.augustinlr17.localhardwarebridge;

import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServerInterface;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServiceInterface;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ServerMessageBroadcastTest {

    private Server server;
    private Method addServiceToChannel;

    @Before
    public void setUp() throws Exception {
        server = new Server();
        addServiceToChannel = Server.class.getDeclaredMethod(
                "addServiceToChannel", String.class, WebSocketServiceInterface.class);
        addServiceToChannel.setAccessible(true);
    }

    private static class RecordingService implements WebSocketServiceInterface {
        private final String channel;
        final List<String> receivedText = new ArrayList<>();
        final List<byte[]> receivedBinary = new ArrayList<>();

        RecordingService(String channel) {
            this.channel = channel;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void messageToService(String message) {
            receivedText.add(message);
        }

        @Override
        public void messageToService(byte[] message) {
            receivedBinary.add(message);
        }

        @Override
        public void onRegister(WebSocketServerInterface server) {}

        @Override
        public void onUnregister() {}

        @Override
        public String getChannel() {
            return channel;
        }
    }

    private void addService(String channel, WebSocketServiceInterface service) throws Exception {
        addServiceToChannel.invoke(server, channel, service);
    }

    @Test
    public void messageToServerTextNoSubscribersDoesNotThrow() {
        server.messageToServer("/empty-text", "hello");
    }

    @Test
    public void messageToServerBinaryNoSubscribersDoesNotThrow() {
        server.messageToServer("/empty-binary", new byte[]{0x01, 0x02, 0x03});
    }

    @Test
    public void messageToServiceTextReachesMockService() throws Exception {
        RecordingService mock = new RecordingService("/printer");
        addService("/printer", mock);

        server.messageToService("/printer", "print-job");

        assertEquals(1, mock.receivedText.size());
        assertEquals("print-job", mock.receivedText.get(0));
    }

    @Test
    public void messageToServiceBinaryReachesMockService() throws Exception {
        RecordingService mock = new RecordingService("/serial/SCALE");
        addService("/serial/SCALE", mock);

        byte[] data = new byte[]{0x10, 0x20, 0x30};
        server.messageToService("/serial/SCALE", data);

        assertEquals(1, mock.receivedBinary.size());
        assertArrayEquals(data, mock.receivedBinary.get(0));
    }
}

package io.github.augustinlr17.localhardwarebridge;

import io.javalin.websocket.WsContext;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for socket channel management and message broadcasting in {@link Server}
 * without starting Javalin.
 *
 * <p>Because {@link WsContext} is an abstract class whose {@code send(String)} and
 * {@code send(ByteBuffer)} methods are final (they delegate to
 * {@code session.getRemote().sendString(...)} / {@code sendBytes(...)}), we cannot
 * override them. Instead we build the WsContext with a proxy {@link Session} whose
 * {@code getRemote()} returns a proxy {@link RemoteEndpoint} that records (or throws
 * on) {@code sendString}/{@code sendBytes} calls.</p>
 */
public class ServerSocketChannelTest {

    private Server server;
    private Field socketChannelSubscriptionsField;
    private Method removeSocketFromChannel;

    @Before
    public void setUp() throws Exception {
        server = new Server();
        socketChannelSubscriptionsField = Server.class.getDeclaredField("socketChannelSubscriptions");
        socketChannelSubscriptionsField.setAccessible(true);
        removeSocketFromChannel = Server.class.getDeclaredMethod(
                "removeSocketFromChannel", String.class, WsContext.class);
        removeSocketFromChannel.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, ConcurrentLinkedQueue<WsContext>> getMap() throws Exception {
        return (ConcurrentHashMap<String, ConcurrentLinkedQueue<WsContext>>)
                socketChannelSubscriptionsField.get(server);
    }

    /** Puts a context directly into the internal map, bypassing addSocketToChannel. */
    private void putSocket(String channel, WsContext ctx) throws Exception {
        getMap().computeIfAbsent(channel, k -> new ConcurrentLinkedQueue<>()).add(ctx);
    }

    /** Holds a record of messages delivered to a fake WsContext. */
    static final class Recording {
        final List<String> textMessages = new ArrayList<>();
        final List<ByteBuffer> binaryMessages = new ArrayList<>();
        boolean throwOnText = false;
        boolean throwOnBinary = false;
    }

    /**
     * Creates a WsContext backed by proxy Session/RemoteEndpoint objects that record
     * sendString/sendBytes calls (or throw when the corresponding flag is set).
     */
    private WsContext createRecordingContext(Recording rec) {
        RemoteEndpoint mockRemote = (RemoteEndpoint) Proxy.newProxyInstance(
                RemoteEndpoint.class.getClassLoader(),
                new Class[]{RemoteEndpoint.class},
                (proxy, method, args) -> {
                    if ("sendString".equals(method.getName()) && args != null && args.length == 1) {
                        if (rec.throwOnText) {
                            throw new RuntimeException("simulated text send failure");
                        }
                        rec.textMessages.add((String) args[0]);
                        return null;
                    }
                    if ("sendBytes".equals(method.getName()) && args != null && args.length == 1) {
                        if (rec.throwOnBinary) {
                            throw new RuntimeException("simulated binary send failure");
                        }
                        rec.binaryMessages.add((ByteBuffer) args[0]);
                        return null;
                    }
                    return proxyDefault(proxy, method, args);
                });

        Session mockSession = (Session) Proxy.newProxyInstance(
                Session.class.getClassLoader(),
                new Class[]{Session.class},
                (proxy, method, args) -> {
                    if ("getRemote".equals(method.getName())) {
                        return mockRemote;
                    }
                    return proxyDefault(proxy, method, args);
                });

        // Empty anonymous subclass — WsContext has no abstract methods.
        return new WsContext("test-session", mockSession) {};
    }

    /**
     * Handles Object methods (equals/hashCode/toString/isProxyClass) properly for a proxy,
     * then falls back to a default return value for other methods.
     */
    private static Object proxyDefault(Object proxy, Method method, Object[] args) {
        if ("equals".equals(method.getName()) && args != null && args.length == 1) {
            return proxy == args[0];
        }
        if ("hashCode".equals(method.getName()) && (args == null || args.length == 0)) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(method.getName()) && (args == null || args.length == 0)) {
            return proxy.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
        }
        return defaultReturnValue(method);
    }

    private static Object defaultReturnValue(Method method) {
        Class<?> rt = method.getReturnType();
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == byte.class) return (byte) 0;
        if (rt == short.class) return (short) 0;
        if (rt == char.class) return (char) 0;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        return null;
    }

    private static byte[] toBytes(ByteBuffer bb) {
        byte[] arr = new byte[bb.remaining()];
        bb.duplicate().get(arr);
        return arr;
    }

    // --- text broadcasting ---

    /** 1. messageToServer text with 2 subscribers on same channel — both receive. */
    @Test
    public void textBroadcastToTwoSubscribersOnSameChannel() throws Exception {
        Recording r1 = new Recording();
        Recording r2 = new Recording();
        putSocket("/ch", createRecordingContext(r1));
        putSocket("/ch", createRecordingContext(r2));

        server.messageToServer("/ch", "hello");

        assertEquals(Collections.singletonList("hello"), r1.textMessages);
        assertEquals(Collections.singletonList("hello"), r2.textMessages);
        assertTrue(r1.binaryMessages.isEmpty());
        assertTrue(r2.binaryMessages.isEmpty());
    }

    /** 2. messageToServer text with subscribers on different channels — only matching receives. */
    @Test
    public void textBroadcastOnlyMatchingChannelReceives() throws Exception {
        Recording rA = new Recording();
        Recording rB = new Recording();
        putSocket("/A", createRecordingContext(rA));
        putSocket("/B", createRecordingContext(rB));

        server.messageToServer("/A", "ping");

        assertEquals(Collections.singletonList("ping"), rA.textMessages);
        assertTrue(rB.textMessages.isEmpty());
        assertTrue(rB.binaryMessages.isEmpty());
    }

    /** 3. messageToServer text with no subscribers — doesn't throw. */
    @Test
    public void textBroadcastNoSubscribersDoesNotThrow() {
        server.messageToServer("/empty", "nobody");
        // no exception = pass
    }

    // --- binary broadcasting ---

    /** 4. messageToServer binary with 2 subscribers — both receive the bytes. */
    @Test
    public void binaryBroadcastToTwoSubscribers() throws Exception {
        Recording r1 = new Recording();
        Recording r2 = new Recording();
        putSocket("/bin", createRecordingContext(r1));
        putSocket("/bin", createRecordingContext(r2));

        byte[] payload = {1, 2, 3, 4};
        server.messageToServer("/bin", payload);

        assertEquals(1, r1.binaryMessages.size());
        assertEquals(1, r2.binaryMessages.size());
        assertArrayEquals(payload, toBytes(r1.binaryMessages.get(0)));
        assertArrayEquals(payload, toBytes(r2.binaryMessages.get(0)));
        assertTrue(r1.textMessages.isEmpty());
        assertTrue(r2.textMessages.isEmpty());
    }

    /** 5. messageToServer binary with no subscribers — doesn't throw. */
    @Test
    public void binaryBroadcastNoSubscribersDoesNotThrow() {
        server.messageToServer("/empty-bin", new byte[]{0x00});
        // no exception = pass
    }

    // --- addSocketToChannel ---

    /** 6. addSocketToChannel creates channel if not exists. */
    @Test
    public void addSocketToChannelCreatesChannel() throws Exception {
        WsContext ctx = createRecordingContext(new Recording());
        server.addSocketToChannel("/new", ctx);

        ConcurrentHashMap<String, ConcurrentLinkedQueue<WsContext>> map = getMap();
        assertTrue(map.containsKey("/new"));
        assertEquals(1, map.get("/new").size());
        assertTrue(map.get("/new").contains(ctx));
    }

    // --- removeSocketFromChannel ---

    /** 7. removeSocketFromChannel removes socket from channel. */
    @Test
    public void removeSocketFromChannelRemovesSocket() throws Exception {
        WsContext ctx = createRecordingContext(new Recording());
        server.addSocketToChannel("/rm", ctx);
        assertEquals(1, getMap().get("/rm").size());

        removeSocketFromChannel.invoke(server, "/rm", ctx);

        assertEquals(0, getMap().get("/rm").size());
    }

    /** 8. removeSocketFromChannel on non-existent channel — doesn't throw. */
    @Test
    public void removeSocketFromNonExistentChannelDoesNotThrow() throws Exception {
        WsContext ctx = createRecordingContext(new Recording());
        removeSocketFromChannel.invoke(server, "/does-not-exist", ctx);
        // no exception = pass
    }

    /** 9. removeSocketFromChannel when socket not in channel — doesn't throw, leaves others. */
    @Test
    public void removeSocketNotPresentDoesNotThrow() throws Exception {
        WsContext c1 = createRecordingContext(new Recording());
        server.addSocketToChannel("/ch9", c1);
        WsContext c2 = createRecordingContext(new Recording());

        removeSocketFromChannel.invoke(server, "/ch9", c2); // c2 not in channel

        assertEquals(1, getMap().get("/ch9").size());
        assertTrue(getMap().get("/ch9").contains(c1));
    }

    // --- failure handling ---

    /** 10. messageToServer text with a subscriber that throws — removes it, others still receive. */
    @Test
    public void textBroadcastRemovesFailingSubscriber() throws Exception {
        Recording bad = new Recording();
        bad.throwOnText = true;
        Recording good = new Recording();
        WsContext cBad = createRecordingContext(bad);
        WsContext cGood = createRecordingContext(good);
        putSocket("/mix", cBad);
        putSocket("/mix", cGood);

        server.messageToServer("/mix", "data");

        assertEquals(Collections.singletonList("data"), good.textMessages);
        ConcurrentLinkedQueue<WsContext> remaining = getMap().get("/mix");
        assertEquals(1, remaining.size());
        assertTrue(remaining.contains(cGood));
        assertFalse(remaining.contains(cBad));
    }

    /** 11. messageToServer binary with a subscriber that throws — removes it, others still receive. */
    @Test
    public void binaryBroadcastRemovesFailingSubscriber() throws Exception {
        Recording bad = new Recording();
        bad.throwOnBinary = true;
        Recording good = new Recording();
        WsContext cBad = createRecordingContext(bad);
        WsContext cGood = createRecordingContext(good);
        putSocket("/mixbin", cBad);
        putSocket("/mixbin", cGood);

        byte[] payload = {9, 8, 7};
        server.messageToServer("/mixbin", payload);

        assertEquals(1, good.binaryMessages.size());
        assertArrayEquals(payload, toBytes(good.binaryMessages.get(0)));
        ConcurrentLinkedQueue<WsContext> remaining = getMap().get("/mixbin");
        assertEquals(1, remaining.size());
        assertTrue(remaining.contains(cGood));
        assertFalse(remaining.contains(cBad));
    }
}
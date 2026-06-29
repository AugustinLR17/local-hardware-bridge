package io.github.augustinlr17.localhardwarebridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class ServerRestartGuardTest {

    private AtomicBoolean getRestarting(Server server) throws Exception {
        Field field = Server.class.getDeclaredField("restarting");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(server);
    }

    @Test
    public void restartingFieldExistsAndDefaultsToFalse() throws Exception {
        Server server = new Server();
        AtomicBoolean restarting = getRestarting(server);
        assertFalse(restarting.get());
    }

    @Test
    public void firstCompareAndSetSucceeds() throws Exception {
        Server server = new Server();
        AtomicBoolean restarting = getRestarting(server);
        assertTrue(restarting.compareAndSet(false, true));
        assertTrue(restarting.get());
    }

    @Test
    public void secondCompareAndSetFailsWhileFirstIsTrue() throws Exception {
        Server server = new Server();
        AtomicBoolean restarting = getRestarting(server);
        assertTrue(restarting.compareAndSet(false, true));
        assertFalse(restarting.compareAndSet(false, true));
        assertTrue(restarting.get());
    }

    @Test
    public void newRestartCanProceedAfterSetFalse() throws Exception {
        Server server = new Server();
        AtomicBoolean restarting = getRestarting(server);
        assertTrue(restarting.compareAndSet(false, true));
        restarting.set(false);
        assertFalse(restarting.get());
        assertTrue(restarting.compareAndSet(false, true));
    }
}

package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ThreadUtil}. Verifies that silentSleep does not
 * throw InterruptedException. Fully hermetic.
 */
public class ThreadUtilTest {

    @Test
    public void silentSleepDoesNotThrow() {
        // A very short sleep should complete without throwing.
        ThreadUtil.silentSleep(1);
    }

    @Test
    public void silentSleepWithZeroIsSafe() {
        ThreadUtil.silentSleep(0);
    }

    @Test
    public void silentSleepHandlesInterruption() throws Exception {
        Thread testThread = Thread.currentThread();

        // Schedule an interrupt after a short delay.
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                // ignore
            }
            testThread.interrupt();
        });
        interrupter.setDaemon(true);
        interrupter.start();

        // This should not throw even if interrupted.
        ThreadUtil.silentSleep(200);

        // Clear the interrupted status so subsequent tests are not affected.
        Thread.interrupted();
    }

    @Test
    public void silentSleepRestoresInterruptFlag() throws Exception {
        Thread testThread = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                // ignore
            }
            testThread.interrupt();
        });
        interrupter.setDaemon(true);
        interrupter.start();

        ThreadUtil.silentSleep(150);

        // After interruption, the interrupt flag should be restored (re-interrupt policy).
        assertTrue("Interrupt flag should be restored after InterruptedException",
                Thread.currentThread().isInterrupted());
        Thread.interrupted(); // clean up
    }
}

package io.github.augustinlr17.localhardwarebridge.utils;

public class ThreadUtil {
    private ThreadUtil() {
    }

    public static void silentSleep(long duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

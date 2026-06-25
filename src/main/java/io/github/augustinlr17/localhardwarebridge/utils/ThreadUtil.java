package io.github.augustinlr17.localhardwarebridge.utils;

public class ThreadUtil {
    public static void silentSleep(long duration) {
        try {
            Thread.sleep(duration);
        } catch (Exception ignored) {
        }
    }
}

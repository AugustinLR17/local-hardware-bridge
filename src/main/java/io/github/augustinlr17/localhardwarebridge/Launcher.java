package io.github.augustinlr17.localhardwarebridge;

/**
 * Application entry point.
 *
 * <p>This is the {@code Main-Class} of the packaged JAR and the launcher main class
 * configured for the native installers. It has no static dependency on logging or
 * config services, which lets it {@link AppHome#anchor()} the working directory
 * <em>before</em> any other application class is loaded (and therefore before
 * {@code ConfigService} reads {@code config.json} or log4j opens {@code log/}).
 *
 * <p>Dispatch:
 * <ul>
 *   <li>{@code -Dlhb.server=true} &rarr; headless server</li>
 *   <li>otherwise &rarr; GUI / system-tray mode</li>
 * </ul>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        AppHome.anchor();

        if (Boolean.getBoolean("lhb.server")) {
            Server.main(args);
        } else {
            GUI.main(args);
        }
    }
}

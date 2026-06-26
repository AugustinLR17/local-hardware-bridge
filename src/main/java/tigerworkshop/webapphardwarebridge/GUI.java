package tigerworkshop.webapphardwarebridge;

/**
 * Backward-compatible entry point that delegates to the new package.
 * Existing scripts, systemd units, and launchd plists that reference
 * {@code tigerworkshop.webapphardwarebridge.GUI} will continue to work.
 */
public class GUI {
    public static void main(String[] args) throws Exception {
        io.github.augustinlr17.localhardwarebridge.GUI.main(args);
    }
}
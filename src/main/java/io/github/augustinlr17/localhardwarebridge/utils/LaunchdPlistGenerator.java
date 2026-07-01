package io.github.augustinlr17.localhardwarebridge.utils;

/**
 * Generates macOS launchd plist XML for Local Hardware Bridge.
 *
 * <p>This class exists so the plist content — which must be correct for the
 * LaunchAgent to start headless under launchd — is fully testable without
 * touching the filesystem or spawning GUI dialogs.
 *
 * <h2>Bugs this class prevents</h2>
 * <ul>
 *   <li><b>GUI instead of Server</b> — the generated {@code ProgramArguments}
 *       always references {@code io.github.augustinlr17.localhardwarebridge.Server}
 *       (headless). Launching GUI under launchd crashes because no display is
 *       available.</li>
 * </ul>
 *
 * @see SystemdServiceGenerator
 */
public final class LaunchdPlistGenerator {

    /** Reverse-DNS label used as the plist {@code Label} and file name. */
    static final String LABEL = "io.github.augustinlr17.localhardwarebridge";

    /** Fully-qualified headless main class used in {@code ProgramArguments}. */
    private static final String SERVER_MAIN_CLASS =
        "io.github.augustinlr17.localhardwarebridge.Server";

    private LaunchdPlistGenerator() {
    }

    /**
     * Returns the headless main class used in {@code ProgramArguments}.
     *
     * @return {@code io.github.augustinlr17.localhardwarebridge.Server}
     */
    public static String getMainClass() {
        return SERVER_MAIN_CLASS;
    }

    /**
     * Generates a macOS launchd LaunchAgent plist for Local Hardware Bridge.
     *
     * <p>The plist always launches the <em>headless</em> {@code Server} main
     * class (never {@code GUI}) and sets {@code RunAtLoad} and
     * {@code KeepAlive} to {@code true} so the agent starts at login and
     * restarts on crash.
     *
     * @param javaExec   absolute path to the {@code java} executable
     * @param classpath  the {@code -cp} classpath to pass to java
     * @param workingDir the {@code WorkingDirectory} for the agent
     * @return the complete plist XML content
     */
    public static String generatePlist(String javaExec, String classpath,
                                       String workingDir) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
            + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
            + "<plist version=\"1.0\">\n<dict>\n"
            + "    <key>Label</key>\n"
            + "    <string>" + LABEL + "</string>\n"
            + "    <key>ProgramArguments</key>\n"
            + "    <array>\n"
            + "        <string>" + javaExec + "</string>\n"
            + "        <string>-cp</string>\n"
            + "        <string>" + classpath + "</string>\n"
            + "        <string>" + SERVER_MAIN_CLASS + "</string>\n"
            + "    </array>\n"
            + "    <key>WorkingDirectory</key>\n"
            + "    <string>" + workingDir + "</string>\n"
            + "    <key>RunAtLoad</key>\n"
            + "    <true/>\n"
            + "    <key>KeepAlive</key>\n"
            + "    <true/>\n"
            + "</dict>\n</plist>";
    }
}
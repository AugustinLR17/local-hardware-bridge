package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link LaunchdPlistGenerator}.
 *
 * <p>These tests guard against the bug that was encountered in production:
 * the plist launching GUI instead of Server, which crashes under launchd
 * where no display is available.
 *
 * <p>Fully hermetic — no filesystem writes and no subprocess execution.
 */
public class LaunchdPlistGeneratorTest {

    private static final String TEST_JAVA_EXEC =
        "/Library/Java/JavaVirtualMachines/java-21.jdk/Contents/Home/bin/java";
    private static final String TEST_CLASSPATH =
        "/opt/local-hardware-bridge/local-hardware-bridge.jar";
    private static final String TEST_WORKING_DIR = "/opt/local-hardware-bridge";

    private static String generate() {
        return LaunchdPlistGenerator.generatePlist(
            TEST_JAVA_EXEC, TEST_CLASSPATH, TEST_WORKING_DIR);
    }

    // --- generatePlist: content correctness ---

    @Test
    public void generatePlist_containsCorrectLabel() {
        String plist = generate();
        assertTrue("Plist must contain the Label key",
            plist.contains("<key>Label</key>"));
        assertTrue("Plist must contain the correct label string",
            plist.contains("io.github.augustinlr17.localhardwarebridge</string>"));
    }

    @Test
    public void generatePlist_usesServerNotGui() {
        String plist = generate();
        // ProgramArguments must reference the headless Server class, not GUI.
        assertTrue("ProgramArguments must contain Server main class",
            plist.contains("io.github.augustinlr17.localhardwarebridge.Server"));
        assertFalse("ProgramArguments must NOT contain GUI main class",
            plist.contains("io.github.augustinlr17.localhardwarebridge.GUI"));
    }

    @Test
    public void generatePlist_hasRunAtLoadTrue() {
        String plist = generate();
        assertTrue("Plist must have RunAtLoad key",
            plist.contains("<key>RunAtLoad</key>"));
        assertTrue("Plist must have RunAtLoad set to true",
            plist.contains("<true/>"));
    }

    @Test
    public void generatePlist_hasKeepAliveTrue() {
        String plist = generate();
        assertTrue("Plist must have KeepAlive key",
            plist.contains("<key>KeepAlive</key>"));
        assertTrue("Plist must have KeepAlive set to true",
            plist.contains("<true/>"));
    }

    @Test
    public void generatePlist_hasWorkingDirectory() {
        String plist = generate();
        assertTrue("Plist must have WorkingDirectory key",
            plist.contains("<key>WorkingDirectory</key>"));
        assertTrue("Plist must contain the working directory value",
            plist.contains(TEST_WORKING_DIR));
    }

    @Test
    public void generatePlist_hasValidXmlHeader() {
        String plist = generate();
        assertTrue("Plist must start with XML declaration",
            plist.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue("Plist must have DOCTYPE for plist 1.0",
            plist.contains("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\""));
        assertTrue("Plist must reference the Apple DTD URL",
            plist.contains("http://www.apple.com/DTDs/PropertyList-1.0.dtd"));
        assertTrue("Plist must have <plist version=\"1.0\"> root element",
            plist.contains("<plist version=\"1.0\">"));
        assertTrue("Plist must have <dict> element",
            plist.contains("<dict>"));
        assertTrue("Plist must end with closing plist tag",
            plist.trim().endsWith("</plist>"));
    }

    @Test
    public void generatePlist_hasProgramArgumentsArray() {
        String plist = generate();
        assertTrue("Plist must have ProgramArguments key",
            plist.contains("<key>ProgramArguments</key>"));
        assertTrue("Plist must have <array> element",
            plist.contains("<array>"));
        assertTrue("Plist must contain the java executable",
            plist.contains(TEST_JAVA_EXEC));
        assertTrue("Plist must contain the -cp flag",
            plist.contains("-cp"));
    }

    @Test
    public void generatePlist_classpathIsIncluded() {
        String plist = generate();
        assertTrue("Plist must contain the classpath value in ProgramArguments",
            plist.contains(TEST_CLASSPATH));
    }

    // --- getMainClass ---

    @Test
    public void getMainClass_returnsServer() {
        assertEquals("Main class must be Server (headless)",
            "io.github.augustinlr17.localhardwarebridge.Server",
            LaunchdPlistGenerator.getMainClass());
    }
}
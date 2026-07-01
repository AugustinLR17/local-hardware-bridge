package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Validates that the AppImage AppRun script in the release.yml workflow points
 * to the correct jpackage launcher path.
 * <p>
 * jpackage places the native launcher inside {@code bin/} under the app-image
 * root. The AppRun {@code exec} line must therefore use
 * {@code usr/lib/local-hardware-bridge/bin/Local Hardware Bridge} and NOT the
 * root path {@code usr/lib/local-hardware-bridge/Local Hardware Bridge}.
 */
public class AppRunPathTest {

    private static final String RELEASE_YML = ".github/workflows/release.yml";
    private static final String APPRUN_LINE_PREFIX = "exec \"$APPDIR/usr/lib/local-hardware-bridge";

    /**
     * Loads the release.yml workflow content, resolving relative to the project
     * root (the current working directory when Gradle runs tests).
     *
     * @return the full contents of the release.yml file
     * @throws Exception if the file cannot be read
     */
    private static String readReleaseYml() throws Exception {
        Path path = Paths.get(RELEASE_YML);
        return Files.readString(path);
    }

    /**
     * Extracts the AppRun exec line from the workflow content.
     *
     * @param content the release.yml file contents
     * @return the first line matching the AppRun exec prefix
     */
    private static String findAppRunLine(String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(APPRUN_LINE_PREFIX)) {
                return trimmed;
            }
        }
        return null;
    }

    @Test
    public void releaseYmlAppRunPathIncludesBinDirectory() throws Exception {
        String content = readReleaseYml();
        String appRunLine = findAppRunLine(content);
        assertNotNull("AppRun exec line should exist in release.yml", appRunLine);
        assertTrue("AppRun line must route through bin/ (jpackage places the launcher there): " + appRunLine,
                appRunLine.contains("/bin/Local Hardware Bridge"));
    }

    @Test
    public void releaseYmlAppRunPathDoesNotUseRootLauncher() throws Exception {
        String content = readReleaseYml();
        String appRunLine = findAppRunLine(content);
        assertNotNull("AppRun exec line should exist in release.yml", appRunLine);
        assertFalse("AppRun line must NOT point to the root launcher without bin/: " + appRunLine,
                appRunLine.contains("usr/lib/local-hardware-bridge/Local Hardware Bridge"));
    }

    @Test
    public void releaseYmlAppRunExists() throws Exception {
        String content = readReleaseYml();
        String appRunLine = findAppRunLine(content);
        assertNotNull("release.yml should contain an AppRun exec line", appRunLine);
    }
}
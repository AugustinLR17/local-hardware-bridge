package io.github.augustinlr17.localhardwarebridge.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Structural validation of the Intune packaging scripts under
 * {@code packaging/intune/}. These tests guard the enterprise deployment
 * artefacts from silent regressions (missing silent flags, missing Defender
 * exclusions, config template drift, broken cross-references in the README).
 */
public class IntunePackagingTest {

    private static final Path INTUNE_DIR =
            Paths.get("packaging", "intune");

    private static String readIntuneFile(String name) throws IOException {
        return Files.readString(INTUNE_DIR.resolve(name));
    }

    // --- install.ps1 -----------------------------------------------------

    @Test
    public void installPs1UsesSilentFlag() throws IOException {
        String install = readIntuneFile("install.ps1");
        assertTrue("install.ps1 must pass /S to the NSIS installer",
                install.contains("/S"));
    }

    @Test
    public void installPs1DeploysConfigOnce() throws IOException {
        String install = readIntuneFile("install.ps1");
        assertTrue("install.ps1 must check existing config before copying",
                install.contains("Test-Path"));
        assertTrue("install.ps1 must reference the config destination variable",
                install.contains("configDest"));
        assertTrue("install.ps1 should guard one-time setup",
                install.contains("already exists"));
    }

    @Test
    public void installPs1HasDefenderExclusion() throws IOException {
        String install = readIntuneFile("install.ps1");
        assertTrue("install.ps1 must add a Defender exclusion",
                install.contains("Add-MpPreference"));
    }

    @Test
    public void installPs1HasExitCodes() throws IOException {
        String install = readIntuneFile("install.ps1");
        assertTrue("install.ps1 must exit 0 on success",
                install.contains("exit 0"));
        assertTrue("install.ps1 must exit 1603 on failure",
                install.contains("exit 1603"));
    }

    // --- uninstall.ps1 ---------------------------------------------------

    @Test
    public void uninstallPs1StopsProcess() throws IOException {
        String uninstall = readIntuneFile("uninstall.ps1");
        assertTrue("uninstall.ps1 must stop the running process",
                uninstall.contains("Stop-Process"));
    }

    @Test
    public void uninstallPs1RunsUninstaller() throws IOException {
        String uninstall = readIntuneFile("uninstall.ps1");
        assertTrue("uninstall.ps1 must invoke uninstall.exe",
                uninstall.contains("uninstall.exe"));
        assertTrue("uninstall.ps1 must run the uninstaller silently (/S)",
                uninstall.contains("/S"));
    }

    @Test
    public void uninstallPs1RemovesDefenderExclusion() throws IOException {
        String uninstall = readIntuneFile("uninstall.ps1");
        assertTrue("uninstall.ps1 must remove the Defender exclusion",
                uninstall.contains("Remove-MpPreference"));
    }

    // --- config-template.json -------------------------------------------

    @Test
    public void configTemplateIsLoadableByConfig() throws IOException {
        String json = readIntuneFile("config-template.json");
        Config config = new ObjectMapper().readValue(json, Config.class);

        assertTrue("auth must be enabled in the template",
                config.getServer().getAuthentication().isEnabled());
        assertEquals("auth token must be lhb002",
                "lhb002", config.getServer().getAuthentication().getToken());

        assertFalse("serial must be disabled in the template",
                config.getSerial().isEnabled());

        assertTrue("printer must be enabled in the template",
                config.getPrinter().isEnabled());

        assertFalse("update must be disabled in the template",
                config.getUpdate().isEnabled());
    }

    @Test
    public void configTemplateHasNoUnknownProperties() throws IOException {
        String json = readIntuneFile("config-template.json");
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // Should not throw — every field in the template must map to Config.
        mapper.readValue(json, Config.class);
    }

    // --- README.md -------------------------------------------------------

    @Test
    public void readmeReferencesAllFiles() throws IOException {
        String readme = readIntuneFile("README.md");
        assertTrue("README must reference install.ps1",
                readme.contains("install.ps1"));
        assertTrue("README must reference uninstall.ps1",
                readme.contains("uninstall.ps1"));
        assertTrue("README must reference config-template.json",
                readme.contains("config-template.json"));
        assertTrue("README must reference Intune-Deployment.md",
                readme.contains("Intune-Deployment.md"));
    }
}
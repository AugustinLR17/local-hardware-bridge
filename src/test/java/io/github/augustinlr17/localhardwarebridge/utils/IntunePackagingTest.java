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
 * exclusions, config template drift, broken cross-references in the README,
 * missing UTF-8 BOM, fragile $PSScriptRoot resolution).
 */
public class IntunePackagingTest {

    private static final Path INTUNE_DIR =
            Paths.get("packaging", "intune");

    private static String readIntuneFile(String name) throws IOException {
        return Files.readString(INTUNE_DIR.resolve(name));
    }

    private static byte[] readIntuneFileBytes(String name) throws IOException {
        return Files.readAllBytes(INTUNE_DIR.resolve(name));
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

    @Test
    public void installPs1HasRobustScriptDirResolution() throws IOException {
        String install = readIntuneFile("install.ps1");
        assertTrue("install.ps1 must use PSScriptRoot as primary source",
                install.contains("$PSScriptRoot"));
        assertTrue("install.ps1 must fallback to MyInvocation when PSScriptRoot is empty",
                install.contains("MyInvocation.MyCommand.Path"));
        assertTrue("install.ps1 must fallback to Get-Location as last resort",
                install.contains("Get-Location"));
    }

    @Test
    public void installPs1AutoDetectsInstallerName() throws IOException {
        String install = readIntuneFile("install.ps1");
        assertTrue("install.ps1 must accept lhb.exe",
                install.contains("lhb.exe"));
        assertTrue("install.ps1 must auto-detect Local-Hardware-Bridge-*.exe pattern",
                install.contains("Local-Hardware-Bridge-*.exe"));
    }

    @Test
    public void installPs1HasUtf8Bom() throws IOException {
        byte[] bytes = readIntuneFileBytes("install.ps1");
        assertEquals("install.ps1 must start with UTF-8 BOM (EF BB BF) for PowerShell 5.1 compatibility",
                (byte) 0xEF, bytes[0]);
        assertEquals("install.ps1 BOM byte 2", (byte) 0xBB, bytes[1]);
        assertEquals("install.ps1 BOM byte 3", (byte) 0xBF, bytes[2]);
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

    @Test
    public void uninstallPs1HasUtf8Bom() throws IOException {
        byte[] bytes = readIntuneFileBytes("uninstall.ps1");
        assertEquals("uninstall.ps1 must start with UTF-8 BOM (EF BB BF) for PowerShell 5.1 compatibility",
                (byte) 0xEF, bytes[0]);
        assertEquals("uninstall.ps1 BOM byte 2", (byte) 0xBB, bytes[1]);
        assertEquals("uninstall.ps1 BOM byte 3", (byte) 0xBF, bytes[2]);
    }

    // --- config-template.json -------------------------------------------

    @Test
    public void configTemplateIsLoadableByConfig() throws IOException {
        String json = readIntuneFile("config-template.json");
        Config config = new ObjectMapper().readValue(json, Config.class);

        assertFalse("auth must be disabled in the template",
                config.getServer().getAuthentication().isEnabled());

        assertFalse("serial must be disabled in the template",
                config.getSerial().isEnabled());

        assertTrue("printer must be enabled in the template",
                config.getPrinter().isEnabled());

        assertTrue("update must be enabled in the template",
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
        assertTrue("README must reference update-config.ps1",
                readme.contains("update-config.ps1"));
        assertTrue("README must reference update-config-api.ps1",
                readme.contains("update-config-api.ps1"));
    }

    // --- update-config.ps1 -----------------------------------------------

    @Test
    public void updateConfigPs1HasUtf8Bom() throws IOException {
        byte[] bytes = readIntuneFileBytes("update-config.ps1");
        assertEquals("update-config.ps1 must start with UTF-8 BOM (EF BB BF) for PowerShell 5.1 compatibility",
                (byte) 0xEF, bytes[0]);
        assertEquals("update-config.ps1 BOM byte 2", (byte) 0xBB, bytes[1]);
        assertEquals("update-config.ps1 BOM byte 3", (byte) 0xBF, bytes[2]);
    }

    @Test
    public void updateConfigPs1BacksUpExistingConfig() throws IOException {
        String script = readIntuneFile("update-config.ps1");
        assertTrue("update-config.ps1 must back up existing config before overwriting",
                script.contains("config.json.bak"));
        assertTrue("update-config.ps1 must copy the existing config to backup",
                script.contains("Copy-Item"));
    }

    @Test
    public void updateConfigPs1DeploysNewConfig() throws IOException {
        String script = readIntuneFile("update-config.ps1");
        assertTrue("update-config.ps1 must copy config-template.json to config.json",
                script.contains("configDest"));
        assertTrue("update-config.ps1 must use -Force to overwrite",
                script.contains("-Force"));
    }

    @Test
    public void updateConfigPs1RestartsApp() throws IOException {
        String script = readIntuneFile("update-config.ps1");
        assertTrue("update-config.ps1 must stop the running app",
                script.contains("Stop-Process"));
        assertTrue("update-config.ps1 must restart via VBS launcher",
                script.contains("wscript.exe"));
        assertTrue("update-config.ps1 must reference the VBS launcher path",
                script.contains("lhb-launcher.vbs"));
    }

    @Test
    public void updateConfigPs1HasExitCodes() throws IOException {
        String script = readIntuneFile("update-config.ps1");
        assertTrue("update-config.ps1 must exit 0 on success",
                script.contains("exit 0"));
        assertTrue("update-config.ps1 must exit 1603 on failure",
                script.contains("exit 1603"));
    }

    @Test
    public void updateConfigPs1HasRobustScriptDirResolution() throws IOException {
        String script = readIntuneFile("update-config.ps1");
        assertTrue("update-config.ps1 must use PSScriptRoot as primary source",
                script.contains("$PSScriptRoot"));
        assertTrue("update-config.ps1 must fallback to MyInvocation",
                script.contains("MyInvocation.MyCommand.Path"));
    }

    @Test
    public void updateConfigPs1ResolvesInstallDirFromRegistry() throws IOException {
        String script = readIntuneFile("update-config.ps1");
        assertTrue("update-config.ps1 must read Install_Dir from registry",
                script.contains("Install_Dir"));
        assertTrue("update-config.ps1 must fallback to LOCALAPPDATA",
                script.contains("LOCALAPPDATA"));
    }

    @Test
    public void updateConfigPs1CreatesVbsLauncherIfMissing() throws IOException {
        String script = readIntuneFile("update-config.ps1");
        assertTrue("update-config.ps1 must create VBS launcher as fallback",
                script.contains("Set-Content"));
        assertTrue("update-config.ps1 VBS must set CurrentDirectory",
                script.contains("CurrentDirectory"));
    }

    // --- update-config-api.ps1 -------------------------------------------

    @Test
    public void updateConfigApiPs1HasUtf8Bom() throws IOException {
        byte[] bytes = readIntuneFileBytes("update-config-api.ps1");
        assertEquals("update-config-api.ps1 must start with UTF-8 BOM (EF BB BF) for PowerShell 5.1 compatibility",
                (byte) 0xEF, bytes[0]);
        assertEquals("update-config-api.ps1 BOM byte 2", (byte) 0xBB, bytes[1]);
        assertEquals("update-config-api.ps1 BOM byte 3", (byte) 0xBF, bytes[2]);
    }

    @Test
    public void updateConfigApiPs1UsesHttpApi() throws IOException {
        String script = readIntuneFile("update-config-api.ps1");
        assertTrue("update-config-api.ps1 must use PUT /config.json",
                script.contains("PUT"));
        assertTrue("update-config-api.ps1 must target the config endpoint",
                script.contains("/config.json"));
        assertTrue("update-config-api.ps1 must use Invoke-WebRequest",
                script.contains("Invoke-WebRequest"));
    }

    @Test
    public void updateConfigApiPs1HasHealthCheck() throws IOException {
        String script = readIntuneFile("update-config-api.ps1");
        assertTrue("update-config-api.ps1 must check if the app is running before pushing config",
                script.contains("/system/health"));
    }

    @Test
    public void updateConfigApiPs1AutoDetectsToken() throws IOException {
        String script = readIntuneFile("update-config-api.ps1");
        assertTrue("update-config-api.ps1 must auto-detect the token from existing config",
                script.contains("authentication.token"));
        assertTrue("update-config-api.ps1 must read existing config.json",
                script.contains("config.json"));
    }

    @Test
    public void updateConfigApiPs1SendsBearerToken() throws IOException {
        String script = readIntuneFile("update-config-api.ps1");
        assertTrue("update-config-api.ps1 must send Bearer token in Authorization header",
                script.contains("Bearer"));
        assertTrue("update-config-api.ps1 must build headers dict",
                script.contains("Authorization"));
    }

    @Test
    public void updateConfigApiPs1HandlesAuthFailure() throws IOException {
        String script = readIntuneFile("update-config-api.ps1");
        assertTrue("update-config-api.ps1 must handle 401 auth failure gracefully",
                script.contains("401"));
        assertTrue("update-config-api.ps1 must advise using update-config.ps1 for token changes",
                script.contains("update-config.ps1"));
    }

    @Test
    public void updateConfigApiPs1HasExitCodes() throws IOException {
        String script = readIntuneFile("update-config-api.ps1");
        assertTrue("update-config-api.ps1 must exit 0 on success",
                script.contains("exit 0"));
        assertTrue("update-config-api.ps1 must exit 1603 on failure",
                script.contains("exit 1603"));
    }

    @Test
    public void updateConfigApiPs1HasRobustScriptDirResolution() throws IOException {
        String script = readIntuneFile("update-config-api.ps1");
        assertTrue("update-config-api.ps1 must use PSScriptRoot as primary source",
                script.contains("$PSScriptRoot"));
        assertTrue("update-config-api.ps1 must fallback to MyInvocation",
                script.contains("MyInvocation.MyCommand.Path"));
    }

    @Test
    public void updateConfigApiPs1DoesNotRestartApp() throws IOException {
        String script = readIntuneFile("update-config-api.ps1");
        // The API script must NOT call Stop-Process — it's a live update
        assertFalse("update-config-api.ps1 must NOT stop the app process (zero-downtime update)",
                script.contains("Stop-Process"));
    }

    @Test
    public void updateConfigApiPs1VerifiesAfterUpdate() throws IOException {
        String script = readIntuneFile("update-config-api.ps1");
        assertTrue("update-config-api.ps1 must verify health after pushing config",
                script.contains("Post-update health check"));
    }

    @Test
    public void updateConfigApiPs1HasDefaultPort() throws IOException {
        String script = readIntuneFile("update-config-api.ps1");
        assertTrue("update-config-api.ps1 must default to port 57212",
                script.contains("57212"));
    }

    // --- Intune-Deployment.md references update scripts -------------------

    @Test
    public void deploymentGuideDocumentsUpdateProcedures() throws IOException {
        Path docPath = Paths.get("docs", "Intune-Deployment.md");
        String doc = Files.readString(docPath);
        assertTrue("Deployment guide must document config-only updates",
                doc.contains("Updating the Configuration"));
        assertTrue("Deployment guide must reference update-config.ps1",
                doc.contains("update-config.ps1"));
        assertTrue("Deployment guide must reference update-config-api.ps1",
                doc.contains("update-config-api.ps1"));
        assertTrue("Deployment guide must explain supersedence for new versions",
                doc.contains("Supersedence"));
    }

    @Test
    public void deploymentGuideHasUpdateDecisionTable() throws IOException {
        Path docPath = Paths.get("docs", "Intune-Deployment.md");
        String doc = Files.readString(docPath);
        assertTrue("Deployment guide must have a decision table for choosing update method",
                doc.contains("When to use which option"));
    }

    @Test
    public void deploymentGuideFileSummaryIncludesUpdateScripts() throws IOException {
        Path docPath = Paths.get("docs", "Intune-Deployment.md");
        String doc = Files.readString(docPath);
        assertTrue("File summary must include update-config.ps1",
                doc.contains("update-config.ps1"));
        assertTrue("File summary must include update-config-api.ps1",
                doc.contains("update-config-api.ps1"));
    }
}
package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Validates the NSIS installer script ({@code install.nsi}) supports both
 * per-user (default) and per-machine ({@code /DPER_MACHINE=1}) install modes
 * with correct registry roots, install directories, and execution levels.
 *
 * <p>This prevents regressions on the dual-mode installer that was added for
 * Intune enterprise deployment.
 */
public class NsisInstallModeTest {

    private static final String NSI_PATH = "install.nsi";

    private String readNsi() throws Exception {
        Path nsi = Paths.get(NSI_PATH);
        if (!nsi.toFile().exists()) {
            // Search upward from CWD
            Path parent = Paths.get(System.getProperty("user.dir"));
            while (parent != null && parent.toFile().exists()) {
                Path candidate = parent.resolve(NSI_PATH);
                if (candidate.toFile().exists()) {
                    return Files.readString(candidate);
                }
                parent = parent.getParent();
            }
            org.junit.Assume.assumeTrue("install.nsi not found — skipping", false);
        }
        return Files.readString(nsi);
    }

    @Test
    public void nsiHasPerMachineFlagBlock() throws Exception {
        String nsi = readNsi();
        assertTrue("install.nsi must have !ifdef PER_MACHINE block",
            nsi.contains("!ifdef PER_MACHINE"));
    }

    @Test
    public void nsiHasRegRootVariable() throws Exception {
        String nsi = readNsi();
        assertTrue("REG_ROOT must be HKLM in per-machine mode",
            nsi.contains("!define REG_ROOT HKLM"));
        assertTrue("REG_ROOT must be HKCU in per-user mode",
            nsi.contains("!define REG_ROOT HKCU"));
    }

    @Test
    public void nsiHasRequestExecutionLevelForBothModes() throws Exception {
        String nsi = readNsi();
        assertTrue("Per-machine mode requires admin rights",
            nsi.contains("RequestExecutionLevel admin"));
        assertTrue("Per-user mode requires user rights",
            nsi.contains("RequestExecutionLevel user"));
    }

    @Test
    public void nsiHasInstallDirForBothModes() throws Exception {
        String nsi = readNsi();
        assertTrue("Per-machine install dir must be ProgramFiles",
            nsi.contains("PROGRAMFILES"));
        assertTrue("Per-user install dir must be LOCALAPPDATA",
            nsi.contains("LOCALAPPDATA"));
    }

    @Test
    public void nsiRegistryWritesUseRegRootVariable() throws Exception {
        String nsi = readNsi();
        assertTrue("WriteRegStr must use ${REG_ROOT}",
            nsi.contains("WriteRegStr ${REG_ROOT}"));
        assertTrue("WriteRegDWORD must use ${REG_ROOT}",
            nsi.contains("WriteRegDWORD ${REG_ROOT}"));
    }

    @Test
    public void nsiInstallDirRegKeyUsesRegRoot() throws Exception {
        String nsi = readNsi();
        assertTrue("InstallDirRegKey must use ${REG_ROOT}",
            nsi.contains("InstallDirRegKey ${REG_ROOT}"));
    }

    @Test
    public void nsiUninstallerUsesRegRootForCleanup() throws Exception {
        String nsi = readNsi();
        assertTrue("Uninstaller DeleteRegKey must use ${REG_ROOT}",
            nsi.contains("DeleteRegKey ${REG_ROOT}"));
        assertTrue("Uninstaller DeleteRegValue must use ${REG_ROOT}",
            nsi.contains("DeleteRegValue ${REG_ROOT}"));
    }

    @Test
    public void nsiLegacyCleanupStaysHKCU() throws Exception {
        String nsi = readNsi();
        // Legacy TigerWorkshop cleanup should always be HKCU (per-user legacy)
        assertTrue("Legacy TigerWorkshop cleanup must stay HKCU",
            nsi.contains("DeleteRegKey HKCU") && nsi.contains("WebApp Hardware Bridge"));
    }

    @Test
    public void nsiAutoStartUsesRegRoot() throws Exception {
        String nsi = readNsi();
        assertTrue("Auto-start Run key must use ${REG_ROOT}",
            nsi.contains("WriteRegStr ${REG_ROOT} \"${RUN_KEY}\""));
    }

    @Test
    public void nsiHasVersionDefine() throws Exception {
        String nsi = readNsi();
        assertTrue("install.nsi must have PRODUCT_VERSION define",
            nsi.contains("!define PRODUCT_VERSION"));
    }
}
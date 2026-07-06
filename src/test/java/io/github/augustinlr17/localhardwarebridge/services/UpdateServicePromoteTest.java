package io.github.augustinlr17.localhardwarebridge.services;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for the Windows-safe two-hop update helpers:
 * - {@link UpdateService#promoteJar} copies the update over the original with a .bak backup
 * - {@link UpdateService#buildRelaunchCommand} builds the relaunch command line
 */
public class UpdateServicePromoteTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void promoteJarReplacesTargetAndKeepsBackup() throws Exception {
        Path source = tmp.newFile("local-hardware-bridge-9.9.9.jar").toPath();
        Path target = tmp.newFile("local-hardware-bridge-9.9.8.jar").toPath();
        Files.writeString(source, "NEW");
        Files.writeString(target, "OLD");

        UpdateService.promoteJar(source, target);

        assertEquals("target must contain the new JAR", "NEW", Files.readString(target));
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        assertTrue("backup must exist", Files.isRegularFile(backup));
        assertEquals("backup must contain the old JAR", "OLD", Files.readString(backup));
        assertTrue("source must still exist (it is the running JAR)", Files.isRegularFile(source));
    }

    @Test
    public void promoteJarOverwritesStaleBackup() throws Exception {
        Path source = tmp.newFile("s.jar").toPath();
        Path target = tmp.newFile("t.jar").toPath();
        Files.writeString(source, "V3");
        Files.writeString(target, "V2");
        Files.writeString(target.resolveSibling("t.jar.bak"), "V1");

        UpdateService.promoteJar(source, target);

        assertEquals("V3", Files.readString(target));
        assertEquals("stale backup must be replaced by the previous version",
                "V2", Files.readString(target.resolveSibling("t.jar.bak")));
    }

    @Test
    public void buildRelaunchCommandPlain() {
        List<String> cmd = UpdateService.buildRelaunchCommand(
                "/jre/bin/java", false, Path.of("/app/new.jar"), null);
        assertEquals(List.of("/jre/bin/java", "-cp", "/app/new.jar",
                "io.github.augustinlr17.localhardwarebridge.Launcher"), cmd);
    }

    @Test
    public void buildRelaunchCommandServerModeWithPromoteTarget() {
        List<String> cmd = UpdateService.buildRelaunchCommand(
                "/jre/bin/java", true, Path.of("/app/updates/new.jar"), Path.of("/app/app/old.jar"));
        assertEquals(List.of("/jre/bin/java",
                "-Dlhb.server=true",
                "-Dlhb.promote.target=/app/app/old.jar",
                "-cp", "/app/updates/new.jar",
                "io.github.augustinlr17.localhardwarebridge.Launcher"), cmd);
    }
}

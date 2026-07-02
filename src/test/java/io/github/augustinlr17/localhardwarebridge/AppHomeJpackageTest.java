package io.github.augustinlr17.localhardwarebridge;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Tests for {@link AppHome#resolveInstallDir(File)} — the logic that
 * determines which directory should be used as {@code user.dir}.
 *
 * <p>Bug context: when packaged with jpackage, the JAR lives in
 * {@code <install>/app/<jar>} but {@code config.json} sits in
 * {@code <install>}. Without walking up from {@code app/}, the app
 * starts with {@code user.dir = <install>/app} and cannot find its
 * config — falling back to built-in defaults with auth disabled.
 */
public class AppHomeJpackageTest {

    @Test
    public void jpackageLayoutResolvesToInstallDirNotAppSubdir() throws IOException {
        // <tmp>/install/app/bridge.jar
        Path tmp = Files.createTempDirectory("apphome-jpkg");
        Path install = tmp.resolve("install");
        Path app = install.resolve("app");
        Files.createDirectories(app);
        Path jar = app.resolve("bridge.jar");
        Files.createFile(jar);

        File resolved = AppHome.resolveInstallDir(jar.toFile());
        assertNotNull("jpackage layout must resolve to a directory", resolved);
        assertEquals("must resolve to <install>, not <install>/app",
                install.toFile().getAbsolutePath(), resolved.getAbsolutePath());

        cleanup(tmp);
    }

    @Test
    public void flatLayoutResolvesToJarParentDir() throws IOException {
        // <tmp>/myapp/bridge.jar
        Path tmp = Files.createTempDirectory("apphome-flat");
        Path jar = tmp.resolve("bridge.jar");
        Files.createFile(jar);

        File resolved = AppHome.resolveInstallDir(jar.toFile());
        assertNotNull("flat layout must resolve to a directory", resolved);
        assertEquals("must resolve to the JAR's parent directory",
                tmp.toFile().getAbsolutePath(), resolved.getAbsolutePath());

        cleanup(tmp);
    }

    @Test
    public void explodedClassesReturnNull() {
        // Simulate a directory (exploded classes) — not a file
        File dir = new File(System.getProperty("java.io.tmpdir"));
        File resolved = AppHome.resolveInstallDir(dir);
        assertNull("exploded classes (directory) must return null (no-op)", resolved);
    }

    @Test
    public void nonJarFileReturnsNull() throws IOException {
        Path tmp = Files.createTempDirectory("apphome-nonjar");
        Path txt = tmp.resolve("readme.txt");
        Files.createFile(txt);

        File resolved = AppHome.resolveInstallDir(txt.toFile());
        assertNull("non-JAR files must return null (no-op)", resolved);

        cleanup(tmp);
    }

    @Test
    public void nullCodeSourceReturnsNull() {
        assertNull("null input must return null", AppHome.resolveInstallDir(null));
    }

    @Test
    public void appSubdirWithNoParentReturnsFallback() throws IOException {
        // Edge case: "app" directory exists but has no parent (shouldn't happen
        // in practice, but the code handles it). We can't truly create a dir
        // with no parent on a real filesystem, so we test that a JAR directly
        // in a dir named "app" at the filesystem root resolves to the parent.
        // Instead, verify the flat-layout fallback works for a dir named "app".
        Path tmp = Files.createTempDirectory("apphome-approot");
        Path appDir = tmp.resolve("app");
        Files.createDirectories(appDir);
        Path jar = appDir.resolve("bridge.jar");
        Files.createFile(jar);

        File resolved = AppHome.resolveInstallDir(jar.toFile());
        // Since "app" has a parent (tmp), it should walk up to tmp
        assertNotNull(resolved);
        assertEquals(tmp.toFile().getAbsolutePath(), resolved.getAbsolutePath());

        cleanup(tmp);
    }

    private void cleanup(Path dir) {
        try {
            Files.walk(dir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    // --- java.class.path fallback (jpackage sets this to the app JAR) ---

    @Test
    public void classPathFallbackResolvesJpackageLayout() throws IOException {
        // Simulate jpackage: java.class.path = <install>/app/bridge.jar
        Path tmp = Files.createTempDirectory("apphome-cp");
        Path install = tmp.resolve("LHB");
        Path app = install.resolve("app");
        Files.createDirectories(app);
        Path jar = app.resolve("bridge.jar");
        Files.createFile(jar);

        String originalCp = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", jar.toFile().getAbsolutePath());
            File resolved = AppHome.resolveViaClassPath();
            assertNotNull("resolveViaClassPath must find the install dir", resolved);
            assertEquals("must resolve <install> via java.class.path",
                    install.toFile().getAbsolutePath(), resolved.getAbsolutePath());
        } finally {
            if (originalCp != null) System.setProperty("java.class.path", originalCp);
        }
        cleanup(tmp);
    }

    // --- java.home fallback (jpackage bundles JRE in <install>/runtime) ---

    @Test
    public void javaHomeFallbackResolvesInstallDir() throws IOException {
        // Simulate jpackage: java.home = <install>/runtime/bin
        Path tmp = Files.createTempDirectory("apphome-jh");
        Path install = tmp.resolve("LHB");
        Path runtime = install.resolve("runtime");
        Path bin = runtime.resolve("bin");
        Path app = install.resolve("app");
        Files.createDirectories(bin);
        Files.createDirectories(app); // marker for jpackage layout detection

        String originalJavaHome = System.getProperty("java.home");
        try {
            System.setProperty("java.home", bin.toFile().getAbsolutePath());
            File resolved = AppHome.resolveViaJavaHome();
            assertNotNull("resolveViaJavaHome must find the install dir", resolved);
            assertEquals("must resolve <install> via java.home",
                    install.toFile().getAbsolutePath(), resolved.getAbsolutePath());
        } finally {
            if (originalJavaHome != null) System.setProperty("java.home", originalJavaHome);
        }
        cleanup(tmp);
    }

    @Test
    public void javaHomeFallbackWithoutAppDirDoesNotResolve() throws IOException {
        // java.home points to a runtime/ dir but no app/ sibling → not jpackage
        Path tmp = Files.createTempDirectory("apphome-jh-noapp");
        Path runtime = tmp.resolve("runtime");
        Path bin = runtime.resolve("bin");
        Files.createDirectories(bin);

        String originalJavaHome = System.getProperty("java.home");
        try {
            System.setProperty("java.home", bin.toFile().getAbsolutePath());
            File resolved = AppHome.resolveViaJavaHome();
            assertNull("must not resolve without app/ marker dir", resolved);
        } finally {
            if (originalJavaHome != null) System.setProperty("java.home", originalJavaHome);
        }
        cleanup(tmp);
    }

    @Test
    public void javaHomeFallbackWithRuntimeDirNotBin() throws IOException {
        // Some jpackage layouts set java.home = <install>/runtime (no /bin)
        Path tmp = Files.createTempDirectory("apphome-jh-rt");
        Path install = tmp.resolve("LHB");
        Path runtime = install.resolve("runtime");
        Path app = install.resolve("app");
        Files.createDirectories(runtime);
        Files.createDirectories(app);

        String originalJavaHome = System.getProperty("java.home");
        try {
            System.setProperty("java.home", runtime.toFile().getAbsolutePath());
            File resolved = AppHome.resolveViaJavaHome();
            assertNotNull("must resolve even when java.home = <install>/runtime", resolved);
            assertEquals(install.toFile().getAbsolutePath(), resolved.getAbsolutePath());
        } finally {
            if (originalJavaHome != null) System.setProperty("java.home", originalJavaHome);
        }
        cleanup(tmp);
    }
}

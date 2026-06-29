package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CertificateGenerator}.
 * Tests cert/key generation, existence checks, CN mismatch regeneration,
 * and file permissions — all using temp directories, no network.
 */
public class CertificateGeneratorTest {

    @Test
    public void isCertificateAndKeyExistReturnsFalseWhenBothMissing() {
        java.nio.file.Path tmp = java.nio.file.Paths.get("/tmp/lhb-cert-missing-" + System.nanoTime());
        assertFalse(CertificateGenerator.isCertificateAndKeyExist(
                tmp.resolve("cert.pem").toString(),
                tmp.resolve("key.pem").toString()));
    }

    @Test
    public void isCertificateAndKeyExistReturnsFalseWhenOnlyCertExists() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-half");
        File cert = dir.resolve("cert.pem").toFile();
        cert.createNewFile();
        cert.deleteOnExit();

        assertFalse(CertificateGenerator.isCertificateAndKeyExist(
                cert.getAbsolutePath(),
                dir.resolve("key.pem").toString()));
    }

    @Test
    public void isCertificateAndKeyExistReturnsTrueWhenBothExist() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-both");
        File cert = dir.resolve("cert.pem").toFile();
        File key = dir.resolve("key.pem").toFile();
        cert.createNewFile();
        key.createNewFile();
        cert.deleteOnExit();
        key.deleteOnExit();

        assertTrue(CertificateGenerator.isCertificateAndKeyExist(
                cert.getAbsolutePath(), key.getAbsolutePath()));
    }

    @Test
    public void generateCreatesValidCertAndKey() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-gen");
        String certPath = dir.resolve("cert.pem").toString();
        String keyPath = dir.resolve("key.pem").toString();

        // Change CWD to the temp dir so the "tls" directory is created there
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());

        try {
            CertificateGenerator.generateSelfSignedCertificate("127.0.0.1", certPath, keyPath);

            File certFile = new File(certPath);
            File keyFile = new File(keyPath);

            assertTrue("cert file must exist", certFile.exists());
            assertTrue("key file must exist", keyFile.exists());
            assertTrue("cert file must not be empty", certFile.length() > 0);
            assertTrue("key file must not be empty", keyFile.length() > 0);

            // Verify the cert is a valid X.509 PEM
            try (java.io.FileInputStream fis = new java.io.FileInputStream(certFile)) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(fis);
                assertNotNull(cert);
                assertEquals("CN=127.0.0.1", cert.getSubjectX500Principal().getName());
            }

            // Verify the key is PEM (starts with "-----BEGIN")
            String keyContent = Files.readString(keyFile.toPath());
            assertTrue("key must be PEM format", keyContent.contains("-----BEGIN"));
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    public void generateWithHostnameCreatesValidCert() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-host");
        String certPath = dir.resolve("cert.pem").toString();
        String keyPath = dir.resolve("key.pem").toString();

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());

        try {
            CertificateGenerator.generateSelfSignedCertificate("local.example.com", certPath, keyPath);

            File certFile = new File(certPath);
            assertTrue(certFile.exists());

            try (java.io.FileInputStream fis = new java.io.FileInputStream(certFile)) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(fis);
                assertEquals("CN=local.example.com", cert.getSubjectX500Principal().getName());
            }
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    public void cnMismatchTriggersRegeneration() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-regen");
        String certPath = dir.resolve("cert.pem").toString();
        String keyPath = dir.resolve("key.pem").toString();

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());

        try {
            // Generate with CN=127.0.0.1
            CertificateGenerator.generateSelfSignedCertificate("127.0.0.1", certPath, keyPath);
            assertTrue(new File(certPath).exists());

            // Read the cert and record its serial number
            BigInteger serial1;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(certPath)) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert1 = (X509Certificate) cf.generateCertificate(fis);
                serial1 = cert1.getSerialNumber();
                assertEquals("CN=127.0.0.1", cert1.getSubjectX500Principal().getName());
            }

            // Generate again with a different CN — should regenerate
            CertificateGenerator.generateSelfSignedCertificate("192.168.1.1", certPath, keyPath);

            // The cert must now have CN=192.168.1.1 and a different serial
            try (java.io.FileInputStream fis = new java.io.FileInputStream(certPath)) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert2 = (X509Certificate) cf.generateCertificate(fis);
                assertEquals("CN=192.168.1.1", cert2.getSubjectX500Principal().getName());
                assertNotEquals("serial must differ after regeneration", serial1, cert2.getSerialNumber());
            }
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    public void sameCnDoesNotRegenerate() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-noregen");
        String certPath = dir.resolve("cert.pem").toString();
        String keyPath = dir.resolve("key.pem").toString();

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());

        try {
            CertificateGenerator.generateSelfSignedCertificate("127.0.0.1", certPath, keyPath);
            assertTrue(new File(certPath).exists());

            // Record file modification time
            long lastModified = new File(certPath).lastModified();

            // Wait briefly so a regeneration would be detectable
            Thread.sleep(100);

            // Call again with same CN — should NOT regenerate
            CertificateGenerator.generateSelfSignedCertificate("127.0.0.1", certPath, keyPath);

            // The file should not have been rewritten
            assertEquals("cert must not be regenerated when CN matches",
                    lastModified, new File(certPath).lastModified());
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    public void privateKeyHasOwnerOnlyPermissions() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-perms");
        String certPath = dir.resolve("cert.pem").toString();
        String keyPath = dir.resolve("key.pem").toString();

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());

        try {
            CertificateGenerator.generateSelfSignedCertificate("127.0.0.1", certPath, keyPath);

            // On POSIX systems, the key must have owner-only permissions (rw-------)
            try {
                var perms = Files.getPosixFilePermissions(java.nio.file.Paths.get(keyPath));
                String permString = java.nio.file.attribute.PosixFilePermissions.toString(perms);
                assertEquals("private key must have rw------- permissions", "rw-------", permString);
            } catch (UnsupportedOperationException e) {
                // Non-POSIX (Windows) — skip
            }
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }
}

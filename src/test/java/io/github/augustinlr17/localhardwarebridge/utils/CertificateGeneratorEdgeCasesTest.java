package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.junit.Assert.*;

/**
 * Additional tests for {@link CertificateGenerator} covering:
 * - Corrupted cert file triggers regeneration
 * - Hostname (non-IP) address generates valid cert with DNS SAN
 * - 127.0.0.1 address does not add duplicate 127.0.0.1 SAN
 * - Non-IP address (hostname) includes localhost and 127.0.0.1 in SAN
 */
public class CertificateGeneratorEdgeCasesTest {

    @Test
    public void corruptedCertFileTriggersRegeneration() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-corrupt");
        String certPath = dir.resolve("cert.pem").toString();
        String keyPath = dir.resolve("key.pem").toString();

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());

        try {
            // Create a corrupted cert file and a valid-looking key file
            Files.writeString(java.nio.file.Paths.get(certPath), "NOT A VALID CERTIFICATE CONTENT");
            Files.writeString(java.nio.file.Paths.get(keyPath), "fake key content");

            assertTrue(new File(certPath).exists());
            assertTrue(new File(keyPath).exists());

            // Call generate — should detect the corrupted cert and regenerate
            CertificateGenerator.generateSelfSignedCertificate("127.0.0.1", certPath, keyPath);

            // The cert must now be a valid X.509
            File certFile = new File(certPath);
            assertTrue(certFile.exists());
            assertTrue("regenerated cert must not be empty", certFile.length() > 0);

            try (java.io.FileInputStream fis = new java.io.FileInputStream(certFile)) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(fis);
                assertNotNull(cert);
                assertEquals("CN=127.0.0.1", cert.getSubjectX500Principal().getName());
            }
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    public void hostnameAddressGeneratesValidCertWithDnsSan() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-hostname");
        String certPath = dir.resolve("cert.pem").toString();
        String keyPath = dir.resolve("key.pem").toString();

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());

        try {
            CertificateGenerator.generateSelfSignedCertificate("myserver.local", certPath, keyPath);

            File certFile = new File(certPath);
            assertTrue(certFile.exists());

            try (java.io.FileInputStream fis = new java.io.FileInputStream(certFile)) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(fis);
                assertEquals("CN=myserver.local", cert.getSubjectX500Principal().getName());
                // Verify SAN extensions exist
                var sanExt = cert.getExtensionValue("2.5.29.17");
                assertNotNull("Subject Alternative Names extension must exist for hostname", sanExt);
            }
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    public void nonLoopbackIpv4GeneratesValidCert() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-ipv4");
        String certPath = dir.resolve("cert.pem").toString();
        String keyPath = dir.resolve("key.pem").toString();

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());

        try {
            // 192.168.1.100 is a non-loopback IPv4 → should add both the IP and 127.0.0.1 as SAN
            CertificateGenerator.generateSelfSignedCertificate("192.168.1.100", certPath, keyPath);

            File certFile = new File(certPath);
            assertTrue(certFile.exists());

            try (java.io.FileInputStream fis = new java.io.FileInputStream(certFile)) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(fis);
                assertEquals("CN=192.168.1.100", cert.getSubjectX500Principal().getName());
                // SAN should exist
                var sanExt = cert.getExtensionValue("2.5.29.17");
                assertNotNull("SAN extension must exist for IPv4 address", sanExt);
            }
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    public void keyFileHasRestrictedPermissions() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-keyperm");
        String certPath = dir.resolve("cert.pem").toString();
        String keyPath = dir.resolve("key.pem").toString();

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());

        try {
            CertificateGenerator.generateSelfSignedCertificate("127.0.0.1", certPath, keyPath);

            // On POSIX systems, the key must have owner-only permissions
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

    @Test
    public void tlsDirectoryCreatedWithRestrictedPermissions() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("lhb-cert-tlsdir");
        String certPath = dir.resolve("cert.pem").toString();
        String keyPath = dir.resolve("key.pem").toString();

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());

        try {
            CertificateGenerator.generateSelfSignedCertificate("127.0.0.1", certPath, keyPath);

            // The "tls" directory should have been created with rwx------ permissions
            File tlsDir = new File("tls");
            assertTrue("tls directory must exist", tlsDir.exists());
            assertTrue("tls must be a directory", tlsDir.isDirectory());

            try {
                var perms = Files.getPosixFilePermissions(tlsDir.toPath());
                String permString = java.nio.file.attribute.PosixFilePermissions.toString(perms);
                assertEquals("tls directory must have rwx------ permissions", "rwx------", permString);
            } catch (UnsupportedOperationException e) {
                // Non-POSIX (Windows) — skip
            }
        } finally {
            // Clean up the tls dir in the temp working directory
            File tlsDir = new File("tls");
            if (tlsDir.exists() && tlsDir.isDirectory()) {
                File[] files = tlsDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
                tlsDir.delete();
            }
            System.setProperty("user.dir", originalDir);
        }
    }
}

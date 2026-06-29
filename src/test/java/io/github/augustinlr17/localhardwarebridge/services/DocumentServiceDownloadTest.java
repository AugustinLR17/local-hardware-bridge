package io.github.augustinlr17.localhardwarebridge.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Tests for {@link DocumentService#download} HTTP error handling and
 * the TLS trust-all configuration path. Uses a lightweight in-process
 * HTTP/HTTPS server via com.sun.net.httpserver.
 * Fully hermetic: no external network.
 */
public class DocumentServiceDownloadTest {

    private File baseDir;
    private Method download;

    @org.junit.Before
    public void setUp() throws Exception {
        baseDir = Files.createTempDirectory("lhb-download").toFile();
        baseDir.deleteOnExit();
        ConfigService.getInstance().getConfig().getDownloader().setPath(baseDir.getAbsolutePath());
        ConfigService.getInstance().getConfig().getDownloader().setBlockPrivateNetworks(false);
        ConfigService.getInstance().getConfig().getDownloader().setIgnoreTLSCertificateError(false);

        download = DocumentService.class.getDeclaredMethod("download", java.net.URL.class, File.class);
        download.setAccessible(true);
    }

    private void invokeDownload(java.net.URL url, File output) throws Throwable {
        try {
            download.invoke(DocumentService.getInstance(), url, output);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    public void httpNon200ResponseIsRejected() throws Throwable {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            File outFile = new File(baseDir, "out.bin");
            invokeDownload(new java.net.URL("http://127.0.0.1:" + port + "/notfound"), outFile);
            fail("expected IOException for HTTP 404");
        } catch (IOException e) {
            assertTrue("error message should mention HTTP status: " + e.getMessage(),
                    e.getMessage().contains("HTTP Status Code: 404"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void http500ResponseIsRejected() throws Throwable {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            File outFile = new File(baseDir, "out.bin");
            invokeDownload(new java.net.URL("http://127.0.0.1:" + port + "/error"), outFile);
            fail("expected IOException for HTTP 500");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("HTTP Status Code: 500"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void http200DownloadsFileSuccessfully() throws Throwable {
        byte[] payload = "downloaded content".getBytes();

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            File outFile = new File(baseDir, "success.bin");
            invokeDownload(new java.net.URL("http://127.0.0.1:" + port + "/file.bin"), outFile);

            assertTrue("output file must exist", outFile.exists());
            assertArrayEquals(payload, Files.readAllBytes(outFile.toPath()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void ignoreTLSCertificateErrorDoesNotThrowWhenHttp() throws Throwable {
        // ignoreTLSCertificateError=true but URL is plain HTTP — trust-all is not applied
        ConfigService.getInstance().getConfig().getDownloader().setIgnoreTLSCertificateError(true);

        byte[] payload = "plain http content".getBytes();

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            File outFile = new File(baseDir, "plain.bin");
            invokeDownload(new java.net.URL("http://127.0.0.1:" + port + "/file.bin"), outFile);
            assertArrayEquals(payload, Files.readAllBytes(outFile.toPath()));
        } finally {
            server.stop(0);
            ConfigService.getInstance().getConfig().getDownloader().setIgnoreTLSCertificateError(false);
        }
    }

    @Test
    public void ignoreTLSCertificateErrorAllowsSelfSignedHttps() throws Throwable {
        // Generates a self-signed cert and starts an HTTPS server, then downloads
        // with ignoreTLSCertificateError=true. The trust-all + hostname verifier
        // relaxation must allow the download to succeed.
        ConfigService.getInstance().getConfig().getDownloader().setIgnoreTLSCertificateError(true);

        java.nio.file.Path certDir = Files.createTempDirectory("lhb-tls-test");
        String certPath = certDir.resolve("cert.pem").toString();
        String keyPath = certDir.resolve("key.pem").toString();
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", certDir.toString());

        try {
            io.github.augustinlr17.localhardwarebridge.utils.CertificateGenerator
                    .generateSelfSignedCertificate("127.0.0.1", certPath, keyPath);

            // Load the generated cert and key into a keystore for the HTTPS server
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(null, "pass".toCharArray());

            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.X509Certificate cert;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(certPath)) {
                cert = (java.security.cert.X509Certificate) cf.generateCertificate(fis);
            }

            // Read the private key PEM
            java.security.PrivateKey privKey = readPrivateKeyFromPem(keyPath);

            ks.setKeyEntry("alias", privKey, "pass".toCharArray(), new java.security.cert.Certificate[]{cert});

            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "pass".toCharArray());
            javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
            sslCtx.init(kmf.getKeyManagers(), null, null);

            com.sun.net.httpserver.HttpsServer server = com.sun.net.httpserver.HttpsServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(sslCtx));

            byte[] payload = "https self-signed content".getBytes();
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            });
            server.start();
            int port = server.getAddress().getPort();

            try {
                File outFile = new File(baseDir, "https.bin");
                invokeDownload(new java.net.URL("https://127.0.0.1:" + port + "/file.bin"), outFile);
                assertTrue("output file must exist after HTTPS download", outFile.exists());
                assertArrayEquals(payload, Files.readAllBytes(outFile.toPath()));
            } finally {
                server.stop(0);
            }
        } finally {
            System.setProperty("user.dir", originalDir);
            ConfigService.getInstance().getConfig().getDownloader().setIgnoreTLSCertificateError(false);
        }
    }

    private java.security.PrivateKey readPrivateKeyFromPem(String keyPath) throws Exception {
        String content = Files.readString(java.nio.file.Paths.get(keyPath));
        String pem = content.replaceAll("-----BEGIN.*-----", "")
                .replaceAll("-----END.*-----", "")
                .replaceAll("\\s", "");
        byte[] der = java.util.Base64.getDecoder().decode(pem);
        java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(der);
        return java.security.KeyFactory.getInstance("RSA").generatePrivate(spec);
    }
}

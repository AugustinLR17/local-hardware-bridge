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
}

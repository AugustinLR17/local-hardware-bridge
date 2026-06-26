package io.github.augustinlr17.localhardwarebridge.services;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.responses.PrintDocument;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Locale;

@Log4j2
public class DocumentService {
    @Getter
    private static final DocumentService instance = new DocumentService();

    public File prepareDocument(PrintDocument printDocument) throws Exception {
        Config.Downloader downloaderConfig = ConfigService.getInstance().getConfig().getDownloader();
        FileUtils.forceMkdir(new File(downloaderConfig.getPath()));

        if (printDocument.getUrl() == null && printDocument.getFileContent() == null) {
            throw new Exception("Both URL and File Content are null");
        }

        File output = getOutputFile(printDocument);
        if (printDocument.getFileContent() != null) {
            byte[] bytes = Base64.getDecoder().decode(printDocument.getFileContent());
            Files.write(output.toPath(), bytes);
        } else {
            URL url = new URL(printDocument.getUrl());
            download(url, output);
        }

        return output;
    }

    public void deleteDocument(PrintDocument printDocument) throws IOException {
        FileUtils.deleteQuietly(getOutputFile(printDocument));
    }

    private File getOutputFile(PrintDocument printDocument) throws IOException {
        Config.Downloader downloaderConfig = ConfigService.getInstance().getConfig().getDownloader();
        File baseDir = new File(downloaderConfig.getPath());

        String rawName;
        if (printDocument.getFileContent() != null) {
            // For inline content, getUrl() is only a suggested filename; strip any directories.
            rawName = FilenameUtils.getName(printDocument.getUrl());
        } else {
            URL url = new URL(printDocument.getUrl());
            rawName = FilenameUtils.getName(url.getPath());
        }
        if (rawName == null || rawName.isEmpty()) {
            rawName = printDocument.getUuid().toString();
        }

        File output = new File(baseDir, printDocument.getUuid() + "-" + rawName);

        // Defense in depth: ensure the resolved file stays inside the downloads directory.
        String basePath = baseDir.getCanonicalPath();
        String outputPath = output.getCanonicalPath();
        if (!outputPath.startsWith(basePath + File.separator)) {
            throw new IOException("Resolved output path escapes downloads directory: " + outputPath);
        }

        return output;
    }

    private void download(URL url, File outputFile) throws Exception {
        Config.Downloader downloaderConfig = ConfigService.getInstance().getConfig().getDownloader();
        log.info("Downloading file from: {}", url);

        // Only http/https are permitted (block file:, ftp:, jar:, etc.)
        String protocol = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IOException("Unsupported URL scheme: " + url.getProtocol());
        }

        // Optionally refuse to reach into private/loopback networks (SSRF mitigation).
        if (downloaderConfig.isBlockPrivateNetworks()) {
            verifyPublicHost(url.getHost());
        }

        long timeStart = System.currentTimeMillis();

        URLConnection urlConnection = url.openConnection();

        // Trust-all is scoped to THIS connection only; never mutate the JVM-wide default.
        if (downloaderConfig.isIgnoreTLSCertificateError() && urlConnection instanceof HttpsURLConnection) {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());

            HttpsURLConnection httpsConnection = (HttpsURLConnection) urlConnection;
            httpsConnection.setSSLSocketFactory(sc.getSocketFactory());
            httpsConnection.setHostnameVerifier((hostname, session) -> true);
        }

        urlConnection.setConnectTimeout((int) downloaderConfig.getTimeout() * 1000);
        urlConnection.setReadTimeout((int) downloaderConfig.getTimeout() * 1000);
        urlConnection.connect();

        int contentLength = urlConnection.getContentLength();
        int responseCode;
        if (urlConnection instanceof HttpsURLConnection) {
            responseCode = ((HttpsURLConnection) urlConnection).getResponseCode();
        } else {
            responseCode = ((HttpURLConnection) urlConnection).getResponseCode();
        }

        log.trace("Content Length: {}", contentLength);
        log.trace("Response Code: {}", responseCode);

        // Status code mismatch
        if (responseCode != 200) {
            throw new IOException("HTTP Status Code: " + responseCode);
        }

        FileUtils.copyInputStreamToFile(urlConnection.getInputStream(), outputFile);

        long timeFinish = System.currentTimeMillis();
        log.info("File {} downloaded in {} ms", outputFile.getName(), timeFinish - timeStart);
    }

    /**
     * Reject hosts that resolve to loopback/link-local/site-local/any-local/multicast
     * (i.e. private or reserved) addresses to mitigate SSRF against internal services.
     */
    private void verifyPublicHost(String host) throws IOException {
        if (host == null || host.isEmpty()) {
            throw new IOException("Cannot resolve empty host");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Unable to resolve host: " + host, e);
        }

        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IOException("Refusing to download from private/reserved address: " + address.getHostAddress());
            }
        }
    }
}

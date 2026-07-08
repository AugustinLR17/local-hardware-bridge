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
            // If the URL had no usable filename (or was null), infer an extension from
            // the decoded content's magic bytes so downstream libraries can identify it.
            if (rawName == null || rawName.isEmpty() || !rawName.contains(".")) {
                String ext = sniffExtension(printDocument.getFileContent());
                if (rawName == null || rawName.isEmpty()) {
                    rawName = printDocument.getUuid().toString() + ext;
                } else {
                    rawName = rawName + ext;
                }
            }
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

        // Disable auto-redirect following so that a 302 from a public URL to an
        // internal address (127.0.0.1, 169.254.169.254, etc.) cannot bypass the
        // SSRF host check above. Print jobs should not redirect.
        if (urlConnection instanceof HttpURLConnection) {
            ((HttpURLConnection) urlConnection).setInstanceFollowRedirects(false);
        }

        // Trust-all is scoped to THIS connection only; never mutate the JVM-wide default.
        // This is an opt-in feature (config.downloader.ignoreTLSCertificateError) for POS/WMS
        // environments that use self-signed certificates. It is off by default.
        if (downloaderConfig.isIgnoreTLSCertificateError() && urlConnection instanceof HttpsURLConnection) {
            @SuppressWarnings("java:S4830")
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) { /* trust-all: no verification */ }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) { /* trust-all: no verification */ }
                    }
            };

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());

            HttpsURLConnection httpsConnection = (HttpsURLConnection) urlConnection;
            httpsConnection.setSSLSocketFactory(sc.getSocketFactory());
            // Hostname verification is intentionally relaxed when the user opts in to
            // ignoreTLSertificateError (e.g. self-signed certs with mismatched CNs).
            @SuppressWarnings("java:S5527")
            var hostnameVerifier = (javax.net.ssl.HostnameVerifier) (hostname, session) -> true;
            httpsConnection.setHostnameVerifier(hostnameVerifier);
        }

        urlConnection.setConnectTimeout((int) downloaderConfig.getTimeout() * 1000);
        urlConnection.setReadTimeout((int) downloaderConfig.getTimeout() * 1000);

        try {
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

            // Reject redirects explicitly — print jobs should not redirect, and following
            // them would bypass the SSRF host check on the redirect target.
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                    || responseCode == 307 /* TEMP_REDIRECT */
                    || responseCode == 308 /* PERM_REDIRECT */) {
                throw new IOException("HTTP redirect (" + responseCode + ") not followed for security — original URL: " + url);
            }

            // Status code mismatch
            if (responseCode != 200) {
                throw new IOException("HTTP Status Code: " + responseCode);
            }

            try (var in = urlConnection.getInputStream()) {
                FileUtils.copyInputStreamToFile(in, outputFile);
            }

            long timeFinish = System.currentTimeMillis();
            log.info("File {} downloaded in {} ms", outputFile.getName(), timeFinish - timeStart);
        } finally {
            // Always disconnect to release the underlying socket — without this, a failed
            // download leaks a file descriptor (socket FD). Over time on a busy POS this
            // exhausts the process FD limit.
            if (urlConnection instanceof HttpURLConnection) {
                ((HttpURLConnection) urlConnection).disconnect();
            }
        }
    }

    /**
     * Sniffs the file extension from the leading bytes of Base64-encoded content.
     *
     * @param base64Content the Base64-encoded file content
     * @return a file extension including the dot (e.g. {@code ".pdf"}, {@code ".png"}),
     *         or {@code ""} if the content is absent or the signature is unrecognized.
     */
    static String sniffExtension(String base64Content) {
        if (base64Content == null || base64Content.isEmpty()) {
            return "";
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Content);
            int len = Math.min(decoded.length, 8);
            if (len < 4) {
                return "";
            }
            // PDF: %PDF
            if (decoded[0] == '%' && decoded[1] == 'P' && decoded[2] == 'D' && decoded[3] == 'F') {
                return ".pdf";
            }
            // PNG: 89 50 4E 47
            if ((decoded[0] & 0xFF) == 0x89 && decoded[1] == 'P' && decoded[2] == 'N' && decoded[3] == 'G') {
                return ".png";
            }
            // JPEG: FF D8 FF
            if ((decoded[0] & 0xFF) == 0xFF && (decoded[1] & 0xFF) == 0xD8 && (decoded[2] & 0xFF) == 0xFF) {
                return ".jpg";
            }
            // GIF: 47 49 46 38
            if (decoded[0] == 'G' && decoded[1] == 'I' && decoded[2] == 'F' && decoded[3] == '8') {
                return ".gif";
            }
        } catch (Exception e) {
            // ignore — return empty extension
        }
        return "";
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

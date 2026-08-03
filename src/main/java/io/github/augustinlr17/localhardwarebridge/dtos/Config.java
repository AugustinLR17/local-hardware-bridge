package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class Config {
    private GUI gui = new GUI();
    private Server server = new Server();
    private Security security = new Security();
    private Downloader downloader = new Downloader();
    private Printer printer = new Printer();
    private Serial serial = new Serial();
    private Update update = new Update();
    private PrintJobs printJobs = new PrintJobs();
    private Webhook webhook = new Webhook();

    public String toJson() throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(this);
    }

    @Data
    @NoArgsConstructor
    public static class GUI {
        private Notification notification = new Notification();
    }

    @Data
    @NoArgsConstructor
    public static class Notification {
        private boolean enabled = true;
    }

    @Data
    @NoArgsConstructor
    public static class Server {
        private String address = "127.0.0.1";
        private String bind = "127.0.0.1";
        private int port = 57212;
        private Authentication authentication = new Authentication();
        private TLS tls = new TLS();
        private Cors cors = new Cors();

        @JsonIgnore
        public String getUri() {
            return (tls.isEnabled() ? "https://" : "http://") + address + ":" + port;
        }

        @Data
        @NoArgsConstructor
        public static class Cors {
            private boolean allowAllOrigins = true;
            private java.util.List<String> allowedOrigins = new java.util.ArrayList<>();
        }
    }

    @Data
    @NoArgsConstructor
    public static class Authentication {
        private boolean enabled = false;
        private String token = null;
    }

    @Data
    @NoArgsConstructor
    public static class TLS {
        private boolean enabled = false ;
        private boolean selfSigned = true;
        private String cert = "tls/default-cert.pem";
        private String key = "tls/default-key.pem";
        private String caBundle = null;
    }

    @Data
    @NoArgsConstructor
    public static class Security {
        private Map<String, EndpointRule> endpoints = new HashMap<>();
    }

    @Data
    @NoArgsConstructor
    public static class EndpointRule {
        private boolean enabled = true;
        private String password = null;
    }

    @Data
    @NoArgsConstructor
    public static class Downloader {
        private boolean ignoreTLSCertificateError = false;
        private boolean blockPrivateNetworks = false;
        private double timeout = 30;
        private String path = "downloads";
    }

    @Data
    @NoArgsConstructor
    public static class Printer {
        private boolean enabled = true;
        private boolean autoAddUnknownType = false;
        private boolean fallbackToDefault = false;
        private ArrayList<PrinterMapping> mappings = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    public static class Serial {
        private boolean enabled = true;
        private ArrayList<SerialMapping> mappings = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrinterMapping {
        private String type;
        private String name;

        private boolean autoRotate = false;
        private boolean resetImageableArea = true;
        private int forceDPI = 0;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SerialMapping {
        private String type;
        private String name;

        private Integer baudRate;
        private Integer numDataBits;
        private Integer numStopBits;
        private Integer parity;

        private Boolean readMultipleBytes = false;
        private String readCharset = StandardCharsets.ISO_8859_1.toString();
    }

    /**
     * Auto-update configuration.
     *
     * <p>The update checker polls the GitHub Releases API and, if a newer
     * version is found, can optionally download and apply the new JAR.
     * Pre-release versions are ignored unless {@code includePrereleases} is true.
     */
    @Data
    @NoArgsConstructor
    public static class Update {
        /** Master switch: if false, no update checks are performed. */
        private boolean enabled = true;

        /**
         * If true, the new JAR is downloaded automatically when an update is
         * detected. If false (default, recommended for B2B/POS), the user is
         * only notified and must trigger the install manually.
         */
        private boolean autoDownload = false;

        /**
         * If true, apply the downloaded update on the next restart without
         * asking. Implies {@code autoDownload}.
         */
        private boolean autoInstall = false;

        /** Include pre-release versions (alpha, beta, RC) in checks. */
        private boolean includePrereleases = false;

        /**
         * Check interval in hours. 0 = only check on startup / manual trigger.
         * Default: 24 (once a day).
         */
        private int checkIntervalHours = 24;

        /** GitHub repository in {@code owner/repo} format. */
        private String repository = "AugustinLR17/local-hardware-bridge";

        /**
         * Optional channel filter: {@code "stable"} (default) or
         * {@code "prerelease"}. When {@code "prerelease"}, sets
         * {@code includePrereleases = true} at check time.
         */
        private String channel = "stable";
    }

    /**
     * Print-job durability, capacity, retention, and retry configuration.
     *
     * <p>All fields are default-initialized so that a pre-2.5 configuration
     * file (which does not contain a {@code printJobs} section) loads with
     * the approved defaults without manual migration.
     */
    @Data
    @NoArgsConstructor
    public static class PrintJobs {
        /** Maximum decoded payload size per job in bytes (default 10 MiB). */
        private int maxPayloadBytes = 10485760;

        /** Maximum number of simultaneously active (queued/printing) jobs. */
        private int maxQueuedJobs = 1000;

        /** Hard aggregate cap on all new persistent data in bytes (500 MiB). */
        private int maxPersistentBytes = 524288000;

        /** Proactive cleanup starts at this threshold in bytes (400 MiB = 80%). */
        private int cleanupThresholdBytes = 419430400;

        /** Cleanup target after crossing the threshold in bytes (350 MiB = 70%). */
        private int cleanupTargetBytes = 367001600;

        /** Minimum free disk space reserve in bytes (256 MiB). */
        private int minFreeBytes = 268435456;

        /** Minimum free disk space as a percentage of filesystem capacity. */
        private int minFreePercent = 5;

        /** Days to retain terminal success metadata before pruning. */
        private int successRetentionDays = 7;

        /** Days to retain terminal failure/unknown/cancelled metadata. */
        private int failureRetentionDays = 30;

        /** Initial retry delay in seconds (exponential backoff start). */
        private int initialRetryDelaySeconds = 30;

        /** Maximum retry delay in seconds (backoff cap). */
        private int maxRetryDelaySeconds = 3600;

        /** Maximum retry attempts before a retryable job becomes a final failure. */
        private int maxAttempts = 10;

        /** Maximum age in hours after which a retryable job becomes a final failure. */
        private int maxRetryAgeHours = 72;

        /** Number of worker threads for retry scheduling. */
        private int retryWorkers = 2;
    }

    /**
     * Webhook result-delivery configuration.
     *
     * <p>Disabled by default. The {@code secret} is the HMAC-SHA256 signing
     * key; it is masked in all config API responses and never logged. The
     * {@code blockPrivateNetworks} field is independent of the downloader's
     * setting but defaults to the same value ({@code false}).
     */
    @Data
    @NoArgsConstructor
    public static class Webhook {
        /** Master switch: when false, no webhook deliveries are attempted. */
        private boolean enabled = false;

        /** Target URL (HTTP/HTTPS only, no user-info). Null when disabled. */
        private String url = null;

        /** HMAC-SHA256 signing secret. Masked in API responses, never logged. */
        private String secret = null;

        /**
         * Independent SSRF guard for webhook delivery. Defaults to
         * {@code false}, matching the downloader's default.
         */
        private boolean blockPrivateNetworks = false;

        /** Connect timeout in seconds. */
        private int connectTimeoutSeconds = 10;

        /** Read timeout in seconds. */
        private int readTimeoutSeconds = 30;

        /** Maximum response body bytes to read from the receiver. */
        private int maxResponseBytes = 65536;

        /** Maximum delivery attempts before the outbox item becomes terminal. */
        private int maxAttempts = 10;

        /** Initial retry delay in seconds (exponential backoff start). */
        private int initialRetryDelaySeconds = 30;

        /** Maximum retry delay in seconds (backoff cap). */
        private int maxRetryDelaySeconds = 3600;

        /** Maximum age in hours after which an outbox item becomes terminal. */
        private int maxRetryAgeHours = 72;

        /** Number of worker threads for webhook delivery. */
        private int deliveryWorkers = 2;
    }
}
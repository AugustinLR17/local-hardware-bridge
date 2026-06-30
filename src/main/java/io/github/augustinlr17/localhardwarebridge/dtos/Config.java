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
}
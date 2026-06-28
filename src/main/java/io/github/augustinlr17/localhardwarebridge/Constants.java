package io.github.augustinlr17.localhardwarebridge;

public class Constants {
    public static final String APP_NAME = "Local Hardware Bridge";
    public static final String APP_ID = "io.github.augustinlr17.localhardwarebridge";
    public static final String VERSION = "2.0.0";

    // Legacy identifiers from the original TigerWorkshop fork — kept for backward compatibility.
    // Clients that check /system/version.json or service names can still recognise the app.
    public static final String LEGACY_APP_NAME = "WebApp Hardware Bridge";
    public static final String LEGACY_APP_ID = "tigerworkshop.webapphardwarebridge";

    // Service name used by the original fork (for migration detection)
    public static final String LEGACY_SERVICE_NAME = "webapp-hardware-bridge";
}

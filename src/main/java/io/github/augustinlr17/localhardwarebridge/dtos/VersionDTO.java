package io.github.augustinlr17.localhardwarebridge.dtos;

import io.github.augustinlr17.localhardwarebridge.Constants;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
public class VersionDTO {
    public String appName;
    public String appId;
    public String version;
    public String legacyAppName;
    public String legacyAppId;

    public VersionDTO(String appName, String appId, String version) {
        this.appName = appName;
        this.appId = appId;
        this.version = version;
        this.legacyAppName = Constants.LEGACY_APP_NAME;
        this.legacyAppId = Constants.LEGACY_APP_ID;
    }
}

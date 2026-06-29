package io.github.augustinlr17.localhardwarebridge.dtos;

import io.github.augustinlr17.localhardwarebridge.Constants;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VersionDTO {
    private String appName;
    private String appId;
    private String version;
    private String legacyAppName;
    private String legacyAppId;

    public VersionDTO(String appName, String appId, String version) {
        this.appName = appName;
        this.appId = appId;
        this.version = version;
        this.legacyAppName = Constants.LEGACY_APP_NAME;
        this.legacyAppId = Constants.LEGACY_APP_ID;
    }
}

package ee.nikolas.resalepilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "yaga.hiding")
public class YagaHidingProperties {

    private boolean enabled = false;
    private boolean confirmEnabled = false;
    private String authStatePath =
            "../playwright/.auth/yaga-state.json";
    private boolean headless = false;
    private int slowMoMs = 100;
    private String managementUrlTemplate =
            "https://www.yaga.ee/{shopSlug}/toode/{productSlug}";
    private Duration confirmationTtl = Duration.ofMinutes(10);
    private Duration hideDataPollTimeout = Duration.ofSeconds(30);
    private Duration hideDataPollInterval = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isConfirmEnabled() {
        return confirmEnabled;
    }

    public void setConfirmEnabled(boolean confirmEnabled) {
        this.confirmEnabled = confirmEnabled;
    }

    public String getAuthStatePath() {
        return authStatePath;
    }

    public void setAuthStatePath(String authStatePath) {
        this.authStatePath = authStatePath;
    }

    public boolean isHeadless() {
        return headless;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public int getSlowMoMs() {
        return slowMoMs;
    }

    public void setSlowMoMs(int slowMoMs) {
        this.slowMoMs = slowMoMs;
    }

    public String getManagementUrlTemplate() {
        return managementUrlTemplate;
    }

    public void setManagementUrlTemplate(
            String managementUrlTemplate
    ) {
        this.managementUrlTemplate = managementUrlTemplate;
    }

    public Duration getConfirmationTtl() {
        return confirmationTtl;
    }

    public void setConfirmationTtl(Duration confirmationTtl) {
        this.confirmationTtl = confirmationTtl;
    }

    public Duration getHideDataPollTimeout() {
        return hideDataPollTimeout;
    }

    public void setHideDataPollTimeout(Duration hideDataPollTimeout) {
        this.hideDataPollTimeout = hideDataPollTimeout;
    }

    public Duration getHideDataPollInterval() {
        return hideDataPollInterval;
    }

    public void setHideDataPollInterval(Duration hideDataPollInterval) {
        this.hideDataPollInterval = hideDataPollInterval;
    }
}

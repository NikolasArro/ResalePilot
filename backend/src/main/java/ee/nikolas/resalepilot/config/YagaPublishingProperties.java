package ee.nikolas.resalepilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yaga.publishing")
public class YagaPublishingProperties {

    private boolean enabled = false;
    private boolean confirmEnabled = false;
    private String authStatePath =
            "../playwright/.auth/yaga-state.json";
    private boolean headless = false;
    private int slowMoMs = 100;
    private String formUrl =
            "https://www.yaga.ee/muuk/lisa-toode";
    private java.time.Duration confirmationTtl =
            java.time.Duration.ofMinutes(10);
    private java.time.Duration publishDataPollTimeout =
            java.time.Duration.ofSeconds(30);
    private java.time.Duration publishDataPollInterval =
            java.time.Duration.ofSeconds(2);

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

    public String getFormUrl() {
        return formUrl;
    }

    public void setFormUrl(String formUrl) {
        this.formUrl = formUrl;
    }

    public java.time.Duration getConfirmationTtl() {
        return confirmationTtl;
    }

    public void setConfirmationTtl(
            java.time.Duration confirmationTtl
    ) {
        this.confirmationTtl = confirmationTtl;
    }

    public java.time.Duration getPublishDataPollTimeout() {
        return publishDataPollTimeout;
    }

    public void setPublishDataPollTimeout(
            java.time.Duration publishDataPollTimeout
    ) {
        this.publishDataPollTimeout = publishDataPollTimeout;
    }

    public java.time.Duration getPublishDataPollInterval() {
        return publishDataPollInterval;
    }

    public void setPublishDataPollInterval(
            java.time.Duration publishDataPollInterval
    ) {
        this.publishDataPollInterval = publishDataPollInterval;
    }
}

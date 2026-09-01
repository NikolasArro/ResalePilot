package ee.nikolas.resalepilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yaga.publishing")
public class YagaPublishingProperties {

    private boolean enabled = false;
    private String authStatePath =
            "../playwright/.auth/yaga-state.json";
    private boolean headless = false;
    private int slowMoMs = 100;
    private String formUrl =
            "https://www.yaga.ee/muuk/lisa-toode";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
}

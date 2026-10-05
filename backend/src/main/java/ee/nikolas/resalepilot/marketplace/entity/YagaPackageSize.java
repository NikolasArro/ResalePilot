package ee.nikolas.resalepilot.marketplace.entity;

import java.util.regex.Pattern;

/** Exact package-size code from Yaga shipping.selected_price. */
public record YagaPackageSize(String code) {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    public YagaPackageSize {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid Yaga carrier package-size code");
        }
    }
}

package ee.nikolas.resalepilot.marketplace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Null on MarketplaceListing means the Yaga delivery choices have not been imported. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YagaDeliverySettings {
    @Column(name = "delivery_omniva_enabled")
    private Boolean omnivaEnabled;
    @Convert(converter = YagaPackageSizeConverter.class)
    @Column(name = "delivery_omniva_size", length = 32)
    private YagaPackageSize omnivaSize;
    @Column(name = "delivery_dpd_enabled")
    private Boolean dpdEnabled;
    @Convert(converter = YagaPackageSizeConverter.class)
    @Column(name = "delivery_dpd_size", length = 32)
    private YagaPackageSize dpdSize;
    @Column(name = "delivery_smartpost_enabled")
    private Boolean smartpostEnabled;
    @Convert(converter = YagaPackageSizeConverter.class)
    @Column(name = "delivery_smartpost_size", length = 32)
    private YagaPackageSize smartpostSize;
    @Column(name = "delivery_pickup_enabled")
    private Boolean pickupEnabled;
    @Column(name = "delivery_agreement_enabled")
    private Boolean agreementEnabled;
    @Column(name = "delivery_bundling_enabled")
    private Boolean bundlingEnabled;

    public YagaDeliverySettings(
            boolean omnivaEnabled, YagaPackageSize omnivaSize,
            boolean dpdEnabled, YagaPackageSize dpdSize,
            boolean smartpostEnabled, YagaPackageSize smartpostSize,
            boolean pickupEnabled, boolean agreementEnabled, boolean bundlingEnabled
    ) {
        if ((omnivaEnabled && omnivaSize == null) ||
                (dpdEnabled && dpdSize == null) ||
                (smartpostEnabled && smartpostSize == null)) {
            throw new IllegalArgumentException("Enabled Yaga carrier requires a package size");
        }
        this.omnivaEnabled = omnivaEnabled;
        this.omnivaSize = omnivaEnabled ? omnivaSize : null;
        this.dpdEnabled = dpdEnabled;
        this.dpdSize = dpdEnabled ? dpdSize : null;
        this.smartpostEnabled = smartpostEnabled;
        this.smartpostSize = smartpostEnabled ? smartpostSize : null;
        this.pickupEnabled = pickupEnabled;
        this.agreementEnabled = agreementEnabled;
        this.bundlingEnabled = bundlingEnabled;
    }
}

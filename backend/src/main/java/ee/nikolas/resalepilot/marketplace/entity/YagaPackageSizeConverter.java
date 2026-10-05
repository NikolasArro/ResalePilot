package ee.nikolas.resalepilot.marketplace.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class YagaPackageSizeConverter implements AttributeConverter<YagaPackageSize, String> {
    @Override
    public String convertToDatabaseColumn(YagaPackageSize size) {
        return size == null ? null : size.code();
    }

    @Override
    public YagaPackageSize convertToEntityAttribute(String code) {
        return code == null ? null : new YagaPackageSize(code);
    }
}

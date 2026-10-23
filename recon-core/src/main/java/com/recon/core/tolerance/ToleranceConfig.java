package com.recon.core.tolerance;

import com.recon.core.model.AssetClass;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * Tolerances for every asset class. Built from already-loaded {@link Properties} so this module
 * stays free of file I/O; callers decide where the properties come from.
 *
 * <p>Keys look like {@code tolerance.EQUITY.price.abs}; see {@code config/tolerances.properties}.
 */
public final class ToleranceConfig {

    private final Map<AssetClass, Tolerance> byAssetClass;

    public ToleranceConfig(Map<AssetClass, Tolerance> byAssetClass) {
        EnumMap<AssetClass, Tolerance> copy = new EnumMap<>(AssetClass.class);
        copy.putAll(byAssetClass);
        for (AssetClass assetClass : AssetClass.values()) {
            if (!copy.containsKey(assetClass)) {
                throw new IllegalArgumentException("No tolerance configured for asset class " + assetClass);
            }
        }
        this.byAssetClass = copy;
    }

    public Tolerance forAssetClass(AssetClass assetClass) {
        return byAssetClass.get(assetClass);
    }

    public static ToleranceConfig fromProperties(Properties properties) {
        Map<AssetClass, Tolerance> tolerances = new EnumMap<>(AssetClass.class);
        for (AssetClass assetClass : AssetClass.values()) {
            String prefix = "tolerance." + assetClass.name() + ".";
            tolerances.put(assetClass, new Tolerance(
                    decimal(properties, prefix + "price.abs"),
                    decimal(properties, prefix + "price.bps"),
                    decimal(properties, prefix + "netAmount.abs"),
                    decimal(properties, prefix + "netAmount.bps"),
                    decimal(properties, prefix + "commission.abs")));
        }
        return new ToleranceConfig(tolerances);
    }

    private static BigDecimal decimal(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing tolerance property '" + key + "'");
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Tolerance property '" + key + "' is not a decimal number: '" + value + "'", e);
        }
    }
}

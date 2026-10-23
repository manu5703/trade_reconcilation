package com.recon.core.tolerance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.recon.core.model.AssetClass;
import java.math.BigDecimal;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ToleranceTest {

    // price: max(0.005, 2 bps); net: max(0.05, 2 bps); commission: 0.01
    private final Tolerance tolerance = new Tolerance(
            new BigDecimal("0.005"), new BigDecimal("2"),
            new BigDecimal("0.05"), new BigDecimal("2"),
            new BigDecimal("0.01"));

    @Test
    void absoluteFloorAppliesToCheapPrices() {
        // 2 bps of 10.00 is 0.002, below the 0.005 floor.
        assertThat(tolerance.priceTolerance(new BigDecimal("10.00"))).isEqualByComparingTo("0.005");
    }

    @Test
    void relativeBandAppliesToExpensivePrices() {
        // 2 bps of 500.00 is 0.10.
        assertThat(tolerance.priceTolerance(new BigDecimal("500.00"))).isEqualByComparingTo("0.10");
    }

    @Test
    void bandEdgeIsInclusive() {
        assertThat(tolerance.priceWithin(new BigDecimal("500.00"), new BigDecimal("500.10"))).isTrue();
        assertThat(tolerance.priceWithin(new BigDecimal("500.00"), new BigDecimal("500.100001"))).isFalse();
        assertThat(tolerance.priceWithin(new BigDecimal("500.00"), new BigDecimal("499.90"))).isTrue();
    }

    @Test
    void netAmountBandUsesAbsoluteValueOfExpected() {
        // 2 bps of |-1,000,000| is 200.
        assertThat(tolerance.netAmountTolerance(new BigDecimal("-1000000"))).isEqualByComparingTo("200");
    }

    @Test
    void commissionIsAbsolute() {
        assertThat(tolerance.commissionWithin(new BigDecimal("12.34"), new BigDecimal("12.35"))).isTrue();
        assertThat(tolerance.commissionWithin(new BigDecimal("12.34"), new BigDecimal("12.36"))).isFalse();
    }

    @Test
    void rejectsNegativeTolerance() {
        assertThatThrownBy(() -> new Tolerance(new BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priceAbs");
    }

    @Test
    void loadsEveryAssetClassFromProperties() {
        ToleranceConfig config = ToleranceConfig.fromProperties(completeProperties());

        assertThat(config.forAssetClass(AssetClass.EQUITY).priceAbs()).isEqualByComparingTo("0.005");
        assertThat(config.forAssetClass(AssetClass.FIXED_INCOME).priceBps()).isEqualByComparingTo("1");
    }

    @Test
    void missingPropertyIsNamedInError() {
        Properties properties = completeProperties();
        properties.remove("tolerance.FIXED_INCOME.commission.abs");

        assertThatThrownBy(() -> ToleranceConfig.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing tolerance property 'tolerance.FIXED_INCOME.commission.abs'");
    }

    @Test
    void nonNumericPropertyIsNamedInError() {
        Properties properties = completeProperties();
        properties.setProperty("tolerance.EQUITY.price.bps", "two");

        assertThatThrownBy(() -> ToleranceConfig.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tolerance.EQUITY.price.bps")
                .hasMessageContaining("'two'");
    }

    private static Properties completeProperties() {
        Properties p = new Properties();
        p.setProperty("tolerance.EQUITY.price.abs", "0.005");
        p.setProperty("tolerance.EQUITY.price.bps", "2");
        p.setProperty("tolerance.EQUITY.netAmount.abs", "0.05");
        p.setProperty("tolerance.EQUITY.netAmount.bps", "2");
        p.setProperty("tolerance.EQUITY.commission.abs", "0.01");
        p.setProperty("tolerance.FIXED_INCOME.price.abs", " 0.001 ");
        p.setProperty("tolerance.FIXED_INCOME.price.bps", "1");
        p.setProperty("tolerance.FIXED_INCOME.netAmount.abs", "0.05");
        p.setProperty("tolerance.FIXED_INCOME.netAmount.bps", "1");
        p.setProperty("tolerance.FIXED_INCOME.commission.abs", "0.01");
        return p;
    }
}

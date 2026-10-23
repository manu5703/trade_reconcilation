package com.recon.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ModelTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 1, 15);

    @Test
    void buyerPaysCommissionSellerReceivesNetOfIt() {
        BigDecimal principal = new BigDecimal("1000.00");
        BigDecimal commission = new BigDecimal("1.50");

        assertThat(Side.BUY.netOf(principal, commission)).isEqualByComparingTo("1001.50");
        assertThat(Side.SELL.netOf(principal, commission)).isEqualByComparingTo("998.50");
    }

    @Test
    void bondPrincipalIsPercentOfFace() {
        assertThat(AssetClass.FIXED_INCOME.principal(new BigDecimal("250000"), new BigDecimal("99.125")))
                .isEqualByComparingTo("247812.50");
        assertThat(AssetClass.EQUITY.principal(new BigDecimal("300"), new BigDecimal("12.3456")))
                .isEqualByComparingTo("3703.68");
    }

    @Test
    void blockAllocationIsDetectedFromBlockRef() {
        assertThat(trade("BLK-1").isBlockAllocation()).isTrue();
        assertThat(trade(null).isBlockAllocation()).isFalse();
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> new InternalTrade("T1", null, "ACC1", "US0378331005", Side.BUY,
                BigDecimal.ZERO, BigDecimal.TEN, TRADE_DATE, TRADE_DATE, "CP001", BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quantity must be positive but was 0");
    }

    @Test
    void rejectsMissingBrokerRef() {
        assertThatThrownBy(() -> new ExternalConfirm(" ", Source.BROKER, "US0378331005", Side.BUY,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, TRADE_DATE, TRADE_DATE, "CP001", BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("brokerRef must not be blank");
    }

    private static InternalTrade trade(String blockRef) {
        return new InternalTrade("T1", blockRef, "ACC1", "US0378331005", Side.BUY,
                BigDecimal.ONE, BigDecimal.TEN, TRADE_DATE, TRADE_DATE, "CP001", BigDecimal.TEN);
    }
}

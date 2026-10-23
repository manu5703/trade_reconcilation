package com.recon.core.match;

import static com.recon.core.match.Fixtures.ALPHA;
import static com.recon.core.match.Fixtures.APPLE;
import static com.recon.core.match.Fixtures.STATIC_DATA;
import static com.recon.core.match.Fixtures.confirmFor;
import static com.recon.core.match.Fixtures.trade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.recon.core.model.AssetClass;
import com.recon.core.model.InternalTrade;
import org.junit.jupiter.api.Test;

class NormalizerTest {

    private final Normalizer normalizer = new Normalizer(STATIC_DATA);

    @Test
    void trimsAndUpperCasesIsinAndMapsAliasToCanonicalId() {
        InternalTrade trade = trade("T1").build();

        NormalizedConfirm confirm = normalizer.normalize(confirmFor(trade, "C1")
                .isin("  us0378331005 ").counterparty(" alpha   securities llc").build());

        assertThat(confirm.isin()).isEqualTo(APPLE);
        assertThat(confirm.counterparty()).isEqualTo(ALPHA);
        assertThat(confirm.ref()).isEqualTo("C1");
    }

    @Test
    void unknownConfirmCounterpartyPassesThroughNormalized() {
        NormalizedConfirm confirm = normalizer.normalize(confirmFor(trade("T1").build(), "C1").counterparty("Gamma & Co").build());

        assertThat(confirm.counterparty()).isEqualTo("GAMMA & CO");
    }

    @Test
    void decimalsGetOneScaleSoTheyCanBeHashKeys() {
        NormalizedTrade a = normalizer.normalize(trade("T1").quantity("1000").price("100.5").build());
        NormalizedTrade b = normalizer.normalize(trade("T2").quantity("1000.000").price("100.500000").build());

        assertThat(a.quantity()).isEqualTo(b.quantity()).hasToString("1000.000000");
        assertThat(a.price()).isEqualTo(b.price());
    }

    @Test
    void tradeGetsScheduledCommissionAndAssetClass() {
        // 1,000 × 100.00 = 100,000 principal at 1.0 bps = 10.00.
        NormalizedTrade trade = normalizer.normalize(trade("T1").build());

        assertThat(trade.expectedCommission()).isEqualByComparingTo("10.00");
        assertThat(trade.assetClass()).isEqualTo(AssetClass.EQUITY);
        assertThat(trade.blockRef()).isNull();
    }

    @Test
    void unknownIsinOnInternalTradeIsAReferenceDataError() {
        InternalTrade valid = trade("T1").build();
        InternalTrade unknownIsin = new InternalTrade("T1", null, valid.account(), "GB0002634946", valid.side(), valid.quantity(),
                valid.price(), valid.tradeDate(), valid.settleDate(), valid.counterparty(), valid.netAmount());

        assertThatThrownBy(() -> normalizer.normalize(unknownIsin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("T1").hasMessageContaining("GB0002634946");
    }
}

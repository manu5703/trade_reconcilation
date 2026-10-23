package com.recon.datagen.score;

import static org.assertj.core.api.Assertions.assertThat;

import com.recon.core.match.MatchResult;
import com.recon.core.match.Matcher;
import com.recon.core.model.BreakType;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.datagen.DatagenConfig;
import com.recon.datagen.TestSupportAccess;
import com.recon.datagen.TradeCase;
import com.recon.datagen.output.Label;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End to end on generator data at the default settings: the full matcher must recover every
 * labelled break with no false breaks, and must beat the exact-only baseline.
 */
class MatcherAccuracyTest {

    private static List<Label> labels;
    private static List<InternalTrade> trades;
    private static List<ExternalConfirm> confirms;
    private static TestSupportAccess.Generated generated;

    @BeforeAll
    static void generate() {
        generated = TestSupportAccess.generate(DatagenConfig.builder().tradesPerDay(20_000).seed(2026).build());
        List<TradeCase> cases = generated.cases();
        labels = cases.stream().map(c -> new Label(c.caseId(), c.breakType(), c.breakField(), c.noise(), c.blockRef(),
                c.trades().stream().map(InternalTrade::tradeRef).toList(),
                c.confirms().stream().map(ExternalConfirm::brokerRef).toList())).toList();
        trades = cases.stream().flatMap(c -> c.trades().stream()).toList();
        confirms = cases.stream().flatMap(c -> c.confirms().stream()).toList();
    }

    @Test
    void fullMatcherFindsEveryBreakWithNoFalseBreaks() {
        Scorecard card = Scorecard.score(labels, Matcher.full(generated.staticData(), generated.tolerances()).match(trades, confirms));

        for (BreakType type : BreakType.values()) {
            assertThat(card.byType().get(type).precision()).as(type + " precision").isEqualTo(1.0);
            assertThat(card.byType().get(type).recall()).as(type + " recall").isEqualTo(1.0);
        }
        assertThat(card.noiseCasesWithFalseBreaks()).isZero();
        assertThat(card.cleanCasesCorrectlyPaired()).isEqualTo(card.cleanCases());
        assertThat(card.straightThroughTrades()).isEqualTo(card.idealStraightThroughTrades());
    }

    @Test
    void baselineMissesFieldBreaksBlocksAndDuplicates() {
        MatchResult result = Matcher.baseline(generated.staticData(), generated.tolerances()).match(trades, confirms);
        Scorecard card = Scorecard.score(labels, result);

        assertThat(card.byType().get(BreakType.QUANTITY_MISMATCH).recall()).isZero();
        assertThat(card.byType().get(BreakType.DUPLICATE_CONFIRM).recall()).isZero();
        // Every block shows up as missing confirms plus an unexpected confirm.
        assertThat(card.byType().get(BreakType.MISSING_CONFIRM).precision()).isLessThan(0.1);
        assertThat(card.stpRate()).isLessThan(card.idealStpRate() - 0.10);
    }

    @Test
    void matchingIsReproducible() {
        Matcher matcher = Matcher.full(generated.staticData(), generated.tolerances());

        assertThat(matcher.match(trades, confirms)).isEqualTo(matcher.match(trades.reversed(), confirms.reversed()));
    }
}

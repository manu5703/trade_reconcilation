package com.recon.core.match;

import static com.recon.core.match.Fixtures.BETA;
import static com.recon.core.match.Fixtures.BOND;
import static com.recon.core.match.Fixtures.MICROSOFT;
import static com.recon.core.match.Fixtures.T_PLUS_1;
import static com.recon.core.match.Fixtures.confirmFor;
import static com.recon.core.match.Fixtures.confirmForBlock;
import static com.recon.core.match.Fixtures.fullMatcher;
import static com.recon.core.match.Fixtures.trade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.recon.core.model.BreakType;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.model.Side;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** One nested class per pass, each driven through the public Matcher. */
class PassesTest {

    private static MatchResult match(List<InternalTrade> trades, List<ExternalConfirm> confirms) {
        return fullMatcher().match(trades, confirms);
    }

    @Nested
    class Exact {

        @Test
        void identicalPairMatchesExactlyWithNoBreaks() {
            InternalTrade t = trade("T1").build();

            MatchResult result = match(List.of(t), List.of(confirmFor(t, "C1").build()));

            assertThat(result.matches()).containsExactly(Match.of(MatchRule.EXACT, 100, List.of("T1"), "C1"));
            assertThat(result.breaks()).isEmpty();
        }

        @Test
        void priceWithinToleranceStillMatchesExactly() {
            // Tolerance at 100.00 is max(0.005, 2 bps = 0.02) = 0.02.
            InternalTrade t = trade("T1").build();

            MatchResult result = match(List.of(t), List.of(confirmFor(t, "C1").price("100.02").build()));

            assertThat(result.matches()).extracting(Match::rule).containsExactly(MatchRule.EXACT);
            assertThat(result.breaks()).isEmpty();
        }

        @Test
        void netAmountRoundingWithinToleranceStillMatches() {
            InternalTrade t = trade("T1").build();

            MatchResult result = match(List.of(t), List.of(confirmFor(t, "C1").netAmount(t.netAmount().add(Fixtures.dec("0.004")).toPlainString()).build()));

            assertThat(result.matches()).extracting(Match::rule).containsExactly(MatchRule.EXACT);
        }

        @Test
        void commissionDifferenceIsNotSwallowedByTheExactPass() {
            InternalTrade t = trade("T1").build();

            MatchResult result = match(List.of(t), List.of(confirmFor(t, "C1").commission("25.00").build()));

            assertThat(result.matches()).extracting(Match::rule).containsExactly(MatchRule.FUZZY);
            assertThat(result.breaks()).extracting(Break::type).containsExactly(BreakType.COMMISSION_DIFFERENCE);
        }

        @Test
        void eachTradeTakesAtMostOneConfirm() {
            InternalTrade a = trade("T1").build();
            InternalTrade b = trade("T2").build();

            MatchResult result = match(List.of(a, b), List.of(confirmFor(a, "C1").build(), confirmFor(b, "C2").build()));

            assertThat(result.matches()).hasSize(2).allSatisfy(m -> assertThat(m.rule()).isEqualTo(MatchRule.EXACT));
            assertThat(result.breaks()).isEmpty();
        }
    }

    @Nested
    class Block {

        private final List<InternalTrade> allocations = List.of(
                trade("T1").block("BLK-1").account("ACC00001").quantity("300").price("100.00").build(),
                trade("T2").block("BLK-1").account("ACC00002").quantity("500").price("100.03").build(),
                trade("T3").block("BLK-1").account("ACC00003").quantity("200").price("99.98").build());

        @Test
        void blockConfirmAtWeightedAveragePriceLinksEveryAllocation() {
            // Weighted average = (300×100 + 500×100.03 + 200×99.98) / 1000 = 100.011
            ExternalConfirm confirm = confirmForBlock(allocations, "C1").build();
            assertThat(confirm.price()).isEqualByComparingTo("100.011");

            MatchResult result = match(allocations, List.of(confirm));

            assertThat(result.matches()).containsExactly(Match.of(MatchRule.BLOCK, 100, List.of("T1", "T2", "T3"), "C1"));
            assertThat(result.links()).extracting(MatchLink::internalRef).containsExactly("T1", "T2", "T3");
            assertThat(result.breaks()).isEmpty();
        }

        @Test
        void blockWhoseQuantitiesDontSumIsNotMatchedAsABlock() {
            MatchResult result = match(allocations, List.of(confirmForBlock(allocations, "C1").quantity("999").build()));

            assertThat(result.matches()).extracting(Match::rule).doesNotContain(MatchRule.BLOCK);
        }

        @Test
        void blockPriceOutsideToleranceOfAverageIsNotMatched() {
            MatchResult result = match(allocations, List.of(confirmForBlock(allocations, "C1").price("100.50").build()));

            assertThat(result.matches()).extracting(Match::rule).doesNotContain(MatchRule.BLOCK);
        }
    }

    @Nested
    class Fuzzy {

        private final InternalTrade t = trade("T1").build();

        @ParameterizedTest
        @EnumSource(value = BreakType.class, names = {
            "QUANTITY_MISMATCH", "PRICE_OUT_OF_TOLERANCE", "SETTLE_DATE_MISMATCH", "SIDE_MISMATCH",
            "COUNTERPARTY_MISMATCH", "COMMISSION_DIFFERENCE"})
        void oneWrongFieldGivesOneFieldBreak(BreakType type) {
            ExternalConfirm confirm = switch (type) {
                case QUANTITY_MISMATCH -> confirmFor(t, "C1").quantity("900").build();
                case PRICE_OUT_OF_TOLERANCE -> confirmFor(t, "C1").price("100.50").build();
                case SETTLE_DATE_MISMATCH -> confirmFor(t, "C1").settleDate(T_PLUS_1.plusDays(1)).build();
                case SIDE_MISMATCH -> confirmFor(t, "C1").side(Side.SELL).build();
                case COUNTERPARTY_MISMATCH -> confirmFor(t, "C1").counterparty(BETA).build();
                case COMMISSION_DIFFERENCE -> confirmFor(t, "C1").commission("60.00").build();
                default -> throw new IllegalArgumentException();
            };

            MatchResult result = match(List.of(t), List.of(confirm));

            assertThat(result.matches()).singleElement().satisfies(m -> {
                assertThat(m.rule()).isEqualTo(MatchRule.FUZZY);
                assertThat(m.score()).isGreaterThanOrEqualTo(Fixtures.dec("0.75"));
            });
            // The net amount moved too, but only the root-cause field is reported.
            assertThat(result.breaks()).singleElement().satisfies(b -> {
                assertThat(b.type()).isEqualTo(type);
                assertThat(b.internalRef()).isEqualTo("T1");
                assertThat(b.externalRef()).isEqualTo("C1");
            });
        }

        @Test
        void fieldBreakRecordsExpectedAndActualValues() {
            MatchResult result = match(List.of(t), List.of(confirmFor(t, "C1").quantity("900").build()));

            assertThat(result.breaks()).containsExactly(new Break(BreakType.QUANTITY_MISMATCH, "T1", "C1", "quantity", "1000", "900"));
        }

        @Test
        void netAmountOnlyDifferenceIsReportedAsFeesAndCharges() {
            MatchResult result = match(List.of(t), List.of(confirmFor(t, "C1").netAmount("100500.00").build()));

            assertThat(result.breaks()).singleElement().satisfies(b -> {
                assertThat(b.type()).isEqualTo(BreakType.COMMISSION_DIFFERENCE);
                assertThat(b.field()).isEqualTo("netAmount");
            });
        }

        @Test
        void twoWrongFieldsScoreBelowThresholdAndStayUnmatched() {
            MatchResult result = match(List.of(t), List.of(confirmFor(t, "C1").quantity("900").counterparty(BETA).build()));

            assertThat(result.matches()).isEmpty();
            assertThat(result.breaks()).extracting(Break::type)
                    .containsExactlyInAnyOrder(BreakType.MISSING_CONFIRM, BreakType.UNEXPECTED_CONFIRM);
        }

        @Test
        void differentIsinIsNeverPaired() {
            MatchResult result = match(List.of(t), List.of(confirmFor(t, "C1").isin(MICROSOFT).build()));

            assertThat(result.matches()).isEmpty();
        }

        @Test
        void greedyTakesTheBestPairFirst() {
            InternalTrade other = trade("T2").quantity("2000").build();
            // C1 is one field off from T1 (score 75); C2 is one field off from T2 via settle date (score 85)
            // and two fields off from T1. The best pair wins first, then the rest pair up.
            ExternalConfirm c1 = confirmFor(t, "C1").price("100.50").build();
            ExternalConfirm c2 = confirmFor(other, "C2").settleDate(T_PLUS_1.plusDays(1)).build();

            MatchResult result = match(List.of(t, other), List.of(c1, c2));

            assertThat(result.matches()).extracting(Match::internalRefs, Match::externalRef).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(List.of("T1"), "C1"),
                    org.assertj.core.groups.Tuple.tuple(List.of("T2"), "C2"));
            assertThat(result.matches()).extracting(Match::score).containsExactly(Fixtures.dec("0.75"), Fixtures.dec("0.85"));
        }

        @Test
        void bondPriceToleranceIsTighter() {
            InternalTrade bond = trade("T1").isin(BOND).quantity("100000").price("99.500").build();
            // Bond tolerance at 99.5: max(0.001, 1 bp = 0.00995) ≈ 0.00995, so 0.02 is a break.
            MatchResult result = match(List.of(bond), List.of(confirmFor(bond, "C1").price("99.520").build()));

            assertThat(result.breaks()).extracting(Break::type).containsExactly(BreakType.PRICE_OUT_OF_TOLERANCE);
        }
    }

    @Nested
    class Leftovers {

        @Test
        void tradeWithoutConfirmIsMissing() {
            MatchResult result = match(List.of(trade("T1").build()), List.of());

            assertThat(result.breaks()).containsExactly(new Break(BreakType.MISSING_CONFIRM, "T1", null, null, null, null));
        }

        @Test
        void confirmWithoutTradeIsUnexpected() {
            MatchResult result = match(List.of(), List.of(confirmFor(trade("T1").build(), "C1").build()));

            assertThat(result.breaks()).containsExactly(new Break(BreakType.UNEXPECTED_CONFIRM, null, "C1", null, null, null));
        }

        @Test
        void secondConfirmForTheSameTradeIsADuplicate() {
            InternalTrade t = trade("T1").build();

            MatchResult result = match(List.of(t), List.of(confirmFor(t, "C1").build(), confirmFor(t, "C2").build()));

            assertThat(result.matches()).extracting(Match::externalRef).containsExactly("C1");
            assertThat(result.breaks()).containsExactly(new Break(BreakType.DUPLICATE_CONFIRM, "T1", "C2", null, "C1", "C2"));
        }

        @Test
        void resentConfirmIsADuplicateEvenWhenAnotherTradeCouldFuzzyMatchIt() {
            // T2 differs from T1 only in price, so C2 (a resend of C1) would score 75 against T2.
            // Duplicate detection runs before the fuzzy pass, so C2 is a duplicate and T2 stays missing.
            InternalTrade t = trade("T1").build();
            InternalTrade lookalike = trade("T2").price("101.00").build();

            MatchResult result = match(List.of(t, lookalike), List.of(confirmFor(t, "C1").build(), confirmFor(t, "C2").build()));

            assertThat(result.breaks()).containsExactly(
                    new Break(BreakType.DUPLICATE_CONFIRM, "T1", "C2", null, "C1", "C2"),
                    new Break(BreakType.MISSING_CONFIRM, "T2", null, null, null, null));
        }

        @Test
        void baselineReportsDuplicatesAndFieldBreaksAsMissingOrUnexpected() {
            InternalTrade t = trade("T1").build();
            InternalTrade u = trade("T2").quantity("2000").build();
            List<ExternalConfirm> confirms = List.of(confirmFor(t, "C1").build(), confirmFor(t, "C2").build(),
                    confirmFor(u, "C3").quantity("1999").build());

            MatchResult result = Matcher.baseline(Fixtures.STATIC_DATA, Fixtures.TOLERANCES).match(List.of(t, u), confirms);

            assertThat(result.breaks()).extracting(Break::type).containsExactlyInAnyOrder(
                    BreakType.UNEXPECTED_CONFIRM, BreakType.MISSING_CONFIRM, BreakType.UNEXPECTED_CONFIRM);
        }
    }

    @Test
    void duplicateInputRefsAreRejected() {
        InternalTrade t = trade("T1").build();

        assertThatThrownBy(() -> match(List.of(t, t), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate trade reference in input: T1");
    }
}

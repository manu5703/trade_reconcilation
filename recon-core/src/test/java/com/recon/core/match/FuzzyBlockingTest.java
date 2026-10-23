package com.recon.core.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** Blocking in the fuzzy pass must never change which pairs it finds; only how many it compares. */
class FuzzyBlockingTest {

    @Test
    void everyAcceptablePairAgreesOnAtLeastTwoBlockingFields() {
        // Exhaustive over all 2^7 combinations of differing fields: no combination that differs
        // in two or more of side, counterparty and quantity can score above the threshold.
        Field[] fields = Field.values();
        for (int mask = 0; mask < 1 << fields.length; mask++) {
            Set<Field> differing = EnumSet.noneOf(Field.class);
            for (int i = 0; i < fields.length; i++) {
                if ((mask & 1 << i) != 0) {
                    differing.add(fields[i]);
                }
            }
            int score = 100 - differing.stream().mapToInt(Field::penalty).sum();
            long blockingFieldsDiffering = FuzzyPass.BLOCKING_FIELDS.stream().filter(differing::contains).count();
            if (score > Matcher.DEFAULT_FUZZY_THRESHOLD) {
                assertThat(blockingFieldsDiffering).as("accepted combination %s", differing).isLessThanOrEqualTo(1);
            }
        }
    }

    @Test
    void thresholdTooLowForBlockingIsRejected() {
        assertThat(FuzzyPass.blockingIsLossless(60)).isTrue();
        assertThatThrownBy(() -> new FuzzyPass(59))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too low for blocking");
    }

    @Property(tries = 500)
    void blockedPassFindsExactlyWhatComparingEveryPairFinds(@ForAll @IntRange(max = 60) int cases, @ForAll long seed) {
        MatcherPropertiesTest.Book book = MatcherPropertiesTest.randomBook(cases, seed);
        Matcher unblocked = new Matcher(Fixtures.STATIC_DATA, Fixtures.TOLERANCES, List.of(new BlockPass(), new ExactPass(),
                new DuplicatePass(), new AllPairsFuzzyPass(Matcher.DEFAULT_FUZZY_THRESHOLD), new LeftoversPass()));

        assertThat(Fixtures.fullMatcher().match(book.trades(), book.confirms()))
                .isEqualTo(unblocked.match(book.trades(), book.confirms()));
    }

    /** The reference: the fuzzy pass before blocking, scoring every pair in an (isin, tradeDate) bucket. */
    private static final class AllPairsFuzzyPass implements MatchPass {
        private final int threshold;

        AllPairsFuzzyPass(int threshold) {
            this.threshold = threshold;
        }

        private record Candidate(NormalizedTrade trade, NormalizedConfirm confirm, int score, List<FieldDiff> diffs) {
        }

        @Override
        public void apply(MatchState state) {
            List<Candidate> candidates = new ArrayList<>();
            for (NormalizedTrade trade : state.unmatchedTrades()) {
                for (NormalizedConfirm confirm : state.unmatchedConfirms()) {
                    if (!confirm.isin().equals(trade.isin()) || !confirm.tradeDate().equals(trade.tradeDate())) {
                        continue;
                    }
                    List<FieldDiff> diffs = Expectation.of(trade).diff(confirm, state.tolerances.forAssetClass(trade.assetClass()));
                    int score = 100 - diffs.stream().mapToInt(d -> d.field().penalty()).sum();
                    if (score > threshold) {
                        candidates.add(new Candidate(trade, confirm, score, diffs));
                    }
                }
            }
            candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                    .thenComparing(c -> c.trade().ref()).thenComparing(c -> c.confirm().ref()));
            Set<String> trades = new HashSet<>();
            Set<String> confirms = new HashSet<>();
            for (Candidate c : candidates) {
                if (trades.contains(c.trade().ref()) || confirms.contains(c.confirm().ref())) {
                    continue;
                }
                trades.add(c.trade().ref());
                confirms.add(c.confirm().ref());
                state.addMatch(Match.of(MatchRule.FUZZY, c.score(), List.of(c.trade().ref()), c.confirm().ref()));
                boolean netOnly = c.diffs().size() == 1 && c.diffs().getFirst().field() == Field.NET_AMOUNT;
                c.diffs().stream().filter(d -> netOnly || d.field() != Field.NET_AMOUNT)
                        .forEach(d -> state.breaks.add(Break.fieldBreak(c.trade().ref(), c.confirm().ref(), d)));
            }
        }
    }
}

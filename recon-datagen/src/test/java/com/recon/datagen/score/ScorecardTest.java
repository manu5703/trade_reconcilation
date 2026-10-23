package com.recon.datagen.score;

import static org.assertj.core.api.Assertions.assertThat;

import com.recon.core.match.Break;
import com.recon.core.match.Match;
import com.recon.core.match.MatchResult;
import com.recon.core.match.MatchRule;
import com.recon.core.model.BreakType;
import com.recon.datagen.NoiseType;
import com.recon.datagen.output.Label;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScorecardTest {

    private static final List<Label> LABELS = List.of(
            new Label("C1", null, null, null, null, List.of("T1"), List.of("X1")),
            new Label("C2", null, null, NoiseType.REF_FORMAT, null, List.of("T2"), List.of("X2")),
            new Label("C3", null, null, null, "BLK", List.of("T3", "T4"), List.of("X3")),
            new Label("C4", BreakType.QUANTITY_MISMATCH, "quantity", null, null, List.of("T5"), List.of("X5")),
            new Label("C5", BreakType.UNEXPECTED_CONFIRM, null, null, null, List.of(), List.of("X6")));

    @Test
    void perfectResultScoresOneEverywhere() {
        MatchResult perfect = new MatchResult(
                List.of(match("X1", "T1"), match("X2", "T2"), match("X3", "T3", "T4"), match("X5", "T5")),
                List.of(new Break(BreakType.QUANTITY_MISMATCH, "T5", "X5", "quantity", "100", "90"),
                        new Break(BreakType.UNEXPECTED_CONFIRM, null, "X6", null, null, null)));

        Scorecard card = Scorecard.score(LABELS, perfect);

        assertThat(card.overall().precision()).isEqualTo(1.0);
        assertThat(card.overall().recall()).isEqualTo(1.0);
        assertThat(card.cleanCasesCorrectlyPaired()).isEqualTo(3);
        assertThat(card.noiseCasesWithFalseBreaks()).isZero();
        // T5 is matched but has a break, so 4 of 5 trades went straight through.
        assertThat(card.stpRate()).isEqualTo(0.8);
        assertThat(card.idealStpRate()).isEqualTo(0.8);
    }

    @Test
    void wrongTypeIsAFalsePositiveAndAFalseNegative() {
        MatchResult wrong = new MatchResult(
                List.of(match("X1", "T1"), match("X3", "T3", "T4"), match("X5", "T5")),
                List.of(new Break(BreakType.PRICE_OUT_OF_TOLERANCE, "T5", "X5", "price", "10", "11"),
                        new Break(BreakType.MISSING_CONFIRM, "T2", null, null, null, null),
                        new Break(BreakType.UNEXPECTED_CONFIRM, null, "X2", null, null, null),
                        new Break(BreakType.UNEXPECTED_CONFIRM, null, "X6", null, null, null)));

        Scorecard card = Scorecard.score(LABELS, wrong);

        assertThat(card.byType().get(BreakType.QUANTITY_MISMATCH).recall()).isZero();
        assertThat(card.byType().get(BreakType.PRICE_OUT_OF_TOLERANCE).precision()).isZero();
        assertThat(card.byType().get(BreakType.UNEXPECTED_CONFIRM).precision()).isEqualTo(0.5);
        assertThat(card.byType().get(BreakType.SIDE_MISMATCH).precision()).isNaN();
        assertThat(card.noiseCasesWithFalseBreaks()).isEqualTo(1);
        assertThat(card.cleanCasesCorrectlyPaired()).isEqualTo(2);
        assertThat(card.falsePositives()).contains("MISSING_CONFIRM T2", "PRICE_OUT_OF_TOLERANCE T5");
        assertThat(card.falseNegatives()).containsExactly("QUANTITY_MISMATCH T5");
    }

    private static Match match(String confirm, String... trades) {
        return new Match(MatchRule.EXACT, BigDecimal.ONE, List.of(trades), confirm);
    }
}

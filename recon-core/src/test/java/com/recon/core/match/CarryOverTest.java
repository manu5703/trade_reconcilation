package com.recon.core.match;

import static com.recon.core.match.Fixtures.confirmFor;
import static com.recon.core.match.Fixtures.fullMatcher;
import static com.recon.core.match.Fixtures.trade;
import static org.assertj.core.api.Assertions.assertThat;

import com.recon.core.match.CarryOver.OpenBreak;
import com.recon.core.model.BreakType;
import com.recon.core.model.InternalTrade;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CarryOverTest {

    private static final Instant MONDAY = Instant.parse("2026-01-12T18:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-01-13T18:00:00Z");

    private final InternalTrade t1 = trade("T1").build();

    @Test
    void newBreakOpensAtRunTime() {
        MatchResult today = fullMatcher().match(List.of(t1), List.of());

        CarryOver.Result result = CarryOver.apply(today, List.of(), TUESDAY);

        assertThat(result.breaks()).singleElement().satisfies(b -> {
            assertThat(b.openedAt()).isEqualTo(TUESDAY);
            assertThat(b.carried()).isFalse();
        });
    }

    @Test
    void recurringBreakKeepsItsOriginalOpenedAt() {
        MatchResult today = fullMatcher().match(List.of(t1), List.of());
        OpenBreak fromMonday = new OpenBreak(new Break.Key(BreakType.MISSING_CONFIRM, "T1", null, null), MONDAY);

        CarryOver.Result result = CarryOver.apply(today, List.of(fromMonday), TUESDAY);

        assertThat(result.breaks()).singleElement().satisfies(b -> {
            assertThat(b.openedAt()).isEqualTo(MONDAY);
            assertThat(b.carried()).isTrue();
        });
        assertThat(result.autoResolved()).isEmpty();
    }

    @Test
    void openBreakIsAutoResolvedOnceItsRecordsMatch() {
        // Monday the confirm was missing; Tuesday it arrived.
        MatchResult today = fullMatcher().match(List.of(t1), List.of(confirmFor(t1, "C1").build()));
        OpenBreak fromMonday = new OpenBreak(new Break.Key(BreakType.MISSING_CONFIRM, "T1", null, null), MONDAY);

        CarryOver.Result result = CarryOver.apply(today, List.of(fromMonday), TUESDAY);

        assertThat(result.breaks()).isEmpty();
        assertThat(result.autoResolved()).containsExactly(fromMonday);
    }

    @Test
    void openBreakForRecordsNotInThisRunIsLeftAlone() {
        MatchResult today = fullMatcher().match(List.of(t1), List.of(confirmFor(t1, "C1").build()));
        OpenBreak otherDate = new OpenBreak(new Break.Key(BreakType.MISSING_CONFIRM, "T-OTHER", null, null), MONDAY);

        CarryOver.Result result = CarryOver.apply(today, List.of(otherDate), TUESDAY);

        assertThat(result.autoResolved()).isEmpty();
    }

    @Test
    void rerunWithSameOpenBreaksIsIdempotent() {
        MatchResult run = fullMatcher().match(List.of(t1, trade("T2").build()), List.of(confirmFor(t1, "C1").quantity("900").build()));
        CarryOver.Result first = CarryOver.apply(run, List.of(), MONDAY);
        List<OpenBreak> stillOpen = first.breaks().stream().map(b -> new OpenBreak(b.brk().key(), b.openedAt())).toList();

        CarryOver.Result second = CarryOver.apply(fullMatcher().match(List.of(t1, trade("T2").build()),
                List.of(confirmFor(t1, "C1").quantity("900").build())), stillOpen, TUESDAY);

        assertThat(second.breaks()).extracting(CarryOver.DatedBreak::brk).isEqualTo(first.breaks().stream().map(CarryOver.DatedBreak::brk).toList());
        assertThat(second.breaks()).allSatisfy(b -> assertThat(b.openedAt()).isEqualTo(MONDAY));
        assertThat(second.autoResolved()).isEmpty();
    }
}

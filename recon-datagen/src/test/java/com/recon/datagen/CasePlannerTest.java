package com.recon.datagen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.recon.core.model.BreakType;
import com.recon.datagen.CasePlanner.CasePlan;
import java.util.Arrays;
import java.util.SplittableRandom;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class CasePlannerTest {

    @Property(tries = 200)
    void planAlwaysHasExactTradeAndBreakCounts(
            @ForAll @IntRange(min = 1, max = 5_000) int trades,
            @ForAll @DoubleRange(min = 0, max = 0.2) double breakRate,
            @ForAll @DoubleRange(min = 0, max = 0.5) double blockShare,
            @ForAll long seed) {
        DatagenConfig config = DatagenConfig.builder().breakRate(breakRate).blockShare(blockShare).build();

        CasePlan plan = CasePlanner.plan(trades, config, new SplittableRandom(seed));

        assertThat(Arrays.stream(plan.allocations()).sum()).isEqualTo(trades);
        assertThat(Arrays.stream(plan.breakTypes()).filter(t -> t != null).count()).isEqualTo(Math.round(trades * breakRate));
        for (int i = 0; i < plan.size(); i++) {
            int allocations = plan.allocations()[i];
            BreakType type = plan.breakTypes()[i];
            assertThat(allocations).isBetween(0, CasePlanner.MAX_BLOCK);
            // Unexpected confirms are exactly the zero-trade cases; blocks are always clean.
            assertThat(allocations == 0).isEqualTo(type == BreakType.UNEXPECTED_CONFIRM);
            if (allocations > 1) {
                assertThat(type).isNull();
            }
        }
    }

    @Test
    void rejectsBreakRateThatNeedsMoreSinglesThanExist() {
        DatagenConfig config = DatagenConfig.builder().breakRate(0.95).blockShare(0.5).build();

        assertThatThrownBy(() -> CasePlanner.plan(1_000, config, new SplittableRandom(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower the break rate or the block share");
    }
}

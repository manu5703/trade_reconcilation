package com.recon.datagen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.recon.core.model.BreakType;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class BreakMixTest {

    @Property
    void allocationAlwaysSumsToTotal(@ForAll @IntRange(min = 0, max = 1_000_000) int total) {
        Map<BreakType, Integer> counts = BreakMix.uniform().allocate(total);

        assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(total);
        // With equal weights no two types differ by more than one.
        int min = counts.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        assertThat(max - min).isLessThanOrEqualTo(1);
    }

    @Test
    void leftoversGoToLargestRemaindersThenEnumOrder() {
        // 10 / 9 = 1 each, remainder 1 goes to the first type in enum order.
        Map<BreakType, Integer> counts = BreakMix.uniform().allocate(10);

        assertThat(counts.get(BreakType.MISSING_CONFIRM)).isEqualTo(2);
        assertThat(counts.get(BreakType.DUPLICATE_CONFIRM)).isEqualTo(1);
    }

    @Test
    void weightsAreProportional() {
        Map<BreakType, Integer> counts = BreakMix.parse("SIDE_MISMATCH=1, QUANTITY_MISMATCH=2").allocate(10);

        // Exact shares 3.33 and 6.67: floors 3 and 6, the leftover goes to the larger remainder.
        assertThat(counts.get(BreakType.SIDE_MISMATCH)).isEqualTo(3);
        assertThat(counts.get(BreakType.QUANTITY_MISMATCH)).isEqualTo(7);
        assertThat(counts.get(BreakType.MISSING_CONFIRM)).isZero();
    }

    @Test
    void unknownTypeListsValidOnes() {
        assertThatThrownBy(() -> BreakMix.parse("PRICE=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown break type 'PRICE'")
                .hasMessageContaining("PRICE_OUT_OF_TOLERANCE");
    }

    @Test
    void rejectsMalformedEntriesAndAllZeroWeights() {
        assertThatThrownBy(() -> BreakMix.parse("SIDE_MISMATCH")).hasMessageContaining("TYPE=weight");
        assertThatThrownBy(() -> BreakMix.parse("SIDE_MISMATCH=x")).hasMessageContaining("not an integer");
        assertThatThrownBy(() -> BreakMix.parse("SIDE_MISMATCH=0")).hasMessageContaining("positive weight");
    }
}

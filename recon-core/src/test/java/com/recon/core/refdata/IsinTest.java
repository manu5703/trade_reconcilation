package com.recon.core.refdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IsinTest {

    @ParameterizedTest
    @ValueSource(strings = {"US0378331005", "US5949181045", "GB0002634946", "AU0000XVGZA3"})
    void acceptsRealIsins(String isin) {
        assertThat(Isin.isValid(isin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"US0378331006", "us0378331005", " US0378331005", "US037833100", "US03783310055"})
    void rejectsWrongCheckDigitOrFormat(String isin) {
        assertThat(Isin.isValid(isin)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(Isin.isValid(null)).isFalse();
    }

    @Test
    void computesKnownCheckDigit() {
        assertThat(Isin.withCheckDigit("US037833100")).isEqualTo("US0378331005");
    }

    @Test
    void rejectsMalformedBody() {
        assertThatThrownBy(() -> Isin.withCheckDigit("1S037833100"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1S037833100");
    }

    @Property
    void generatedCheckDigitIsAlwaysValid(@ForAll("isinBodies") String body) {
        assertThat(Isin.isValid(Isin.withCheckDigit(body))).isTrue();
    }

    @Property
    void anyOtherCheckDigitIsInvalid(@ForAll("isinBodies") String body, @ForAll @IntRange(min = 1, max = 9) int offset) {
        int correct = Isin.withCheckDigit(body).charAt(11) - '0';
        assertThat(Isin.isValid(body + (correct + offset) % 10)).isFalse();
    }

    @Provide
    Arbitrary<String> isinBodies() {
        Arbitrary<String> country = Arbitraries.strings().withCharRange('A', 'Z').ofLength(2);
        Arbitrary<String> nsin = Arbitraries.strings().withCharRange('A', 'Z').numeric().ofLength(9);
        return Combinators.combine(country, nsin).as(String::concat);
    }
}

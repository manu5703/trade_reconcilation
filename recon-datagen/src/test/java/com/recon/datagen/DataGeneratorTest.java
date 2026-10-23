package com.recon.datagen;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.recon.core.model.BreakType;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.refdata.Isin;
import com.recon.datagen.TestSupport.Dataset;
import com.recon.datagen.refdata.Instrument;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DataGeneratorTest {

    private static final int TRADES = 20_000;
    private static Dataset dataset;
    private static CaseOracle oracle;

    @BeforeAll
    static void generateOnce() {
        dataset = TestSupport.generate(DatagenConfig.builder().tradesPerDay(TRADES).seed(42).build());
        oracle = new CaseOracle(dataset.referenceData(), TestSupport.tolerances());
    }

    @Test
    void generatesTheRequestedNumberOfInternalTrades() {
        assertThat(dataset.cases().stream().mapToInt(c -> c.trades().size()).sum()).isEqualTo(TRADES);
    }

    @Test
    void injectedBreakRateMatchesConfig() {
        long breaks = dataset.cases().stream().filter(TradeCase::isBreak).count();

        assertThat(breaks).isEqualTo(Math.round(TRADES * 0.03));
        assertThat((double) breaks / TRADES).isEqualTo(0.03);
    }

    @Test
    void defaultMixCoversAllNineTypesEqually() {
        Map<BreakType, Long> byType = breakCounts(dataset.cases());

        // 600 breaks / 9 types = 66.67, so every type gets 66 or 67.
        assertThat(byType).containsOnlyKeys(BreakType.values());
        assertThat(byType.values()).allSatisfy(n -> assertThat(n).isBetween(66L, 67L));
    }

    @Test
    void customMixIsHonoured() {
        BreakMix mix = BreakMix.parse("PRICE_OUT_OF_TOLERANCE=3,MISSING_CONFIRM=1");
        Dataset custom = TestSupport.generate(DatagenConfig.builder().tradesPerDay(10_000).seed(7).breakRate(0.05).breakMix(mix).build());

        assertThat(breakCounts(custom.cases())).containsExactlyInAnyOrderEntriesOf(Map.of(
                BreakType.PRICE_OUT_OF_TOLERANCE, 375L,
                BreakType.MISSING_CONFIRM, 125L));
    }

    @Test
    void aboutFifteenPercentOfTradesAreBlockAllocations() {
        List<TradeCase> blocks = dataset.cases().stream().filter(c -> c.blockRef() != null).toList();
        int allocations = blocks.stream().mapToInt(c -> c.trades().size()).sum();

        assertThat((double) allocations / TRADES).isBetween(0.149, 0.151);
        assertThat(blocks).allSatisfy(block -> {
            assertThat(block.trades()).hasSizeBetween(2, 8);
            assertThat(block.trades()).extracting(InternalTrade::account).doesNotHaveDuplicates();
            assertThat(block.trades()).extracting(InternalTrade::blockRef).containsOnly(block.blockRef());
            assertThat(block.confirms()).hasSize(1);
        });
    }

    @Test
    void cleanCasesIncludingAllNoiseAreWithinTolerance() {
        // Collect only the offenders, so a failure names the case, its noise and the bad fields.
        Map<String, String> outOfTolerance = new TreeMap<>();
        for (TradeCase c : dataset.cases()) {
            if (!c.isBreak()) {
                Set<String> differing = oracle.differingFields(c.trades(), c.confirms().getFirst());
                if (!differing.isEmpty()) {
                    outOfTolerance.put(c.caseId(), c.noise() + " " + differing);
                }
            }
        }

        assertThat(outOfTolerance).isEmpty();
    }

    @Test
    void everyNoiseTypeIsPresentAtRoughlyTheConfiguredRate() {
        List<TradeCase> clean = dataset.cases().stream().filter(c -> !c.isBreak()).toList();
        Map<NoiseType, Long> byNoise = clean.stream().filter(c -> c.noise() != null)
                .collect(groupingBy(TradeCase::noise, () -> new EnumMap<>(NoiseType.class), counting()));
        long noisy = byNoise.values().stream().mapToLong(Long::longValue).sum();

        assertThat(byNoise).containsOnlyKeys(NoiseType.values());
        // 10% noise rate; binomial standard deviation here is about 0.2 percentage points.
        assertThat((double) noisy / clean.size()).isBetween(0.09, 0.11);
    }

    @Test
    void fieldLevelBreaksDifferOnlyInTheLabelledField() {
        List<TradeCase> fieldBreaks = dataset.cases().stream().filter(c -> c.breakField() != null).toList();

        assertThat(fieldBreaks).hasSizeGreaterThan(300).allSatisfy(c -> {
            Set<String> differing = oracle.differingFields(c.trades(), c.confirms().getFirst());
            // A broker's confirm is self-consistent, so changing quantity, price, side or
            // commission also moves its net amount.
            differing.remove("netAmount");
            assertThat(differing).as(c.caseId() + " " + c.breakType()).containsExactly(c.breakField());
        });
    }

    @Test
    void structuralBreaksHaveTheRightShape() {
        for (TradeCase c : dataset.cases()) {
            if (c.breakType() == BreakType.MISSING_CONFIRM) {
                assertThat(c.trades()).hasSize(1);
                assertThat(c.confirms()).isEmpty();
            } else if (c.breakType() == BreakType.UNEXPECTED_CONFIRM) {
                assertThat(c.trades()).isEmpty();
                assertThat(c.confirms()).hasSize(1);
            } else if (c.breakType() == BreakType.DUPLICATE_CONFIRM) {
                assertThat(c.trades()).hasSize(1);
                assertThat(c.confirms()).hasSize(2);
                assertThat(oracle.differingFields(c.trades(), c.confirms().get(0))).isEmpty();
                assertThat(c.confirms().get(1)).usingRecursiveComparison().ignoringFields("brokerRef").isEqualTo(c.confirms().get(0));
            }
        }
    }

    @Test
    void referencesAreUnique() {
        List<String> tradeRefs = dataset.cases().stream().flatMap(c -> c.trades().stream()).map(InternalTrade::tradeRef).toList();
        List<String> brokerRefs = dataset.cases().stream().flatMap(c -> c.confirms().stream()).map(ExternalConfirm::brokerRef).toList();

        assertThat(tradeRefs).doesNotHaveDuplicates();
        assertThat(brokerRefs).doesNotHaveDuplicates();
    }

    @Test
    void referenceDataMatchesSpec() {
        assertThat(dataset.referenceData().instruments()).hasSize(200)
                .allSatisfy(i -> assertThat(Isin.isValid(i.isin())).as(i.isin()).isTrue())
                .extracting(Instrument::isin).doesNotHaveDuplicates();
        assertThat(dataset.referenceData().counterparties()).hasSize(30);
        assertThat(dataset.referenceData().counterparties().stream().flatMap(c -> c.aliases().stream()).toList()).doesNotHaveDuplicates();
        assertThat(dataset.referenceData().accounts()).hasSize(2_000);
    }

    @Test
    void sameSeedGivesIdenticalData() {
        DatagenConfig config = DatagenConfig.builder().tradesPerDay(2_000).seed(99).businessDays(2).build();

        assertThat(TestSupport.generate(config).cases()).isEqualTo(TestSupport.generate(config).cases());
    }

    @Test
    void differentSeedGivesDifferentData() {
        List<TradeCase> a = TestSupport.generate(DatagenConfig.builder().tradesPerDay(2_000).seed(1).build()).cases();
        List<TradeCase> b = TestSupport.generate(DatagenConfig.builder().tradesPerDay(2_000).seed(2).build()).cases();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void multipleDaysUseConsecutiveBusinessDates() {
        // 2026-01-16 is a Friday, so the second day is Monday the 19th.
        Dataset twoDays = TestSupport.generate(DatagenConfig.builder().tradesPerDay(500).seed(3)
                .startDate(LocalDate.of(2026, 1, 16)).businessDays(2).build());

        assertThat(twoDays.cases().stream().map(TradeCase::tradeDate).distinct().sorted().toList())
                .containsExactly(LocalDate.of(2026, 1, 16), LocalDate.of(2026, 1, 19));
        assertThat(twoDays.summary().internalTrades()).isEqualTo(1_000);
    }

    private static Map<BreakType, Long> breakCounts(List<TradeCase> cases) {
        return cases.stream().filter(TradeCase::isBreak)
                .collect(groupingBy(TradeCase::breakType, () -> new EnumMap<>(BreakType.class), counting()));
    }
}

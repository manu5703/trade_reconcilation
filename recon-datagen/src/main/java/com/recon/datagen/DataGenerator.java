package com.recon.datagen;

import com.recon.core.tolerance.ToleranceConfig;
import com.recon.datagen.CasePlanner.CasePlan;
import com.recon.datagen.refdata.ReferenceData;
import com.recon.datagen.refdata.ReferenceDataGenerator;
import java.io.IOException;
import java.time.LocalDate;
import java.util.SplittableRandom;

/**
 * Generates a full dataset: reference data, then each business day's cases.
 *
 * <p>Reproducibility: one root {@link SplittableRandom} is seeded from the config and split into
 * independent child streams (reference data, then per day: the plan and the case content). The
 * same seed and config therefore always give byte-identical output.
 */
public final class DataGenerator {

    private final DatagenConfig config;
    private final ToleranceConfig tolerances;

    public DataGenerator(DatagenConfig config, ToleranceConfig tolerances) {
        this.config = config;
        this.tolerances = tolerances;
    }

    public GenerationSummary generate(DatasetSink sink) throws IOException {
        SplittableRandom root = new SplittableRandom(config.seed());
        ReferenceData referenceData = ReferenceDataGenerator.generate(root.split(),
                config.instruments(), config.counterparties(), config.accounts());
        sink.referenceData(referenceData);

        GenerationSummary summary = new GenerationSummary();
        summary.referenceData(referenceData);
        LocalDate date = BusinessDays.onOrAfter(config.startDate());
        for (int day = 0; day < config.businessDays(); day++) {
            SplittableRandom dayRng = root.split();
            CasePlan plan = CasePlanner.plan(config.tradesPerDay(), config, dayRng.split());
            CaseGenerator generator = new CaseGenerator(referenceData, tolerances, config.noiseRate(), date, dayRng.split());
            for (int i = 0; i < plan.size(); i++) {
                TradeCase tradeCase = generator.next(plan.allocations()[i], plan.breakTypes()[i]);
                summary.record(tradeCase);
                sink.accept(tradeCase);
            }
            date = BusinessDays.add(date, 1);
        }
        return summary;
    }
}

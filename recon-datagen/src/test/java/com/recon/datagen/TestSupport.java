package com.recon.datagen;

import com.recon.core.tolerance.ToleranceConfig;
import com.recon.datagen.refdata.ReferenceData;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class TestSupport {

    private TestSupport() {
    }

    /** The repo's real tolerance file, so tests exercise the shipped configuration. */
    static Path tolerancesFile() {
        return Path.of(System.getProperty("recon.rootDir", ".."), "config", "tolerances.properties");
    }

    static ToleranceConfig tolerances() {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(tolerancesFile())) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ToleranceConfig.fromProperties(properties);
    }

    static Dataset generate(DatagenConfig config) {
        InMemorySink sink = new InMemorySink();
        try {
            GenerationSummary summary = new DataGenerator(config, tolerances()).generate(sink);
            return new Dataset(sink.referenceData, sink.cases, summary);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record Dataset(ReferenceData referenceData, List<TradeCase> cases, GenerationSummary summary) {
    }

    private static final class InMemorySink implements DatasetSink {
        private ReferenceData referenceData;
        private final List<TradeCase> cases = new ArrayList<>();

        @Override
        public void referenceData(ReferenceData data) {
            referenceData = data;
        }

        @Override
        public void accept(TradeCase tradeCase) {
            cases.add(tradeCase);
        }
    }
}

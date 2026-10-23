package com.recon.bench;

import com.recon.core.match.MatchResult;
import com.recon.core.match.Matcher;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.refdata.StaticData;
import com.recon.core.tolerance.ToleranceConfig;
import com.recon.datagen.DataGenerator;
import com.recon.datagen.DatagenConfig;
import com.recon.datagen.DatasetSink;
import com.recon.datagen.TradeCase;
import com.recon.datagen.refdata.ReferenceData;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * One full match of a generated business date, at 100k and 1M internal trades (plus their
 * ~88% as many confirms). Data generation happens once per trial, outside the measurement.
 * Rows/s = (trades + confirms) / time per op.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(value = 1, jvmArgsAppend = {"-Xms6g", "-Xmx6g"})
public class MatcherBenchmark {

    @Param({"100000", "1000000"})
    public int trades;

    private List<InternalTrade> internalTrades;
    private List<ExternalConfirm> confirms;
    private Matcher full;
    private Matcher baseline;

    @Setup(Level.Trial)
    public void generate() throws IOException {
        ToleranceConfig tolerances = loadTolerances();
        Collector collector = new Collector();
        new DataGenerator(DatagenConfig.builder().tradesPerDay(trades).seed(42).build(), tolerances).generate(collector);
        internalTrades = collector.trades;
        confirms = collector.confirms;
        StaticData staticData = collector.referenceData.toStaticData();
        full = Matcher.full(staticData, tolerances);
        baseline = Matcher.baseline(staticData, tolerances);
    }

    @Benchmark
    public MatchResult fullMatcher() {
        return full.match(internalTrades, confirms);
    }

    @Benchmark
    public MatchResult exactOnlyBaseline() {
        return baseline.match(internalTrades, confirms);
    }

    private static ToleranceConfig loadTolerances() throws IOException {
        Properties properties = new Properties();
        Path file = Path.of(System.getProperty("recon.rootDir", "."), "config", "tolerances.properties");
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        return ToleranceConfig.fromProperties(properties);
    }

    private static final class Collector implements DatasetSink {
        private ReferenceData referenceData;
        private final List<InternalTrade> trades = new ArrayList<>();
        private final List<ExternalConfirm> confirms = new ArrayList<>();

        @Override
        public void referenceData(ReferenceData data) {
            referenceData = data;
        }

        @Override
        public void accept(TradeCase tradeCase) {
            trades.addAll(tradeCase.trades());
            confirms.addAll(tradeCase.confirms());
        }
    }
}

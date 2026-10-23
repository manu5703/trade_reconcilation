package com.recon.spark;

import static org.assertj.core.api.Assertions.assertThat;

import com.recon.core.match.Break;
import com.recon.core.match.MatchResult;
import com.recon.core.match.Matcher;
import com.recon.core.model.BreakType;
import com.recon.core.tolerance.ToleranceConfig;
import com.recon.datagen.DataGenerator;
import com.recon.datagen.DatagenConfig;
import com.recon.datagen.output.CsvDatasetWriter;
import com.recon.datagen.output.DatasetReader;
import com.recon.datagen.score.Scorecard;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Spark job must reach exactly the conclusions of the single-JVM matcher. */
class ReconJobTest {

    private static final Path TOLERANCES = Path.of(System.getProperty("recon.rootDir", ".."), "config", "tolerances.properties");

    @TempDir
    static Path data;
    @TempDir
    static Path out;

    private static SparkSession spark;
    private static ReconJob.Summary summary;
    private static MatchResult expected;

    @BeforeAll
    static void runBoth() throws IOException {
        ToleranceConfig tolerances = ToleranceConfig.fromProperties(load(TOLERANCES));
        // Two business days with a high break rate: more groups, more competition inside them.
        DatagenConfig config = DatagenConfig.builder().tradesPerDay(3_000).businessDays(2).breakRate(0.15).seed(11).build();
        try (CsvDatasetWriter writer = new CsvDatasetWriter(data, true)) {
            new DataGenerator(config, tolerances).generate(writer);
        }
        expected = Matcher.full(DatasetReader.staticData(data), tolerances)
                .match(DatasetReader.internalTrades(data), DatasetReader.externalConfirms(data));

        spark = ReconSparkJob.session("local[2]", 8);
        summary = new ReconJob(spark).run(data, TOLERANCES, out);
    }

    @AfterAll
    static void stop() {
        spark.stop();
    }

    @Test
    void linksAreIdenticalToTheSingleJvmMatcher() {
        Set<String> spark = rows("match_links").stream()
                .map(r -> r.getAs("internalRef") + "->" + r.getAs("externalRef") + " " + r.getAs("rule") + " " + r.<BigDecimal>getAs("score").stripTrailingZeros())
                .collect(Collectors.toSet());
        Set<String> jvm = expected.links().stream()
                .map(l -> l.internalRef() + "->" + l.externalRef() + " " + l.rule() + " " + l.score().stripTrailingZeros())
                .collect(Collectors.toSet());

        assertThat(spark).hasSize(expected.links().size()).isEqualTo(jvm);
    }

    @Test
    void breaksAreIdenticalToTheSingleJvmMatcher() {
        Set<Break> spark = new HashSet<>();
        for (Row r : rows("breaks")) {
            spark.add(new Break(BreakType.valueOf(r.getAs("breakType")), r.getAs("internalRef"),
                    r.getAs("externalRef"), r.getAs("field"), r.getAs("expected"), r.getAs("actual")));
        }

        assertThat(spark).hasSize(expected.breaks().size()).isEqualTo(new HashSet<>(expected.breaks()));
    }

    @Test
    void evaluationAgainstLabelsMatchesTheSingleJvmScorecard() throws IOException {
        Scorecard jvm = Scorecard.score(DatasetReader.labels(data), expected);

        assertThat(summary.trades()).isEqualTo(6_000);
        assertThat(summary.breaksByType().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(expected.breaks().size());
        for (BreakType type : BreakType.values()) {
            Scorecard.TypeScore score = jvm.byType().get(type);
            long[] spark = summary.evaluation().byType().getOrDefault(type.name(), new long[3]);
            assertThat(spark).as(type.name()).containsExactly(score.labelled(), score.predicted(), score.correct());
        }
        assertThat(summary.evaluation().noiseCases()).isEqualTo(jvm.noiseCases());
        assertThat(summary.evaluation().noiseCasesWithFalseBreaks()).isEqualTo(jvm.noiseCasesWithFalseBreaks());
        assertThat(summary.straightThroughTrades()).isEqualTo(jvm.straightThroughTrades());
    }

    private static List<Row> rows(String table) {
        return spark.read().parquet(out.resolve(table).toString()).collectAsList();
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        return properties;
    }
}

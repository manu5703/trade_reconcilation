package com.recon.spark;

import static org.apache.spark.sql.functions.coalesce;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.concat_ws;
import static org.apache.spark.sql.functions.explode;
import static org.apache.spark.sql.functions.split;
import static org.apache.spark.sql.functions.when;

import com.recon.core.match.Break;
import com.recon.core.match.MatchResult;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.model.Side;
import com.recon.core.model.Source;
import com.recon.core.refdata.StaticData;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.CoGroupFunction;
import org.apache.spark.api.java.function.MapFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;

/**
 * The end-of-day reconciliation on Spark, using exactly the recon-core matcher.
 *
 * <p><b>Why grouping by (normalized ISIN, trade date) gives the same answer as one big match.</b>
 * Every pass in recon-core only ever compares records that share both: the exact and duplicate
 * keys contain them, block allocations must agree on them, and the fuzzy pass blocks within them.
 * Groups therefore never compete for a record, so matching each group independently and taking
 * the union is identical to {@code Matcher.full} over the whole input. {@code ReconJobTest}
 * checks that equality.
 *
 * <p>Each group is matched in one task, holding only that group in memory. That's what lets 10M
 * trades run on a laptop, where the single-JVM matcher would need every record in the heap at once.
 */
public final class ReconJob {

    /** What a run produced and how long the stages took. */
    public record Summary(long trades, long confirms, Map<String, Long> matchesByRule, Map<String, Long> breaksByType,
                          long matchedTrades, long straightThroughTrades, long matchAndWriteMillis, long totalMillis,
                          long evaluationMillis, Evaluation evaluation) {

        public double stpRate() {
            return trades == 0 ? 0 : (double) straightThroughTrades / trades;
        }
    }

    /** Accuracy against labels.csv: per break type, how many were labelled, predicted and right. */
    public record Evaluation(Map<String, long[]> byType, long noiseCases, long noiseCasesWithFalseBreaks) {
    }

    private final SparkSession spark;

    public ReconJob(SparkSession spark) {
        this.spark = spark;
    }

    public Summary run(Path data, Path tolerancesFile, Path out) throws IOException {
        long start = System.nanoTime();
        MatcherSpec spec = loadSpec(data, tolerancesFile);
        Broadcast<MatcherSpec> broadcast = JavaSparkContext.fromSparkContext(spark.sparkContext()).broadcast(spec);

        Dataset<Row> trades = spark.read().parquet(data.resolve("internal_trades.parquet").toString());
        Dataset<Row> confirms = spark.read().parquet(data.resolve("external_confirms.parquet").toString());

        Dataset<ResultRow> results = trades
                .groupByKey((MapFunction<Row, String>) ReconJob::groupKey, Encoders.STRING())
                .cogroup(confirms.groupByKey((MapFunction<Row, String>) ReconJob::groupKey, Encoders.STRING()),
                        (CoGroupFunction<String, Row, Row, ResultRow>) (key, ts, cs) -> matchGroup(broadcast.value(), ts, cs),
                        Encoders.bean(ResultRow.class))
                // Written twice and counted several times: compute it once.
                .persist(StorageLevel.MEMORY_AND_DISK());

        Dataset<Row> links = results.filter(col("kind").equalTo(ResultRow.LINK))
                .select("internalRef", "externalRef", "rule", "score");
        Dataset<Row> breaks = results.filter(col("kind").equalTo(ResultRow.BREAK))
                .select("breakType", "internalRef", "externalRef", "field", "expected", "actual");
        links.write().mode(SaveMode.Overwrite).parquet(out.resolve("match_links").toString());
        breaks.write().mode(SaveMode.Overwrite).parquet(out.resolve("breaks").toString());
        long matchAndWrite = millisSince(start);

        Map<String, Long> byRule = countBy(links, "rule");
        Map<String, Long> byType = countBy(breaks, "breakType");
        Dataset<Row> matchedTrades = links.select("internalRef").distinct();
        long matched = matchedTrades.count();
        long stp = matchedTrades.except(breaks.select("internalRef").where(col("internalRef").isNotNull()).distinct()).count();
        long tradeCount = trades.count();
        long confirmCount = confirms.count();

        long evaluationStart = System.nanoTime();
        Path labels = data.resolve("labels.csv");
        Evaluation evaluation = Files.isRegularFile(labels) ? evaluate(labels, breaks) : null;
        long evaluationMillis = millisSince(evaluationStart);
        results.unpersist();
        return new Summary(tradeCount, confirmCount, byRule, byType, matched, stp, matchAndWrite, millisSince(start),
                evaluationMillis, evaluation);
    }

    /** Same normalization as the matcher, so " us0378331005" lands in the same group as US0378331005. */
    static String groupKey(Row row) {
        return StaticData.normalizeCode(row.<String>getAs("isin")) + "|" + row.<LocalDate>getAs("trade_date");
    }

    static Iterator<ResultRow> matchGroup(MatcherSpec spec, Iterator<Row> tradeRows, Iterator<Row> confirmRows) {
        List<InternalTrade> trades = new ArrayList<>();
        tradeRows.forEachRemaining(r -> trades.add(trade(r)));
        List<ExternalConfirm> confirms = new ArrayList<>();
        confirmRows.forEachRemaining(r -> confirms.add(confirm(r)));

        MatchResult result = spec.matcher().match(trades, confirms);
        List<ResultRow> rows = new ArrayList<>(result.matches().size() + result.breaks().size());
        result.links().forEach(link -> rows.add(ResultRow.of(link)));
        for (Break brk : result.breaks()) {
            rows.add(ResultRow.of(brk));
        }
        return rows.iterator();
    }

    private static InternalTrade trade(Row r) {
        return new InternalTrade(r.getAs("trade_ref"), r.getAs("block_ref"), r.getAs("account"), r.getAs("isin"),
                Side.valueOf(r.getAs("side")), r.getAs("quantity"), r.getAs("price"), r.getAs("trade_date"),
                r.getAs("settle_date"), r.getAs("counterparty"), r.getAs("net_amount"));
    }

    private static ExternalConfirm confirm(Row r) {
        return new ExternalConfirm(r.getAs("broker_ref"), Source.valueOf(r.getAs("source")), r.getAs("isin"),
                Side.valueOf(r.getAs("side")), r.getAs("quantity"), r.getAs("price"), r.getAs("commission"),
                r.getAs("trade_date"), r.getAs("settle_date"), r.getAs("counterparty"), r.getAs("net_amount"));
    }

    /** Reference data is small: read it on the driver and broadcast it. */
    private MatcherSpec loadSpec(Path data, Path tolerancesFile) throws IOException {
        Map<String, String> instruments = new HashMap<>();
        for (Row r : csv(data.resolve("instruments.csv")).collectAsList()) {
            instruments.put(r.getAs("isin"), r.getAs("asset_class"));
        }
        List<MatcherSpec.Counterparty> counterparties = new ArrayList<>();
        for (Row r : csv(data.resolve("counterparties.csv")).collectAsList()) {
            counterparties.add(new MatcherSpec.Counterparty(r.getAs("counterparty_id"),
                    new ArrayList<>(Arrays.asList(r.<String>getAs("aliases").split("\\|"))), r.getAs("commission_bps")));
        }
        Properties tolerances = new Properties();
        try (Reader reader = Files.newBufferedReader(tolerancesFile, StandardCharsets.UTF_8)) {
            tolerances.load(reader);
        }
        return new MatcherSpec(instruments, counterparties, tolerances);
    }

    /**
     * Scores the breaks against labels.csv the same way the single-JVM scorer does: a predicted
     * break is right when its (type, anchor ref) equals a labelled one.
     */
    private Evaluation evaluate(Path labelsFile, Dataset<Row> breaks) {
        Dataset<Row> labels = csv(labelsFile);
        Column anchor = when(col("trade_refs").isNull(), split(col("broker_refs"), "\\|").getItem(0))
                .otherwise(split(col("trade_refs"), "\\|").getItem(0));
        Dataset<Row> truth = labels.where(col("break_type").isNotNull())
                .select(col("break_type").as("type"), anchor.as("anchor")).distinct().persist();
        Dataset<Row> predicted = breaks
                .select(col("breakType").as("type"), coalesce(col("internalRef"), col("externalRef")).as("anchor"))
                .distinct().persist();
        Map<String, Long> labelled = countBy(truth, "type");
        Map<String, Long> predictedCounts = countBy(predicted, "type");
        Map<String, Long> correct = countBy(truth.join(predicted, List.of("type", "anchor").toArray(new String[0])), "type");
        Map<String, long[]> byType = new TreeMap<>();
        for (String type : unionKeys(labelled, predictedCounts)) {
            byType.put(type, new long[] {labelled.getOrDefault(type, 0L), predictedCounts.getOrDefault(type, 0L), correct.getOrDefault(type, 0L)});
        }

        // A noise case has a false break if any break touches any of its records.
        Dataset<Row> noiseRefs = labels.where(col("noise").isNotNull())
                .select(col("case_id"), explode(split(concat_ws("|", col("trade_refs"), col("broker_refs")), "\\|")).as("ref"));
        Dataset<Row> breakRefs = breaks.select(col("internalRef").as("ref")).union(breaks.select(col("externalRef").as("ref")))
                .where(col("ref").isNotNull()).distinct();
        long noiseCases = labels.where(col("noise").isNotNull()).count();
        long falseNoise = noiseRefs.join(breakRefs, "ref").select("case_id").distinct().count();
        truth.unpersist();
        predicted.unpersist();
        return new Evaluation(byType, noiseCases, falseNoise);
    }

    private Dataset<Row> csv(Path file) {
        return spark.read().option("header", "true").csv(file.toString());
    }

    private static Map<String, Long> countBy(Dataset<Row> rows, String column) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Row r : rows.groupBy(column).count().orderBy(column).collectAsList()) {
            counts.put(r.getString(0), r.getLong(1));
        }
        return counts;
    }

    private static List<String> unionKeys(Map<String, Long> a, Map<String, Long> b) {
        TreeMap<String, Long> all = new TreeMap<>(a);
        b.keySet().forEach(k -> all.putIfAbsent(k, 0L));
        return new ArrayList<>(all.keySet());
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

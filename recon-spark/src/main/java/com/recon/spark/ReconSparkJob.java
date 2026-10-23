package com.recon.spark;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.spark.sql.SparkSession;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "recon-spark", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Reconciles a generated dataset (Parquet) with the recon-core matcher on Spark.")
public final class ReconSparkJob implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = "--data", required = true, description = "Dataset directory written by recon-datagen (with Parquet).")
    private Path data;

    @Option(names = "--out", defaultValue = "build/spark-out", description = "Where match_links/ and breaks/ are written (default: ${DEFAULT-VALUE}).")
    private Path out;

    @Option(names = "--tolerances", description = "Tolerance config (default: the copy in the dataset directory).")
    private Path tolerances;

    @Option(names = "--master", defaultValue = "local[*]", description = "Spark master (default: ${DEFAULT-VALUE}, all local cores).")
    private String master;

    @Option(names = "--shuffle-partitions", defaultValue = "200", description = "spark.sql.shuffle.partitions (default: ${DEFAULT-VALUE}).")
    private int shufflePartitions;

    public static void main(String[] args) {
        System.exit(new CommandLine(new ReconSparkJob()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        Path tolerancesFile = tolerances != null ? tolerances : data.resolve("tolerances.properties");
        for (Path required : new Path[] {data.resolve("internal_trades.parquet"), data.resolve("external_confirms.parquet"), tolerancesFile}) {
            if (!Files.exists(required)) {
                throw new ParameterException(spec.commandLine(), "Missing " + required.toAbsolutePath()
                        + "; generate the dataset with recon-datagen (Parquet is on by default)");
            }
        }
        long sessionStart = System.nanoTime();
        try (SparkSession spark = session(master, shufflePartitions)) {
            long sessionMillis = (System.nanoTime() - sessionStart) / 1_000_000;
            ReconJob.Summary summary = new ReconJob(spark).run(data, tolerancesFile, out);
            print(spec.commandLine().getOut(), summary, sessionMillis, spark.sparkContext().defaultParallelism());
        }
        return 0;
    }

    /** A local Spark session tuned for one machine: no web UI, Java time types, fixed shuffle width. */
    public static SparkSession session(String master, int shufflePartitions) {
        return SparkSession.builder()
                .appName("trade-recon-eod")
                .master(master)
                .config("spark.ui.enabled", "false")
                .config("spark.sql.shuffle.partitions", shufflePartitions)
                // DATE columns as java.time.LocalDate, which is what recon-core's records use.
                .config("spark.sql.datetime.java8API.enabled", "true")
                // Java serialization (the default) for the broadcast MatcherSpec: Spark's bundled
                // Kryo can't serialize Java records. Rows use Spark's own encoders either way.
                .getOrCreate();
    }

    private void print(PrintWriter w, ReconJob.Summary s, long sessionMillis, int cores) {
        w.printf("Reconciled %,d internal trades and %,d confirms on %d cores%n", s.trades(), s.confirms(), cores);
        w.printf("  read + match + write   %,8.1f s   (%,.0f rows/s)%n", s.matchAndWriteMillis() / 1e3,
                (s.trades() + s.confirms()) / (s.matchAndWriteMillis() / 1e3));
        w.printf("  whole job              %,8.1f s   (of which %.1f s scoring against labels.csv; plus %.1f s Spark startup)%n",
                s.totalMillis() / 1e3, s.evaluationMillis() / 1e3, sessionMillis / 1e3);
        w.printf("  matches by rule        %s%n", s.matchesByRule());
        w.printf("  matched trades         %,d   STP %,d = %.2f%%%n", s.matchedTrades(), s.straightThroughTrades(), 100 * s.stpRate());
        w.printf("  breaks                 %,d%n", s.breaksByType().values().stream().mapToLong(Long::longValue).sum());
        if (s.evaluation() != null) {
            w.printf("%n  %-24s %10s %10s %10s %10s%n", "against labels.csv", "labelled", "predicted", "precision", "recall");
            long labelled = 0;
            long predicted = 0;
            long correct = 0;
            for (Map.Entry<String, long[]> e : s.evaluation().byType().entrySet()) {
                long[] v = e.getValue();
                labelled += v[0];
                predicted += v[1];
                correct += v[2];
                w.printf("  %-24s %,10d %,10d %10s %10s%n", e.getKey(), v[0], v[1], percent(v[2], v[1]), percent(v[2], v[0]));
            }
            w.printf("  %-24s %,10d %,10d %10s %10s%n", "ALL BREAKS", labelled, predicted, percent(correct, predicted), percent(correct, labelled));
            w.printf("  false-break rate on noise rows: %s (%,d/%,d)%n",
                    percent(s.evaluation().noiseCasesWithFalseBreaks(), s.evaluation().noiseCases()),
                    s.evaluation().noiseCasesWithFalseBreaks(), s.evaluation().noiseCases());
        }
        w.printf("Output: %s%n", out.toAbsolutePath().normalize());
        w.flush();
    }

    private static String percent(long part, long whole) {
        return whole == 0 ? "n/a" : "%.2f%%".formatted(100.0 * part / whole);
    }
}

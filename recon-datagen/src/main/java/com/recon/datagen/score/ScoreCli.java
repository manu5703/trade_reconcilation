package com.recon.datagen.score;

import com.recon.core.match.MatchResult;
import com.recon.core.match.MatchRule;
import com.recon.core.match.Matcher;
import com.recon.core.model.BreakType;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.refdata.StaticData;
import com.recon.core.tolerance.ToleranceConfig;
import com.recon.datagen.output.DatasetReader;
import com.recon.datagen.output.Label;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/** Runs the full matcher and the exact-only baseline on a generated dataset and scores both against labels.csv. */
@Command(name = "recon-score", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Matches a generated dataset and prints precision/recall per break type against labels.csv.")
public final class ScoreCli implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = "--data", defaultValue = "data", description = "Dataset directory written by recon-datagen (default: ${DEFAULT-VALUE}).")
    private Path data;

    @Option(names = "--tolerances", description = "Tolerance config (default: the copy saved in the dataset directory).")
    private Path tolerancesFile;

    @Option(names = "--repeat", defaultValue = "3", description = "Timed runs per matcher; the fastest is reported, so JIT warm-up doesn't count (default: ${DEFAULT-VALUE}).")
    private int repeat;

    @Option(names = "--show-errors", defaultValue = "0", description = "List up to N misclassified breaks for the full matcher (default: ${DEFAULT-VALUE}).")
    private int showErrors;

    public static void main(String[] args) {
        System.exit(new CommandLine(new ScoreCli()).execute(args));
    }

    /** A matcher's result and how long its fastest run took. */
    record Run(MatchResult result, Scorecard scorecard, long nanos) {
    }

    @Override
    public Integer call() throws IOException {
        if (repeat < 1) {
            throw new ParameterException(spec.commandLine(), "--repeat must be at least 1");
        }
        Path tolerancePath = tolerancesFile != null ? tolerancesFile : data.resolve("tolerances.properties");
        if (!Files.isRegularFile(data.resolve("labels.csv"))) {
            throw new ParameterException(spec.commandLine(), "No dataset in " + data.toAbsolutePath() + "; run recon-datagen first");
        }
        if (!Files.isRegularFile(tolerancePath)) {
            throw new ParameterException(spec.commandLine(), "Tolerance config not found: " + tolerancePath.toAbsolutePath());
        }

        long loadStart = System.nanoTime();
        ToleranceConfig tolerances = ToleranceConfig.fromProperties(load(tolerancePath));
        StaticData staticData = DatasetReader.staticData(data);
        List<InternalTrade> trades = DatasetReader.internalTrades(data);
        List<ExternalConfirm> confirms = DatasetReader.externalConfirms(data);
        List<Label> labels = DatasetReader.labels(data);
        long loadNanos = System.nanoTime() - loadStart;

        Run full = run(Matcher.full(staticData, tolerances), trades, confirms, labels);
        Run baseline = run(Matcher.baseline(staticData, tolerances), trades, confirms, labels);

        print(spec.commandLine().getOut(), trades.size(), confirms.size(), labels.size(), loadNanos, full, baseline);
        return 0;
    }

    private Run run(Matcher matcher, List<InternalTrade> trades, List<ExternalConfirm> confirms, List<Label> labels) {
        MatchResult result = null;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < repeat; i++) {
            long start = System.nanoTime();
            result = matcher.match(trades, confirms);
            best = Math.min(best, System.nanoTime() - start);
        }
        return new Run(result, Scorecard.score(labels, result), best);
    }

    private void print(PrintWriter w, int trades, int confirms, int cases, long loadNanos, Run full, Run baseline) {
        w.printf("Dataset %s: %,d internal trades, %,d confirms, %,d labelled cases (loaded in %.2f s)%n%n",
                data.toAbsolutePath().normalize(), trades, confirms, cases, loadNanos / 1e9);
        w.printf("%-24s %8s | %-22s | %-22s%n", "", "", "full matcher", "baseline (exact only)");
        w.printf("%-24s %8s | %10s %10s  | %10s %10s%n", "break type", "labelled", "precision", "recall", "precision", "recall");
        w.println("-".repeat(85));
        for (BreakType type : BreakType.values()) {
            row(w, type.name(), full.scorecard().byType().get(type), baseline.scorecard().byType().get(type));
        }
        w.println("-".repeat(85));
        row(w, "ALL BREAKS", full.scorecard().overall(), baseline.scorecard().overall());
        w.println();

        Scorecard f = full.scorecard();
        Scorecard b = baseline.scorecard();
        line(w, "false-break rate, noise rows", ratio(f.noiseCasesWithFalseBreaks(), f.noiseCases()), ratio(b.noiseCasesWithFalseBreaks(), b.noiseCases()));
        line(w, "false-break rate, all clean", ratio(f.cleanCasesWithFalseBreaks(), f.cleanCases()), ratio(b.cleanCasesWithFalseBreaks(), b.cleanCases()));
        line(w, "clean cases paired right", ratio(f.cleanCasesCorrectlyPaired(), f.cleanCases()), ratio(b.cleanCasesCorrectlyPaired(), b.cleanCases()));
        line(w, "STP rate", ratio(f.straightThroughTrades(), f.internalTrades()), ratio(b.straightThroughTrades(), b.internalTrades()));
        line(w, "STP rate, perfect matcher", ratio(f.idealStraightThroughTrades(), f.internalTrades()), "");
        line(w, "matches by rule", rules(full.result()), rules(baseline.result()));
        line(w, "match time (best of " + repeat + ")", timing(full.nanos(), trades + confirms), timing(baseline.nanos(), trades + confirms));
        if (showErrors > 0) {
            w.printf("%nFull matcher errors (first %d of each):%n", showErrors);
            w.printf("  predicted, not labelled: %s%n", f.falsePositives().stream().limit(showErrors).toList());
            w.printf("  labelled, not predicted: %s%n", f.falseNegatives().stream().limit(showErrors).toList());
        }
        w.flush();
    }

    private static void row(PrintWriter w, String name, Scorecard.TypeScore full, Scorecard.TypeScore baseline) {
        w.printf("%-24s %8d | %10s %10s  | %10s %10s%n", name, full.labelled(),
                percent(full.precision()), percent(full.recall()), percent(baseline.precision()), percent(baseline.recall()));
    }

    private static void line(PrintWriter w, String name, String full, String baseline) {
        w.printf("%-33s | %-22s | %s%n", name, full, baseline);
    }

    private static String percent(double value) {
        return Double.isNaN(value) ? "n/a" : "%.1f%%".formatted(100 * value);
    }

    private static String ratio(int part, int whole) {
        return "%.2f%% (%,d/%,d)".formatted(whole == 0 ? 0 : 100.0 * part / whole, part, whole);
    }

    private static String rules(MatchResult result) {
        var counts = result.matchesByRule();
        return "E %,d  B %,d  F %,d".formatted(counts.getOrDefault(MatchRule.EXACT, 0),
                counts.getOrDefault(MatchRule.BLOCK, 0), counts.getOrDefault(MatchRule.FUZZY, 0));
    }

    private static String timing(long nanos, int rows) {
        return "%,d ms, %,.0f rows/s".formatted(nanos / 1_000_000, rows / (nanos / 1e9));
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}

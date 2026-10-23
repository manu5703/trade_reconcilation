package com.recon.datagen;

import com.recon.core.model.BreakType;
import com.recon.core.tolerance.ToleranceConfig;
import com.recon.datagen.output.CsvDatasetWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "recon-datagen", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Generates synthetic internal trades, external confirms and ground-truth labels.")
public final class DatagenCli implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = "--trades", defaultValue = "10000", description = "Internal trades per business day (default: ${DEFAULT-VALUE}).")
    private int trades;

    @Option(names = "--seed", defaultValue = "42", description = "Random seed (default: ${DEFAULT-VALUE}).")
    private long seed;

    @Option(names = "--out", defaultValue = "data", description = "Output directory (default: ${DEFAULT-VALUE}).")
    private Path out;

    @Option(names = "--start-date", defaultValue = "2026-01-15", description = "First business date (default: ${DEFAULT-VALUE}).")
    private LocalDate startDate;

    @Option(names = "--days", defaultValue = "1", description = "Number of business days (default: ${DEFAULT-VALUE}).")
    private int days;

    @Option(names = "--break-rate", defaultValue = "0.03", description = "Fraction of internal trades with an injected break (default: ${DEFAULT-VALUE}).")
    private double breakRate;

    @Option(names = "--noise-rate", defaultValue = "0.10", description = "Fraction of clean cases with harmless noise (default: ${DEFAULT-VALUE}).")
    private double noiseRate;

    @Option(names = "--block-share", defaultValue = "0.15", description = "Fraction of internal trades that are block allocations (default: ${DEFAULT-VALUE}).")
    private double blockShare;

    @Option(names = "--break-mix", description = "Relative break weights, e.g. PRICE_OUT_OF_TOLERANCE=2,MISSING_CONFIRM=1 (default: all 9 types equally).")
    private String breakMix;

    @Option(names = "--instruments", defaultValue = "200", description = "Number of instruments; fewer means denser books (default: ${DEFAULT-VALUE}).")
    private int instruments;

    @Option(names = "--tolerances", defaultValue = "config/tolerances.properties", description = "Tolerance config (default: ${DEFAULT-VALUE}).")
    private Path tolerancesFile;

    @Option(names = "--no-parquet", description = "Skip the Parquet copies of trades and confirms.")
    private boolean noParquet;

    public static void main(String[] args) {
        System.exit(new CommandLine(new DatagenCli()).execute(args));
    }

    @Override
    public Integer call() throws IOException {
        DatagenConfig config;
        ToleranceConfig tolerances;
        try {
            config = DatagenConfig.builder()
                    .tradesPerDay(trades).seed(seed).startDate(startDate).businessDays(days)
                    .breakRate(breakRate).noiseRate(noiseRate).blockShare(blockShare)
                    .breakMix(breakMix == null ? BreakMix.uniform() : BreakMix.parse(breakMix))
                    .instruments(instruments)
                    .build();
            tolerances = ToleranceConfig.fromProperties(loadProperties(tolerancesFile));
        } catch (IllegalArgumentException e) {
            // Reported by picocli as a usage error (exit code 2) instead of a stack trace.
            throw new ParameterException(spec.commandLine(), e.getMessage());
        }

        long started = System.nanoTime();
        GenerationSummary summary;
        try (CsvDatasetWriter writer = new CsvDatasetWriter(out, !noParquet)) {
            summary = new DataGenerator(config, tolerances).generate(writer);
        } catch (IllegalArgumentException e) {
            throw new ParameterException(spec.commandLine(), e.getMessage());
        }
        // Keep the exact inputs next to the data so a dataset can always be regenerated.
        Files.copy(tolerancesFile, out.resolve("tolerances.properties"), StandardCopyOption.REPLACE_EXISTING);
        Files.write(out.resolve("manifest.properties"), manifest(config), StandardCharsets.UTF_8);
        double seconds = (System.nanoTime() - started) / 1e9;

        print(spec.commandLine().getOut(), config, summary, seconds);
        return 0;
    }

    private Properties loadProperties(Path file) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read tolerance config " + file.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        return properties;
    }

    private static List<String> manifest(DatagenConfig c) {
        return List.of(
                "seed=" + c.seed(),
                "tradesPerDay=" + c.tradesPerDay(),
                "startDate=" + c.startDate(),
                "businessDays=" + c.businessDays(),
                "breakRate=" + c.breakRate(),
                "noiseRate=" + c.noiseRate(),
                "blockShare=" + c.blockShare(),
                "breakMix=" + c.breakMix().weights(),
                "instruments=" + c.instruments(),
                "counterparties=" + c.counterparties(),
                "accounts=" + c.accounts());
    }

    private void print(PrintWriter w, DatagenConfig config, GenerationSummary s, double seconds) {
        LocalDate first = BusinessDays.onOrAfter(config.startDate());
        w.printf("Generated %d business day(s) from %s, seed %d, in %.2f s%n", config.businessDays(), first, config.seed(), seconds);
        w.printf("  reference data     %d instruments, %d counterparties, %d accounts%n",
                s.referenceData().instruments().size(), s.referenceData().counterparties().size(), s.referenceData().accounts().size());
        w.printf("  internal trades    %,d  (block allocations %,d = %.1f%%)%n",
                s.internalTrades(), s.blockAllocations(), percent(s.blockAllocations(), s.internalTrades()));
        w.printf("  external confirms  %,d%n", s.confirms());
        w.printf("  cases (labels)     %,d%n", s.cases());
        w.printf("  injected breaks    %,d  (%.2f%% of internal trades)%n", s.totalBreaks(), percent(s.totalBreaks(), s.internalTrades()));
        for (BreakType type : BreakType.values()) {
            w.printf("    %-24s %,6d%n", type, s.breaks(type));
        }
        w.printf("  harmless noise     %,d%n", s.totalNoise());
        for (NoiseType type : NoiseType.values()) {
            w.printf("    %-24s %,6d%n", type, s.noise(type));
        }
        w.printf("Output: %s%n", out.toAbsolutePath().normalize());
        w.flush();
    }

    private static double percent(int part, int whole) {
        return whole == 0 ? 0 : 100.0 * part / whole;
    }
}

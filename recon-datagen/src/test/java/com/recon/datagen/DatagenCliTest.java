package com.recon.datagen;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** End to end: run the CLI, then check what landed on disk. */
class DatagenCliTest {

    @TempDir
    Path out;

    @Test
    void labelsOnDiskMatchConfiguredBreakRateAndMix() throws IOException {
        int exitCode = run("--trades", "9000", "--seed", "42", "--out", out.toString(), "--tolerances", TestSupport.tolerancesFile().toString());
        assertThat(exitCode).isZero();

        List<CSVRecord> labels = readCsv(out.resolve("labels.csv"));
        List<CSVRecord> breaks = labels.stream().filter(r -> r.get("expected").equals("BREAK")).toList();
        Map<String, Long> byType = breaks.stream().collect(groupingBy(r -> r.get("break_type"), counting()));

        assertThat(breaks).hasSize(270);            // 3% of 9,000
        assertThat(byType).hasSize(9).allSatisfy((type, n) -> assertThat(n).isEqualTo(30L));
        assertThat(readCsv(out.resolve("internal_trades.csv"))).hasSize(9_000);
    }

    @Test
    void sameSeedWritesByteIdenticalFiles(@TempDir Path other) throws IOException {
        run("--trades", "3000", "--seed", "5", "--out", out.toString(), "--no-parquet", "--tolerances", TestSupport.tolerancesFile().toString());
        run("--trades", "3000", "--seed", "5", "--out", other.toString(), "--no-parquet", "--tolerances", TestSupport.tolerancesFile().toString());

        for (String file : List.of("internal_trades.csv", "external_confirms.csv", "labels.csv", "instruments.csv", "counterparties.csv", "manifest.properties")) {
            assertThat(Files.mismatch(out.resolve(file), other.resolve(file))).as(file).isEqualTo(-1L);
        }
    }

    @Test
    void parquetHasSameRowsAndExactDecimalTypes() throws Exception {
        run("--trades", "4000", "--seed", "11", "--out", out.toString(), "--tolerances", TestSupport.tolerancesFile().toString());

        try (Connection db = DriverManager.getConnection("jdbc:duckdb:"); Statement s = db.createStatement()) {
            assertThat(scalar(s, "SELECT count(*) FROM " + parquet("internal_trades"))).isEqualTo("4000");
            assertThat(scalar(s, "SELECT count(*) FROM " + parquet("external_confirms")))
                    .isEqualTo(String.valueOf(readCsv(out.resolve("external_confirms.csv")).size()));
            assertThat(scalar(s, "SELECT typeof(price) FROM " + parquet("internal_trades") + " LIMIT 1")).isEqualTo("DECIMAL(20,6)");
            assertThat(scalar(s, "SELECT typeof(trade_date) FROM " + parquet("external_confirms") + " LIMIT 1")).isEqualTo("DATE");
            // REF_FORMAT noise pads some ISINs with spaces; Parquet must keep them as-is for the normalizer.
            assertThat(Long.parseLong(scalar(s, "SELECT count(*) FROM " + parquet("external_confirms") + " WHERE isin LIKE ' %'"))).isPositive();
            // Single trades have no block ref, which must be NULL rather than an empty string.
            assertThat(Long.parseLong(scalar(s, "SELECT count(*) FROM " + parquet("internal_trades") + " WHERE block_ref IS NULL"))).isPositive();
        }
    }

    @Test
    void invalidOptionIsAUsageErrorWithAClearMessage() {
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new DatagenCli());
        cli.setErr(new PrintWriter(err));

        int exitCode = cli.execute("--break-rate", "1.5", "--out", out.toString());

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("break rate must be between 0 and 1, was 1.5");
    }

    @Test
    void missingToleranceFileIsAUsageError() {
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new DatagenCli());
        cli.setErr(new PrintWriter(err));

        int exitCode = cli.execute("--tolerances", out.resolve("nope.properties").toString(), "--out", out.toString());

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Cannot read tolerance config");
    }

    private static int run(String... args) {
        CommandLine cli = new CommandLine(new DatagenCli());
        cli.setOut(new PrintWriter(new StringWriter()));
        return cli.execute(args);
    }

    private String parquet(String name) {
        return "'" + out.resolve(name + ".parquet") + "'";
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<CSVRecord> readCsv(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader).getRecords();
        }
    }
}

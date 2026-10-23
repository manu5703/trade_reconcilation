package com.recon.service.importer;

import com.recon.service.config.ReconProperties;
import com.recon.service.web.ApiException;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a dataset written by recon-datagen from the configured import directory.
 *
 * <p>Each CSV is streamed into a temporary staging table with PostgreSQL {@code COPY}, the
 * fastest bulk path (no per-row statements), then copied into the real table with
 * {@code INSERT ... ON CONFLICT DO NOTHING}. Importing the same files twice therefore changes
 * nothing, and an import can be re-run safely after a failure. Everything happens in one
 * transaction: a bad file leaves the database as it was.
 */
@Service
public class DatasetImportService {

    private static final List<String> REQUIRED_FILES = List.of(
            "instruments.csv", "counterparties.csv", "internal_trades.csv", "external_confirms.csv");

    private static final String TRADE_COLUMNS =
            "trade_ref, block_ref, account, isin, side, quantity, price, trade_date, settle_date, counterparty, net_amount";
    private static final String CONFIRM_COLUMNS =
            "broker_ref, source, isin, side, quantity, price, commission, trade_date, settle_date, counterparty, net_amount";

    /** Rows read from a file, how many were new, and how many were already there. */
    public record TableResult(int read, int inserted, int skipped) {
        static TableResult of(long read, int inserted) {
            return new TableResult(Math.toIntExact(read), inserted, Math.toIntExact(read - inserted));
        }
    }

    public record ImportResult(String directory, Map<String, TableResult> tables) {
    }

    private final ReconProperties properties;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public DatasetImportService(ReconProperties properties, DataSource dataSource, JdbcTemplate jdbc) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Transactional
    public ImportResult importDataset() {
        Path dir = properties.importDirectory().toAbsolutePath().normalize();
        List<String> missing = REQUIRED_FILES.stream().filter(f -> !Files.isRegularFile(dir.resolve(f))).toList();
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("No complete dataset in " + dir + " (missing " + String.join(", ", missing)
                    + "). Generate one with ./gradlew :recon-datagen:run, or point recon.import-directory at one.");
        }

        Map<String, TableResult> results = new LinkedHashMap<>();
        results.put("instrument", load(dir.resolve("instruments.csv"),
                "create temp table stage_instrument (isin text, asset_class text, reference_price numeric(20,6)) on commit drop",
                "stage_instrument",
                "insert into instrument select * from stage_instrument on conflict (isin) do nothing"));
        results.put("counterparty", load(dir.resolve("counterparties.csv"),
                "create temp table stage_counterparty (counterparty_id text, name text, aliases text, confirm_source text, commission_bps numeric(20,6)) on commit drop",
                "stage_counterparty",
                "insert into counterparty select counterparty_id, name, confirm_source, commission_bps from stage_counterparty on conflict (counterparty_id) do nothing"));
        // Aliases are pipe-separated in one CSV column; unnest turns them into one row each.
        jdbc.update("""
                insert into counterparty_alias (counterparty_id, alias)
                select counterparty_id, unnest(string_to_array(aliases, '|')) from stage_counterparty
                on conflict do nothing""");
        results.put("internal_trade", load(dir.resolve("internal_trades.csv"),
                "create temp table stage_internal_trade on commit drop as select " + TRADE_COLUMNS + " from internal_trade with no data",
                "stage_internal_trade",
                "insert into internal_trade (" + TRADE_COLUMNS + ") select * from stage_internal_trade on conflict (trade_ref) do nothing"));
        results.put("external_confirm", load(dir.resolve("external_confirms.csv"),
                "create temp table stage_external_confirm on commit drop as select " + CONFIRM_COLUMNS + " from external_confirm with no data",
                "stage_external_confirm",
                "insert into external_confirm (" + CONFIRM_COLUMNS + ") select * from stage_external_confirm on conflict (broker_ref) do nothing"));
        return new ImportResult(dir.toString(), results);
    }

    /** Creates the staging table, COPYs the file into it, then inserts the new rows. */
    private TableResult load(Path csv, String createStaging, String stagingTable, String insert) {
        jdbc.execute(createStaging);
        long read = copyIn(csv, stagingTable);
        int inserted = jdbc.update(insert);
        return TableResult.of(read, inserted);
    }

    private long copyIn(Path csv, String table) {
        // The transaction's own connection, so the COPY sees the temp table and rolls back with the import.
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            return connection.unwrap(PGConnection.class).getCopyAPI()
                    .copyIn("copy " + table + " from stdin with (format csv, header true)", reader);
        } catch (SQLException e) {
            throw ApiException.unprocessable("Could not load " + csv.getFileName() + ": " + e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

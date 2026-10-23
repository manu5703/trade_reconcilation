package com.recon.datagen.output;

import com.recon.core.model.AssetClass;
import com.recon.core.model.BreakType;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.model.Side;
import com.recon.core.model.Source;
import com.recon.core.refdata.CounterpartyRef;
import com.recon.core.refdata.StaticData;
import com.recon.datagen.NoiseType;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/** Reads a dataset written by {@link CsvDatasetWriter} back into domain objects. */
public final class DatasetReader {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();

    private DatasetReader() {
    }

    public static StaticData staticData(Path dir) throws IOException {
        Map<String, AssetClass> instruments = new HashMap<>();
        for (CSVRecord r : records(dir.resolve("instruments.csv"))) {
            instruments.put(r.get("isin"), AssetClass.valueOf(r.get("asset_class")));
        }
        List<CounterpartyRef> counterparties = read(dir.resolve("counterparties.csv"), r -> new CounterpartyRef(
                r.get("counterparty_id"), split(r.get("aliases")), new BigDecimal(r.get("commission_bps"))));
        return new StaticData(instruments, counterparties);
    }

    public static List<InternalTrade> internalTrades(Path dir) throws IOException {
        return read(dir.resolve(CsvDatasetWriter.INTERNAL_TRADES + ".csv"), r -> new InternalTrade(
                r.get("trade_ref"), emptyToNull(r.get("block_ref")), r.get("account"), r.get("isin"), Side.valueOf(r.get("side")),
                new BigDecimal(r.get("quantity")), new BigDecimal(r.get("price")), LocalDate.parse(r.get("trade_date")),
                LocalDate.parse(r.get("settle_date")), r.get("counterparty"), new BigDecimal(r.get("net_amount"))));
    }

    public static List<ExternalConfirm> externalConfirms(Path dir) throws IOException {
        return read(dir.resolve(CsvDatasetWriter.EXTERNAL_CONFIRMS + ".csv"), r -> new ExternalConfirm(
                r.get("broker_ref"), Source.valueOf(r.get("source")), r.get("isin"), Side.valueOf(r.get("side")),
                new BigDecimal(r.get("quantity")), new BigDecimal(r.get("price")), new BigDecimal(r.get("commission")),
                LocalDate.parse(r.get("trade_date")), LocalDate.parse(r.get("settle_date")), r.get("counterparty"),
                new BigDecimal(r.get("net_amount"))));
    }

    public static List<Label> labels(Path dir) throws IOException {
        return read(dir.resolve(CsvDatasetWriter.LABELS), r -> new Label(
                r.get("case_id"),
                r.get("break_type").isEmpty() ? null : BreakType.valueOf(r.get("break_type")),
                emptyToNull(r.get("break_field")),
                r.get("noise").isEmpty() ? null : NoiseType.valueOf(r.get("noise")),
                emptyToNull(r.get("block_ref")),
                split(r.get("trade_refs")),
                split(r.get("broker_refs"))));
    }

    private static <T> List<T> read(Path file, Function<CSVRecord, T> mapper) throws IOException {
        List<T> rows = new ArrayList<>();
        for (CSVRecord r : records(file)) {
            try {
                rows.add(mapper.apply(r));
            } catch (RuntimeException e) {
                throw new IOException("Bad row " + r.getRecordNumber() + " in " + file + ": " + e.getMessage(), e);
            }
        }
        return rows;
    }

    private static List<CSVRecord> records(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8); CSVParser parser = FORMAT.parse(reader)) {
            return parser.getRecords();
        }
    }

    private static List<String> split(String pipeSeparated) {
        return pipeSeparated.isEmpty() ? List.of() : Arrays.asList(pipeSeparated.split("\\|"));
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}

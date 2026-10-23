package com.recon.datagen.output;

import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.datagen.DatasetSink;
import com.recon.datagen.TradeCase;
import com.recon.datagen.refdata.Counterparty;
import com.recon.datagen.refdata.Instrument;
import com.recon.datagen.refdata.ReferenceData;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/**
 * Writes the dataset as CSV, streaming one case at a time, and converts trades and confirms to
 * Parquet on close.
 *
 * <p>Files: instruments.csv, counterparties.csv, accounts.csv, internal_trades.csv/.parquet,
 * external_confirms.csv/.parquet, labels.csv. Multi-valued label fields (refs, aliases) are
 * pipe-separated.
 */
public final class CsvDatasetWriter implements DatasetSink {

    public static final String INTERNAL_TRADES = "internal_trades";
    public static final String EXTERNAL_CONFIRMS = "external_confirms";
    public static final String LABELS = "labels.csv";

    static final String[] LABEL_HEADER = {
        "case_id", "trade_date", "expected", "break_type", "break_field", "noise", "block_ref", "trade_refs", "broker_refs"
    };

    private final Path outDir;
    private final boolean writeParquet;
    private final CSVPrinter trades;
    private final CSVPrinter confirms;
    private final CSVPrinter labels;

    public CsvDatasetWriter(Path outDir, boolean writeParquet) throws IOException {
        this.outDir = Files.createDirectories(outDir);
        this.writeParquet = writeParquet;
        this.trades = printer(INTERNAL_TRADES + ".csv", Column.names(Column.INTERNAL_TRADE));
        this.confirms = printer(EXTERNAL_CONFIRMS + ".csv", Column.names(Column.EXTERNAL_CONFIRM));
        this.labels = printer(LABELS, LABEL_HEADER);
    }

    @Override
    public void referenceData(ReferenceData data) throws IOException {
        try (CSVPrinter out = printer("instruments.csv", "isin", "asset_class", "reference_price")) {
            for (Instrument i : data.instruments()) {
                out.printRecord(i.isin(), i.assetClass(), plain(i.referencePrice()));
            }
        }
        try (CSVPrinter out = printer("counterparties.csv", "counterparty_id", "name", "aliases", "confirm_source", "commission_bps")) {
            for (Counterparty c : data.counterparties()) {
                out.printRecord(c.id(), c.name(), String.join("|", c.aliases()), c.confirmSource(), plain(c.commissionBps()));
            }
        }
        try (CSVPrinter out = printer("accounts.csv", "account_id")) {
            for (String account : data.accounts()) {
                out.printRecord(account);
            }
        }
    }

    @Override
    public void accept(TradeCase c) throws IOException {
        for (InternalTrade t : c.trades()) {
            trades.printRecord(t.tradeRef(), t.blockRef(), t.account(), t.isin(), t.side(), plain(t.quantity()),
                    plain(t.price()), t.tradeDate(), t.settleDate(), t.counterparty(), plain(t.netAmount()));
        }
        for (ExternalConfirm x : c.confirms()) {
            confirms.printRecord(x.brokerRef(), x.source(), x.isin(), x.side(), plain(x.quantity()), plain(x.price()),
                    plain(x.commission()), x.tradeDate(), x.settleDate(), x.counterparty(), plain(x.netAmount()));
        }
        labels.printRecord(c.caseId(), c.tradeDate(), c.isBreak() ? "BREAK" : "MATCH", c.breakType(), c.breakField(),
                c.noise(), c.blockRef(),
                c.trades().stream().map(InternalTrade::tradeRef).collect(Collectors.joining("|")),
                c.confirms().stream().map(ExternalConfirm::brokerRef).collect(Collectors.joining("|")));
    }

    @Override
    public void close() throws IOException {
        trades.close();
        confirms.close();
        labels.close();
        if (writeParquet) {
            ParquetConverter.convert(outDir.resolve(INTERNAL_TRADES + ".csv"), outDir.resolve(INTERNAL_TRADES + ".parquet"), Column.INTERNAL_TRADE);
            ParquetConverter.convert(outDir.resolve(EXTERNAL_CONFIRMS + ".csv"), outDir.resolve(EXTERNAL_CONFIRMS + ".parquet"), Column.EXTERNAL_CONFIRM);
        }
    }

    private CSVPrinter printer(String fileName, String... header) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(outDir.resolve(fileName), StandardCharsets.UTF_8);
        // Fixed "\n" line endings keep files byte-identical across operating systems.
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader(header).setRecordSeparator('\n').build();
        return new CSVPrinter(writer, format);
    }

    /** toPlainString avoids scientific notation such as 1E+3, which some CSV readers misparse. */
    private static String plain(BigDecimal value) {
        return value.toPlainString();
    }
}

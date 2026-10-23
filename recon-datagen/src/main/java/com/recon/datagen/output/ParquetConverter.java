package com.recon.datagen.output;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts a finished CSV file to Parquet using an in-process DuckDB database.
 *
 * <p>DuckDB is a single dependency, whereas parquet-java pulls in Hadoop. The column types are
 * given explicitly (no type sniffing), so decimals land as DECIMAL(20,6) and the Parquet schema
 * never depends on the data. See docs/decisions/0003.
 */
final class ParquetConverter {

    private ParquetConverter() {
    }

    static void convert(Path csv, Path parquet, List<Column> columns) throws IOException {
        String columnTypes = columns.stream()
                .map(c -> "'" + c.name() + "': '" + c.sqlType() + "'")
                .collect(Collectors.joining(", ", "{", "}"));
        String sql = "COPY (SELECT * FROM read_csv(%s, header = true, auto_detect = false, columns = %s)) TO %s (FORMAT PARQUET, COMPRESSION ZSTD)"
                .formatted(literal(csv), columnTypes, literal(parquet));
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IOException("Could not convert " + csv + " to Parquet: " + e.getMessage(), e);
        }
    }

    private static String literal(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'";
    }
}

package com.recon.datagen.score;

import static org.assertj.core.api.Assertions.assertThat;

import com.recon.datagen.DatagenCli;
import com.recon.datagen.TestSupportAccess;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ScoreCliTest {

    @TempDir
    Path data;

    @Test
    void scoresAGeneratedDatasetFromDisk() {
        int generated = new CommandLine(new DatagenCli()).setOut(new PrintWriter(new StringWriter()))
                .execute("--trades", "3000", "--seed", "8", "--no-parquet", "--out", data.toString(), "--tolerances", TestSupportAccess.tolerancesFile());
        assertThat(generated).isZero();

        StringWriter out = new StringWriter();
        int exitCode = new CommandLine(new ScoreCli()).setOut(new PrintWriter(out)).execute("--data", data.toString(), "--repeat", "1");

        assertThat(exitCode).isZero();
        assertThat(out.toString())
                .containsPattern("ALL BREAKS\\s+90 \\|\\s+100\\.0%\\s+100\\.0%")
                .contains("false-break rate, noise rows      | 0.00%")
                .contains("STP rate");
    }

    @Test
    void missingDatasetIsAUsageError() {
        StringWriter err = new StringWriter();

        int exitCode = new CommandLine(new ScoreCli()).setErr(new PrintWriter(err)).execute("--data", data.resolve("nope").toString());

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("run recon-datagen first");
    }
}

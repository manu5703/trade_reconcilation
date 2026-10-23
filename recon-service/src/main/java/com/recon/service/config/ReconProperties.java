package com.recon.service.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Service settings under {@code recon.*}.
 *
 * @param importDirectory where {@code POST /api/imports} reads a generated dataset from. Fixed by
 *                        configuration, never taken from the request, so the API can't be used to
 *                        read arbitrary files.
 * @param runThreads      how many business dates can be reconciled at the same time
 */
@Validated
@ConfigurationProperties("recon")
public record ReconProperties(@NotNull Path importDirectory, @Min(1) @Max(16) int runThreads) {
}

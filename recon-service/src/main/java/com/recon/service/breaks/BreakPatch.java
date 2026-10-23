package com.recon.service.breaks;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Changes to a break. Any combination in one request: assign and note together, or note and
 * resolve together.
 *
 * @param owner           assigns the break (status becomes ASSIGNED)
 * @param note            appended to the break's notes with a timestamp
 * @param status          only RESOLVED is accepted; it requires {@code reason}
 * @param expectedVersion if given, the change is refused with 409 when the break has changed since
 */
public record BreakPatch(
        @Schema(example = "j.smith") @Size(min = 1, max = 100) String owner,
        @Schema(example = "Chased broker for corrected confirm") @Size(min = 1, max = 2_000) String note,
        @Schema(example = "RESOLVED") BreakStatus status,
        @Schema(example = "Broker confirmed booking error; amended in OMS") @Size(max = 2_000) String reason,
        Long expectedVersion) {
}

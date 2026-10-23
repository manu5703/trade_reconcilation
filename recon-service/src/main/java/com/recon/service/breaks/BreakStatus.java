package com.recon.service.breaks;

/** OPEN → ASSIGNED (someone owns it) → RESOLVED (by a person with a reason, or by a later run). */
public enum BreakStatus {
    OPEN,
    ASSIGNED,
    RESOLVED
}

package com.recon.service.breaks;

import com.recon.core.model.BreakType;
import com.recon.service.web.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A row of the break register. Runs insert and refresh these in bulk (see ReconDataRepository);
 * this entity is how people work them: assign, add notes, resolve.
 */
@Entity
@Table(name = "recon_break")
public class ReconBreak {

    @Id
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BreakType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "internal_id")
    private TradeRecord trade;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "external_id")
    private ConfirmRecord confirm;

    private String field;
    private String expected;
    private String actual;
    private String counterparty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BreakStatus status;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    private String owner;
    private String note;

    @Column(name = "resolution_reason")
    private String resolutionReason;

    @Version
    private long version;

    protected ReconBreak() {
        // for JPA
    }

    void assignTo(String newOwner) {
        requireNotResolved();
        owner = newOwner;
        status = BreakStatus.ASSIGNED;
    }

    /** Notes are appended, never overwritten, each stamped with its time: the break's audit trail. */
    void addNote(String text, Instant at) {
        requireNotResolved();
        String entry = "[" + at + "] " + text;
        note = note == null ? entry : note + "\n" + entry;
    }

    void resolve(String reason, Instant at) {
        requireNotResolved();
        status = BreakStatus.RESOLVED;
        resolutionReason = reason;
        resolvedAt = at;
    }

    private void requireNotResolved() {
        if (status == BreakStatus.RESOLVED) {
            throw ApiException.conflict("Break " + id + " is already resolved and can no longer be changed");
        }
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public BreakType getType() { return type; }
    public TradeRecord getTrade() { return trade; }
    public ConfirmRecord getConfirm() { return confirm; }
    public String getField() { return field; }
    public String getExpected() { return expected; }
    public String getActual() { return actual; }
    public String getCounterparty() { return counterparty; }
    public BreakStatus getStatus() { return status; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getOwner() { return owner; }
    public String getNote() { return note; }
    public String getResolutionReason() { return resolutionReason; }
    public long getVersion() { return version; }
}

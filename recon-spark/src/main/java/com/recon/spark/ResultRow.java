package com.recon.spark;

import com.recon.core.match.Break;
import com.recon.core.match.MatchLink;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * One output row of the job: a match link or a break. A single JavaBean for both, so one Spark
 * Encoder covers the whole result and the two are split only when written.
 */
public class ResultRow implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String LINK = "LINK";
    public static final String BREAK = "BREAK";

    private String kind;
    private String internalRef;
    private String externalRef;
    private String rule;
    private BigDecimal score;
    private String breakType;
    private String field;
    private String expected;
    private String actual;

    /** For Spark's bean encoder. */
    public ResultRow() {
    }

    static ResultRow of(MatchLink link) {
        ResultRow row = new ResultRow();
        row.kind = LINK;
        row.internalRef = link.internalRef();
        row.externalRef = link.externalRef();
        row.rule = link.rule().name();
        row.score = link.score();
        return row;
    }

    static ResultRow of(Break brk) {
        ResultRow row = new ResultRow();
        row.kind = BREAK;
        row.internalRef = brk.internalRef();
        row.externalRef = brk.externalRef();
        row.breakType = brk.type().name();
        row.field = brk.field();
        row.expected = brk.expected();
        row.actual = brk.actual();
        return row;
    }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getInternalRef() { return internalRef; }
    public void setInternalRef(String internalRef) { this.internalRef = internalRef; }
    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
    public String getRule() { return rule; }
    public void setRule(String rule) { this.rule = rule; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getBreakType() { return breakType; }
    public void setBreakType(String breakType) { this.breakType = breakType; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }
    public String getActual() { return actual; }
    public void setActual(String actual) { this.actual = actual; }
}

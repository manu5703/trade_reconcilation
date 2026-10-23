package com.recon.service.breaks;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;

/** Read-only view of an external confirm, joined into break responses. Written only by the importer. */
@Entity
@Immutable
@Table(name = "external_confirm")
public class ConfirmRecord {

    @Id
    private Long id;
    @Column(name = "broker_ref")
    private String brokerRef;
    private String source;
    private String isin;
    private String side;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal commission;
    @Column(name = "trade_date")
    private LocalDate tradeDate;
    @Column(name = "settle_date")
    private LocalDate settleDate;
    private String counterparty;
    @Column(name = "net_amount")
    private BigDecimal netAmount;

    protected ConfirmRecord() {
        // for JPA
    }

    public Long getId() { return id; }
    public String getBrokerRef() { return brokerRef; }
    public String getSource() { return source; }
    public String getIsin() { return isin; }
    public String getSide() { return side; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getCommission() { return commission; }
    public LocalDate getTradeDate() { return tradeDate; }
    public LocalDate getSettleDate() { return settleDate; }
    public String getCounterparty() { return counterparty; }
    public BigDecimal getNetAmount() { return netAmount; }
}

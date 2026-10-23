package com.recon.service.breaks;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;

/** Read-only view of an internal trade, joined into break responses. Written only by the importer. */
@Entity
@Immutable
@Table(name = "internal_trade")
public class TradeRecord {

    @Id
    private Long id;
    @Column(name = "trade_ref")
    private String tradeRef;
    @Column(name = "block_ref")
    private String blockRef;
    private String account;
    private String isin;
    private String side;
    private BigDecimal quantity;
    private BigDecimal price;
    @Column(name = "trade_date")
    private LocalDate tradeDate;
    @Column(name = "settle_date")
    private LocalDate settleDate;
    private String counterparty;
    @Column(name = "net_amount")
    private BigDecimal netAmount;

    protected TradeRecord() {
        // for JPA
    }

    public Long getId() { return id; }
    public String getTradeRef() { return tradeRef; }
    public String getBlockRef() { return blockRef; }
    public String getAccount() { return account; }
    public String getIsin() { return isin; }
    public String getSide() { return side; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public LocalDate getTradeDate() { return tradeDate; }
    public LocalDate getSettleDate() { return settleDate; }
    public String getCounterparty() { return counterparty; }
    public BigDecimal getNetAmount() { return netAmount; }
}

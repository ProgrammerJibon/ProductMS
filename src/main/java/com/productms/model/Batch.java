package com.productms.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** A received batch/lot of a variant, optionally with an expiry date. */
public class Batch {
    public int batchId;
    public int variantId;
    public String batchNo;
    public LocalDate manufactureDate;
    public LocalDate expiryDate;
    public BigDecimal qtyReceived = BigDecimal.ZERO;
    public BigDecimal qtyRemaining = BigDecimal.ZERO;
    public BigDecimal unitCost = BigDecimal.ZERO;
    public String productName;
    public String variantLabel;
    public String sku;

    public boolean isExpired() {
        return expiryDate != null && expiryDate.isBefore(LocalDate.now());
    }

    public long daysToExpiry() {
        return expiryDate == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
    }

    @Override
    public String toString() {
        String exp = expiryDate == null ? "no expiry" : "exp " + expiryDate;
        return batchNo + " (" + com.productms.util.Money.qty(qtyRemaining) + " left, " + exp + ")";
    }
}

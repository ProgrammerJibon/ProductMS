package com.productms.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** A completed (or cancelled) sale / invoice. */
public class Sale {
    public int saleId;
    public String invoiceNo;
    public int customerId;
    public String customerName;
    public String customerPhone;
    public String customerAddress;
    public boolean walkIn;
    public int userId;
    public String cashierName;
    public Integer shiftId;
    public String priceMode = "RETAIL";
    public BigDecimal subtotal = BigDecimal.ZERO;
    public BigDecimal itemDiscount = BigDecimal.ZERO;
    public BigDecimal cartDiscount = BigDecimal.ZERO;
    public BigDecimal vatTotal = BigDecimal.ZERO;
    public BigDecimal grandTotal = BigDecimal.ZERO;
    public BigDecimal paidTotal = BigDecimal.ZERO;
    public BigDecimal dueTotal = BigDecimal.ZERO;
    public BigDecimal tenderedTotal = BigDecimal.ZERO;
    public BigDecimal changeAmount = BigDecimal.ZERO;
    public BigDecimal costTotal = BigDecimal.ZERO;
    public BigDecimal returnedTotal = BigDecimal.ZERO;
    public String paymentStatus = "PAID";
    public String status = "COMPLETED";
    public String note;
    public LocalDateTime createdAt;
    public LocalDateTime cancelledAt;
    public String cancelReason;
    public int itemCount;
    public List<SaleItem> items = new ArrayList<>();
    public List<Payment> payments = new ArrayList<>();

    public boolean isCancelled() {
        return "CANCELLED".equals(status);
    }
}

package com.productms.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A payment applied to a sale (one sale can have several = split payment). */
public class Payment {
    public static final String[] METHODS = {"CASH", "CARD", "BKASH", "NAGAD", "ROCKET"};

    public int paymentId;
    public String method;
    public BigDecimal amount;
    public String reference;
    public String paymentType = "SALE";
    public LocalDateTime createdAt;

    public Payment() {
    }

    public Payment(String method, BigDecimal amount, String reference) {
        this.method = method;
        this.amount = amount;
        this.reference = reference;
    }

    public static String label(String method) {
        switch (method) {
            case "CASH": return "Cash";
            case "CARD": return "Card";
            case "BKASH": return "bKash";
            case "NAGAD": return "Nagad";
            case "ROCKET": return "Rocket";
            case "STORE_CREDIT": return "Store Credit";
            case "NONE": return "None";
            default: return method;
        }
    }
}

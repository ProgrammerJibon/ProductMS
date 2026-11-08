package com.productms.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A payment applied to a sale (one sale can have several = split payment). */
public class Payment {

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

    /** Display name of a payment method code (from the payment_methods table). */
    public static String label(String method) {
        return com.productms.dao.PaymentMethodDAO.label(method);
    }

    /** Codes of the active tender methods, in display order. */
    public static String[] methods() {
        return com.productms.dao.PaymentMethodDAO.tenderMethods().stream().map(m -> m.code).toArray(String[]::new);
    }

    public boolean isCash() {
        com.productms.model.PaymentMethod m = com.productms.dao.PaymentMethodDAO.get(method);
        return m != null ? m.isCash() : "CASH".equals(method);
    }
}
